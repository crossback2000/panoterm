// Tests for app/src/main/assets/term/tables.js (table lens, reader mode, approval prompt).
// Run: node --test app/src/test/js/
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const T = require(path.join(__dirname, '../../main/assets/term/tables.js'));

const lines = (s) => s.replace(/^\n/, '').split('\n');

// ---------- findTables ----------

test('box table with header (Claude Code style)', () => {
  const t = T.findTables(lines(`
┌────────┬──────────────┐
│ 앱     │ 장점         │
├────────┼──────────────┤
│ Termius│ 깔끔한 UI    │
│ JuiceSSH│ 무료        │
└────────┴──────────────┘`));
  assert.equal(t.length, 1);
  assert.equal(t[0].header, true);
  assert.deepEqual(t[0].rows, [['앱', '장점'], ['Termius', '깔끔한 UI'], ['JuiceSSH', '무료']]);
});

test('markdown table', () => {
  const t = T.findTables(lines(`
| 이름 | 값 |
|------|----|
| a    | 1  |
| b    | 2  |`));
  assert.equal(t.length, 1);
  assert.equal(t[0].header, true);
  assert.deepEqual(t[0].rows, [['이름', '값'], ['a', '1'], ['b', '2']]);
});

test('markdown table with alignment colons', () => {
  const t = T.findTables(lines(`
| k | v |
|:--|--:|
| x | 1 |`));
  assert.equal(t.length, 1);
  assert.equal(t[0].header, true);
  assert.deepEqual(t[0].rows, [['k', 'v'], ['x', '1']]);
});

test('multi-line cells are joined (rows separated by borders)', () => {
  // Claude wraps long cell text inside the cell and draws a border between every row.
  const t = T.findTables(lines(`
┌──────┬────────────┐
│ 항목 │ 설명       │
├──────┼────────────┤
│ 표   │ 큰 글씨로  │
│      │ 다시 보기  │
├──────┼────────────┤
│ 넓게 │ 120열      │
└──────┴────────────┘`));
  assert.equal(t.length, 1);
  assert.equal(t[0].header, true);
  assert.deepEqual(t[0].rows, [['항목', '설명'], ['표', '큰 글씨로 다시 보기'], ['넓게', '120열']]);
});

test('a row soft-wrapped by the terminal is one logical line and parses whole', () => {
  // term.js joins isWrapped rows before parsing; a wrapped row arrives as one long string.
  const wrapped = '│ 아주 긴 첫 번째 칸 내용이 여기 있음 │ 두 번째 칸도 길어서 터미널 폭을 넘어감 │';
  const t = T.findTables(['┌───┬───┐', '│ A │ B │', '├───┼───┤', wrapped, '└───┴───┘']);
  assert.equal(t.length, 1);
  assert.deepEqual(t[0].rows[1], ['아주 긴 첫 번째 칸 내용이 여기 있음', '두 번째 칸도 길어서 터미널 폭을 넘어감']);
});

test('table without header separator', () => {
  const t = T.findTables(lines(`
┌───┬───┐
│ a │ 1 │
│ b │ 2 │
└───┴───┘`));
  assert.equal(t.length, 1);
  assert.equal(t[0].header, false);
  assert.deepEqual(t[0].rows, [['a', '1'], ['b', '2']]);
});

test('short rows are padded to the widest row', () => {
  const t = T.findTables(['| a | b | c |', '|---|---|---|', '| 1 | 2 |']);
  assert.equal(t.length, 1);
  assert.deepEqual(t[0].rows[1], ['1', '2', '']);
});

test('double-line box table', () => {
  const t = T.findTables(lines(`
╔═══╦═══╗
║ a ║ b ║
╠═══╬═══╣
║ 1 ║ 2 ║
╚═══╩═══╝`));
  assert.deepEqual(t, [{ header: true, rows: [['a', 'b'], ['1', '2']] }]);
});

test('heavy box table', () => {
  const t = T.findTables(lines(`
┏━━━┳━━━┓
┃ a ┃ b ┃
┣━━━╋━━━┫
┃ 1 ┃ 2 ┃
┗━━━┻━━━┛`));
  assert.deepEqual(t, [{ header: true, rows: [['a', 'b'], ['1', '2']] }]);
});

test('mixed heavy-header box (┡━━━╇━━━┩, as drawn by rich)', () => {
  const t = T.findTables(lines(`
┏━━━┳━━━┓
┃ a ┃ b ┃
┡━━━╇━━━┩
│ 1 │ 2 │
└───┴───┘`));
  assert.deepEqual(t, [{ header: true, rows: [['a', 'b'], ['1', '2']] }]);
});

test('two tables separated by text are found separately, oldest first', () => {
  const t = T.findTables(lines(`
| a | b |
|---|---|
| 1 | 2 |
사이 문단
┌───┬───┐
│ c │ d │
├───┼───┤
│ 3 │ 4 │
└───┴───┘`));
  assert.equal(t.length, 2);
  assert.deepEqual(t[0].rows[0], ['a', 'b']);
  assert.deepEqual(t[1].rows[0], ['c', 'd']);
});

test('Claude Code input box is not a table', () => {
  assert.deepEqual(T.findTables(lines(`
╭──────────────────────────╮
│ > 무엇을 할까요?          │
╰──────────────────────────╯`)), []);
});

test('plain text, a lone border, and a single-column box are not tables', () => {
  assert.deepEqual(T.findTables(['hello', 'world']), []);
  assert.deepEqual(T.findTables(['────────────']), []);
  assert.deepEqual(T.findTables(['┌────┐', '│ a  │', '│ b  │', '└────┘']), []);
});

test('fewer than three table lines is ignored', () => {
  assert.deepEqual(T.findTables(['| a | b |', '| 1 | 2 |']), []);
});

test('isBorder', () => {
  assert.ok(T.isBorder('├────┼────┤'));
  assert.ok(T.isBorder('|----|----|'));
  assert.ok(T.isBorder('+----+----+'));
  assert.ok(!T.isBorder('│ a │ b │'));
  assert.ok(!T.isBorder('--'));
  assert.ok(!T.isBorder('- a - b -'));
});

// ---------- tablesToCards / readerText ----------

test('tablesToCards turns rows into labelled cards and keeps other text', () => {
  const out = T.tablesToCards(lines(`
비교:
| 앱 | 장점 |
|----|------|
| Termius | 깔끔한 UI |
| JuiceSSH | 무료 |
끝`));
  assert.deepEqual(out, [
    '비교:',
    '▸ 앱: Termius', '   장점: 깔끔한 UI', '',
    '▸ 앱: JuiceSSH', '   장점: 무료', '',
    '끝'
  ]);
});

test('tablesToCards without header has no labels', () => {
  assert.deepEqual(T.tablesToCards(['┌───┬───┐', '│ a │ 1 │', '│ b │ 2 │', '└───┴───┘']),
    ['▸ a', '   1', '', '▸ b', '   2', '']);
});

test('tablesToCards leaves non-table box blocks untouched', () => {
  const box = ['╭────╮', '│ hi │', '╰────╯'];
  assert.deepEqual(T.tablesToCards(box), box);
});

test("readerText 'last' starts at the last ⏺ marker and trims trailing blanks", () => {
  const text = T.readerText([
    '⏺ 첫 답변', '내용', '> 사용자 질문', '⏺ 두 번째 답변   ', '| a | b |', '|---|---|', '| 1 | 2 |', '', '  '
  ], 'last');
  assert.equal(text, '⏺ 두 번째 답변\n▸ a: 1\n   b: 2\n');
});

test("readerText 'last' also accepts ● and falls back to the last 400 lines", () => {
  assert.equal(T.readerText(['x', '● 답', 'y'], 'last'), '● 답\ny');
  const many = Array.from({ length: 500 }, (_, i) => 'l' + i);
  const r = T.readerText(many, 'last').split('\n');
  assert.equal(r.length, 400);
  assert.equal(r[0], 'l100');
});

// ---------- findPrompt ----------

test('Claude Code approval prompt inside a box', () => {
  const p = T.findPrompt(lines(`
╭───────────────────────────────────────────╮
│ Bash command                              │
│   npm test                                │
│ Do you want to proceed?                   │
│ ❯ 1. Yes                                  │
│   2. Yes, and don't ask again for npm     │
│   3. No, and tell Claude what to do (esc) │
╰───────────────────────────────────────────╯`));
  assert.deepEqual(p, {
    q: 'Do you want to proceed?',
    opts: [
      { n: '1', label: 'Yes' },
      { n: '2', label: "Yes, and don't ask again for npm" },
      { n: '3', label: 'No, and tell Claude what to do (esc)' }
    ]
  });
});

test('prompt without a box, Korean question, trailing hint after two spaces is dropped', () => {
  const p = T.findPrompt(['이 파일을 수정하시겠습니까? 진행하시겠어요?', '› 1. 예  (Enter)', '  2. 아니오']);
  assert.deepEqual(p.opts, [{ n: '1', label: '예' }, { n: '2', label: '아니오' }]);
});

test('uses the last question when several are on screen', () => {
  const p = T.findPrompt(['Do you want A?', '1. a', '2. b', 'Would you like B?', '1. c', '2. d']);
  assert.equal(p.q, 'Would you like B?');
  assert.deepEqual(p.opts.map((o) => o.label), ['c', 'd']);
});

test('no prompt: no question, or fewer than two options', () => {
  assert.equal(T.findPrompt(['1. a', '2. b']), null);
  assert.equal(T.findPrompt(['Do you want to proceed?', '1. Yes']), null);
  assert.equal(T.findPrompt([]), null);
});
