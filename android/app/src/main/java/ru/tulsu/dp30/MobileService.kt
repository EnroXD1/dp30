package ru.tulsu.dp30

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class DispatchApp : Application() {
    val mobile by lazy { MobileService(this) }
}

class MobileService(private val context: Context) {
    private val db = Room.databaseBuilder(context, DispatchDatabase::class.java, "dispatcher.db").build()
    val local = db.local()
    private val vault = SessionVault(context)
    private val mutex = Mutex()
    private val accountState = MutableStateFlow(vault.load())
    val account = accountState.asStateFlow()

    private fun authError(): Nothing = throw ApiError(401, json("message" to "Для отправки войдите снова."))

    private fun validSession(active: Account) = active.token != null &&
        runCatching { OffsetDateTime.parse(active.expiresAt).toInstant().isAfter(Instant.now()) }.getOrDefault(false)

    private fun api(): Api {
        val active = account.value ?: authError()
        if (!validSession(active)) authError()
        return Api(active.base, active.token)
    }

    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): Any = mutex.withLock {
        try {
            api().call(path, method, body)
        } catch (error: ApiError) {
            if (error.status == 401) expire()
            throw error
        }
    }

    private suspend fun publish(active: Account) = withContext(Dispatchers.IO) {
        vault.save(active)
        accountState.value = active
    }

    private suspend fun expire() {
        val current = account.value ?: return
        if (current.token != null) publish(current.copy(token = null, expiresAt = ""))
        for (pending in local.waiting()) {
            local.savePending(pending.copy(state = "AUTH_REQUIRED", message = "Для повтора войдите снова."))
        }
    }

    suspend fun login(base: String, name: String, password: String) = mutex.withLock {
        val endpoint = base.trim().trimEnd('/')
        val response = Api(endpoint).obj("/auth/login", "POST", json("login" to name, "password" to password))
        val active = Account(endpoint, response.getJSONObject("user"), response.getString("token"),
            response.getString("expires_at"))
        val previous = account.value
        if (previous != null && previous.identity() != active.identity()) clearLocal()
        publish(active)
        for (pending in local.waiting()) schedule(pending)
    }

    suspend fun profile(): JSONObject = mutex.withLock {
        try {
            val profile = api().obj("/auth/me")
            val previous = account.value ?: authError()
            val active = previous.copy(profile = profile)
            if (previous.identity() != active.identity()) clearLocal()
            publish(active)
            profile
        } catch (error: ApiError) {
            if (error.status == 401) expire()
            throw error
        }
    }

    private suspend fun clearLocal() {
        WorkManager.getInstance(context).cancelAllWorkByTag("dp30-sync")
        db.withTransaction { local.clearTasks(); local.clearDrafts(); local.clearPending() }
    }

    suspend fun logout() = mutex.withLock {
        val active = account.value
        val revoked = if (active?.token != null) runCatching {
            Api(active.base, active.token).obj("/auth/logout", "POST")
        }.isSuccess else false
        clearLocal()
        withContext(Dispatchers.IO) { vault.clear(); accountState.value = null }
        revoked
    }

    suspend fun cache(cards: List<JSONObject>, actualAt: String, owner: List<Any?>?) = mutex.withLock {
        if (account.value?.identity() == owner && account.value?.profile?.optString("role") == "worker") {
            local.cache(cards.map { card -> CachedTask(card.getLong("id"), card.toString(), actualAt) })
        }
    }

    suspend fun queue(id: Long, version: Int, result: String): PendingCommand = mutex.withLock {
        require(account.value?.profile?.optString("role") == "worker")
        val old = local.pending(id)
        if (old != null && old.state in setOf("WAITING_NETWORK", "SENDING", "AUTH_REQUIRED")) {
            return@withLock old
        }
        val packet = versioned(version).put("action", "complete").put("result", result)
        val command = PendingCommand(id, packet.getString("operation_id"), packet.toString(), "WAITING_NETWORK")
        db.withTransaction { local.saveDraft(Draft(id, result, version)); local.savePending(command) }
        schedule(command)
        command
    }

    suspend fun saveDraft(draft: Draft) = mutex.withLock {
        require(account.value?.profile?.optString("role") == "worker")
        local.saveDraft(draft)
    }

    fun schedule(command: PendingCommand) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(workDataOf("request" to command.requestId, "operation" to command.operationId))
            .addTag("dp30-sync").build()
        WorkManager.getInstance(context).enqueueUniqueWork("dp30-${command.operationId}", ExistingWorkPolicy.KEEP, work)
    }

    suspend fun sync(id: Long, operation: String): String = mutex.withLock {
        val row = local.pending(id) ?: return@withLock "REJECTED"
        if (row.operationId != operation || row.state !in setOf("WAITING_NETWORK", "SENDING", "AUTH_REQUIRED")) {
            return@withLock row.state
        }
        local.savePending(row.copy(state = "SENDING"))
        var confirmed: JSONObject? = null
        val updated = try {
            confirmed = api().obj("/requests/$id/commands", "POST", JSONObject(row.packet))
            row.copy(state = "CONFIRMED", message = "Результат подтверждён сервером.")
        } catch (error: ApiError) {
            val state = when (error.status) {
                401 -> "AUTH_REQUIRED"
                409 -> "CONFLICT"
                429, in 500..599 -> "WAITING_NETWORK"
                else -> "REJECTED"
            }
            if (error.status == 401) expire()
            if (error.status == 404) local.forgetTask(id)
            row.copy(state = state, message = error.message.orEmpty())
        } catch (_: IOException) {
            row.copy(state = "WAITING_NETWORK", message = "Нет связи. Пакет сохранён для повтора.")
        }
        db.withTransaction {
            local.savePending(updated)
            confirmed?.let { card ->
                val cached = local.task(id)
                if (cached == null || JSONObject(cached.card).getInt("version") <= card.getInt("version")) {
                    local.cache(listOf(CachedTask(id, card.toString(), card.getString("server_time"))))
                }
            }
            if (updated.state == "CONFIRMED") {
                val draft = local.draft(id)
                if (draft != null && draft.text == JSONObject(row.packet).getString("result") &&
                    draft.baseVersion == JSONObject(row.packet).getInt("expected_version")) local.removeDraft(id)
            }
        }
        updated.state
    }

    suspend fun syncWaiting() {
        for (row in local.waiting()) sync(row.requestId, row.operationId)
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val service = (applicationContext as DispatchApp).mobile
        val operation = inputData.getString("operation") ?: return Result.failure()
        return if (service.sync(inputData.getLong("request", -1), operation) == "WAITING_NETWORK") {
            Result.retry()
        } else Result.success()
    }
}
