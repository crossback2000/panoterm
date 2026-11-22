package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.R
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.term.AgentState
import dev.panoterm.term.TermSession
import dev.panoterm.term.TmuxWindow

private val Working = Color(0xFF42A5F5)
private val Waiting = Color(0xFFFFB300)
private val Idle = Color(0xFF66BB6A)
private val Offline = Color(0xFF757575)

fun agentColor(s: AgentState): Color = when (s) {
    AgentState.WAITING -> Waiting
    AgentState.WORKING -> Working
    AgentState.IDLE -> Idle
    AgentState.OFFLINE -> Offline
}

/**
 * Status tabs under the terminal's top bar: one chip per open session and, when the current
 * session runs tmux with several windows, one per window. The dot shows what is happening
 * there (blue pulsing = working, amber = waiting for the user, e.g. an approval 1/2/3,
 * green = idle, grey = disconnected). One tap switches.
 *
 * Shown only when there is something to switch between.
 */
@Composable
fun AgentTabs(sessions: List<TermSession>, current: TermSession, onSwitch: (TermSession) -> Unit) {
    val windows by current.windows.collectAsState()
    val showWindows = windows.size > 1
    if (sessions.size < 2 && !showWindows) return
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF101014))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sessions.size > 1) {
            sessions.forEach { s -> SessionChip(s, s.id == current.id) { if (s.id != current.id) onSwitch(s) } }
        }
        if (showWindows) {
            if (sessions.size > 1) {
                Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFF3A3A46)))
            }
            windows.forEach { w -> WindowChip(w) { if (!w.active) current.selectTmuxWindow(w.index) } }
        }
    }
}

@Composable
private fun SessionChip(s: TermSession, selected: Boolean, onTap: () -> Unit) {
    val st by s.agent.collectAsState()
    Chip(s.host.title + (s.label?.let { " · $it" } ?: ""), st, selected, onTap)
}

@Composable
private fun WindowChip(w: TmuxWindow, onTap: () -> Unit) {
    val name = w.name.ifBlank { w.command }
    Chip("${w.index}:$name", w.state, w.active, onTap)
}

@Composable
private fun Chip(label: String, state: AgentState, selected: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        state == AgentState.WAITING -> Color(0x40FFB300)
        selected -> Color(0xFF263238)
        else -> Color(0xFF1B1B22)
    }
    Row(
        Modifier.clip(shape).background(bg)
            .then(if (selected) Modifier.border(1.dp, Color(0xFF90A4AE), shape) else Modifier)
            // No focus taken: the soft keyboard stays attached to the terminal.
            .pointerInput(onTap) { detectTapGestures { onTap() } }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(state)
        Spacer(Modifier.width(5.dp))
        Text(
            label + if (state == AgentState.WAITING) stringResource(R.string.agent_waiting_suffix) else "",
            color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

@Composable
fun StateDot(state: AgentState, size: Int = 8) {
    val alpha = if (state == AgentState.WORKING) {
        val t = rememberInfiniteTransition(label = "working")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Box(Modifier.size(size.dp).alpha(alpha).clip(CircleShape).background(agentColor(state)))
}
