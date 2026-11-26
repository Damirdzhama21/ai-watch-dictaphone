package kz.damird.diktofon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.damird.diktofon.*
import androidx.lifecycle.repeatOnLifecycle

/** Что показывать: вкладки + открытая запись поверх. */
class Nav {
    var tab by mutableStateOf("recs")
    var recId by mutableStateOf<Long?>(null)
}

/** Источник данных для экранов: в приложении — сеть, в тестах — подставные данные. */
interface Dannye {
    suspend fun recs(q: String, offset: Int): RecList
    suspend fun rec(id: Long): RecFull
    suspend fun tasks(done: Boolean): List<Task>
    suspend fun setDone(id: Long, done: Boolean)
    suspend fun rename(id: Long, title: String)
    suspend fun delete(id: Long)
}

class SetevyeDannye(private val api: Api) : Dannye {
    override suspend fun recs(q: String, offset: Int) = api.recs(q, offset)
    override suspend fun rec(id: Long) = api.rec(id)
    override suspend fun tasks(done: Boolean) = api.tasks(done)
    override suspend fun setDone(id: Long, done: Boolean) = api.setDone(id, done)
    override suspend fun rename(id: Long, title: String) = api.rename(id, title)
    override suspend fun delete(id: Long) = api.delete(id)
}

@Composable
fun cv() = LocalCveta.current

// ---------------- общий каркас ----------------
@Composable
fun MainScaffold(
    nav: Nav, d: Dannye, player: @Composable (RecFull, (Throwable) -> Unit) -> Unit,
    onAuthLost: () -> Unit, onTasksChanged: () -> Unit, openTasks: Int,
) {
    val c = cv()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val err: (Throwable) -> Unit = { e ->
        if (e is AuthException) onAuthLost() else scope.launch { snack.showSnackbar(e.message ?: "Ошибка") }
    }
    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (nav.recId == null) NizBar(nav, openTasks) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val id = nav.recId
            when {
                id != null -> {
                    BackHandler { nav.recId = null }
                    RecScreen(id, d, player, onBack = { nav.recId = null }, onErr = err, onTasksChanged = onTasksChanged,
                        onDeleted = { nav.recId = null; scope.launch { snack.showSnackbar("Запись удалена") } })
                }
                nav.tab == "tasks" -> TasksScreen(d, open = { nav.recId = it }, onErr = err, onTasksChanged = onTasksChanged)
                else -> RecsScreen(d, open = { nav.recId = it }, onErr = err)
            }
        }
    }
}

@Composable
private fun NizBar(nav: Nav, openTasks: Int) {
    val c = cv()
    Column(Modifier.background(c.bg).navigationBarsPadding()) {
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            NizItem("Записи", Icons.Outlined.Mic, nav.tab == "recs", 0, Modifier.weight(1f)) { nav.tab = "recs" }
            NizItem("Задачи", Icons.Outlined.CheckBox, nav.tab == "tasks", openTasks, Modifier.weight(1f)) { nav.tab = "tasks" }
        }
    }
}

@Composable
private fun NizItem(label: String, icon: ImageVector, on: Boolean, badge: Int, mod: Modifier, click: () -> Unit) {
    val c = cv()
    val col = if (on) c.accent else c.faint
    Column(mod.fillMaxHeight().clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BadgedBox(badge = {
            if (badge > 0) Badge(containerColor = c.accent, contentColor = Color.White) { Text(if (badge > 99) "99+" else "$badge") }
        }) { Icon(icon, null, tint = col, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(2.dp))
        Text(label, style = T.label.copy(letterSpacing = 0.sp), color = col)
    }
}


@Composable
fun Kartochka(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = cv()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
fun Razdel(text: String, top: Dp = 22.dp) {
    Text(text.uppercase(), style = T.label, color = cv().muted, modifier = Modifier.padding(start = 2.dp, top = top, bottom = 8.dp))
}

@Composable
fun Chip(text: String, grey: Boolean = false) {
    val c = cv()
    Text(
        text, style = T.meta.copy(fontWeight = FontWeight.SemiBold),
        color = if (grey) c.muted else c.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (grey) c.line else c.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun Pusto(title: String, text: String) {
    val c = cv()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = T.title.copy(fontSize = 17.sp), color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(text, style = T.small, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ---------------- вход ----------------
@Composable
fun LoginScreen(onLogin: suspend (String) -> Unit) {
    val c = cv()
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val go = {
        if (pw.isNotBlank() && !busy) scope.launch {
            busy = true; err = ""
            runCatching { onLogin(pw.trim()) }.onFailure { err = it.message ?: "Ошибка" }
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.ink), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.height(18.dp))
            Text("Диктофон", style = T.h1.copy(fontSize = 26.sp), color = c.ink)
            Text("Записи с часов: расшифровка, сводка, задачи.", style = T.body, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true, placeholder = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                    focusedContainerColor = c.card, unfocusedContainerColor = c.card),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { go() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg),
            ) { Text(if (busy) "Вхожу…" else "Войти", style = T.title) }
            Text(err, style = T.small, color = c.accent, modifier = Modifier.padding(top = 10.dp).heightIn(min = 20.dp))
        }
    }
}

// ---------------- список записей ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecsScreen(d: Dannye, open: (Long) -> Unit, onErr: (Throwable) -> Unit) {
    val c = cv()
    var q by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Rec>?>(null) }
    var more by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean = false, silent: Boolean = false) {
        runCatching { d.recs(q.trim(), if (append) items.orEmpty().size else 0) }
            .onSuccess { r -> items = if (append) items.orEmpty() + r.items else r.items; more = r.more }
            .onFailure { if (!silent || it is AuthException) onErr(it) }
    }
    // Свежие записи без «потяни вниз»: при каждом возврате в приложение и пока экран открыт —
    // раз в 30 с, а пока какая-то запись обрабатывается — раз в 10 с. Догруженные страницы не сбрасываем.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(q) {
        delay(if (q.isEmpty()) 0L else 300L)
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            load(silent = items != null)
            while (true) {
                val busy = items.orEmpty().any { it.status != "done" && it.status != "error" }
                delay(if (busy) 10_000L else 30_000L)
                if (items.orEmpty().size <= 40) load(silent = true)
            }
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 14.dp)) {
                    Text("Записи", style = T.h1, color = c.ink)
                    Spacer(Modifier.height(12.dp))
                    Poisk(q) { q = it }
                }
            }
            val list = items
            when {
                list == null -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) } }
                list.isEmpty() -> item {
                    if (q.isNotBlank()) Pusto("Ничего не нашлось", "Попробуйте другое слово")
                    else Pusto("Записей пока нет", "Нажмите кнопку на часах — запись появится здесь через минуту после окончания")
                }
                else -> {
                    grouped(list).forEach { (day, recs) ->
                        item(key = "d-$day") { Razdel(day) }
                        item(key = "g-$day-${recs.first().id}") {
                            Kartochka { recs.forEachIndexed { i, r -> if (i > 0) HorizontalDivider(color = c.line); RecRow(r) { open(r.id) } } }
                        }
                    }
                    if (more) item {
                        OutlinedButton(onClick = { scope.launch { load(append = true) } }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(44.dp),
                            shape = RoundedCornerShape(12.dp)) { Text("Показать ещё", color = c.muted) }
                    }
                }
            }
        }
    }
}

fun grouped(list: List<Rec>): List<Pair<String, List<Rec>>> {
    val out = linkedMapOf<String, MutableList<Rec>>()
    for (r in list) {
        val lab = Vremya.parse(r.recorded)?.let { Vremya.dayLabel(it.toLocalDate()) } ?: "Без даты"
        out.getOrPut(lab) { mutableListOf() } += r
    }
    return out.map { it.key to it.value }
}

@Composable
private fun Poisk(q: String, set: (String) -> Unit) {
    val c = cv()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = c.faint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text("Поиск по тексту и сводкам", style = T.body, color = c.faint)
            BasicTextField(q, set, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
        }
        if (q.isNotEmpty()) Icon(Icons.Outlined.Close, "Очистить", tint = c.muted, modifier = Modifier.size(20.dp).clickable { set("") })
    }
}

@Composable
private fun RecRow(r: Rec, click: () -> Unit) {
    val c = cv()
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val t = Vremya.parse(r.recorded)
            Text(listOfNotNull(t?.let { Vremya.hm(it) }, Vremya.dur(r.duration)).joinToString("  ·  "), style = T.meta, color = c.muted)
            if (r.tasksOpen > 0) Chip(Vremya.plural(r.tasksOpen, "задача", "задачи", "задач"))
            if (r.status == "error") Chip("ошибка")
            else if (r.status != "done") Chip("обрабатывается", grey = true)
        }
        Text(r.title.ifBlank { "Без темы" }, style = T.title, color = c.ink, modifier = Modifier.padding(top = 3.dp, bottom = 2.dp))
        if (r.preview.isNotBlank()) Text(r.preview, style = T.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- карточка записи ----------------
@Composable
fun RecScreen(
    id: Long, d: Dannye, player: @Composable (RecFull, (Throwable) -> Unit) -> Unit, onBack: () -> Unit, onErr: (Throwable) -> Unit,
    onTasksChanged: () -> Unit, onDeleted: () -> Unit,
) {
    val c = cv()
    var r by remember(id) { mutableStateOf<RecFull?>(null) }
    var rename by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        runCatching { d.rec(id) }.onSuccess { r = it }.onFailure { onErr(it); onBack(); return@LaunchedEffect }
        // запись ещё в обработке — подтягиваем, пока не будет готова
        while (r?.status == "pending" || r?.status == "working") {
            delay(10_000L)
            runCatching { d.rec(id) }.onSuccess { r = it }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text("Записи", color = c.accent, style = T.body)
            }
            Spacer(Modifier.weight(1f))
            if (r != null) {
                IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Переименовать", tint = c.muted) }
                IconButton(onClick = { del = true }) { Icon(Icons.Outlined.Delete, "Удалить", tint = c.muted) }
            }
        }
        val rec = r
        if (rec == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(rec.title.ifBlank { "Без темы" }, style = T.h2, color = c.ink)
            val t = Vremya.parse(rec.recorded)
            Text(
                listOfNotNull(t?.let { Vremya.dayLabel(it.toLocalDate()) + ", " + Vremya.hm(it) }, Vremya.durWords(rec.duration),
                    Vremya.plural(rec.words, "слово", "слова", "слов")).joinToString("  ·  "),
                style = T.meta, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (rec.hasAudio) player(rec, onErr)
        else RecBody(rec, d, null, onErr, onTasksChanged)
    }

    if (rename) {
        var t by remember { mutableStateOf(r?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { rename = false }, containerColor = c.card,
            title = { Text("Название записи") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    rename = false
                    val nt = t.trim(); if (nt.isEmpty()) return@TextButton
                    scope.launch { runCatching { d.rename(id, nt) }.onSuccess { r = r?.copy(title = nt) }.onFailure(onErr) }
                }) { Text("Сохранить", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена", color = c.muted) } },
        )
    }
    if (del) {
        AlertDialog(
            onDismissRequest = { del = false }, containerColor = c.card,
            title = { Text("Удалить запись?") },
            text = { Text("Аудио, расшифровка и задачи удалятся. Вернуть не получится.") },
            confirmButton = {
                TextButton(onClick = {
                    del = false
                    scope.launch { runCatching { d.delete(id) }.onSuccess { onTasksChanged(); onDeleted() }.onFailure(onErr) }
                }) { Text("Удалить", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { del = false }) { Text("Отмена", color = c.muted) } },
        )
    }
}

/** Тело карточки: сводка, задачи, расшифровка. seek != null — абзацы кликабельны (есть плеер). */
@Composable
fun RecBody(rec: RecFull, d: Dannye, pl: PlayerState?, onErr: (Throwable) -> Unit, onTasksChanged: () -> Unit) {
    val c = cv()
    val sm = remember(rec.summary) { Svodka.parse(rec.summary) }
    val abz = remember(rec.transcript) { Rasshifrovka.parse(rec.transcript) }
    val state = rememberLazyListState()
    // подсветка и автопрокрутка к звучащему абзацу
    val nowIdx = pl?.let { p -> abz.indexOfLast { it.sec <= p.posSec + 0.3 } } ?: -1
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        if (rec.status != "done") item {
            Spacer(Modifier.height(16.dp))
            Kartochka { Text(if (rec.status == "error") "Не удалось обработать: ${rec.error}" else "Запись ещё обрабатывается — обновите через минуту.",
                style = T.small, color = c.muted, modifier = Modifier.padding(16.dp)) }
        }
        sm["Коротко"]?.takeIf { it.isNotEmpty() }?.let { k ->
            item { Razdel("Коротко"); Kartochka { Text(k.joinToString(" "), style = T.body, color = c.ink, modifier = Modifier.padding(16.dp)) } }
        }
        if (rec.tasks.isNotEmpty()) item {
            Razdel("Задачи")
            Kartochka { rec.tasks.forEachIndexed { i, t -> if (i > 0) HorizontalDivider(color = c.line); TaskRow(t, null, d, onErr) { onTasksChanged() } } }
        }
        for (k in listOf("Решения", "Цифры и факты", "Открытые вопросы")) {
            val v = sm[k]?.takeIf { it.isNotEmpty() } ?: continue
            item {
                Razdel(k)
                Kartochka {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        v.forEach { line -> Row { Text("•  ", style = T.body, color = c.faint); Text(line, style = T.body, color = c.ink) } }
                    }
                }
            }
        }
        if (abz.isNotEmpty()) {
            item { Razdel("Расшифровка") }
            transcript(abz, nowIdx, pl)
        } else if (rec.status == "done") item {
            Spacer(Modifier.height(16.dp)); Kartochka { Text("Речь не распознана — тишина или шум.", style = T.small, color = c.muted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

private fun LazyListScope.transcript(abz: List<Rasshifrovka.Abzac>, nowIdx: Int, pl: PlayerState?) {
    items(abz.size) { i ->
        val c = cv()
        val a = abz[i]
        val first = i == 0; val last = i == abz.lastIndex
        val shape = RoundedCornerShape(topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp,
            bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp)
        val bg by animateColorAsState(if (i == nowIdx) c.accentSoft else c.card, label = "abz")
        Column(Modifier.fillMaxWidth().clip(shape).background(bg)) {
            if (!first) HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().clickable(enabled = pl != null) { pl?.seek(a.sec) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(Vremya.dur(a.sec.toDouble()), style = T.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = c.accent, modifier = Modifier.width(48.dp).padding(top = 3.dp))
                Text(a.text, style = T.body, color = c.ink)
            }
        }
    }
}

// ---------------- задачи ----------------
@Composable
fun TaskRow(t: Task, openRec: ((Long) -> Unit)?, d: Dannye, onErr: (Throwable) -> Unit, onChanged: (Boolean) -> Unit) {
    val c = cv()
    var done by remember(t.id, t.done) { mutableStateOf(t.done != 0) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp).size(26.dp).clip(RoundedCornerShape(7.dp))
                .background(if (done) c.ok else Color.Transparent)
                .border(2.dp, if (done) c.ok else c.faint, RoundedCornerShape(7.dp))
                .clickable {
                    val nv = !done; done = nv
                    scope.launch {
                        runCatching { d.setDone(t.id, nv) }.onSuccess { onChanged(nv) }.onFailure { done = !nv; onErr(it) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, "Выполнено", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!done) Vremya.srok(t.dueDate, t.dueTime)?.let { s ->
                Text(s.text, style = T.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = if (s.today || s.late) c.accent else c.muted)
            }
            Text(t.text, style = T.body, color = if (done) c.faint else c.ink,
                textDecoration = if (done) TextDecoration.LineThrough else null)
            if (openRec != null && t.recId != null) {
                val dt = Vremya.parse(t.recorded)
                Text(
                    listOfNotNull(dt?.let { Vremya.dayLabel(it.toLocalDate()) + ", " + Vremya.hm(it) }, t.title?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = T.meta, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp).clickable { openRec(t.recId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(d: Dannye, open: (Long) -> Unit, onErr: (Throwable) -> Unit, onTasksChanged: () -> Unit) {
    val c = cv()
    var doneTab by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Task>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(silent: Boolean = false) {
        runCatching { d.tasks(doneTab) }.onSuccess { items = it }.onFailure { if (!silent || it is AuthException) onErr(it) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(doneTab) {
        items = null
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            load(silent = items != null)
            while (true) { delay(30_000L); load(silent = true) }
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 14.dp)) {
                    Text("Задачи", style = T.h1, color = c.ink)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.line).padding(3.dp)) {
                        listOf(false to "Открытые", true to "Выполненные").forEach { (v, lab) ->
                            val on = doneTab == v
                            Text(lab, style = if (on) T.small.copy(fontWeight = FontWeight.SemiBold) else T.small,
                                color = if (on) c.ink else c.muted,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.card else Color.Transparent)
                                    .clickable { doneTab = v }.padding(horizontal = 14.dp, vertical = 7.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            val list = items
            when {
                list == null -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) } }
                list.isEmpty() -> item {
                    if (doneTab) Pusto("Пока ничего не закрыто", "")
                    else Pusto("Открытых задач нет", "Задачи появляются сами из записей — со сроками, если вы их назвали")
                }
                else -> item {
                    Kartochka {
                        list.forEachIndexed { i, t ->
                            key(t.id) {
                                if (i > 0) HorizontalDivider(color = c.line)
                                TaskRow(t, open, d, onErr) {
                                    onTasksChanged()
                                    // закрытая уезжает из «Открытых» (и наоборот), но не мгновенно — чтобы было видно галочку
                                    scope.launch { delay(600); items = items?.filter { it.id != t.id } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
