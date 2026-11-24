// PanoTerm text analysis: table extraction, reader cards, approval prompt detection, search.
// Pure functions over arrays of logical lines (no xterm / DOM), so they run in the WebView
// (as the global `PanoText`, loaded before term.js) and under Node for tests
// (app/src/test/js/tables.test.js).
(function (root) {
  'use strict';

  // ---------- tables ----------

  const VBAR = /[│┃║]/;
  // Any box-drawing character (U+2500–U+257F: light, heavy, double, mixed junctions) or
  // ASCII + | : -, with a horizontal run of 3+; or a markdown separator like |:--|--:|.
  function isBorder(s) {
    const t = s.trim();
    if (t.length <= 2) return false;
    if (/^[─-╿+|:\- ]+$/.test(t) && /[─━═╌╍┄┅┈┉\-]{3,}/.test(t)) return true;
    return /^\|(\s*:?-+:?\s*\|)+$/.test(t);
  }
  function isBoxRow(s) { return VBAR.test(s) && !isBorder(s); }
  function isMdRow(s) { const t = s.trim(); return t.length > 2 && t[0] === '|' && t[t.length - 1] === '|' && !isBorder(s); }
  function isTableLine(s) { return isBorder(s) || isBoxRow(s) || isMdRow(s); }

  function cells(s) {
    let t = s.trim();
    if (VBAR.test(t)) {
      t = t.replace(/^[│┃║]/, '').replace(/[│┃║]$/, '');
      return t.split(VBAR).map(function (c) { return c.trim(); });
    }
    t = t.replace(/^\|/, '').replace(/\|$/, '');
    return t.split('|').map(function (c) { return c.trim(); });
  }

  function parseBlock(lines) {
    // lines: consecutive border / data lines
    const n = lines.length;
    let first = 0, last = n - 1;
    while (first <= last && isBorder(lines[first])) first++;
    while (last >= first && isBorder(lines[last])) last--;
    if (first > last) return null;
    let innerBorders = 0;
    for (let i = first; i <= last; i++) if (isBorder(lines[i])) innerBorders++;

    const rows = [];
    let header = false;
    if (innerBorders > 1) {
      // Multi-line cells: rows are separated by border lines.
      let group = null;
      for (let i = first; i <= last; i++) {
        if (isBorder(lines[i])) { if (group) rows.push(group); group = null; continue; }
        const c = cells(lines[i]);
        if (!group) group = c;
        else c.forEach(function (v, k) { if (v) group[k] = group[k] ? group[k] + ' ' + v : v; });
      }
      if (group) rows.push(group);
      header = true;
    } else {
      for (let i = first; i <= last; i++) {
        if (isBorder(lines[i])) { if (rows.length === 1) header = true; continue; }
        rows.push(cells(lines[i]));
      }
    }
    if (rows.length < 2) return null;
    const width = Math.max.apply(null, rows.map(function (r) { return r.length; }));
    if (width < 2) return null;
    rows.forEach(function (r) { while (r.length < width) r.push(''); });
    return { header: header, rows: rows };
  }

  function findTables(lines) {
    const tables = [];
    let block = [];
    function flush() {
      if (block.length >= 3) { const t = parseBlock(block); if (t) tables.push(t); }
      block = [];
    }
    for (const s of lines) {
      if (isTableLine(s)) block.push(s);
      else flush();
    }
    flush();
    return tables;
  }

  // Replace table blocks with one "card" per row (reads well on a narrow screen):
  //   ▸ 앱: Termius
  //      장점: 깔끔한 UI
  function tablesToCards(lines) {
    const out = [];
    let block = [];
    function flush() {
      const t = block.length >= 3 ? parseBlock(block) : null;
      if (!t) { out.push.apply(out, block); block = []; return; }
      const head = t.header ? t.rows[0] : null;
      const body = t.header ? t.rows.slice(1) : t.rows;
      body.forEach(function (r) {
        r.forEach(function (cell, i) {
          const label = head && head[i] ? head[i] + ': ' : '';
          out.push((i === 0 ? '▸ ' : '   ') + label + cell);
        });
        out.push('');
      });
      block = [];
    }
    for (const s of lines) {
      if (isTableLine(s)) block.push(s);
      else { if (block.length) flush(); out.push(s); }
    }
    if (block.length) flush();
    return out;
  }

  // ---------- reader ----------

  /** mode 'last': from Claude's last response marker (⏺/●) to the end; otherwise the last 400 lines. */
  function readerText(logical, mode) {
    const lines = logical.map(function (s) { return s.replace(/\s+$/, ''); });
    let start = Math.max(0, lines.length - 400);
    if (mode === 'last') {
      for (let i = lines.length - 1; i >= 0; i--) {
        if (/^\s*[⏺●]\s/.test(lines[i])) { start = i; break; }
      }
    }
    let end = lines.length;
    while (end > start && lines[end - 1].trim() === '') end--;
    return tablesToCards(lines.slice(start, end)).join('\n');
  }

  // ---------- approval prompt ----------

  const QUESTION = /(Do you want|Would you like|Allow |Proceed\?|진행하시겠|허용하시겠)/;
  const OPTION = /^\s*[│┃|]?\s*(?:❯|›|>|▶)?\s*(\d)\.\s+(.+?)\s*[│┃|]?\s*$/;

  /** The last question line in view followed by >= 2 numbered options, or null. */
  function findPrompt(view) {
    let qi = -1;
    for (let i = view.length - 1; i >= 0; i--) { if (QUESTION.test(view[i])) { qi = i; break; } }
    if (qi < 0) return null;
    const opts = [];
    for (let i = qi + 1; i < view.length; i++) {
      const m = OPTION.exec(view[i]);
      if (m) opts.push({ n: m[1], label: m[2].replace(/\s{2,}.*$/, '') });
    }
    if (opts.length < 2) return null;
    return { q: view[qi].replace(/[│┃|]/g, '').trim(), opts: opts };
  }

  // ---------- scrollback search ----------

  /**
   * Start indexes of [q] in [text], left to right, not overlapping. Smart case like tmux and
   * less: a query without capitals ignores case, one with capitals matches it exactly.
   */
  function findAll(text, q) {
    const out = [];
    if (!q) return out;
    const fold = q === q.toLowerCase();
    const hay = fold ? text.toLowerCase() : text;
    const needle = fold ? q.toLowerCase() : q;
    // toLowerCase() can change the length of a few characters (İ): positions would be off.
    if (hay.length !== text.length) return out;
    let i = hay.indexOf(needle);
    while (i >= 0) { out.push(i); i = hay.indexOf(needle, i + needle.length); }
    return out;
  }

  const api = {
    isBorder: isBorder, cells: cells, parseBlock: parseBlock, findTables: findTables,
    tablesToCards: tablesToCards, readerText: readerText, findPrompt: findPrompt, findAll: findAll
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.PanoText = api;
})(this);
