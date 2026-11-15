package dev.panoterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.term.ApprovalPrompt
import dev.panoterm.term.PortForward
import dev.panoterm.term.TermSession
import dev.panoterm.term.TermTable

private val Bg = Color(0xFF101014)
private val Panel = Color(0xFF1B1B22)
private val Grid = Color(0xFF3A3A46)

/** D2Coding loaded from the app's assets (same font as the terminal). */
@Composable
fun d2Coding(): FontFamily {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            FontFamily(
                Font("term/fonts/D2Coding-Regular.ttf", ctx.assets, FontWeight.Normal),
                Font("term/fonts/D2Coding-Bold.ttf", ctx.assets, FontWeight.Bold),
            )
        }.getOrDefault(FontFamily.Monospace)
    }
}

private fun copy(ctx: android.content.Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
}

/** Display width in terminal cells (CJK = 2). */
private fun cellWidth(s: String): Int = s.fold(0) { acc, c ->
    val cp = c.code
    acc + if (cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFF60) 2 else 1
}

// ------------------------------------------------------------------ table lens

@Composable
fun TableLens(tables: List<TermTable>, initialFont: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val font = d2Coding()
    var index by remember(tables) { mutableIntStateOf((tables.size - 1).coerceAtLeast(0)) }
    var fs by remember { mutableIntStateOf((initialFont + 2).coerceIn(10, 28)) }
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕ 닫기") }
            if (tables.isNotEmpty()) {
                TextButton(onClick = { if (index > 0) index-- }, enabled = index > 0) { Text("◀") }
                Text("표 ${index + 1}/${tables.size}", color = Color.White)
                TextButton(onClick = { if (index < tables.size - 1) index++ }, enabled = index < tables.size - 1) { Text("▶") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { fs = (fs - 1).coerceAtLeast(8) }) { Text("A−") }
            TextButton(onClick = { fs = (fs + 1).coerceAtMost(32) }) { Text("A+") }
            if (tables.isNotEmpty()) {
                TextButton(onClick = { copy(ctx, "table", toMarkdown(tables[index])) }) { Text("복사") }
            }
        }
        if (tables.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("화면 기록에서 표를 찾지 못했습니다.\n넓게(↔) 모드에서 Claude가 표를 그리게 하면 잘 인식됩니다.", color = Color.LightGray)
            }
            return@Column
        }
        val t = tables[index]
        val cols = t.rows.maxOf { it.size }
        val charDp = fs * 0.62f
        val widths = (0 until cols).map { c ->
            val longest = t.rows.maxOf { r -> cellWidth(r.getOrElse(c) { "" }) }
            (longest.coerceIn(4, 28) * charDp + 16).dp
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp)) {
            t.rows.forEachIndexed { ri, row ->
                val isHeader = t.header && ri == 0
                val bg = if (isHeader) Color(0xFF263238) else if (ri % 2 == 0) Bg else Color(0xFF16161C)
                Row(Modifier.background(bg)) {
                    // Frozen first column
                    LensCell(row.getOrElse(0) { "" }, widths[0], fs, font, isHeader || true)
                    Row(Modifier.horizontalScroll(hScroll)) {
                        for (c in 1 until cols) LensCell(row.getOrElse(c) { "" }, widths[c], fs, font, isHeader)
                    }
                }
            }
        }
    }
}

@Composable
private fun LensCell(text: String, w: androidx.compose.ui.unit.Dp, fs: Int, font: FontFamily, bold: Boolean) {
    Box(Modifier.width(w).border(0.5.dp, Grid).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(text, color = Color.White, fontSize = fs.sp, lineHeight = (fs * 1.3f).sp, fontFamily = font, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun toMarkdown(t: TermTable): String {
    val sb = StringBuilder()
    t.rows.forEachIndexed { i, r ->
        sb.append("| ").append(r.joinToString(" | ") { it.replace("|", "\\|") }).append(" |\n")
        if (i == 0) sb.append("|").append(r.joinToString("|") { " --- " }).append("|\n")
    }
    return sb.toString()
}

// ------------------------------------------------------------------ reader

@Composable
fun ReaderView(load: (lastOnly: Boolean, (String) -> Unit) -> Unit, initialFont: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val font = d2Coding()
    var lastOnly by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("불러오는 중…") }
    var fs by remember { mutableIntStateOf((initialFont + 3).coerceIn(10, 30)) }
    LaunchedEffect(lastOnly) { load(lastOnly) { text = it.ifBlank { "(내용 없음)" } } }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕ 닫기") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { lastOnly = true }) { Text(if (lastOnly) "● 마지막 응답" else "마지막 응답", maxLines = 1) }
                TextButton(onClick = { lastOnly = false }) { Text(if (!lastOnly) "● 최근 400줄" else "최근 400줄", maxLines = 1) }
                TextButton(onClick = { fs = (fs - 1).coerceAtLeast(8) }) { Text("A−") }
                TextButton(onClick = { fs = (fs + 1).coerceAtMost(36) }) { Text("A+") }
                TextButton(onClick = { copy(ctx, "reader", text) }) { Text("복사", maxLines = 1) }
            }
        }
        SelectionContainer(Modifier.fillMaxSize()) {
            Text(
                text,
                color = Color(0xFFE8E8E8),
                fontSize = fs.sp,
                lineHeight = (fs * 1.45f).sp,
                fontFamily = font,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ approval bar

@Composable
fun ApprovalBar(prompt: ApprovalPrompt, onChoose: (String) -> Unit, onEsc: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A237E)).padding(6.dp)) {
        Text(prompt.question, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            prompt.options.forEach { (n, label) ->
                Button(onClick = { onChoose(n) }) { Text("$n. ${label.take(28)}", maxLines = 1) }
            }
            OutlinedButton(onClick = onEsc) { Text("Esc") }
        }
    }
}

// ------------------------------------------------------------------ port forwards

@Composable
fun PortsDialog(session: TermSession, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val forwards by session.forwards.collectAsState()
    var port by remember { mutableStateOf("3000") }
    var host by remember { mutableStateOf("localhost") }
    var busy by remember { mutableStateOf(false) }

    fun open(f: PortForward) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${f.localPort}/")))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("포트 포워딩 미리보기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("서버의 개발 서버를 폰 브라우저에서 엽니다 (SSH 터널).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("서버 쪽 호스트") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("포트") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(
                    enabled = !busy && (port.toIntOrNull() ?: 0) in 1..65535,
                    onClick = {
                        busy = true
                        session.forward(host.ifBlank { "localhost" }, port.toInt()) { r ->
                            busy = false
                            r.onSuccess { open(it) }
                                .onFailure { Toast.makeText(ctx, "실패: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                ) { Text("연결하고 열기") }
                forwards.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.remoteHost}:${f.remotePort} → 127.0.0.1:${f.localPort}", Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { open(f) }) { Text("열기") }
                        TextButton(onClick = { session.stopForward(f) }) { Text("중지") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

// ------------------------------------------------------------------ command palette

@Composable
fun CommandPalette(items: List<dev.panoterm.data.Snippet>, onDismiss: () -> Unit, onPick: (dev.panoterm.data.Snippet) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isBlank() || it.label.contains(q, true) || it.text.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("명령") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { s ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 8.dp)) {
                            Text(s.label, color = Color.White, fontSize = 16.sp)
                            if (s.text != s.label || !s.submit) {
                                Text(
                                    s.text.replace('\n', ' ') + if (s.submit) "" else "  (Enter 안 누름)",
                                    color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (shown.isEmpty()) Text("없음. 설정 → 명령 편집에서 추가하세요.", color = Color.Gray)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun TablesChip(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.padding(8.dp).background(Color(0xCC1565C0), MaterialTheme.shapes.small)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("▦ 표 ${count}개 크게 보기", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.height(8.dp))
