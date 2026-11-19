package dev.panoterm.term

/**
 * Small line-based syntax highlighter for the file viewer/editor. It doesn't parse: it marks
 * comments, strings, numbers, keywords and a few language-specific things, which is what makes
 * code readable on a phone. State (block comment, multi-line string, code fence) carries from
 * one line to the next, so lines can be highlighted lazily in order.
 */
object Syntax {
    enum class Tok { KEYWORD, CONSTANT, STRING, COMMENT, NUMBER, TYPE, FUNCTION, KEY, VARIABLE, HEADING, PUNCT, VALUE, TAG }

    /** [start, end) within the line. */
    data class Span(val start: Int, val end: Int, val tok: Tok)

    enum class Kind { CODE, ENV, YAML, MARKDOWN, XML, PLAIN }

    class Lang(
        val name: String,
        val kind: Kind = Kind.CODE,
        val lineComments: List<String> = emptyList(),
        val block: Pair<String, String>? = null,
        /** String delimiters, longest first. */
        val strings: List<String> = listOf("\"", "'"),
        /** Delimiters whose strings may continue on the next lines. */
        val multiline: Set<String> = emptySet(),
        /** Delimiters without backslash escapes. */
        val raw: Set<String> = emptySet(),
        val keywords: Set<String> = emptySet(),
        val constants: Set<String> = emptySet(),
        val ignoreCase: Boolean = false,
        /** $VAR / ${VAR} as variables (shell, Makefile, Dockerfile). */
        val dollarVars: Boolean = false,
        /** Capitalised identifiers are types. */
        val types: Boolean = true,
        /** A string followed by ':' is a key (JSON). */
        val keyStrings: Boolean = false,
    )

    /** Highlighter state between lines. */
    sealed interface State {
        data object Normal : State
        data object InBlockComment : State
        data class InString(val delim: String) : State
        data object InFence : State
    }

    private fun words(s: String) = s.split(' ', '\n').filter { it.isNotBlank() }.toSet()

    private val C_LIKE_CONST = words("true false null")

    val PLAIN = Lang("텍스트", Kind.PLAIN)
    val ENV = Lang("env", Kind.ENV)
    val INI = Lang("ini", Kind.ENV)
    val YAML = Lang("yaml", Kind.YAML)
    val MARKDOWN = Lang("markdown", Kind.MARKDOWN)
    val XML = Lang("xml", Kind.XML)

    val KOTLIN = Lang(
        "kotlin", lineComments = listOf("//"), block = "/*" to "*/",
        strings = listOf("\"\"\"", "\"", "'"), multiline = setOf("\"\"\""), raw = setOf("\"\"\""),
        keywords = words(
            "package import class interface object fun val var if else when for while do return break continue " +
                "try catch finally throw is in as by get set init constructor companion data sealed enum open " +
                "abstract override private protected internal public inline suspend operator infix lateinit const " +
                "this super typealias where out vararg reified annotation"
        ),
        constants = C_LIKE_CONST,
    )
    val JAVA = Lang(
        "java", lineComments = listOf("//"), block = "/*" to "*/",
        strings = listOf("\"\"\"", "\"", "'"), multiline = setOf("\"\"\""),
        keywords = words(
            "package import class interface enum extends implements new return if else for while do switch case " +
                "default break continue try catch finally throw throws public private protected static final abstract " +
                "synchronized volatile transient native void int long short byte char boolean float double this super " +
                "instanceof var record sealed permits yield"
        ),
        constants = C_LIKE_CONST,
    )
    val GO = Lang(
        "go", lineComments = listOf("//"), block = "/*" to "*/",
        strings = listOf("\"", "'", "`"), multiline = setOf("`"), raw = setOf("`"),
        keywords = words(
            "package import func var const type struct interface map chan go defer return if else for range switch " +
                "case default break continue fallthrough goto select"
        ),
        constants = words("true false nil iota"),
    )
    val JS = Lang(
        "javascript", lineComments = listOf("//"), block = "/*" to "*/",
        strings = listOf("\"", "'", "`"), multiline = setOf("`"),
        keywords = words(
            "var let const function return if else for while do switch case default break continue new delete typeof " +
                "instanceof in of class extends super this import export from as async await yield try catch finally " +
                "throw void static get set interface type enum implements private public protected readonly declare " +
                "namespace keyof"
        ),
        constants = words("true false null undefined NaN Infinity"),
    )
    val PYTHON = Lang(
        "python", lineComments = listOf("#"),
        strings = listOf("\"\"\"", "'''", "\"", "'"), multiline = setOf("\"\"\"", "'''"),
        keywords = words(
            "def class return if elif else for while in not and or is import from as with try except finally raise " +
                "pass break continue lambda yield global nonlocal assert del async await match case self"
        ),
        constants = words("True False None"),
    )
    val SHELL = Lang(
        "shell", lineComments = listOf("#"), raw = setOf("'"), multiline = setOf("\"", "'"),
        keywords = words(
            "if then else elif fi for while until do done case esac in function return exit export local readonly " +
                "declare set unset shift source alias eval exec trap break continue select"
        ),
        constants = words("true false"), dollarVars = true, types = false,
    )
    val RUST = Lang(
        "rust", lineComments = listOf("//"), block = "/*" to "*/", strings = listOf("\""),
        keywords = words(
            "fn let mut const static struct enum trait impl for in if else match loop while return break continue " +
                "pub use mod crate self super Self where as ref move async await dyn unsafe extern type"
        ),
        constants = words("true false None Some Ok Err"),
    )
    val C = Lang(
        "c", lineComments = listOf("//"), block = "/*" to "*/",
        keywords = words(
            "int long short char unsigned signed float double void struct union enum typedef const static extern " +
                "return if else for while do switch case default break continue goto sizeof inline auto register " +
                "volatile class public private protected virtual override template typename namespace using new " +
                "delete this nullptr include define ifdef ifndef endif pragma bool"
        ),
        constants = words("true false NULL nullptr"),
    )
    val JSON = Lang("json", lineComments = listOf("//"), strings = listOf("\""), constants = C_LIKE_CONST, types = false, keyStrings = true)
    val SQL = Lang(
        "sql", lineComments = listOf("--"), block = "/*" to "*/", strings = listOf("'", "\""), ignoreCase = true, types = false,
        keywords = words(
            "select from where and or not insert into values update set delete create table index view drop alter add " +
                "column primary key foreign references join left right inner outer on group by order having limit offset " +
                "as distinct union all case when then else end is in like between exists default unique integer text " +
                "varchar boolean begin commit rollback"
        ),
        constants = words("null true false"),
    )
    val CSS = Lang(
        "css", block = "/*" to "*/", lineComments = emptyList(), types = false,
        keywords = words("important media import keyframes from to root"),
    )
    val DOCKERFILE = Lang(
        "dockerfile", lineComments = listOf("#"), dollarVars = true, types = false, ignoreCase = true,
        keywords = words("from run cmd label expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as"),
    )
    val MAKEFILE = Lang("makefile", lineComments = listOf("#"), dollarVars = true, types = false, keywords = words("ifeq ifneq ifdef ifndef else endif include define endef export"))
    val TOML = Lang("toml", Kind.ENV)

    /** Picks the language from the file name (and the first line for scripts without extension). */
    fun languageFor(fileName: String, firstLine: String = ""): Lang {
        val n = fileName.lowercase()
        when {
            n == ".env" || n.startsWith(".env.") || n.endsWith(".env") -> return ENV
            n == "dockerfile" || n.startsWith("dockerfile.") || n.endsWith(".dockerfile") -> return DOCKERFILE
            n == "makefile" || n == "gnumakefile" || n.endsWith(".mk") -> return MAKEFILE
            n in setOf(".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile", ".bash_aliases", ".zshenv") -> return SHELL
            n in setOf(".gitconfig", ".editorconfig", ".npmrc", ".gitmodules") -> return INI
            n in setOf(".gitignore", ".dockerignore", ".tmux.conf", "authorized_keys", "known_hosts") -> return Lang("conf", lineComments = listOf("#"), types = false, strings = emptyList())
        }
        val ext = n.substringAfterLast('.', "")
        val byExt = when (ext) {
            "kt", "kts" -> KOTLIN
            "java", "scala", "groovy", "gradle", "dart", "swift", "cs" -> JAVA
            "go" -> GO
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts", "vue", "svelte" -> JS
            "py", "pyi" -> PYTHON
            "sh", "bash", "zsh", "fish", "ksh" -> SHELL
            "rs" -> RUST
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm" -> C
            "json", "jsonc", "json5", "lock" -> if (n.endsWith(".lock") && !n.endsWith("package-lock.json")) null else JSON
            "yaml", "yml" -> YAML
            "toml" -> TOML
            "ini", "cfg", "conf", "properties", "service", "timer", "desktop" -> INI
            "md", "markdown", "mdx" -> MARKDOWN
            "xml", "html", "htm", "svg", "xhtml", "plist" -> XML
            "sql" -> SQL
            "css", "scss", "less" -> CSS
            else -> null
        }
        if (byExt != null) return byExt
        if (firstLine.startsWith("#!")) {
            return when {
                "python" in firstLine -> PYTHON
                "node" in firstLine || "deno" in firstLine || "bun" in firstLine -> JS
                else -> SHELL
            }
        }
        return PLAIN
    }

    /** Highlights [lines] in order (state carries across lines). */
    fun highlight(lines: List<String>, lang: Lang): List<List<Span>> {
        var st: State = State.Normal
        return lines.map { line ->
            val (spans, next) = line(line, lang, st)
            st = next
            spans
        }
    }

    /** One line: its spans and the state for the next line. */
    fun line(text: String, lang: Lang, state: State): Pair<List<Span>, State> = when (lang.kind) {
        Kind.CODE -> code(text, lang, state)
        Kind.ENV -> env(text, lang) to State.Normal
        Kind.YAML -> yaml(text) to State.Normal
        Kind.MARKDOWN -> markdown(text, state)
        Kind.XML -> xml(text, state)
        Kind.PLAIN -> emptyList<Span>() to State.Normal
    }

    private fun isIdStart(c: Char) = c.isLetter() || c == '_'
    private fun isId(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Index after the closing [delim] starting the search at [from], or -1 if not on this line. */
    private fun closeString(t: String, from: Int, delim: String, raw: Boolean): Int {
        var i = from
        while (i < t.length) {
            if (!raw && t[i] == '\\') { i += 2; continue }
            if (t.startsWith(delim, i)) return i + delim.length
            i++
        }
        return -1
    }

    private fun code(t: String, lang: Lang, state0: State): Pair<List<Span>, State> {
        val out = ArrayList<Span>()
        var i = 0
        var state = state0
        // Carry-over from the previous line.
        when (state) {
            is State.InBlockComment -> {
                val end = t.indexOf(lang.block!!.second)
                if (end < 0) return listOf(Span(0, t.length, Tok.COMMENT)) to state
                i = end + lang.block.second.length
                out += Span(0, i, Tok.COMMENT)
                state = State.Normal
            }
            is State.InString -> {
                val end = closeString(t, 0, state.delim, state.delim in lang.raw)
                if (end < 0) return listOf(Span(0, t.length, Tok.STRING)) to state
                out += Span(0, end, Tok.STRING)
                i = end
                state = State.Normal
            }
            else -> {}
        }
        while (i < t.length) {
            val c = t[i]
            if (c == ' ' || c == '\t') { i++; continue }
            // Line comment ("#" only at a word boundary: shell's a#b is not a comment).
            val lc = lang.lineComments.firstOrNull { t.startsWith(it, i) }
            if (lc != null && (lc != "#" || i == 0 || t[i - 1].isWhitespace())) {
                out += Span(i, t.length, Tok.COMMENT)
                return out to State.Normal
            }
            val b = lang.block
            if (b != null && t.startsWith(b.first, i)) {
                val end = t.indexOf(b.second, i + b.first.length)
                if (end < 0) {
                    out += Span(i, t.length, Tok.COMMENT)
                    return out to State.InBlockComment
                }
                out += Span(i, end + b.second.length, Tok.COMMENT)
                i = end + b.second.length
                continue
            }
            val sd = lang.strings.firstOrNull { t.startsWith(it, i) }
            if (sd != null) {
                val end = closeString(t, i + sd.length, sd, sd in lang.raw)
                if (end < 0) {
                    out += Span(i, t.length, Tok.STRING)
                    return out to if (sd in lang.multiline) State.InString(sd) else State.Normal
                }
                var tok = Tok.STRING
                if (lang.keyStrings) {
                    var j = end
                    while (j < t.length && t[j] == ' ') j++
                    if (j < t.length && t[j] == ':') tok = Tok.KEY
                }
                out += Span(i, end, tok)
                i = end
                continue
            }
            if (lang.dollarVars && c == '$' && i + 1 < t.length) {
                val n = t[i + 1]
                val end = when {
                    n == '{' || n == '(' -> t.indexOf(if (n == '{') '}' else ')', i + 2).let { if (it < 0) t.length else it + 1 }
                    isIdStart(n) -> { var j = i + 1; while (j < t.length && isId(t[j])) j++; j }
                    n.isDigit() || n in "@*#?$!-" -> i + 2
                    else -> -1
                }
                if (end > 0) { out += Span(i, end, Tok.VARIABLE); i = end; continue }
            }
            if (c.isDigit() && (i == 0 || !isId(t[i - 1]))) {
                var j = i + 1
                while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_' || (t[j] == '.' && j + 1 < t.length && t[j + 1].isDigit()))) j++
                out += Span(i, j, Tok.NUMBER)
                i = j
                continue
            }
            if (isIdStart(c) || (c == '@' && i + 1 < t.length && isIdStart(t[i + 1]))) {
                var j = i + 1
                while (j < t.length && (isId(t[j]) || (lang.kind == Kind.CODE && lang === CSS && t[j] == '-'))) j++
                val w = t.substring(i, j)
                val key = if (lang.ignoreCase) w.lowercase() else w
                val tok = when {
                    c == '@' -> Tok.TYPE // annotations / decorators
                    key in lang.keywords -> Tok.KEYWORD
                    key in lang.constants -> Tok.CONSTANT
                    lang.types && w[0].isUpperCase() && w.length > 1 -> Tok.TYPE
                    else -> {
                        var k = j
                        while (k < t.length && t[k] == ' ') k++
                        if (k < t.length && t[k] == '(' && lang.types) Tok.FUNCTION else null
                    }
                }
                if (tok != null) out += Span(i, j, tok)
                i = j
                continue
            }
            i++
        }
        return out to State.Normal
    }

    /** .env / ini / toml / properties: comments, [sections], key = value. */
    private fun env(t: String, lang: Lang): List<Span> {
        val lead = t.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return emptyList()
        val c = t[lead]
        if (c == '#' || c == ';' || (c == '!' && lang === INI)) return listOf(Span(lead, t.length, Tok.COMMENT))
        if (c == '[') return listOf(Span(lead, t.length, Tok.HEADING))
        val out = ArrayList<Span>()
        var k = lead
        if (t.startsWith("export ", lead)) {
            out += Span(lead, lead + 6, Tok.KEYWORD)
            k = lead + 7
            while (k < t.length && t[k] == ' ') k++
        }
        val eq = t.indexOfAny(charArrayOf('=', ':'), k).takeIf { it >= 0 && (t[it] == '=' || lang === INI) } ?: return out
        val keyEnd = t.substring(k, eq).trimEnd().length + k
        if (keyEnd > k) out += Span(k, keyEnd, Tok.KEY)
        out += Span(eq, eq + 1, Tok.PUNCT)
        var v = eq + 1
        while (v < t.length && t[v] == ' ') v++
        if (v >= t.length) return out
        // Quoted values end at the closing quote; unquoted ones at " #".
        val q = t[v]
        var end = if (q == '"' || q == '\'') {
            closeString(t, v + 1, q.toString(), q == '\'').let { if (it < 0) t.length else it }
        } else {
            t.indexOf(" #", v).let { if (it < 0) t.length else it }
        }
        end = t.substring(v, end).trimEnd().length + v
        val valueTok = if (lang === TOML || lang === INI) valueKind(t.substring(v, end)) else Tok.VALUE
        out += Span(v, end, valueTok)
        val hash = t.indexOf('#', end)
        if (hash >= 0) out += Span(hash, t.length, Tok.COMMENT)
        return out
    }

    private fun valueKind(v: String): Tok = when {
        v.toDoubleOrNull() != null -> Tok.NUMBER
        v.lowercase() in setOf("true", "false", "yes", "no", "on", "off", "null", "~") -> Tok.CONSTANT
        else -> Tok.VALUE
    }

    private val YAML_KEY = Regex("^(\\s*)(- +)?([^#'\"\\s][^#]*?|\"[^\"]*\"|'[^']*')\\s*:(\\s|$)")

    private fun yaml(t: String): List<Span> {
        val out = ArrayList<Span>()
        val lead = t.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return out
        if (t[lead] == '#') return listOf(Span(lead, t.length, Tok.COMMENT))
        if (t.startsWith("---") || t.startsWith("...")) return listOf(Span(0, t.length, Tok.PUNCT))
        var v = lead
        val m = YAML_KEY.find(t)
        if (m != null) {
            m.groups[2]?.let { out += Span(it.range.first, it.range.first + 1, Tok.PUNCT) }
            val k = m.groups[3]!!.range
            out += Span(k.first, k.last + 1, Tok.KEY)
            v = m.groups[3]!!.range.last + 1
            v = t.indexOf(':', v) + 1
        } else if (t.startsWith("- ", lead)) {
            out += Span(lead, lead + 1, Tok.PUNCT)
            v = lead + 2
        }
        while (v < t.length && t[v] == ' ') v++
        if (v >= t.length) return out
        var end = t.length
        val q = t[v]
        if (q == '"' || q == '\'') {
            end = closeString(t, v + 1, q.toString(), q == '\'').let { if (it < 0) t.length else it }
        } else {
            val h = t.indexOf(" #", v)
            if (h >= 0) end = h
        }
        val value = t.substring(v, end).trimEnd()
        if (value.isNotEmpty()) {
            val tok = when {
                q == '"' || q == '\'' -> Tok.STRING
                q == '|' || q == '>' || q == '&' || q == '*' || q == '!' -> Tok.PUNCT
                else -> valueKind(value).let { if (it == Tok.VALUE) Tok.STRING else it }
            }
            out += Span(v, v + value.length, tok)
        }
        val h = t.indexOf('#', v + value.length)
        if (h >= 0 && (h == 0 || t[h - 1] == ' ')) out += Span(h, t.length, Tok.COMMENT)
        return out
    }

    private val MD_INLINE = Regex("(`[^`]+`)|(\\*\\*[^*]+\\*\\*|__[^_]+__)|(\\[[^\\]]+\\]\\([^)]+\\))")

    private fun markdown(t: String, state: State): Pair<List<Span>, State> {
        val trimmed = t.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            return listOf(Span(0, t.length, Tok.PUNCT)) to if (state == State.InFence) State.Normal else State.InFence
        }
        if (state == State.InFence) return listOf(Span(0, t.length, Tok.STRING)) to state
        if (trimmed.startsWith("#")) return listOf(Span(0, t.length, Tok.HEADING)) to State.Normal
        if (trimmed.startsWith(">")) return listOf(Span(0, t.length, Tok.COMMENT)) to State.Normal
        val out = ArrayList<Span>()
        val lead = t.length - trimmed.length
        Regex("^([-*+]|\\d+[.)])\\s").find(trimmed)?.let { out += Span(lead, lead + it.groupValues[1].length, Tok.PUNCT) }
        for (m in MD_INLINE.findAll(t)) {
            val tok = when {
                m.groups[1] != null -> Tok.STRING
                m.groups[2] != null -> Tok.KEYWORD
                else -> Tok.FUNCTION
            }
            out += Span(m.range.first, m.range.last + 1, tok)
        }
        return out to State.Normal
    }

    private val XML_TAG = Regex("</?[A-Za-z_][\\w:.-]*|/?>")
    private val XML_ATTR = Regex("([A-Za-z_:][\\w:.-]*)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')")

    private fun xml(t: String, state: State): Pair<List<Span>, State> {
        val out = ArrayList<Span>()
        var i = 0
        if (state == State.InBlockComment) {
            val end = t.indexOf("-->")
            if (end < 0) return listOf(Span(0, t.length, Tok.COMMENT)) to state
            out += Span(0, end + 3, Tok.COMMENT)
            i = end + 3
        }
        while (i < t.length) {
            val cs = t.indexOf("<!--", i)
            val segEnd = if (cs < 0) t.length else cs
            val seg = t.substring(i, segEnd)
            // Inside tags: tag names and attributes.
            var depth = false
            var j = 0
            while (j < seg.length) {
                if (!depth) {
                    val lt = seg.indexOf('<', j)
                    if (lt < 0) break
                    depth = true
                    j = lt
                    continue
                }
                val gt = seg.indexOf('>', j).let { if (it < 0) seg.length else it + 1 }
                val tagText = seg.substring(j, gt)
                XML_TAG.findAll(tagText).forEach { out += Span(i + j + it.range.first, i + j + it.range.last + 1, Tok.TAG) }
                XML_ATTR.findAll(tagText).forEach {
                    val a = it.groups[1]!!.range
                    val v = it.groups[3]!!.range
                    out += Span(i + j + a.first, i + j + a.last + 1, Tok.KEY)
                    out += Span(i + j + v.first, i + j + v.last + 1, Tok.STRING)
                }
                depth = false
                j = gt
            }
            if (cs < 0) break
            val ce = t.indexOf("-->", cs + 4)
            if (ce < 0) {
                out += Span(cs, t.length, Tok.COMMENT)
                return out.sortedBy { it.start } to State.InBlockComment
            }
            out += Span(cs, ce + 3, Tok.COMMENT)
            i = ce + 3
        }
        return out.sortedBy { it.start } to State.Normal
    }

    /** Range of the value in a .env line ("KEY=value", "export KEY=value"), or null. For hiding secrets. */
    fun envValue(line: String): IntRange? =
        env(line, ENV).firstOrNull { it.tok == Tok.VALUE }?.let { it.start until it.end }
}
