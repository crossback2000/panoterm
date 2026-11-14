// xterm.js is used for rendering only. Keyboard input is handled natively in
// Kotlin (TerminalInputView / MainActivity.dispatchKeyEvent); onData here only
// carries terminal query replies (DA, DSR, ...) and mouse reports.
// The bundled D2Coding font must be loaded before xterm measures cell size,
// otherwise the grid is computed with the fallback font and columns misalign.
Promise.all([
  document.fonts.load('400 14px "D2Coding"', '가A'),
  document.fonts.load('700 14px "D2Coding"', '가A')
]).catch(function () { }).then(start);

function start() {
  const params = new URLSearchParams(location.hash.slice(1));
  const initialFont = parseInt(params.get('fs') || '14', 10);

  const term = new Terminal({
    fontFamily: '"D2Coding", monospace',
    fontWeight: 'normal',
    fontWeightBold: 'bold',
    lineHeight: 1.0,
    fontSize: initialFont,
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

  const el = document.getElementById('t');
  term.open(el);

  function doFit() { try { fit.fit(); } catch (e) { } }

  let lastModes = '';
  function checkModes() {
    const m = term.modes;
    const k = (m.applicationCursorKeysMode ? '1' : '0') + (m.bracketedPasteMode ? '1' : '0');
    if (k !== lastModes) {
      lastModes = k;
      Bridge.onModes(m.applicationCursorKeysMode, m.bracketedPasteMode);
    }
  }

  window.termWrite = function (b64) {
    const bin = atob(b64);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    term.write(u8, checkModes);
  };
  window.setFontSize = function (n) { term.options.fontSize = n; doFit(); };
  window.getSelectionText = function () { return term.getSelection(); };

  term.onResize(function (s) { Bridge.onResize(s.cols, s.rows); });
  term.onData(function (d) { Bridge.onTermData(d); });
  term.onBinary(function (d) { Bridge.onTermBinary(d); });

  new ResizeObserver(doFit).observe(el);
  doFit();
  Bridge.onReady(term.cols, term.rows);
}
