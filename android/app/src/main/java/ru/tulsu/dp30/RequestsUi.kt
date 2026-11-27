package ru.tulsu.dp30

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withResolverStyle(ResolverStyle.STRICT)
fun displayDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)
}.getOrDefault(value)
fun isoDate(value: String): String? = runCatching {
    LocalDateTime.parse(value.trim(), dateFormat).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}.getOrNull()

private data class TaskPage(val items: List<JSONObject> = emptyList(), val next: Long? = null,
                            val serverTime: String = "", val offline: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestList(service: MobileService, account: Account, actions: ScreenActions,
                open: (Long) -> Unit, create: () -> Unit) {
    var page by remember { mutableStateOf(TaskPage()) }
    var number by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var brigade by remember { mutableStateOf("") }
    var onlyLate by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var brigadeOptions by remember { mutableStateOf(mapOf("" to "Все бригады")) }
    var applied by remember { mutableStateOf("") }

    fun load(offset: Long = 0) {
        if (offset == 0L) {
            if (number.isNotBlank() && (number.toLongOrNull() ?: 0) <= 0) {
                actions.warn("number", "Номер должен быть положительным числом.")
                return
            }
            if (due.isNotBlank() && isoDate(due) == null) {
                actions.warn("due_before", "Используйте формат ДД.ММ.ГГГГ ЧЧ:ММ.")
                return
            }
            applied = queryOf(mapOf("number" to number, "status" to status, "brigade_id" to brigade,
                "due_before" to isoDate(due).orEmpty(), "overdue" to onlyLate.toString()))
        }
        actions.run {
            try {
                val response = service.call("/requests?offset=$offset&$applied") as JSONObject
                val items = response.getJSONArray("items").objects()
                val serverTime = response.getString("server_time")
                service.cache(items, serverTime, account.identity())
                page = TaskPage(if (offset == 0L) items else (page.items + items).distinctBy { it.getLong("id") },
                    response.optionalLong("next_offset"), serverTime)
            } catch (error: Exception) {
                if (account.profile.optString("role") != "worker" ||
                    (error !is IOException && !(error is ApiError && error.status == 401))) throw error
                val cachedTasks = service.local.tasks()
                val cached = cachedTasks.map { JSONObject(it.card) }.filter { card ->
                    (number.isBlank() || card.getLong("id").toString() == number) &&
                    (status.isBlank() || card.getString("status") == status) &&
                    (brigade.isBlank() || card.optionalLong("brigade_id")?.toString() == brigade) &&
                    (due.isBlank() || !OffsetDateTime.parse(card.getString("due_at")).isAfter(OffsetDateTime.parse(isoDate(due))))
                }
                page = TaskPage(cached, serverTime = cachedTasks.minOfOrNull { it.actualAt }.orEmpty(), offline = true)
                actions.message = if (error is ApiError) "Войдите снова. Открыты задания, сохранённые на устройстве."
                                  else "Нет связи. Открыты задания, сохранённые на устройстве."
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    val role = account.profile.optString("role")
    Heading(if (role == "worker") "Мои задания" else "Заявки")
    if (role == "dispatcher") Action("Создать заявку", actions, R.drawable.ic_plus, create)
    Panel {
        Field("Номер заявки", "number", number, actions) { number = it }
        FilterChip(
            selected = showFilters,
            enabled = !actions.busy,
            onClick = {
                showFilters = !showFilters
                if (showFilters && !page.offline) actions.run {
                    brigadeOptions = mapOf("" to "Все бригады") +
                        (service.call("/brigades") as org.json.JSONArray).objects()
                            .associate { it.getLong("id").toString() to it.getString("name") }
                }
            },
            label = { Text(if (showFilters) "Скрыть фильтры" else "Фильтры") },
            leadingIcon = { Glyph(R.drawable.ic_filter) },
            shape = MaterialTheme.shapes.large,
        )
        if (showFilters) {
            Choice("Статус", status, mapOf("" to "Все статусы") + statuses) { status = it }
            Choice("Бригада", brigade, brigadeOptions) { brigade = it }
            Field("Срок до · ДД.ММ.ГГГГ ЧЧ:ММ", "due_before", due, actions) { due = it }
            if (!page.offline) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(onlyLate, { onlyLate = it })
                Text("Только просроченные")
            }
        }
        QuietAction("Найти и обновить", !actions.busy, R.drawable.ic_search) { load() }
    }
    if (page.serverTime.isNotBlank()) Muted(
        (if (page.offline) "Сохранено на устройстве · " else "Обновлено · ") + displayDate(page.serverTime),
        Modifier.padding(horizontal = 4.dp),
    )
    if (page.items.isEmpty() && !actions.busy) Panel { Text("По выбранным условиям заявок нет.") }
    for (card in page.items) {
        val id = card.getLong("id")
        val cardStatus = card.getString("status")
        val colors = MaterialTheme.colorScheme
        OutlinedCard(
            onClick = { open(id) }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow),
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("№ $id", Modifier.weight(1f), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge)
                    Glyph(R.drawable.ic_arrow)
                }
                Text(card.getString("address"), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(statuses[cardStatus] ?: cardStatus, requestTone(cardStatus))
                    if (card.getString("priority") == "high") StatusPill("Высокий приоритет", Tone.SECONDARY)
                }
                Muted("Срок · ${displayDate(card.getString("due_at"))}")
            }
        }
    }
    page.next?.let { next -> QuietAction("Показать ещё", !actions.busy) { load(next) } }
}

class MutationRetry {
    private var last = ""
    private var packet: JSONObject? = null
    fun body(version: Int, vararg fields: Pair<String, Any?>): JSONObject {
        val key = json(*fields).put("expected_version", version).toString()
        if (packet == null || last != key) {
            last = key
            packet = versioned(version)
            for ((field, value) in fields) packet!!.put(field, value ?: JSONObject.NULL)
        }
        return packet!!
    }
}

@Composable
fun CreateScreen(service: MobileService, actions: ScreenActions, parent: Long?, finish: (Long) -> Unit) {
    val retry = remember { MutationRetry() }
    var address by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var due by remember { mutableStateOf(LocalDateTime.now().plusDays(1).format(dateFormat)) }
    Heading("Новая заявка")
    parent?.let { Notice("Связана с завершённой заявкой № $it.", Tone.SECONDARY, R.drawable.ic_link) }
    Panel {
        Field("Адрес", "address", address, actions) { address = it }
        Field("Описание", "description", description, actions, multiline = true) { description = it }
        Choice("Приоритет", priority, priorities) { priority = it }
        Field("Срок · ДД.ММ.ГГГГ ЧЧ:ММ", "due_at", due, actions) { due = it }
    }
    Action("Зарегистрировать", actions) {
        when {
            address.isBlank() -> actions.warn("address", "Введите адрес.")
            description.isBlank() -> actions.warn("description", "Введите описание.")
            isoDate(due) == null -> actions.warn("due_at", "Используйте формат ДД.ММ.ГГГГ ЧЧ:ММ.")
            else -> actions.run {
                val packet = retry.body(0, "address" to address, "description" to description,
                    "priority" to priority, "due_at" to isoDate(due), "parent_id" to parent)
                val reply = service.call("/requests", "POST", packet) as JSONObject
                finish(reply.getLong("id"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestScreen(service: MobileService, id: Long, actions: ScreenActions, role: String, related: () -> Unit) {
    var card by remember { mutableStateOf<JSONObject?>(null) }
    var offline by remember { mutableStateOf(false) }
    var cachedDate by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var selectedBrigade by remember { mutableStateOf("") }
    var brigades by remember { mutableStateOf(mapOf("" to "Выберите бригаду")) }
    var history by remember { mutableStateOf<List<JSONObject>?>(null) }
    var pending by remember { mutableStateOf<PendingCommand?>(null) }
    var draftVersion by remember { mutableStateOf<Int?>(null) }
    val retry = remember { MutationRetry() }

    suspend fun refresh() {
        val owner = service.account.value?.identity()
        try {
            card = service.call("/requests/$id") as JSONObject
            offline = false
            val stamp = card!!.getString("server_time")
            service.cache(listOf(card!!), stamp, owner)
            if (role == "dispatcher" && card?.getString("status") in setOf("NEW", "ASSIGNED")) {
                brigades = mapOf("" to "Выберите бригаду") +
                    (service.call("/brigades") as org.json.JSONArray).objects().filter { it.getBoolean("active") }
                        .associate { it.getLong("id").toString() to it.getString("name") }
            }
        } catch (error: Exception) {
            if (role == "worker" && error is ApiError && error.status == 404) {
                service.local.forgetTask(id); card = null; history = null
                throw error
            }
            if (role != "worker" || (error !is IOException && !(error is ApiError && error.status == 401))) throw error
            val cached = service.local.task(id) ?: throw error
            card = JSONObject(cached.card)
            cachedDate = cached.actualAt
            offline = true
        }
    }

    LaunchedEffect(id) {
        val draft = service.local.draft(id)
        result = draft?.text.orEmpty()
        draftVersion = draft?.baseVersion
        pending = service.local.pending(id)
        load@ while (true) {
            if (card == null && !actions.busy) {
                actions.run { refresh() }
                break@load
            }
            delay(100)
        }
    }
    LaunchedEffect(id, role) {
        if (role == "worker") while (true) {
            val current = service.local.pending(id)
            if (current?.state == "CONFIRMED" && pending?.state != "CONFIRMED" && !actions.busy) {
                draftVersion = service.local.draft(id)?.baseVersion
                if (draftVersion == null) result = ""
                actions.run { refresh() }
            }
            pending = current
            delay(500)
        }
    }

    Heading("Заявка № $id")
    val item = card
    if (item == null) {
        QuietAction("Обновить карточку", !actions.busy, R.drawable.ic_refresh) { actions.run { refresh() } }
        return
    }
    val status = item.getString("status")
    val version = item.getInt("version")
    if (offline) Notice("Локальная копия · ${displayDate(cachedDate)}", Tone.SECONDARY, R.drawable.ic_sync)
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(statuses[status] ?: status, requestTone(status))
            StatusPill("Приоритет · ${priorities[item.getString("priority")].orEmpty()}", Tone.NEUTRAL)
        }
        Text(item.getString("address"), style = MaterialTheme.typography.titleLarge)
        Text(item.getString("description"), style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_clock)
            Text("Срок · ${displayDate(item.getString("due_at"))}", style = MaterialTheme.typography.bodyMedium)
        }
        Muted("Версия $version" + (item.optionalText("brigade_name")?.let { " · $it" } ?: ""))
        item.optionalLong("parent_id")?.let { Muted("Исходная заявка № $it") }
    }
    item.optionalText("result")?.let { resultText ->
        Panel(color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer, bordered = false) {
            Text("Подтверждённый результат", style = MaterialTheme.typography.titleMedium)
            Text(resultText)
        }
    }

    fun change(action: String, vararg fields: Pair<String, Any?>) {
        actions.run {
            val packet = retry.body(version, "action" to action, *fields)
            try {
                card = service.call("/requests/$id/commands", "POST", packet) as JSONObject
                history = null
                refresh()
            } catch (error: ApiError) {
                if (error.status == 409) error.detail.optJSONObject("current")?.let { card = it }
                throw error
            }
        }
    }
    if (!offline && role == "dispatcher") {
        if (status in setOf("NEW", "ASSIGNED")) {
            Choice("Бригада", selectedBrigade, brigades) { selectedBrigade = it }
            Action(if (status == "NEW") "Назначить бригаду" else "Сменить бригаду", actions) {
                if (selectedBrigade.isBlank()) actions.warn("brigade_id", "Выберите бригаду.")
                else change("assign", "brigade_id" to selectedBrigade.toLong())
            }
        }
        if (status !in setOf("CLOSED", "CANCELLED")) {
            Field("Причина возврата или отмены", "reason", reason, actions) { reason = it }
            if (status == "COMPLETED") {
                Action("Закрыть заявку", actions) { change("close") }
                Action("Вернуть в работу", actions) { change("return", "reason" to reason) }
            }
            QuietAction("Отменить заявку", !actions.busy) { change("cancel", "reason" to reason) }
        } else Action("Создать связанную заявку", actions, R.drawable.ic_link, related)
    }
    if (!offline && role == "worker") {
        if (status == "ASSIGNED") Action("Принять задание", actions) { change("accept") }
        if (status == "ACCEPTED") Action("Начать работы", actions) { change("start") }
    }
    if (role == "worker") {
        val awaiting = pending?.state in setOf("WAITING_NETWORK", "AUTH_REQUIRED", "SENDING")
        pending?.let { packet ->
            val label = when (packet.state) {
                "SENDING" -> "Идёт отправка"
                "WAITING_NETWORK" -> "Ожидает подтверждения"
                "AUTH_REQUIRED" -> "Требуется повторный вход"
                "CONFLICT" -> "Конфликт версии или состояния"
                "REJECTED" -> "Отправка отклонена"
                else -> "Отправка подтверждена"
            }
            val tone = when (packet.state) {
                "CONFLICT", "REJECTED" -> Tone.ERROR
                "CONFIRMED" -> Tone.TERTIARY
                else -> Tone.SECONDARY
            }
            Notice(listOf(label, packet.message).filter { it.isNotBlank() }.joinToString(". "),
                tone, if (packet.state == "CONFIRMED") R.drawable.ic_check else R.drawable.ic_sync)
            if (awaiting) Action("Повторить отправку", actions) {
                actions.run { service.sync(id, packet.operationId); pending = service.local.pending(id); if (pending?.state == "CONFIRMED") refresh() }
            }
        }
        if (status == "IN_WORK" || draftVersion != null) {
            if (draftVersion != null && draftVersion != version) {
                Text("Черновик подготовлен для версии $draftVersion. Сверьте его с актуальной карточкой.")
            }
            Field("Результат работ", "result", result, actions, multiline = true) { result = it }
            fun validate() = (result.trim().length in 1..4000).also {
                if (!it) actions.warn("result", "Введите от 1 до 4000 символов.")
            }
            Action("Сохранить черновик", actions) {
                if (validate()) actions.run {
                    val base = draftVersion ?: version
                    service.saveDraft(Draft(id, result.trim(), base))
                    draftVersion = base
                }
            }
            if (!awaiting && status == "IN_WORK") {
                val rebase = draftVersion != null && draftVersion != version || pending?.state == "CONFLICT"
                if (!rebase || !offline) {
                    Action(if (rebase) "Отправить новой командой по текущей версии" else "Передать результат", actions) {
                        if (validate()) actions.run {
                            val packet = service.queue(id, if (rebase) version else draftVersion ?: version, result.trim())
                            pending = packet
                            service.sync(id, packet.operationId)
                            pending = service.local.pending(id)
                            if (pending?.state == "CONFIRMED") {
                                draftVersion = service.local.draft(id)?.baseVersion
                                if (draftVersion == null) result = ""
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }

    QuietAction("Обновить карточку", !actions.busy, R.drawable.ic_refresh) { actions.run { refresh() } }
    if (!offline) QuietAction("Показать историю", !actions.busy, R.drawable.ic_clock) {
        actions.run { history = (service.call("/requests/$id/events") as org.json.JSONArray).objects() }
    }
    history?.let { list ->
        Text("История заявки", style = MaterialTheme.typography.titleLarge)
        for (row in list) {
            Panel {
                Text("${events[row.getString("kind")] ?: row.getString("kind")} · ${row.getString("actor_name")}",
                    style = MaterialTheme.typography.titleSmall)
                Muted(displayDate(row.getString("occurred_at")))
                row.optionalText("comment")?.let { Text(it) }
            }
        }
    }
}
