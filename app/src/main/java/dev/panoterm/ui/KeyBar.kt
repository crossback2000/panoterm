package dev.panoterm.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface BarKey {
    val label: String

    /** Raw bytes. A single printable character honours sticky Ctrl/Alt. */
    data class Text(override val label: String, val text: String) : BarKey

    /** Raw control sequence; ignores sticky modifiers. */
    data class Raw(override val label: String, val bytes: String) : BarKey

    /** Special key encoded by KeyEncoder (honours sticky modifiers and cursor mode). */
    data class Code(override val label: String, val keyCode: Int, val shift: Boolean = false, val repeat: Boolean = false) : BarKey

    data class Toggle(override val label: String, val ctrl: Boolean) : BarKey
}

/** Claude Code row (bottom). ⏎ Enter is pinned at the right edge. */
val ClaudeKeys = listOf(
    BarKey.Code("Esc", KeyEvent.KEYCODE_ESCAPE),
    BarKey.Code("⇧Tab", KeyEvent.KEYCODE_TAB, shift = true),
    BarKey.Raw("Esc²", "\u001b\u001b"),
    BarKey.Raw("^C", "\u0003"),
    BarKey.Code("Tab", KeyEvent.KEYCODE_TAB),
    BarKey.Toggle("Ctrl", ctrl = true),
    BarKey.Toggle("Alt", ctrl = false),
    BarKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
    BarKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
    BarKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
    BarKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
    BarKey.Text("/", "/"),
    BarKey.Text("@", "@"),
    BarKey.Text("|", "|"),
    BarKey.Text("~", "~"),
    BarKey.Text("-", "-"),
    BarKey.Code("⌫", KeyEvent.KEYCODE_DEL, repeat = true),
    BarKey.Raw("⇧⏎ 줄바꿈", "\u001b\r"),
)

/** tmux row (top, optional). Assumes the default prefix Ctrl+B. */
val TmuxKeys = listOf(
    BarKey.Raw("^B", "\u0002"),
    BarKey.Raw("새 창", "\u0002c"),
    BarKey.Raw("다음", "\u0002n"),
    BarKey.Raw("이전", "\u0002p"),
    BarKey.Raw("확대", "\u0002z"),
    BarKey.Raw("스크롤", "\u0002["),
    BarKey.Raw("분리", "\u0002d"),
    BarKey.Code("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
    BarKey.Code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
    BarKey.Text("q", "q"),
)

val EnterKey = BarKey.Raw("⏎ Enter", "\r")

@Composable
fun KeyBar(
    showTmux: Boolean,
    ctrlOn: Boolean,
    altOn: Boolean,
    onKey: (BarKey) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF15151A)).padding(horizontal = 2.dp, vertical = 2.dp)) {
        if (showTmux) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TmuxKeys.forEach { KeyCap(it, active = false, onKey = onKey) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                ClaudeKeys.forEach { k ->
                    val active = k is BarKey.Toggle && (if (k.ctrl) ctrlOn else altOn)
                    KeyCap(k, active = active, onKey = onKey)
                }
            }
            KeyCap(EnterKey, active = false, wide = true, accent = true, onKey = onKey)
        }
    }
}

/**
 * Key cap using raw pointer input (not clickable) so tapping never steals focus
 * from the terminal input view — the soft keyboard stays open.
 * Fires on release; repeat keys auto-repeat while held. Scrolling the row cancels.
 */
@Composable
private fun KeyCap(k: BarKey, active: Boolean, wide: Boolean = false, accent: Boolean = false, onKey: (BarKey) -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val fire by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    val repeat = (k as? BarKey.Code)?.repeat == true

    val bg = when {
        pressed -> Color(0xFF5A5A6A)
        active -> Color(0xFF3D5AFE)
        accent -> Color(0xFF2E7D32)
        else -> Color(0xFF2B2B33)
    }
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(42.dp)
            .widthIn(min = if (wide) 84.dp else 44.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .pointerInput(k) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var fired = false
                    var job: Job? = null
                    if (repeat) {
                        job = scope.launch {
                            delay(350)
                            while (true) {
                                fired = true
                                fire(k)
                                delay(45)
                            }
                        }
                    }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    if (up != null && !fired) fire(k)
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k.label,
            color = Color.White,
            fontSize = if (wide) 16.sp else 15.sp,
            fontWeight = if (wide) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
