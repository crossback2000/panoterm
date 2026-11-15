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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed interface BarKey {
    val id: String
    val label: String

    /** Text. A single printable character honours sticky Ctrl/Alt. */
    data class Text(override val id: String, override val label: String, val text: String) : BarKey

    /** Raw control sequence; ignores sticky modifiers. */
    data class Raw(override val id: String, override val label: String, val bytes: String) : BarKey

    /** Special key encoded by KeyEncoder (honours sticky modifiers and cursor mode). */
    data class Code(override val id: String, override val label: String, val keyCode: Int, val shift: Boolean = false, val repeat: Boolean = false) : BarKey

    data class Toggle(override val id: String, override val label: String, val ctrl: Boolean) : BarKey

    /** Cursor pad: drag left/right (or up/down) to send arrow keys. */
    data class Pad(override val id: String, override val label: String) : BarKey

    /** Opens the command palette. */
    data class Palette(override val id: String, override val label: String) : BarKey
}

/** Every key that can be placed on the bottom row (order here = order in the editor). */
val ALL_KEYS: List<BarKey> = listOf(
    BarKey.Code("esc", "Esc", KeyEvent.KEYCODE_ESCAPE),
    BarKey.Code("stab", "⇧Tab", KeyEvent.KEYCODE_TAB, shift = true),
    BarKey.Raw("esc2", "Esc²", "\u001b\u001b"),
    BarKey.Raw("ctrlc", "^C", "\u0003"),
    BarKey.Code("tab", "Tab", KeyEvent.KEYCODE_TAB),
    BarKey.Toggle("ctrl", "Ctrl", ctrl = true),
    BarKey.Toggle("alt", "Alt", ctrl = false),
    BarKey.Pad("pad", "◀ ▶"),
    BarKey.Code("left", "←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
    BarKey.Code("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
    BarKey.Code("up", "↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
    BarKey.Code("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
    BarKey.Palette("palette", "⚡"),
    BarKey.Text("slash", "/", "/"),
    BarKey.Text("at", "@", "@"),
    BarKey.Text("pipe", "|", "|"),
    BarKey.Text("tilde", "~", "~"),
    BarKey.Text("dash", "-", "-"),
    BarKey.Text("bang", "!", "!"),
    BarKey.Text("hash", "#", "#"),
    BarKey.Code("bs", "⌫", KeyEvent.KEYCODE_DEL, repeat = true),
    BarKey.Code("home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
    BarKey.Code("end", "End", KeyEvent.KEYCODE_MOVE_END),
    BarKey.Raw("ctrlr", "^R", "\u0012"),
    BarKey.Raw("ctrlo", "^O", "\u000f"),
    BarKey.Raw("ctrld", "^D", "\u0004"),
    BarKey.Raw("nl", "⇧⏎ 줄바꿈", "\u001b\r"),
)

val DEFAULT_KEY_IDS = listOf(
    "esc", "pad", "left", "down", "up", "right", "palette", "slash", "bs",
)

fun keysFor(ids: List<String>): List<BarKey> {
    val byId = ALL_KEYS.associateBy { it.id }
    return (ids.ifEmpty { DEFAULT_KEY_IDS }).mapNotNull { byId[it] }
}

/** tmux row (top, optional). Assumes the default prefix Ctrl+B. */
val TmuxKeys = listOf(
    BarKey.Raw("t_pre", "^B", "\u0002"),
    BarKey.Raw("t_new", "새 창", "\u0002c"),
    BarKey.Raw("t_next", "다음", "\u0002n"),
    BarKey.Raw("t_prev", "이전", "\u0002p"),
    BarKey.Raw("t_zoom", "확대", "\u0002z"),
    BarKey.Raw("t_copy", "스크롤", "\u0002["),
    BarKey.Raw("t_det", "분리", "\u0002d"),
    BarKey.Code("t_pgup", "PgUp", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
    BarKey.Code("t_pgdn", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
    BarKey.Text("t_q", "q", "q"),
)

val EnterKey = BarKey.Raw("enter", "⏎ Enter", "\r")

/** What a key press means for the terminal. Pad drags arrive as Code keys. */
@Composable
fun KeyBar(
    keys: List<BarKey>,
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
                keys.forEach { k ->
                    val active = k is BarKey.Toggle && (if (k.ctrl) ctrlOn else altOn)
                    if (k is BarKey.Pad) CursorPad(k, onKey) else KeyCap(k, active = active, onKey = onKey)
                }
            }
            KeyCap(EnterKey, active = false, wide = true, accent = true, onKey = onKey)
        }
    }
}

private val ARROW_LEFT = BarKey.Code("left", "←", KeyEvent.KEYCODE_DPAD_LEFT)
private val ARROW_RIGHT = BarKey.Code("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT)
private val ARROW_UP = BarKey.Code("up", "↑", KeyEvent.KEYCODE_DPAD_UP)
private val ARROW_DOWN = BarKey.Code("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN)

/** Drag on this key to move the cursor: every ~18dp of travel sends one arrow key. */
@Composable
private fun CursorPad(k: BarKey, onKey: (BarKey) -> Unit) {
    val view = LocalView.current
    val fire by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(42.dp)
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (pressed) Color(0xFF5A5A6A) else Color(0xFF263238))
            .pointerInput(Unit) {
                val step = 18.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var accX = 0f
                    var accY = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.positionChange()
                        ch.consume() // keep the row from scrolling while using the pad
                        accX += d.x; accY += d.y
                        while (abs(accX) >= step) {
                            fire(if (accX > 0) ARROW_RIGHT else ARROW_LEFT)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accX -= if (accX > 0) step else -step
                        }
                        while (abs(accY) >= step * 1.5f) {
                            fire(if (accY > 0) ARROW_DOWN else ARROW_UP)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accY -= if (accY > 0) step * 1.5f else -step * 1.5f
                        }
                    }
                    pressed = false
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(k.label, color = Color(0xFF80CBC4), fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * Key cap using raw pointer input (not clickable) so tapping never steals focus
 * from the terminal input view — the soft keyboard stays open.
 * Fires on release; repeat keys auto-repeat while held. Scrolling the row cancels.
 */
@Composable
fun KeyCap(k: BarKey, active: Boolean, wide: Boolean = false, accent: Boolean = false, onKey: (BarKey) -> Unit) {
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
