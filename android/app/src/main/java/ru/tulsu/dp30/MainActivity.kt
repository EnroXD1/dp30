package ru.tulsu.dp30

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val service = (application as DispatchApp).mobile
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (service.account.value?.token != null) service.syncWaiting()
                    delay(3000)
                }
            }
        }
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var mode by remember {
                mutableStateOf(ThemeMode.entries.find { it.name == appearance.getString("mode", null) }
                    ?: ThemeMode.DARK)
            }
            var wallpaper by remember { mutableStateOf(appearance.getBoolean("wallpaper", false)) }
            DispatchTheme(mode, wallpaper, updateSystemBars = {
                enableEdgeToEdge(statusBarStyle = it, navigationBarStyle = it)
            }) {
                DispatchScreen(
                    service, mode, wallpaper,
                    onTheme = { mode = it; appearance.edit { putString("mode", it.name) } },
                    onWallpaper = { wallpaper = it; appearance.edit { putBoolean("wallpaper", it) } },
                )
            }
        }
    }
}

val statuses = linkedMapOf("NEW" to "Новая", "ASSIGNED" to "Назначена", "ACCEPTED" to "Принята",
    "IN_WORK" to "В работе", "COMPLETED" to "Выполнена", "CLOSED" to "Закрыта", "CANCELLED" to "Отменена")
val roles = linkedMapOf("dispatcher" to "Диспетчер", "worker" to "Исполнитель",
    "manager" to "Руководитель", "admin" to "Администратор")
val priorities = linkedMapOf("low" to "Низкий", "normal" to "Обычный", "high" to "Высокий")
val events = mapOf("CREATE" to "Создание", "ASSIGN" to "Назначение", "REASSIGN" to "Смена бригады",
    "ACCEPT" to "Задание принято", "START" to "Начало работ", "COMPLETE" to "Результат передан",
    "RETURN" to "Возврат в работу", "CLOSE" to "Закрытие", "CANCEL" to "Отмена", "UPDATE" to "Изменение",
    "BLOCK" to "Блокировка")

class ScreenActions(private val launch: (suspend () -> Unit) -> Unit) {
    var message by mutableStateOf("")
    var field by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        field = null
        launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiError) {
                message = error.message.orEmpty()
                field = error.detail.optionalText("field")
            } catch (_: javax.net.ssl.SSLException) {
                message = "Не удалось подтвердить сертификат сервера. Проверьте адрес и сертификат."
            } catch (_: IOException) {
                message = "Сервер недоступен. Проверьте соединение и повторите."
            } catch (error: IllegalArgumentException) {
                message = error.message ?: "Проверьте введённые данные."
            } finally {
                busy = false
            }
        }
    }
    fun warn(label: String, text: String) { field = label; message = text }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DispatchScreen(
    service: MobileService, mode: ThemeMode, wallpaper: Boolean,
    onTheme: (ThemeMode) -> Unit, onWallpaper: (Boolean) -> Unit,
) {
    val account by service.account.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = remember { ScreenActions { task -> scope.launch { task() } } }
    var screen by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var parent by rememberSaveable { mutableStateOf<Long?>(null) }
    var warning by remember { mutableStateOf(false) }
    var relogin by remember { mutableStateOf(false) }
    val role = account?.profile?.optString("role")
    val home = if (role == "admin") "admin" else "list"
    fun go(target: String) {
        screen = target
        actions.message = ""
        actions.field = null
    }
    val currentScreen = if (screen == "home") home else screen
    val signingIn = account == null || relogin
    val nested = currentScreen in setOf("create", "detail")
    fun back() { if (relogin) relogin = false else go(home) }
    BackHandler(account != null && (currentScreen != home || relogin)) {
        back()
    }

    AmbientBackground {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 620.dp).fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        ) {
            AppHeader(
                role = if (signingIn) null else roles[role],
                showBack = nested || relogin,
                onBack = ::back,
                mode = mode, wallpaper = wallpaper, onTheme = onTheme, onWallpaper = onWallpaper,
            )
            Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 24.dp)) {
                if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            key(currentScreen, selected, role, relogin) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (actions.message.isNotBlank()) {
                        Notice(actions.message)
                    }
                    if (signingIn) {
                        LoginForm(account, actions) { base, login, password ->
                            actions.run { service.login(base, login, password); relogin = false; go("home") }
                        }
                    } else {
                        if (account?.token == null) {
                            QuietAction("Войти снова", enabled = !actions.busy, icon = R.drawable.ic_lock) {
                                relogin = true
                            }
                        }
                        when (currentScreen) {
                            "profile" -> Profile(service, account!!, actions) { warning = true }
                            "admin" -> AdminScreen(service, actions)
                            "summary" -> SummaryScreen(service, actions)
                            "create" -> CreateScreen(service, actions, parent) { selected = it; go("detail") }
                            "detail" -> RequestScreen(service, selected!!, actions, role.orEmpty()) {
                                parent = selected; go("create")
                            }
                            else -> RequestList(service, account!!, actions,
                                open = { selected = it; go("detail") },
                                create = { parent = null; go("create") })
                        }
                    }
                }
            }
            if (!signingIn && !WindowInsets.isImeVisible) {
                AppDock(role, home, if (nested) home else currentScreen, !actions.busy, ::go)
            }
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false },
        title = { Text("Выйти из приложения?") },
        text = { Text("Кеш, черновики и неподтверждённые отправления на этом устройстве будут удалены.") },
        confirmButton = { TextButton(onClick = {
            warning = false
            actions.run { service.logout(); go("home") }
        }) { Text("Выйти и удалить") } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text("Остаться") } })
}

@Composable
private fun AppHeader(
    role: String?, showBack: Boolean, onBack: () -> Unit,
    mode: ThemeMode, wallpaper: Boolean, onTheme: (ThemeMode) -> Unit, onWallpaper: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (showBack) {
                FilledTonalIconButton(onClick = onBack) { Glyph(R.drawable.ic_back, description = "Назад") }
            } else BrandMark()
            Column(Modifier.weight(1f)) {
                Text("ДП‑30", style = MaterialTheme.typography.titleLarge)
                if (role != null) Muted(role)
            }
            ThemePicker(mode, wallpaper, onTheme, onWallpaper)
        }
    }
}

@Composable
private fun ThemePicker(
    mode: ThemeMode, wallpaper: Boolean, onTheme: (ThemeMode) -> Unit, onWallpaper: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Glyph(R.drawable.ic_theme, description = "Оформление") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    trailingIcon = if (mode == option) ({ Glyph(R.drawable.ic_check, description = "Выбрано") }) else null,
                    onClick = { onTheme(option); expanded = false },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Цвета обоев") },
                    trailingIcon = if (wallpaper) ({ Glyph(R.drawable.ic_check, description = "Включено") }) else null,
                    onClick = { onWallpaper(!wallpaper); expanded = false },
                )
            }
        }
    }
}

private data class Destination(val route: String, val title: String, val icon: Int)

@Composable
private fun AppDock(role: String?, home: String, selected: String, enabled: Boolean, go: (String) -> Unit) {
    val main = when (role) {
        "admin" -> Destination(home, "Управление", R.drawable.ic_shield)
        "worker" -> Destination(home, "Задания", R.drawable.ic_board)
        else -> Destination(home, "Заявки", R.drawable.ic_board)
    }
    val tabs = buildList {
        add(main)
        if (role == "manager") add(Destination("summary", "Сводка", R.drawable.ic_chart))
        add(Destination("profile", "Профиль", R.drawable.ic_person))
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 4.dp,
    ) {
        NavigationBar(
            containerColor = colors.surfaceContainerHigh,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            for (tab in tabs) {
                NavigationBarItem(
                    selected = selected == tab.route,
                    onClick = { go(tab.route) },
                    enabled = enabled,
                    icon = { Glyph(tab.icon) },
                    label = { Text(tab.title, textAlign = TextAlign.Center) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = colors.primaryContainer,
                        selectedIconColor = colors.onPrimaryContainer,
                        selectedTextColor = colors.onSurface,
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
fun Heading(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)

@Composable
fun Field(label: String, key: String, value: String, actions: ScreenActions, multiline: Boolean = false,
          password: Boolean = false, change: (String) -> Unit) {
    val bad = actions.field == key
    var visible by remember { mutableStateOf(false) }
    val symbol = when (key) {
        "login" -> R.drawable.ic_person
        "password" -> R.drawable.ic_lock
        "number" -> R.drawable.ic_search
        "base" -> R.drawable.ic_link
        else -> null
    }
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(value, {
        if (bad) { actions.field = null; actions.message = "" }
        change(it)
    }, label = { Text(label) }, isError = bad,
        supportingText = if (bad) ({ Text(actions.message) }) else null,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        leadingIcon = symbol?.let { { Glyph(it) } },
        trailingIcon = if (password) ({
            IconButton(onClick = { visible = !visible }) {
                Glyph(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                    description = if (visible) "Скрыть пароль" else "Показать пароль")
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            key == "number" -> KeyboardType.Number
            key == "base" -> KeyboardType.Uri
            else -> KeyboardType.Text
        }),
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = colors.surfaceContainerLowest,
            focusedContainerColor = colors.surfaceContainerLowest,
            unfocusedBorderColor = colors.outline,
        ),
        modifier = Modifier.fillMaxWidth(), enabled = !actions.busy)
}

@Composable
fun Action(text: String, actions: ScreenActions, icon: Int? = null, click: () -> Unit) {
    Button(onClick = click, enabled = !actions.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        if (icon != null) {
            Glyph(icon)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun Choice(label: String, current: String, choices: Map<String, String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("$label · ${choices[current] ?: current}", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Glyph(R.drawable.ic_chevron)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; change(key) })
            }
        }
    }
}

@Composable
private fun LoginForm(account: Account?, actions: ScreenActions, login: (String, String, String) -> Unit) {
    var base by rememberSaveable { mutableStateOf(account?.base ?: "https://10.0.2.2:8443") }
    var name by rememberSaveable { mutableStateOf(account?.profile?.optString("login").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var showServer by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerHigh, contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box {
            HeroMotif(Modifier.matchParentSize())
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Диспетчерский пункт", style = MaterialTheme.typography.displaySmall)
                Text("Заявки и выездные бригады", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
        }
    }
    Panel {
        Text("Вход", style = MaterialTheme.typography.titleLarge)
        Field("Логин", "login", name, actions) { name = it }
        Field("Пароль", "password", password, actions, password = true) { password = it }
        Action("Войти", actions, R.drawable.ic_arrow) {
            if (name.isBlank()) actions.warn("login", "Введите логин.")
            else if (password.isEmpty()) actions.warn("password", "Введите пароль.")
            else login(base, name, password)
        }
        TextButton(onClick = { showServer = !showServer }, enabled = !actions.busy) {
            Glyph(R.drawable.ic_link)
            Spacer(Modifier.width(8.dp))
            Text(if (showServer) "Скрыть адрес сервера" else "Адрес сервера")
        }
        if (showServer) Field("HTTPS-адрес сервера", "base", base, actions) { base = it }
    }
}

@Composable
private fun Profile(service: MobileService, account: Account, actions: ScreenActions, logout: () -> Unit) {
    val profile = account.profile
    Heading("Профиль")
    val colors = MaterialTheme.colorScheme
    Panel {
        Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
            Glyph(R.drawable.ic_person, modifier = Modifier.padding(12.dp))
        }
        Text(profile.getString("display_name"), style = MaterialTheme.typography.headlineSmall)
        Muted(profile.getString("login"))
        StatusPill(roles.getValue(profile.getString("role")), Tone.PRIMARY)
        profile.optionalLong("brigade_id")?.let { Text("Бригада: ${profile.optionalText("brigade_name") ?: it}") }
    }
    QuietAction("Обновить профиль", !actions.busy, R.drawable.ic_refresh) { actions.run { service.profile() } }
    QuietAction("Выйти из приложения", !actions.busy, click = logout)
}
