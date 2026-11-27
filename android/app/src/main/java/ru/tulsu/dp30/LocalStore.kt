package ru.tulsu.dp30

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity
data class CachedTask(@PrimaryKey val requestId: Long, val card: String, val actualAt: String)

@Entity
data class Draft(@PrimaryKey val requestId: Long, val text: String, val baseVersion: Int)

@Entity
data class PendingCommand(
    @PrimaryKey val requestId: Long,
    val operationId: String,
    val packet: String,
    val state: String,
    val message: String = ""
)

@Dao
interface LocalStore {
    @Query("SELECT * FROM CachedTask ORDER BY requestId DESC") suspend fun tasks(): List<CachedTask>
    @Query("SELECT * FROM CachedTask WHERE requestId=:id") suspend fun task(id: Long): CachedTask?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(rows: List<CachedTask>)
    @Query("DELETE FROM CachedTask WHERE requestId=:id") suspend fun forgetTask(id: Long)
    @Query("DELETE FROM CachedTask") suspend fun clearTasks()
    @Query("SELECT * FROM Draft WHERE requestId=:id") suspend fun draft(id: Long): Draft?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(row: Draft)
    @Query("SELECT COUNT(*) FROM Draft") suspend fun draftCount(): Int
    @Query("DELETE FROM Draft WHERE requestId=:id") suspend fun removeDraft(id: Long)
    @Query("SELECT * FROM PendingCommand WHERE requestId=:id") suspend fun pending(id: Long): PendingCommand?
    @Query("SELECT * FROM PendingCommand WHERE state IN ('WAITING_NETWORK','AUTH_REQUIRED','SENDING')")
    suspend fun waiting(): List<PendingCommand>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePending(row: PendingCommand)
    @Query("DELETE FROM Draft") suspend fun clearDrafts()
    @Query("DELETE FROM PendingCommand") suspend fun clearPending()
}

@Database(entities = [CachedTask::class, Draft::class, PendingCommand::class], version = 1)
abstract class DispatchDatabase : RoomDatabase() {
    abstract fun local(): LocalStore
}

data class Account(val base: String, val profile: JSONObject, val token: String?, val expiresAt: String) {
    fun identity() = listOf(base, profile.getLong("id"), profile.getString("role"), profile.optionalLong("brigade_id"))
}

class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "dp30.session"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun load(): Account? {
        val base = preferences.getString("base", null) ?: return null
        val profile = preferences.getString("profile", null) ?: return null
        val token = runCatching {
            val data = Base64.decode(preferences.getString("sealed", "")!!, Base64.NO_WRAP)
            require(data.size > 12)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
            }
        }.getOrNull()
        return Account(base, JSONObject(profile), token, preferences.getString("expires", "")!!)
    }

    fun save(account: Account) {
        val editor = preferences.edit().putString("base", account.base)
            .putString("profile", account.profile.toString()).putString("expires", account.expiresAt)
        if (account.token == null) editor.remove("sealed")
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.iv + cipher.doFinal(account.token.toByteArray(Charsets.UTF_8))
            editor.putString("sealed", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Не удалось сохранить сессию." }
    }

    fun clear() { check(preferences.edit().clear().commit()) }
}
