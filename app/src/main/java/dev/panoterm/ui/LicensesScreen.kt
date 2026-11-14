package dev.panoterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One third-party component. [file] is relative to assets/licenses/. Keep in sync with THIRD_PARTY_NOTICES.md. */
private data class Notice(val name: String, val license: String, val copyright: String, val file: String)

private val NOTICES = listOf(
    Notice("D2Coding 폰트 1.3.2", "SIL Open Font License 1.1", "© 2015 NAVER Corporation", "D2Coding.OFL.txt"),
    Notice("xterm.js 5.5.0", "MIT", "© The xterm.js authors, SourceLair, Christopher Jeffrey", "xterm.LICENSE.txt"),
    Notice("@xterm/addon-fit 0.10.0", "MIT", "© The xterm.js authors", "xterm-addon-fit.LICENSE.txt"),
    Notice("@xterm/addon-unicode11 0.8.0", "MIT", "© The xterm.js authors", "xterm-addon-unicode11.LICENSE.txt"),
    Notice("Tailscale (tsnet)", "BSD-3-Clause", "© 2020 Tailscale Inc & contributors", "tailscale.LICENSE.txt"),
    Notice("wireguard-go", "MIT", "© WireGuard LLC", "wireguard-go.LICENSE.txt"),
    Notice("Go 표준 라이브러리", "BSD-3-Clause", "© 2009 The Go Authors", "go.LICENSE.txt"),
    Notice("golang.org/x/crypto", "BSD-3-Clause", "© The Go Authors", "golang.org-x-crypto.LICENSE.txt"),
    Notice("golang.org/x/mobile", "BSD-3-Clause", "© The Go Authors", "golang.org-x-mobile.LICENSE.txt"),
    Notice("AndroidX · Jetpack Compose", "Apache-2.0", "© The Android Open Source Project", "Apache-2.0.txt"),
    Notice("Kotlin · kotlinx.coroutines", "Apache-2.0", "© JetBrains s.r.o. and contributors", "Apache-2.0.txt"),
)

private const val GO_MODULES = "go-modules.csv"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<Pair<String, String>?>(null) } // title, file

    val hasGoList = remember {
        runCatching { ctx.assets.list("licenses")?.contains(GO_MODULES) == true }.getOrDefault(false)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(open?.first ?: "오픈소스 라이선스") },
                navigationIcon = {
                    TextButton(onClick = { if (open != null) open = null else onBack() }) { Text("←") }
                },
            )
        },
    ) { pad ->
        val o = open
        if (o != null) {
            val text = remember(o.second) {
                runCatching { ctx.assets.open("licenses/${o.second}").bufferedReader().use { it.readText() } }
                    .getOrElse { "라이선스 파일을 찾을 수 없습니다: ${o.second}" }
            }
            Column(
                Modifier.fillMaxSize().padding(pad).padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Text(
                        "PanoTerm는 아래 오픈소스 소프트웨어와 폰트를 사용합니다. 항목을 누르면 라이선스 전문을 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(NOTICES) { n ->
                    Column(
                        Modifier.fillMaxWidth().clickable { open = n.name to n.file }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(n.name, style = MaterialTheme.typography.titleSmall)
                        Text("${n.license} · ${n.copyright}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = hasGoList) { open = "Go 모듈 전체 목록" to GO_MODULES }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Go 모듈 전체 목록 (간접 의존성 포함)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hasGoList) "go-licenses로 생성된 목록" else "아직 생성되지 않음 — scripts/gen-go-licenses.ps1 실행 필요",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Text(
                        "Tailscale은 Tailscale Inc.의, WireGuard는 Jason A. Donenfeld의, Claude는 Anthropic의 상표입니다. " +
                            "PanoTerm는 이들과 무관하며 보증받지 않았습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
