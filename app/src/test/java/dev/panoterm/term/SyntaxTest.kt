package dev.panoterm.term

import dev.panoterm.term.Syntax.Tok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxTest {
    private fun toks(line: String, lang: Syntax.Lang): List<Pair<String, Tok>> =
        Syntax.highlight(listOf(line), lang)[0].map { line.substring(it.start, it.end) to it.tok }

    @Test fun picksLanguageFromName() {
        assertSame(Syntax.ENV, Syntax.languageFor(".env"))
        assertSame(Syntax.ENV, Syntax.languageFor(".env.local"))
        assertSame(Syntax.KOTLIN, Syntax.languageFor("TermSession.kt"))
        assertSame(Syntax.GO, Syntax.languageFor("files.go"))
        assertSame(Syntax.JS, Syntax.languageFor("app.tsx"))
        assertSame(Syntax.YAML, Syntax.languageFor("ci.yml"))
        assertSame(Syntax.MARKDOWN, Syntax.languageFor("CLAUDE.md"))
        assertSame(Syntax.DOCKERFILE, Syntax.languageFor("Dockerfile"))
        assertSame(Syntax.SHELL, Syntax.languageFor(".bashrc"))
        assertSame(Syntax.PYTHON, Syntax.languageFor("run", "#!/usr/bin/env python3"))
        assertSame(Syntax.SHELL, Syntax.languageFor("deploy", "#!/bin/bash"))
        assertSame(Syntax.PLAIN, Syntax.languageFor("notes.txt"))
    }

    @Test fun codeTokens() {
        val t = toks("val x = foo(\"hi\") // done", Syntax.KOTLIN)
        assertEquals(
            listOf("val" to Tok.KEYWORD, "foo" to Tok.FUNCTION, "\"hi\"" to Tok.STRING, "// done" to Tok.COMMENT),
            t,
        )
        assertEquals(listOf("return" to Tok.KEYWORD, "nil" to Tok.CONSTANT, "0x1F" to Tok.NUMBER), toks("return nil, 0x1F", Syntax.GO))
        // Escaped quote stays inside the string.
        assertEquals(listOf("\"a\\\"b\"" to Tok.STRING), toks("\"a\\\"b\"", Syntax.JS))
    }

    @Test fun blockCommentsAndStringsSpanLines() {
        val lines = listOf("a /* start", "middle", "end */ val b = 1", "s = \"\"\"x", "y\"\"\" + 2")
        val h = Syntax.highlight(lines, Syntax.KOTLIN)
        assertEquals(listOf(Syntax.Span(2, 10, Tok.COMMENT)), h[0])
        assertEquals(listOf(Syntax.Span(0, 6, Tok.COMMENT)), h[1])
        assertEquals(Syntax.Span(0, 6, Tok.COMMENT), h[2][0])
        assertEquals(Tok.KEYWORD, h[2][1].tok)
        assertEquals(Tok.STRING, h[3].last().tok)
        assertEquals(Syntax.Span(0, 4, Tok.STRING), h[4][0])
        assertEquals(Tok.NUMBER, h[4][1].tok)
    }

    @Test fun shellHashOnlyAtWordStartAndVariables() {
        val t = toks("echo a#b \$HOME \${X} # note", Syntax.SHELL)
        assertEquals(listOf("\$HOME" to Tok.VARIABLE, "\${X}" to Tok.VARIABLE, "# note" to Tok.COMMENT), t)
    }

    @Test fun jsonKeysVsValues() {
        assertEquals(
            listOf("\"name\"" to Tok.KEY, "\"pano\"" to Tok.STRING, "\"ok\"" to Tok.KEY, "true" to Tok.CONSTANT),
            toks("{\"name\": \"pano\", \"ok\": true}", Syntax.JSON),
        )
    }

    @Test fun envLines() {
        assertEquals(
            listOf("export" to Tok.KEYWORD, "API_KEY" to Tok.KEY, "=" to Tok.PUNCT, "\"sk-1 2\"" to Tok.VALUE, "# prod" to Tok.COMMENT),
            toks("export API_KEY=\"sk-1 2\" # prod", Syntax.ENV),
        )
        assertEquals(listOf("# c" to Tok.COMMENT), toks("# c", Syntax.ENV))
        assertEquals(listOf("[server]" to Tok.HEADING), toks("[server]", Syntax.INI))
        assertEquals(4 until 10, Syntax.envValue("KEY=secret"))
        assertEquals(15 until 20, Syntax.envValue("export DB_URL= abc:d"))
        assertNull(Syntax.envValue("# KEY=x"))
        assertNull(Syntax.envValue("EMPTY="))
    }

    @Test fun yamlKeysAndValues() {
        assertEquals(listOf("name" to Tok.KEY, "CI" to Tok.STRING), toks("name: CI", Syntax.YAML))
        assertEquals(
            listOf("-" to Tok.PUNCT, "uses" to Tok.KEY, "actions/checkout@v5" to Tok.STRING, "# pin" to Tok.COMMENT),
            toks("  - uses: actions/checkout@v5 # pin", Syntax.YAML),
        )
        assertEquals(listOf("go" to Tok.KEY, "1.27" to Tok.NUMBER), toks("go: 1.27", Syntax.YAML))
        assertEquals(listOf("on" to Tok.KEY), toks("on:", Syntax.YAML))
    }

    @Test fun markdownFenceAndHeading() {
        val h = Syntax.highlight(listOf("# Title", "```kotlin", "val x", "```", "use `code` and **bold**"), Syntax.MARKDOWN)
        assertEquals(Tok.HEADING, h[0][0].tok)
        assertEquals(Tok.STRING, h[2][0].tok) // inside the fence
        assertEquals(listOf(Tok.STRING, Tok.KEYWORD), h[4].map { it.tok })
    }

    @Test fun xmlTagsAndAttributes() {
        val t = toks("<a href=\"x\">hi</a> <!-- c -->", Syntax.XML)
        assertTrue(t.contains("<a" to Tok.TAG))
        assertTrue(t.contains("href" to Tok.KEY))
        assertTrue(t.contains("\"x\"" to Tok.STRING))
        assertTrue(t.contains("</a" to Tok.TAG))
        assertTrue(t.contains("<!-- c -->" to Tok.COMMENT))
    }
}
