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
    theme: { background: '#000000' },
    // OSC 8 hyperlinks: hand them to the app (http/https only; it shows the real address
    // first, since the text on screen is chosen by the program) instead of xterm's default
    // confirm() + window.open(), which would navigate this WebView away from the terminal.
    linkHandler: {
      allowNonHttpProtocols: false,
      activate: function (e, uri) { Bridge.openLink(uri); }
    }
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
  //   webgl  — fastest on a real GPU, but some drivers clip glyphs in the texture atlas
  //   dom    — xterm's built-in renderer: slower, works everywhere
  //   auto   — WebGL on a hardware GPU, DOM on software GL (SwiftShader etc. clip glyphs)
  // xterm.js 6 removed the canvas renderer; a saved "canvas" setting is treated as auto.
  // Measured on xterm 5 (scroll step frame time, real GPU): DOM 24-30ms, WebGL 17-22ms.
  let renderer = 'dom', rendererAddon = null;
  // index.html loads only the addon the startup renderer needs (each is ~100 KB of JS per
  // terminal). Another one (settings change, WebGL fallback) is loaded here on demand; a
  // generation counter drops a late load when the renderer was changed again meanwhile.
  let rendererGen = 0;
  const addonTried = {};
  function loadAddonScript(name, done) {
    addonTried[name] = true;
    const s = document.createElement('script');
    s.src = 'addon-' + name + '.js';
    s.onload = s.onerror = function () { done(); };
    document.head.appendChild(s);
  }
  // Returns true when the addon is there; otherwise starts loading it (unless that already
  // failed), calls retry() once it has loaded, and returns null (pending) or false (unavailable).
  function needAddon(name, global, retry) {
    if (window[global]) return true;
    if (addonTried[name]) return false;
    const gen = rendererGen;
    loadAddonScript(name, function () {
      if (gen !== rendererGen) return;
      retry();
      term.refresh(0, term.rows - 1);
    });
    return null;
  }
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
  function useWebgl() {
    const have = needAddon('webgl', 'WebglAddon', useWebgl);
    if (have !== true) return have === null;
    try {
      const gl = new WebglAddon.WebglAddon();
      // GPU reset / context lost: continue with the DOM renderer.
      gl.onContextLoss(function () { gl.dispose(); renderer = 'dom'; rendererAddon = null; rendererGen++; });
      term.loadAddon(gl);
      renderer = 'webgl'; rendererAddon = gl;
      return true;
    } catch (e) { return false; }
  }
  // Anything but webgl/auto (dom, or no addon available) leaves xterm's DOM renderer.
  function useRenderer(want) {
    if (want === 'canvas') want = 'auto';
    if (want === 'webgl' || (want === 'auto' && !softwareGL())) useWebgl();
  }
  useRenderer(params.get('r') || 'auto');
  window.rendererName = function () { return renderer; };
  // Settings change: swap the renderer in place (the terminal and its contents stay).
  window.setRenderer = function (want) {
    rendererGen++;
    if (rendererAddon) { rendererAddon.dispose(); rendererAddon = null; renderer = 'dom'; }
    useRenderer(want);
    term.refresh(0, term.rows - 1);
  };

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
    placeScreen();
  }

  // The view only changes size when something really changed (soft keyboard animation
  // finished, rotation, banner shown). During the keyboard animation the native side moves
  // the whole terminal with the keyboard instead, so the size changes once per animation.

  // Native bars drawn over the bottom of the terminal (Hangul composing text, autocomplete,
  // approval buttons). They don't change the terminal size (no repaint per keystroke); the
  // screen is shifted up instead so the bottom rows stay visible above them.
  let bottomInset = 0;
  window.setBottomInset = function (px) {
    px = Math.max(0, Math.round(px) || 0);
    if (px !== bottomInset) { bottomInset = px; placeScreen(); }
  };
  function placeScreen() {
    const sc = screenNode();
    if (!sc) return;
    const visible = wrap.clientHeight - bottomInset;
    const used = sc.offsetTop + sc.offsetHeight + 2; // #t padding below the rows
    const shift = Math.max(0, used - visible);
    const top = shift ? (-shift) + 'px' : '';
    if (el.style.top !== top) el.style.top = top;
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
  term.onResize(function () { requestAnimationFrame(placeScreen); });
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

  // ---------- long-press text selection ----------
  // xterm.js has no touch selection. Long-press selects the word under the finger,
  // dragging (without lifting) extends it, and the edges auto-scroll.
  const LONG_PRESS_MS = 380; // a bit under Android's 400 ms long-press, so hold-then-drag selects
  let sel = null, pressTimer = 0, pressPoint = null;

  function cellAt(t) {
    const sc = screenNode();
    const r = sc.getBoundingClientRect();
    const col = Math.max(0, Math.min(term.cols - 1, Math.floor((t.clientX - r.left) / (r.width / term.cols))));
    const vr = Math.max(0, Math.min(term.rows - 1, Math.floor((t.clientY - r.top) / (r.height / term.rows))));
    return { col: col, row: term.buffer.active.viewportY + vr, vr: vr };
  }
  function isWordCell(line, x) {
    const c = line.getCell(x);
    if (!c) return false;
    const ch = c.getChars();
    if (ch === '') return c.getWidth() === 0; // right half of a wide (Korean) char
    return !/\s/.test(ch);
  }
  function wordAt(p) {
    const line = term.buffer.active.getLine(p.row);
    if (!line || !isWordCell(line, p.col)) return { s: p, e: p };
    let s = p.col, e = p.col;
    while (s > 0 && isWordCell(line, s - 1)) s--;
    while (e < term.cols - 1 && isWordCell(line, e + 1)) e++;
    return { s: { col: s, row: p.row }, e: { col: e, row: p.row } };
  }
  function before(a, b) { return a.row < b.row || (a.row === b.row && a.col < b.col); }
  function selectRange(s, e) {
    term.select(s.col, s.row, (e.row - s.row) * term.cols + (e.col - s.col) + 1);
  }
  function beginSelect() {
    pressTimer = 0;
    if (!pressPoint || (pan && pan.dir)) return;
    stopFling(); setOffset(0);
    const p = cellAt(pressPoint);
    const line = term.buffer.active.getLine(p.row);
    if (!line || !isWordCell(line, p.col)) {
      // Long-press on blank space: offer paste there instead of selecting a blank.
      pan = null;
      Bridge.onPasteMenu(pressPoint.clientX, pressPoint.clientY);
      return;
    }
    const w = wordAt(p);
    sel = { a: w.s, b: w.e };
    selectRange(w.s, w.e);
    pan = null;
    Bridge.onSelectStart();
  }
  function extendSelect(t) {
    const p = cellAt(t);
    if (p.vr === 0) term.scrollLines(-1);
    else if (p.vr === term.rows - 1) term.scrollLines(1);
    if (before(p, sel.a)) selectRange(p, sel.b);
    else if (before(sel.b, p)) selectRange(sel.a, p);
    else selectRange(sel.a, sel.b);
  }
  window.selectAllText = function () { term.selectAll(); };
  window.clearSelectionText = function () { term.clearSelection(); };

  // Selection state for the native copy/paste toolbar: the selected area in CSS px (clamped to
  // the visible rows) and whether the finger is still dragging it (toolbar hidden meanwhile).
  function reportSelection() {
    const pos = term.hasSelection() && term.getSelectionPosition();
    const sc = screenNode();
    if (!pos || !sc) { Bridge.onSelection(false, false, 0, 0, 0, 0); return; }
    const r = sc.getBoundingClientRect();
    const cw = r.width / term.cols, ch = r.height / term.rows, vy = term.buffer.active.viewportY;
    const clamp = function (y) { return Math.max(0, Math.min(term.rows - 1, y - vy)); };
    const top = clamp(pos.start.y), bottom = clamp(pos.end.y);
    const oneLine = pos.start.y === pos.end.y;
    Bridge.onSelection(true, !!sel,
      r.left + (oneLine ? pos.start.x : 0) * cw, r.top + top * ch,
      r.left + (oneLine ? pos.end.x : term.cols) * cw, r.top + (bottom + 1) * ch);
  }
  term.onSelectionChange(reportSelection);
  term.onScroll(function () { if (term.hasSelection()) reportSelection(); });

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
      pressPoint = { clientX: t.clientX, clientY: t.clientY };
      clearTimeout(pressTimer);
      pressTimer = setTimeout(beginSelect, LONG_PRESS_MS);
    }
    if (e.touches.length !== 1) { clearTimeout(pressTimer); pressTimer = 0; }
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
    if (sel && e.touches.length === 1) {
      extendSelect(e.touches[0]);
      e.preventDefault(); e.stopPropagation();
      return;
    }
    if (pan && e.touches.length === 1) {
      const dx = e.touches[0].clientX - pan.x, dy = e.touches[0].clientY - pan.y;
      if (!pan.dir && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
        pan.dir = Math.abs(dx) > Math.abs(dy) ? 'h' : 'v';
        clearTimeout(pressTimer); pressTimer = 0; // it's a scroll, not a long-press
      }
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
    clearTimeout(pressTimer); pressTimer = 0;
    if (sel && e.touches.length === 0) {
      sel = null; pan = null;
      reportSelection(); // drag finished: show the toolbar
      e.preventDefault(); e.stopPropagation();
      return;
    }
    // A plain tap dismisses the selection (and its toolbar).
    if (e.touches.length === 0 && pan && !pan.dir && term.hasSelection()) term.clearSelection();
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
  function inputLine() {
    const buf = term.buffer.active;
    const y = buf.baseY + buf.cursorY;
    let start = y;
    while (start > 0 && buf.getLine(start) && buf.getLine(start).isWrapped) start--;
    let s = '';
    for (let i = start; i < y; i++) { const l = buf.getLine(i); if (l) s += l.translateToString(false); }
    const cur = buf.getLine(y);
    if (cur) s += cur.translateToString(false, 0, buf.cursorX);
    return { text: s, start: start, y: y };
  }
  window.getInputLine = function () { return inputLine().text; };

  // Cursor cell in page (CSS) pixels, or null when the cursor row is scrolled out of view.
  function cursorBox() {
    const sc = screenNode();
    if (!sc || !term.rows) return null;
    const buf = term.buffer.active;
    const row = buf.baseY + buf.cursorY - buf.viewportY;
    if (row < 0 || row >= term.rows) return null;
    const r = sc.getBoundingClientRect();
    const ch = r.height / term.rows, cw = r.width / term.cols;
    const top = r.top + row * ch;
    return { left: r.left + buf.cursorX * cw, top: top, bottom: top + ch, ch: ch };
  }

  function rowText(y) { const l = term.buffer.active.getLine(y); return l ? l.translateToString(true) : ''; }
  function isRule(s) { return /^\s*[─━]{10,}\s*$/.test(s); }
  // Claude Code's slash menu row: "  ❯ /copy      Copy the last…" or "    /config   Open settings".
  const MENU_ROW = /^\s*(?:[❯>›]\s*)?(\/[\w:.\-]+)(?:\s{2,}(.*))?$/;

  // What the autocomplete needs in one call: the input line, whether it sits in Claude Code's
  // input box (a ─── rule right above it), the commands Claude's own "/" menu shows next to the
  // box (newer versions above it, older below), and where the cursor is on screen.
  window.getInputContext = function () {
    const inp = inputLine();
    const buf = term.buffer.active;
    const claude = inp.start > 0 && isRule(rowText(inp.start - 1));
    const menu = [];
    if (claude && /^\s*[│┃|]?\s*[>❯]\s?\/\S*$/.test(inp.text)) {
      const take = function (y) {
        const m = MENU_ROW.exec(rowText(y));
        if (!m) return false;
        menu.push([m[1], (m[2] || '').trim()]);
        return true;
      };
      const above = [];
      for (let y = inp.start - 2; y >= Math.max(0, inp.start - 30); y--) {
        const m = MENU_ROW.exec(rowText(y));
        if (!m) break;
        above.unshift([m[1], (m[2] || '').trim()]);
      }
      menu.push.apply(menu, above);
      // Below: skip to the rule closing the box, then read the rows after it.
      let y = inp.y + 1;
      while (y < buf.length && y <= inp.y + 4 && !isRule(rowText(y))) y++;
      if (y < buf.length && isRule(rowText(y))) {
        for (let k = y + 1; k < Math.min(buf.length, y + 30); k++) if (!take(k)) break;
      }
    }
    return JSON.stringify({ line: inp.text, claude: claude, menu: menu, cursor: cursorBox() });
  };

  // ---------- IME composition shown at the cursor ----------
  // The syllable being typed (soft keyboard or the hardware Hangul mode) is drawn over the
  // cursor cell like a native text field does, instead of in a bar that pushed the screen up.
  let imeText = '', imeEl = null;
  function placeIme() {
    if (!imeText) { if (imeEl) imeEl.style.display = 'none'; return; }
    const b = cursorBox();
    if (!imeEl) {
      imeEl = document.createElement('div');
      imeEl.style.cssText = 'position:fixed;z-index:20;pointer-events:none;white-space:pre;' +
        'background:#000;color:#fff;font-family:"D2Coding",monospace;text-decoration:underline;' +
        'text-decoration-color:#80cbc4;text-underline-offset:2px;';
      document.body.appendChild(imeEl);
    }
    if (!b) { imeEl.style.display = 'none'; return; }
    imeEl.textContent = imeText;
    imeEl.style.fontSize = term.options.fontSize + 'px';
    imeEl.style.lineHeight = b.ch + 'px';
    imeEl.style.height = b.ch + 'px';
    imeEl.style.left = b.left + 'px';
    imeEl.style.top = b.top + 'px';
    imeEl.style.display = 'block';
  }
  window.setComposition = function (t) { imeText = t || ''; placeIme(); };
  term.onRender(function () { if (imeText) placeIme(); });
  term.onCursorMove(function () { if (imeText) placeIme(); });
  wrap.addEventListener('scroll', function () { if (imeText) placeIme(); }, { passive: true });

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

  // ---------- tables / reader / prompt (pure logic in tables.js) ----------

  const findTables = PanoText.findTables;

  /** All tables in the last ~3000 rows, oldest first. */
  window.getTables = function () {
    const buf = term.buffer.active;
    return JSON.stringify(findTables(logicalLines(buf.length - 3000, buf.length)));
  };

  // ---------- reader ----------

  /** mode 'last': from Claude's last response marker (⏺/●) to the end; 'recent': last 400 lines. */
  window.getReaderText = function (mode) {
    const buf = term.buffer.active;
    return PanoText.readerText(logicalLines(buf.length - 3000, buf.length), mode);
  };

  // ---------- analysis after output: approval prompt + table count ----------

  let lastPrompt = '', lastTables = -1;

  function analyze() {
    const buf = term.buffer.active;
    const bottom = buf.baseY + term.rows;
    const view = logicalLines(bottom - term.rows, bottom);

    // Approval prompt: a question line followed by numbered options.
    const p = PanoText.findPrompt(view);
    const prompt = p ? JSON.stringify(p) : '';
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

  new ResizeObserver(function () { doFit(); }).observe(wrap); // runs after layout, before paint
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
