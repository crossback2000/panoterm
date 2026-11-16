// PanoTerm renderer.
// xterm.js is used for rendering only. Keyboard input is handled natively in
// Kotlin; onData here only carries terminal query replies and mouse reports.
//
// Features implemented here:
//  - wide viewport (fixed column count + horizontal pan)
//  - pinch zoom (font size; in wide mode the column count stays fixed)
//  - table extraction for the native "table lens"
//  - reader text (soft-wrapped lines joined, for native reflow)
//  - Claude Code approval prompt detection
//  - clickable URLs

// The bundled D2Coding font must be loaded before xterm measures cell size.
Promise.all([
  document.fonts.load('400 14px "D2Coding"', '가A'),
  document.fonts.load('700 14px "D2Coding"', '가A')
]).catch(function () { }).then(start);

function start() {
  const params = new URLSearchParams(location.hash.slice(1));
  let fontSize = parseInt(params.get('fs') || '14', 10);
  let fixedCols = parseInt(params.get('cols') || '0', 10);

  const term = new Terminal({
    fontFamily: '"D2Coding", monospace',
    fontWeight: 'normal',
    fontWeightBold: 'bold',
    lineHeight: 1.0,
    fontSize: fontSize,
    scrollback: 10000,
    cursorBlink: true,
    allowProposedApi: true,
    theme: { background: '#000000' }
  });
  window.term = term;

  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  const uni = new Unicode11Addon.Unicode11Addon();
  term.loadAddon(uni);
  term.unicode.activeVersion = '11'; // correct width for Korean / emoji
  if (window.WebLinksAddon) {
    term.loadAddon(new WebLinksAddon.WebLinksAddon(function (e, uri) { Bridge.openUrl(uri); }));
  }

  const wrap = document.getElementById('wrap');
  const el = document.getElementById('t');
  term.open(el);

  // Renderer (settings → 화면 그리기 방식):
  //   canvas — 2D canvas with a glyph cache: far faster than DOM, works on all GPUs (default)
  //   webgl  — fastest on a real GPU, but some drivers clip glyphs in the texture atlas
  //   dom    — slowest, most compatible
  //   auto   — WebGL on a hardware GPU, Canvas on software GL (SwiftShader etc. clip glyphs)
  // Measured (scroll step frame time, real GPU): DOM 24-30ms, Canvas 20-23ms, WebGL 17-22ms.
  let renderer = 'dom';
  function softwareGL() {
    try {
      const c = document.createElement('canvas');
      const g = c.getContext('webgl2') || c.getContext('webgl');
      if (!g) return true;
      const ext = g.getExtension('WEBGL_debug_renderer_info');
      const name = ext ? g.getParameter(ext.UNMASKED_RENDERER_WEBGL) : g.getParameter(g.RENDERER);
      return /swiftshader|llvmpipe|software|softpipe/i.test(String(name));
    } catch (e) { return true; }
  }
  function useCanvas() {
    if (!window.CanvasAddon) return false;
    try { term.loadAddon(new CanvasAddon.CanvasAddon()); renderer = 'canvas'; return true; } catch (e) { return false; }
  }
  function useWebgl() {
    if (!window.WebglAddon) return false;
    try {
      const gl = new WebglAddon.WebglAddon();
      // GPU reset / context lost: drop to the canvas renderer instead of the slow DOM one.
      gl.onContextLoss(function () { gl.dispose(); renderer = 'dom'; useCanvas(); });
      term.loadAddon(gl);
      renderer = 'webgl';
      return true;
    } catch (e) { return false; }
  }
  const want = params.get('r') || 'auto';
  if (want === 'webgl') { useWebgl() || useCanvas(); }
  else if (want === 'canvas') { useCanvas(); }
  else if (want === 'auto') { (!softwareGL() && useWebgl()) || useCanvas(); }
  window.rendererName = function () { return renderer; };

  // ---------- sizing / wide viewport ----------

  function screenWidth() {
    const sc = el.querySelector('.xterm-screen');
    return sc ? sc.offsetWidth : 0;
  }

  function doFit() {
    if (fixedCols <= 0) el.style.width = '';
    let dims;
    try { dims = fit.proposeDimensions(); } catch (e) { dims = null; }
    if (!dims || !isFinite(dims.rows) || dims.rows <= 0) return;
    const cols = fixedCols > 0 ? fixedCols : Math.max(10, dims.cols);
    const rows = Math.max(4, dims.rows);
    if (cols !== term.cols || rows !== term.rows) term.resize(cols, rows);
    if (fixedCols > 0) el.style.width = (screenWidth() + 10) + 'px';
  }

  window.setFontSize = function (n) { fontSize = n; term.options.fontSize = n; invalidateMetrics(); doFit(); };
  window.setFixedCols = function (n) { fixedCols = n; if (n <= 0) wrap.scrollLeft = 0; doFit(); };
  window.panBy = function (dx) { wrap.scrollLeft += dx * wrap.clientWidth; };
  window.followCursor = function () {
    if (fixedCols <= 0) return;
    const cw = screenWidth() / term.cols;
    const x = term.buffer.active.cursorX * cw;
    if (x < wrap.scrollLeft + cw * 2 || x > wrap.scrollLeft + wrap.clientWidth - cw * 4) {
      wrap.scrollLeft = Math.max(0, x - wrap.clientWidth * 0.6);
    }
  };

  // ---------- touch: vertical kinetic scroll, horizontal pan, pinch zoom ----------
  // Capture-phase listeners on #wrap run before xterm's own handlers, so xterm's
  // line-jumpy touch scrolling never runs; we do our own with momentum.

  // Cached metrics: reading offsetHeight on every touchmove forced a layout per event.
  let chCache = 0, screenEl = null;
  function screenNode() { return screenEl && screenEl.isConnected ? screenEl : (screenEl = el.querySelector('.xterm-screen')); }
  function cellHeight() {
    if (!chCache) {
      const sc = screenNode();
      chCache = sc && term.rows ? sc.offsetHeight / term.rows : 16;
    }
    return chCache || 16;
  }
  function invalidateMetrics() { chCache = 0; }
  term.onResize(invalidateMetrics);
  term.onRender(function () { if (!chCache) cellHeight(); });

  // Sub-line offset: between whole-line scroll steps the screen is shifted by the leftover
  // pixels with a compositor-only transform, so the text follows the finger smoothly.
  let offsetPx = 0;
  function setOffset(px) {
    const buf = term.buffer.active;
    if (buf.type !== 'normal') px = 0;
    else if ((px < 0 && buf.viewportY <= 0) || (px > 0 && buf.viewportY >= buf.baseY)) px = 0; // at the ends
    if (px === offsetPx) return;
    offsetPx = px;
    const sc = screenNode();
    if (sc) {
      sc.style.willChange = 'transform';
      sc.style.transform = px ? 'translate3d(0,' + (-px).toFixed(1) + 'px,0)' : '';
    }
  }

  // Scroll by n lines (n > 0 = towards newer output / down).
  //  - normal buffer (shell, Claude Code): scroll xterm's scrollback
  //  - alternate buffer with mouse reporting (tmux "mouse on", vim, less): send wheel events
  //    at the touch position so the program scrolls its own history
  let wheelPos = { col: 1, row: 1 };
  function scrollBy(n) {
    if (!n) return;
    const buf = term.buffer.active;
    if (buf.type === 'normal') { term.scrollLines(n); return; }
    if (term.modes.mouseTrackingMode !== 'none') {
      const code = n > 0 ? 65 : 64; // SGR wheel down / up
      let s = '';
      for (let i = 0; i < Math.min(Math.abs(n), 10); i++) s += '\x1b[<' + code + ';' + wheelPos.col + ';' + wheelPos.row + 'M';
      Bridge.onTermData(s);
    }
  }
  function touchToCell(t) {
    const sc = el.querySelector('.xterm-screen');
    if (!sc) return { col: 1, row: 1 };
    const r = sc.getBoundingClientRect();
    return {
      col: Math.max(1, Math.min(term.cols, Math.floor((t.clientX - r.left) / (r.width / term.cols)) + 1)),
      row: Math.max(1, Math.min(term.rows, Math.floor((t.clientY - r.top) / (r.height / term.rows)) + 1)),
    };
  }

  let fling = 0; // requestAnimationFrame id
  function stopFling() { if (fling) cancelAnimationFrame(fling); fling = 0; }
  function startFling(v /* px per ms, + = finger moved up = scroll down */, acc0) {
    stopFling();
    let acc = acc0 || 0, last = performance.now();
    const ch = cellHeight();
    function step(now) {
      const dt = Math.min(32, now - last); last = now;
      acc += v * dt;
      const lines = Math.trunc(acc / ch);
      if (lines) { scrollBy(lines); acc -= lines * ch; }
      v *= Math.pow(0.95, dt / 16); // friction
      if (Math.abs(v) > 0.03) { setOffset(acc); fling = requestAnimationFrame(step); }
      else { setOffset(0); fling = 0; }
    }
    fling = requestAnimationFrame(step);
  }

  let pinch = null, pan = null;
  function dist(e) {
    const a = e.touches[0], b = e.touches[1];
    return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
  }
  function centerX(e) { return (e.touches[0].clientX + e.touches[1].clientX) / 2; }
  wrap.addEventListener('touchstart', function (e) {
    if (e.touches.length === 2) {
      pinch = { d: dist(e), fs: fontSize, s: 1, cx0: centerX(e), cx: centerX(e) };
      pan = null;
      e.preventDefault(); e.stopPropagation();
    } else if (e.touches.length === 1) {
      stopFling();
      const t = e.touches[0];
      // Continue from the current sub-line offset so catching a fling doesn't jump.
      pan = { x: t.clientX, y: t.clientY, sl: wrap.scrollLeft, dir: null, lastY: t.clientY, acc: offsetPx, samples: [] };
      wheelPos = touchToCell(t);
    }
  }, { capture: true, passive: false });

  wrap.addEventListener('touchmove', function (e) {
    if (pinch && e.touches.length === 2) {
      pinch.s = Math.max(0.4, Math.min(3, dist(e) / pinch.d));
      pinch.cx = centerX(e);
      // Only scale visibly when it is really a pinch (not a two-finger swipe).
      el.style.transformOrigin = '0 0';
      el.style.transform = Math.abs(pinch.s - 1) > 0.08 ? 'scale(' + pinch.s + ')' : '';
      e.preventDefault(); e.stopPropagation();
      return;
    }
    if (pan && e.touches.length === 1) {
      const dx = e.touches[0].clientX - pan.x, dy = e.touches[0].clientY - pan.y;
      if (!pan.dir && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) pan.dir = Math.abs(dx) > Math.abs(dy) ? 'h' : 'v';
      if (pan.dir === 'h' && fixedCols > 0) {
        wrap.scrollLeft = pan.sl - dx;
        e.preventDefault(); e.stopPropagation();
      } else if (pan.dir === 'v') {
        const y = e.touches[0].clientY;
        const d = pan.lastY - y; // finger up => positive => scroll down (newer)
        pan.lastY = y;
        pan.acc += d;
        const ch = cellHeight();
        const lines = Math.trunc(pan.acc / ch);
        if (lines) { scrollBy(lines); pan.acc -= lines * ch; }
        setOffset(pan.acc);
        if (offsetPx === 0 && pan.acc !== 0 && term.buffer.active.type === 'normal') pan.acc = 0; // hit an end
        const now = performance.now();
        pan.samples.push({ t: now, y: y });
        while (pan.samples.length > 2 && now - pan.samples[0].t > 100) pan.samples.shift();
        e.preventDefault(); e.stopPropagation();
      }
    }
  }, { capture: true, passive: false });

  wrap.addEventListener('touchend', function (e) {
    if (pinch && e.touches.length < 2) {
      el.style.transform = '';
      const p = pinch;
      pinch = null;
      const dx = p.cx - p.cx0;
      if (Math.abs(p.s - 1) <= 0.08 && Math.abs(dx) > 70) {
        // Two-finger horizontal swipe (no pinch): gesture.
        Bridge.onGesture(dx < 0 ? 'swipe-left' : 'swipe-right');
      } else {
        const n = Math.max(8, Math.min(32, Math.round(p.fs * p.s)));
        if (n !== fontSize) { window.setFontSize(n); Bridge.onFontSize(n); }
      }
      e.stopPropagation();
    }
    if (e.touches.length === 0 && pan) {
      // Momentum: velocity over the last ~100 ms of the drag.
      if (pan.dir === 'v') {
        let flung = false;
        if (pan.samples.length >= 2) {
          const a = pan.samples[0], b = pan.samples[pan.samples.length - 1];
          const dt = b.t - a.t;
          if (dt > 0 && performance.now() - b.t < 80) {
            const v = (a.y - b.y) / dt;
            if (Math.abs(v) > 0.3) { startFling(v, pan.acc); flung = true; }
          }
        }
        if (!flung) setOffset(0); // released without a fling: settle on the line grid
        e.stopPropagation();
      }
      pan = null;
    }
  }, { capture: true, passive: false });

  // ---------- output / modes ----------

  let lastModes = '';
  function checkModes() {
    const m = term.modes;
    const k = (m.applicationCursorKeysMode ? '1' : '0') + (m.bracketedPasteMode ? '1' : '0');
    if (k !== lastModes) {
      lastModes = k;
      Bridge.onModes(m.applicationCursorKeysMode, m.bracketedPasteMode);
    }
  }

  let analyzeTimer = 0;
  function afterWrite() {
    checkModes();
    clearTimeout(analyzeTimer);
    analyzeTimer = setTimeout(analyze, 300);
  }

  window.termWrite = function (b64) {
    const bin = atob(b64);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    term.write(u8, afterWrite);
  };
  window.getSelectionText = function () { return term.getSelection(); };

  // Text of the current input line up to the cursor (soft-wrapped rows joined): used by
  // the autocomplete to see what is being typed.
  window.getInputLine = function () {
    const buf = term.buffer.active;
    const y = buf.baseY + buf.cursorY;
    let start = y;
    while (start > 0 && buf.getLine(start) && buf.getLine(start).isWrapped) start--;
    let s = '';
    for (let i = start; i < y; i++) { const l = buf.getLine(i); if (l) s += l.translateToString(false); }
    const cur = buf.getLine(y);
    if (cur) s += cur.translateToString(false, 0, buf.cursorX);
    return s;
  };

  // ---------- buffer helpers ----------

  // Logical lines (soft-wrapped rows joined) in [from, to) buffer rows.
  function logicalLines(from, to) {
    const buf = term.buffer.active;
    const out = [];
    for (let y = Math.max(0, from); y < Math.min(to, buf.length); y++) {
      const line = buf.getLine(y);
      if (!line) continue;
      const s = line.translateToString(true);
      if (line.isWrapped && out.length) out[out.length - 1] += s;
      else out.push(s);
    }
    return out;
  }

  // ---------- table extraction ----------

  const VBAR = /[│┃║]/;
  function isBorder(s) {
    const t = s.trim();
    return t.length > 2 && /^[┌┏╔╭├┣╠└┗╚╰┬┼┴┐┓╗╮┤┫╣┘┛╝╯─━═╤╪╧╞╡╟╢+|:\- ]+$/.test(t) && /[─━═\-]{3,}/.test(t);
  }
  function isBoxRow(s) { return VBAR.test(s) && !isBorder(s); }
  function isMdRow(s) { const t = s.trim(); return t.length > 2 && t[0] === '|' && t[t.length - 1] === '|' && !isBorder(s); }

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
      if (isBorder(s) || isBoxRow(s) || isMdRow(s)) block.push(s);
      else flush();
    }
    flush();
    return tables;
  }

  /** All tables in the last ~3000 rows, oldest first. */
  window.getTables = function () {
    const buf = term.buffer.active;
    return JSON.stringify(findTables(logicalLines(buf.length - 3000, buf.length)));
  };

  // ---------- reader ----------

  /** mode 'last': from Claude's last response marker (⏺/●) to the end; 'recent': last 400 lines. */
  window.getReaderText = function (mode) {
    const buf = term.buffer.active;
    const lines = logicalLines(buf.length - 3000, buf.length).map(function (s) { return s.replace(/\s+$/, ''); });
    let start = Math.max(0, lines.length - 400);
    if (mode === 'last') {
      for (let i = lines.length - 1; i >= 0; i--) {
        if (/^\s*[⏺●]\s/.test(lines[i])) { start = i; break; }
      }
    }
    let end = lines.length;
    while (end > start && lines[end - 1].trim() === '') end--;
    return tablesToCards(lines.slice(start, end)).join('\n');
  };

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
      if (isBorder(s) || isBoxRow(s) || isMdRow(s)) block.push(s);
      else { if (block.length) flush(); out.push(s); }
    }
    if (block.length) flush();
    return out;
  }

  // ---------- analysis after output: approval prompt + table count ----------

  const QUESTION = /(Do you want|Would you like|Allow |Proceed\?|진행하시겠|허용하시겠)/;
  const OPTION = /^\s*[│┃|]?\s*(?:❯|›|>|▶)?\s*(\d)\.\s+(.+?)\s*[│┃|]?\s*$/;
  let lastPrompt = '', lastTables = -1;

  function analyze() {
    const buf = term.buffer.active;
    const bottom = buf.baseY + term.rows;
    const view = logicalLines(bottom - term.rows, bottom);

    // Approval prompt: a question line followed by numbered options.
    let prompt = '';
    let qi = -1;
    for (let i = view.length - 1; i >= 0; i--) { if (QUESTION.test(view[i])) { qi = i; break; } }
    if (qi >= 0) {
      const opts = [];
      for (let i = qi + 1; i < view.length; i++) {
        const m = OPTION.exec(view[i]);
        if (m) opts.push({ n: m[1], label: m[2].replace(/\s{2,}.*$/, '') });
      }
      if (opts.length >= 2) {
        prompt = JSON.stringify({ q: view[qi].replace(/[│┃|]/g, '').trim(), opts: opts });
      }
    }
    if (prompt !== lastPrompt) { lastPrompt = prompt; Bridge.onPrompt(prompt); }

    // Tables near the current viewport.
    const top = buf.viewportY;
    const n = findTables(logicalLines(top - 60, top + term.rows)).length;
    if (n !== lastTables) { lastTables = n; Bridge.onTables(n); }
  }

  term.onScroll(function () { clearTimeout(analyzeTimer); analyzeTimer = setTimeout(analyze, 300); });
  term.onResize(function (s) { Bridge.onResize(s.cols, s.rows); });
  term.onData(function (d) { Bridge.onTermData(d); });
  term.onBinary(function (d) { Bridge.onTermBinary(d); });

  new ResizeObserver(doFit).observe(wrap);
  doFit();
  Bridge.onReady(term.cols, term.rows);

  if (params.get('debug') === '1') {
    window.diag = function () {
      const sc = el.querySelector('.xterm-screen');
      const rowsEl = el.querySelector('.xterm-rows');
      return JSON.stringify({
        renderer: renderer, viewportY: term.buffer.active.viewportY, baseY: term.buffer.active.baseY,
        cols: term.cols, rows: term.rows, win: [innerWidth, innerHeight],
        wrap: [wrap.clientWidth, wrap.clientHeight], screen: sc ? [sc.offsetWidth, sc.offsetHeight] : null,
        canvas: el.querySelectorAll('canvas').length, bufLen: term.buffer.active.length,
        rowsText: rowsEl ? [...rowsEl.children].slice(0, 4).map(function (d) { return d.textContent; }) : null,
        color: rowsEl ? getComputedStyle(rowsEl).color : null, font: document.fonts.check('14px "D2Coding"'),
        css: [getComputedStyle(wrap).position, getComputedStyle(wrap).height, document.body.clientHeight, document.documentElement.clientHeight, el.clientHeight]
      });
    };
    setInterval(function () { console.log('diag ' + window.diag()); }, 500);

    // Scroll benchmark: 120 one-line scroll steps, one per animation frame, through the
    // same scrollBy() the touch handler uses. Reports frame intervals (ms).
    window.benchScroll = function (dir) {
      return new Promise(function (resolve) {
        const times = []; let last = performance.now(); let i = 0;
        function step(now) {
          times.push(now - last); last = now;
          scrollBy(dir);
          if (++i < 120) requestAnimationFrame(step);
          else {
            times.shift();
            times.sort(function (a, b) { return a - b; });
            const avg = times.reduce(function (s, x) { return s + x; }, 0) / times.length;
            resolve({ renderer: renderer, avg: +avg.toFixed(1), p95: +times[Math.floor(times.length * 0.95)].toFixed(1), max: +times[times.length - 1].toFixed(1), janky: times.filter(function (x) { return x > 20; }).length });
          }
        }
        requestAnimationFrame(step);
      });
    };
    const benchTimer = setInterval(function () {
      if (params.get('bench') !== '1') { clearInterval(benchTimer); return; }
      if (term.buffer.active.length < 200) return;
      clearInterval(benchTimer);
      setTimeout(function () {
        window.benchScroll(-1).then(function (a) {
          return window.benchScroll(1).then(function (b) { console.log('bench ' + JSON.stringify({ up: a, down: b })); });
        });
      }, 1500);
    }, 1000);
  }
}
