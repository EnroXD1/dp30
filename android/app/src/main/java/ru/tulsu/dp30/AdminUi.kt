package ru.tulsu.dp30

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminScreen(service: MobileService, actions: ScreenActions) {
    var tab by remember { mutableStateOf("users") }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var brigades by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val people = tab == "users"
    val reference = tab == "references"
    val titleField = if (people) "display_name" else if (reference) "title" else "name"

    suspend fun refresh() {
        rows = (service.call(if (tab == "audit") "/admin/audit" else "/$tab") as JSONArray).objects()
        if (tab == "users") brigades = (service.call("/brigades") as JSONArray).objects()
    }
    LaunchedEffect(tab) { actions.run { refresh() } }

    Heading("Доступ и справочники")
    Panel {
        Choice("Раздел", tab, mapOf("users" to "Пользователи", "brigades" to "Бригады",
            "references" to "Справочники", "audit" to "Журнал действий")) {
            if (!actions.busy && tab != it) { tab = it; rows = emptyList(); editing = false; actions.message = "" }
        }
    }
    if (tab == "audit") {
        QuietAction("Обновить журнал", !actions.busy, R.drawable.ic_refresh) { actions.run { refresh() } }
        if (rows.isEmpty() && !actions.busy) Panel { Text("В журнале пока нет записей.") }
        for (entry in rows) {
            val entity = when (entry.getString("entity")) {
                "users" -> "пользователь"
                "reference_entries" -> "справочная запись"
                else -> "бригада"
            }
            Panel {
                Text("${events[entry.getString("kind")]}", style = MaterialTheme.typography.titleMedium)
                Text("$entity № ${entry.getLong("entity_id")}")
                Muted("${displayDate(entry.getString("occurred_at"))} · администратор № ${entry.getLong("actor_id")}")
            }
        }
        return
    }
    if (!editing) {
        Action(if (people) "Добавить пользователя" else if (reference) "Добавить запись" else "Добавить бригаду",
            actions, R.drawable.ic_plus) {
            selected = null; editing = true; revision++
        }
        QuietAction("Обновить список", !actions.busy, R.drawable.ic_refresh) { actions.run { refresh() } }
        if (rows.isEmpty() && !actions.busy) Panel { Text("Пока нет записей.") }
        for (row in rows) {
            OutlinedCard(onClick = {
                if (!actions.busy) { selected = row; editing = true; revision++; actions.message = "" }
            }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(
                    Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.getString(titleField), style = MaterialTheme.typography.titleMedium)
                        if (people) Muted("${row.getString("login")} · ${roles[row.getString("role")]}")
                        if (reference) Muted("${row.getString("kind")} · ${row.getString("code")}")
                        val active = row.getBoolean("active")
                        StatusPill(if (active) "Действует" else "Не действует",
                            if (active) Tone.SECONDARY else Tone.NEUTRAL)
                    }
                    Glyph(R.drawable.ic_chevron)
                }
            }
        }
        return
    }
    key(revision) {
        val row = selected
        val isNew = row == null
        var title by remember { mutableStateOf(row?.getString(titleField).orEmpty()) }
        var kind by remember { mutableStateOf(row?.optionalText("kind").orEmpty()) }
        var code by remember { mutableStateOf(row?.optionalText("code").orEmpty()) }
        var login by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var role by remember { mutableStateOf(row?.getString("role") ?: "dispatcher") }
        var active by remember { mutableStateOf(row?.getBoolean("active") ?: true) }
        var brigade by remember { mutableStateOf(row?.optionalLong("brigade_id")?.toString().orEmpty()) }
        val retry = remember { MutationRetry() }
        Heading(if (people) "Учётная запись" else if (reference) "Справочная запись" else "Карточка бригады")
        Panel {
            Field(if (people) "Имя сотрудника" else "Название", titleField, title, actions) { title = it }
            if (reference) {
                Field("Вид справочника", "kind", kind, actions) { kind = it }
                Field("Код записи", "code", code, actions) { code = it }
            }
            if (people && isNew) {
                Field("Логин", "login", login, actions) { login = it }
                Field("Пароль (от 8 символов)", "password", password, actions, password = true) { password = it }
            }
            if (people) {
                Choice("Роль", role, roles) { role = it }
                if (role == "worker") Choice("Бригада", brigade, mapOf("" to "Выберите бригаду") +
                    brigades.associate { it.getLong("id").toString() to it.getString("name") }) { brigade = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(active, { active = it }, enabled = !actions.busy)
                Text(if (people) "Доступ разрешён" else "Запись действует")
            }
        }
        Action("Сохранить", actions, R.drawable.ic_check) {
            actions.run {
                val fields = mutableListOf<Pair<String, Any?>>("active" to active)
                fields += titleField to title
                if (reference) { fields += "kind" to kind; fields += "code" to code }
                if (people) {
                    fields += "role" to role
                    fields += "brigade_id" to (if (role == "worker") brigade.toLongOrNull() else null)
                    if (isNew) { fields += "login" to login; fields += "password" to password }
                }
                val packet = retry.body(row?.getInt("version") ?: 0, *fields.toTypedArray())
                if (isNew) service.call("/$tab", "POST", packet)
                else service.call("/$tab/${row!!.getLong("id")}", "PATCH", packet)
                editing = false
                refresh()
            }
        }
        QuietAction("К списку", !actions.busy, R.drawable.ic_back) {
            editing = false; actions.message = ""; actions.field = null
        }
    }
}

@Composable
fun SummaryScreen(service: MobileService, actions: ScreenActions) {
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var due by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var brigade by remember { mutableStateOf("") }
    var onlyLate by remember { mutableStateOf(false) }
    var brigades by remember { mutableStateOf(mapOf("" to "Все бригады")) }
    fun load() {
        if (due.isNotBlank() && isoDate(due) == null) {
            actions.warn("due_before", "Используйте формат ДД.ММ.ГГГГ ЧЧ:ММ.")
            return
        }
        actions.run {
            brigades = mapOf("" to "Все бригады") + (service.call("/brigades") as JSONArray).objects()
                .associate { it.getLong("id").toString() to it.getString("name") }
            val params = queryOf(mapOf("status" to status, "brigade_id" to brigade,
                "due_before" to isoDate(due).orEmpty(), "overdue" to onlyLate.toString()))
            summary = service.call("/summary?$params") as JSONObject
        }
    }
    LaunchedEffect(Unit) { load() }
    Heading("Сводка пункта")
    summary?.let { data ->
        val colors = MaterialTheme.colorScheme
        Panel(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, bordered = false) {
            Text("Всего заявок: ${data.getInt("total")}", style = MaterialTheme.typography.headlineSmall)
            val late = data.getInt("overdue")
            StatusPill("Просрочены: $late", if (late == 0) Tone.NEUTRAL else Tone.ERROR, R.drawable.ic_clock)
            Text("На ${displayDate(data.getString("server_time"))}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Panel {
        Text("Фильтры сводки", style = MaterialTheme.typography.titleMedium)
        Choice("Статус", status, mapOf("" to "Все статусы") + statuses) { status = it }
        Choice("Бригада", brigade, brigades) { brigade = it }
        Field("Срок до · ДД.ММ.ГГГГ ЧЧ:ММ", "due_before", due, actions) { due = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyLate, { onlyLate = it }, enabled = !actions.busy)
            Text("Только просроченные")
        }
        QuietAction("Обновить сводку", !actions.busy, R.drawable.ic_refresh) { load() }
    }
    summary?.let { data ->
        val total = data.getInt("total")
        val byStatus = data.getJSONObject("by_status")
        Panel {
            Text("По статусам", style = MaterialTheme.typography.titleMedium)
            for ((key, label) in statuses) {
                val count = byStatus.optInt(key)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                }
                if (total > 0) LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val byBrigade = data.getJSONObject("by_brigade")
        Panel {
            Text("Активные назначения по бригадам", style = MaterialTheme.typography.titleMedium)
            if (byBrigade.length() == 0) Muted("Активных назначений нет.")
            for (key in byBrigade.keys()) Text("${brigades[key] ?: "Бригада № $key"}: ${byBrigade.getInt(key)}")
        }
    }
}
