/* The note editor, lifted from Yeetlist's notes-ui.js and themed to UrsaPDF.
   notes.js (Yeetlist's converter, unchanged but for its accent) turns the
   markdown file into editable HTML and back. The app holds the file; this page
   talks to it through the `Ursa` bridge. */

const $ = (id) => document.getElementById(id);
const MS = 250;
const SWATCHES = [8, 24, 36, 48, 60, 76, 128, 144, 168, 180, 192, 208, 240, 288, 316, 344];
const SAT = 75;
const ZOOMS = [50, 75, 100, 125, 150, 175, 200];
const LEVELS = [
  ['p', 'Body'], ['h1', 'Level 1 (Title)'], ['h2', 'Level 2 (Heading)'], ['h3', 'Level 3 (Subheading)'],
  ['h4', 'Level 4'], ['h5', 'Level 5'], ['h6', 'Level 6'], ['sep'], ['pre', 'Code Block'],
];

const bridge = window.Ursa || {
  load: () => JSON.stringify({ name: '', named: false, md: '# Hello\n\nA note.', hue: 24, zoom: 100, light: false }),
  save() {}, setHue() {}, setZoom() {}, setLight() {}, exportNote() {}, copy() {}, share() {}, deleteNote() {}, done() {},
};

let note = null;
let saveTimer = null;
let zoom = 100;

function hsvHex(h, s, b) {
  const sat = s / 100;
  const val = b / 100;
  const f = (n) => {
    const k = (n + h / 60) % 6;
    return val - val * sat * Math.max(0, Math.min(k, 4 - k, 1));
  };
  return '#' + [f(5), f(3), f(1)].map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join('');
}

/* ── Opening ──────────────────────────────────────────────────────────── */

function open() {
  note = JSON.parse(bridge.load());
  document.documentElement.dataset.theme = note.light ? 'light' : 'dark';
  const body = $('noteBody');
  body.innerHTML = markdownToHtml(note.md || '');
  if (!body.firstElementChild) body.innerHTML = '<p><br></p>';
  $('noteTitle').value = note.named ? note.name : '';
  paintTitle();
  paintHue(note.hue);
  setZoom(note.zoom || 100, false);
  syncTools();
  if (!String(note.md || '').trim()) { body.focus(); caretToEnd(body); }
}

function setInsets(top, bottom) {
  document.documentElement.style.setProperty('--top', `${top}px`);
  document.documentElement.style.setProperty('--bottom', `${bottom}px`);
}

/* The hint is the name the list would show: the first line, while no name is typed. */
function paintTitle() {
  const md = htmlToMarkdown($('noteBody'));
  $('noteTitle').placeholder = md.trim() ? titleOf(md) : 'Untitled note';
}

function caretToEnd(el) {
  const range = document.createRange();
  range.selectNodeContents(el);
  range.collapse(false);
  const sel = getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
}

/* ── Saving: on a 600 ms timer, never per keystroke ───────────────────── */

function save({ now = false } = {}) {
  clearTimeout(saveTimer);
  if (!now) {
    $('noteState').textContent = 'Saving…';
    saveTimer = setTimeout(() => save({ now: true }), 600);
    return;
  }
  const md = htmlToMarkdown($('noteBody'));
  const name = $('noteTitle').value.trim();
  $('noteState').textContent = 'Saved';
  if (md === note.md && name === (note.named ? note.name : '')) return;
  note.md = md;
  bridge.save(name, md);
  if (name) { note.name = name; note.named = true; } else note.named = false;
}

/* Back from the app: close whatever is open first, else save and leave. */
function back() {
  if (!$('modal').hidden) { closeModal(); return true; }
  if (menuOpen && menuBackTo) { const to = menuBackTo; menuBackTo = null; closeMenu(); setTimeout(to, 0); return true; }
  if (menuOpen) { closeMenu(); return true; }
  if (!$('huePop').hidden) { hueOpen(false); return true; }
  if ($('findBar').dataset.open !== undefined) { closeFind(); return true; }
  leave();
  return true;
}

/* A note with no name takes its first line, once, on close (Yeetlist's rule). */
function leave() {
  save({ now: true });
  const md = note.md || '';
  bridge.done(!note.named && md.trim() ? titleOf(md) : '');
}

/* ── The toolbar ─────────────────────────────────────────────────────── */

const COMMANDS = { bold: 'bold', italic: 'italic', strike: 'strikeThrough', bullets: 'insertUnorderedList', numbers: 'insertOrderedList' };

function runMark(mark) {
  const body = $('noteBody');
  body.focus();
  if (mark === 'code') toggleCode();
  else if (mark === 'link') { askLink(); return; }
  else if (COMMANDS[mark]) document.execCommand(COMMANDS[mark]);
  flatten();
  syncTools();
  save();
}

const CODE_BLOCKS = 'p, div, h1, h2, h3, h4, h5, h6, ul, ol, li, pre, blockquote';

function toggleCode() {
  const held = caretIn('code');
  if (held) { held.replaceWith(...held.childNodes); return; }
  const sel = getSelection();
  if (!sel || !sel.rangeCount) return;
  const range = sel.getRangeAt(0);
  const code = document.createElement('code');
  if (range.collapsed) {
    code.textContent = '​';
    range.insertNode(code);
  } else {
    code.append(range.extractContents());
    for (const block of code.querySelectorAll(CODE_BLOCKS)) block.replaceWith(...block.childNodes);
    range.insertNode(code);
  }
  const inside = document.createRange();
  inside.selectNodeContents(code);
  inside.collapse(false);
  sel.removeAllRanges();
  sel.addRange(inside);
}

const noteHref = (value) => (/^[a-z][a-z0-9+.-]*:/i.test(value) ? value : `https://${value}`);

/* A link asks for its address in the app's own modal; the selection is kept
   across it, since focus moves to the field. */
function askLink() {
  const sel = getSelection();
  const kept = sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null;
  const held = caretIn('a');
  modal({
    title: held ? 'Edit link' : 'Add link',
    field: held ? held.getAttribute('href') : '',
    placeholder: 'Address',
    ok: held ? 'Save' : 'Add',
    onOk(value) {
      const body = $('noteBody');
      body.focus();
      if (kept) { sel.removeAllRanges(); sel.addRange(kept); }
      const url = value.trim();
      if (url) {
        if (kept && kept.collapsed && !held) {
          document.execCommand('insertHTML', false, `<a href="${noteHref(url).replace(/"/g, '&quot;')}">${url.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))}</a>`);
        } else document.execCommand('createLink', false, noteHref(url));
      } else document.execCommand('unlink');
      flatten();
      syncTools();
      save();
    },
  });
}

function setLevel(level) {
  $('noteBody').focus();
  document.execCommand('formatBlock', false, `<${level}>`);
  flatten();
  syncTools();
  save();
}

let level = 'p';
function syncTools() {
  for (const button of document.querySelectorAll('.note-tool')) {
    const mark = button.dataset.mark;
    let on = false;
    try { on = document.queryCommandState(COMMANDS[mark]); } catch { on = false; }
    button.setAttribute('aria-pressed', on ? 'true' : 'false');
  }
  let more = Boolean(caretIn('a')) || Boolean(caretIn('code'));
  try { more = more || document.queryCommandState('strikeThrough'); } catch { /* none */ }
  $('noteMore').setAttribute('aria-pressed', more ? 'true' : 'false');
  const block = caretIn('h1,h2,h3,h4,h5,h6,pre');
  level = block ? block.tagName.toLowerCase() : 'p';
  const label = LEVELS.find(([k]) => k === level)[1];
  $('noteLevelValue').textContent = level === 'pre' ? 'Code' : label.replace(/ \(.+\)$/, '');
}

function caretIn(selector) {
  const sel = getSelection();
  if (!sel || !sel.rangeCount) return null;
  let node = sel.getRangeAt(0).startContainer;
  if (node.nodeType === 3) node = node.parentElement;
  const body = $('noteBody');
  if (!body.contains(node)) return null;
  for (let n = node; n && n !== body; n = n.parentElement) {
    if (n.matches && n.matches(selector)) return n;
  }
  return null;
}

/* ── Pasting: plain text, unless the text is itself markdown ─────────── */

function onPaste(event) {
  event.preventDefault();
  const text = event.clipboardData?.getData('text/plain') ?? '';
  if (!text) return;
  document.execCommand('insertHTML', false, pasteToHtml(text));
  flatten();
  syncTools();
  save();
}

/* ── Keeping the structure flat ──────────────────────────────────────── */

const FLAT = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6']);
const BLOCKY = new Set([...FLAT, 'UL', 'OL', 'BLOCKQUOTE', 'PRE']);

function flatten() {
  const body = $('noteBody');
  for (let pass = 0; pass < 5; pass += 1) {
    const guilty = [...body.querySelectorAll('p, div, h1, h2, h3, h4, h5, h6')]
      .filter((el) => FLAT.has(el.tagName) && [...el.children].some((c) => BLOCKY.has(c.tagName)));
    if (!guilty.length) return;
    for (const el of guilty) el.replaceWith(...el.childNodes);
  }
}

/* "- " or "1. " at the start of a line makes a list. */
function autoList(event) {
  if (!String(event.inputType || '').startsWith('insert')) return;
  const sel = getSelection();
  if (!sel.rangeCount || !sel.isCollapsed) return;
  const body = $('noteBody');
  const node = sel.anchorNode;
  const block = (node.nodeType === 1 ? node : node.parentElement)?.closest('p, div, h1, h2, h3, h4, h5, h6');
  if (!block || !body.contains(block) || block === body || block.closest('li, pre')) return;
  const before = document.createRange();
  before.setStart(block, 0);
  before.setEnd(sel.anchorNode, sel.anchorOffset);
  const marker = before.toString().match(/^(?:([-*])|(\d{1,3})\.)[\s ]$/);
  if (!marker) return;
  sel.removeAllRanges();
  sel.addRange(before);
  document.execCommand('delete');
  document.execCommand(marker[1] ? 'insertUnorderedList' : 'insertOrderedList');
  const at = getSelection().anchorNode;
  const list = (at?.nodeType === 1 ? at : at?.parentElement)?.closest('ol');
  if (marker[2] && list && marker[2] !== '1') list.setAttribute('start', String(Number(marker[2])));
  flatten();
  syncTools();
}

/* "[ ] " or "[x] " at the start of a bullet makes a task. */
function autoTask(event) {
  if (!String(event.inputType || '').startsWith('insert')) return;
  const sel = getSelection();
  if (!sel.rangeCount || !sel.isCollapsed) return;
  const body = $('noteBody');
  const node = sel.anchorNode;
  const el = node.nodeType === 1 ? node : node.parentElement;
  const block = el?.closest('li, p, div');
  if (!block || !body.contains(block) || block === body || block.closest('pre, ol')) return;
  const before = document.createRange();
  before.setStart(block, 0);
  before.setEnd(sel.anchorNode, sel.anchorOffset);
  const box = before.toString().match(/^\[([ xX]?)\][\s ]$/);
  if (!box) return;
  sel.removeAllRanges();
  sel.addRange(before);
  document.execCommand('delete');
  if (block.tagName !== 'LI') document.execCommand('insertUnorderedList');
  const at = getSelection().anchorNode;
  const li = (at?.nodeType === 1 ? at : at?.parentElement)?.closest('li');
  if (li) li.setAttribute('data-task', /x/i.test(box[1]) ? 'done' : 'open');
  flatten();
  syncTools();
}

function freshTasks() {
  $('noteBody').querySelectorAll('li[data-task="done"]').forEach((li) => {
    if (!li.textContent.trim()) li.setAttribute('data-task', 'open');
  });
}

/* ── Menus: one floating list, filled by whichever control opened it ──── */

let menuOpen = null;
let menuTimer = null;
/* Where Back goes from a menu that replaced another, as Share replaces the ⋮ menu. */
let menuBackTo = null;

function openMenu(anchor, items, { align = 'start' } = {}) {
  const list = $('menu');
  if (menuOpen === anchor) { closeMenu(); return; }
  menuBackTo = null;
  clearTimeout(menuTimer);
  list.innerHTML = '';
  for (const item of items) {
    const li = document.createElement('li');
    if (item.sep) { li.className = 'sep'; list.append(li); continue; }
    li.setAttribute('role', item.checked === undefined ? 'menuitem' : 'menuitemradio');
    if (item.checked !== undefined) li.setAttribute('aria-checked', String(item.checked));
    if (item.danger) li.classList.add('danger');
    // A switch draws its toggle as the icon and keeps the menu open when pressed.
    const icon = () => (item.on ? (item.on() ? 'toggle-on' : 'toggle-off') : item.icon);
    if (icon()) {
      li.classList.add('has-icon');
      li.innerHTML = `<svg><use href="#i-${icon()}"/></svg>`;
      if (item.on) { li.classList.add('switch'); li.classList.toggle('on', item.on()); }
    }
    li.append(document.createTextNode(item.label));
    li.addEventListener('click', (e) => {
      e.stopPropagation();
      if (item.on) {
        item.act();
        li.querySelector('use').setAttribute('href', `#i-${icon()}`);
        li.classList.toggle('on', item.on());
        return;
      }
      closeMenu();
      item.act();
    });
    list.append(li);
  }
  list.hidden = false;
  menuOpen = anchor;
  const box = anchor.getBoundingClientRect();
  const w = list.offsetWidth;
  const h = list.offsetHeight;
  const left = align === 'end' ? box.right - w : box.left;
  list.style.left = `${Math.max(8, Math.min(left, innerWidth - w - 8))}px`;
  const below = innerHeight - box.bottom;
  list.style.top = below < h + 8 && box.top > h + 8 ? `${box.top - h - 4}px` : `${box.bottom + 4}px`;
  requestAnimationFrame(() => { list.dataset.on = ''; });
}

function closeMenu() {
  const list = $('menu');
  if (!menuOpen) return;
  menuOpen = null;
  delete list.dataset.on;
  clearTimeout(menuTimer);
  menuTimer = setTimeout(() => { list.hidden = true; }, MS);
}

/* Keep the selection when a tool is pressed: a button tap would blur the text. */
for (const el of document.querySelectorAll('.note-tools button')) {
  el.addEventListener('mousedown', (e) => e.preventDefault());
  el.addEventListener('pointerdown', (e) => { if (e.pointerType !== 'mouse') e.preventDefault(); });
}

$('noteLevelTrigger').addEventListener('click', (e) => {
  e.stopPropagation();
  openMenu(e.currentTarget, LEVELS.map(([k, label]) => (k === 'sep' ? { sep: true } : { label, checked: k === level, act: () => setLevel(k) })));
});

$('noteMore').addEventListener('click', (e) => {
  e.stopPropagation();
  let strike = false;
  try { strike = document.queryCommandState('strikeThrough'); } catch { strike = false; }
  openMenu(e.currentTarget, [
    { label: 'Strikethrough', icon: 'strike', checked: strike, act: () => runMark('strike') },
    { label: 'Code', icon: 'code', checked: Boolean(caretIn('code')), act: () => runMark('code') },
    { label: caretIn('a') ? 'Edit Link' : 'Link', icon: 'link', checked: Boolean(caretIn('a')), act: () => runMark('link') },
  ]);
});

function setZoom(pct, store = true) {
  zoom = pct;
  document.documentElement.style.setProperty('--zoom', String(pct / 100));
  $('noteZoomValue').textContent = `${pct}%`;
  $('noteZoomTrigger').setAttribute('aria-label', `Text size, ${pct}%`);
  if (store) bridge.setZoom(pct);
}

$('noteZoomTrigger').addEventListener('click', (e) => {
  e.stopPropagation();
  openMenu(e.currentTarget, ZOOMS.map((z) => ({ label: `${z}%`, checked: z === zoom, act: () => setZoom(z) })), { align: 'end' });
});

/* ── Share: Yeetlist's menu, less the two paste sites, which need its server ── */

function fileName(title) {
  const clean = String(title || 'Untitled note').replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim().replace(/[. ]+$/, '');
  return (clean || 'Untitled note').slice(0, 60);
}

function current() {
  save({ now: true });
  const md = note.md || '';
  const title = (note.named && note.name) || titleOf(md);
  return { md, title };
}

/* The ⋮ menu: Dark Mode, Share and Delete. Share swaps the menu for the formats. */
function setLight(light) {
  document.documentElement.dataset.theme = light ? 'light' : 'dark';
  note.light = light;
  bridge.setLight(light);
}

$('noteOptions').addEventListener('click', (e) => {
  e.stopPropagation();
  const anchor = e.currentTarget;
  openMenu(anchor, [
    { label: 'Dark Mode', on: () => !note.light, act: () => setLight(!note.light) },
    { label: 'Share', icon: 'share', act: () => setTimeout(() => shareMenu(anchor), 0) },
    { label: 'Delete', icon: 'trash', act: askDelete },
  ], { align: 'end' });
});

function shareMenu(anchor) {
  const options = () => $('noteOptions').click();
  // Every row carries an icon, so the labels line up under Return's.
  openMenu(anchor, [
    { label: 'Return', icon: 'back', act: () => setTimeout(options, 0) },
    { label: 'MD', icon: 'download', act: () => { const n = current(); bridge.exportNote('md', `${fileName(n.title)}.md`, n.md); } },
    { label: 'HTML', icon: 'download', act: () => { const n = current(); bridge.exportNote('html', `${fileName(n.title)}.html`, noteToHtmlPage(n.title, n.md)); } },
    { label: 'TXT', icon: 'download', act: () => { const n = current(); bridge.exportNote('txt', `${fileName(n.title)}.txt`, markdownToText(n.md)); } },
    { label: 'Copy to Clipboard', icon: 'copy', act: () => { const n = current(); bridge.copy(n.md.trim(), n.title); } },
    { label: 'External', icon: 'share', act: () => { const n = current(); bridge.share(n.md.trim(), n.title); } },
  ], { align: 'end' });
  menuBackTo = options;
}

function askDelete() {
  const n = current();
  modal({
    title: 'Delete note?',
    text: `“${n.title}” will be deleted from this device. This cannot be undone.`,
    ok: 'Delete',
    onOk: () => { clearTimeout(saveTimer); bridge.deleteNote(); },
  });
}

$('noteBack').addEventListener('click', leave);

/* ── The colour ───────────────────────────────────────────────────────── */

function paintHue(hue) {
  const grid = $('hueGrid');
  if (!grid.childElementCount) {
    for (const h of SWATCHES) {
      const b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('role', 'radio');
      b.dataset.hue = String(h);
      b.setAttribute('aria-label', `Hue ${h}`);
      b.style.background = hsvHex(h, SAT, 100);
      b.addEventListener('click', (e) => { e.stopPropagation(); setHue(h); hueOpen(false); });
      grid.append(b);
    }
  }
  $('noteSwatch').style.background = hsvHex(hue, SAT, 100);
  for (const b of grid.children) b.setAttribute('aria-checked', String(Number(b.dataset.hue) === hue));
}

function setHue(h) {
  paintHue(h);
  if (note.hue === h) return;
  note.hue = h;
  bridge.setHue(h);
}

let hueTimer = null;
function hueOpen(on) {
  const pop = $('huePop');
  $('noteSwatch').setAttribute('aria-expanded', String(on));
  clearTimeout(hueTimer);
  if (on) {
    pop.hidden = false;
    // The button sits at the right of the bar, so the grid lines up with its end.
    const box = $('noteHue').getBoundingClientRect();
    pop.style.left = `${Math.max(8, Math.min(box.right - pop.offsetWidth, innerWidth - pop.offsetWidth - 8))}px`;
    pop.style.top = `${box.bottom + 4}px`;
    requestAnimationFrame(() => { pop.dataset.on = ''; });
  } else {
    delete pop.dataset.on;
    hueTimer = setTimeout(() => { pop.hidden = true; }, MS);
  }
}

$('noteHue').addEventListener('click', (e) => { e.stopPropagation(); closeMenu(); hueOpen($('huePop').hidden || $('huePop').dataset.on === undefined); });

/* ── Search in the note: amber hits, as UrsaPDF's ─────────────────────── */

let hits = [];
let hitAt = -1;

function openFind() {
  closeMenu();
  $('findBar').dataset.open = '';
  $('findBar').setAttribute('aria-hidden', 'false');
  $('findInput').focus();
  find();
}

function closeFind() {
  delete $('findBar').dataset.open;
  $('findBar').setAttribute('aria-hidden', 'true');
  $('findInput').blur();
  clearHits();
}

function clearHits() {
  hits = [];
  hitAt = -1;
  if (window.CSS && CSS.highlights) { CSS.highlights.delete('hits'); CSS.highlights.delete('hit-now'); }
  $('findCount').textContent = '';
}

function find() {
  const q = $('findInput').value;
  clearHits();
  if (!q) return;
  const needle = q.toLocaleLowerCase();
  const walker = document.createTreeWalker($('noteBody'), NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const text = n.data.toLocaleLowerCase();
    let i = text.indexOf(needle);
    while (i >= 0) {
      const r = new Range();
      r.setStart(n, i);
      r.setEnd(n, i + needle.length);
      hits.push(r);
      i = text.indexOf(needle, i + needle.length);
    }
  }
  if (window.CSS && CSS.highlights && hits.length) CSS.highlights.set('hits', new Highlight(...hits));
  if (hits.length) go(0); else $('findCount').textContent = '0';
}

function go(k) {
  if (!hits.length) return;
  hitAt = (k + hits.length) % hits.length;
  $('findCount').textContent = `${hitAt + 1}/${hits.length}`;
  if (window.CSS && CSS.highlights) CSS.highlights.set('hit-now', new Highlight(hits[hitAt]));
  const box = hits[hitAt].getBoundingClientRect();
  const scroll = $('noteScroll');
  const view = scroll.getBoundingClientRect();
  if (box.top < view.top + 48 || box.bottom > view.bottom - 48) {
    scroll.scrollBy({ top: box.top - view.top - view.height / 3, behavior: 'smooth' });
  }
}

$('noteFind').addEventListener('click', (e) => {
  e.stopPropagation();
  if ($('findBar').dataset.open !== undefined) closeFind(); else openFind();
});
$('findInput').addEventListener('input', find);
$('findInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); go(hitAt + (e.shiftKey ? -1 : 1)); }
  if (e.key === 'Escape') { e.preventDefault(); closeFind(); }
});
$('findPrev').addEventListener('click', () => go(hitAt - 1));
$('findNext').addEventListener('click', () => go(hitAt + 1));
$('findClose').addEventListener('click', closeFind);
for (const id of ['findPrev', 'findNext']) $(id).addEventListener('mousedown', (e) => e.preventDefault());

/* ── The modal ───────────────────────────────────────────────────────── */

let modalDone = null;
let modalTimer = null;

function modal({ title, text = '', field = null, placeholder = '', ok = 'OK', onOk }) {
  const m = $('modal');
  clearTimeout(modalTimer);
  $('modalTitle').textContent = title;
  $('modalText').textContent = text;
  const input = $('modalField');
  input.hidden = field === null;
  input.value = field ?? '';
  input.placeholder = placeholder;
  $('modalOk').textContent = ok;
  modalDone = onOk;
  m.hidden = false;
  requestAnimationFrame(() => { m.dataset.on = ''; });
  if (field !== null) setTimeout(() => { input.focus(); input.select(); }, 0);
}

function closeModal() {
  const m = $('modal');
  modalDone = null;
  delete m.dataset.on;
  clearTimeout(modalTimer);
  modalTimer = setTimeout(() => { m.hidden = true; }, MS);
}

$('modalCancel').addEventListener('click', closeModal);
$('modal').querySelector('.scrim').addEventListener('click', closeModal);
$('modalOk').addEventListener('click', () => {
  const done = modalDone;
  const value = $('modalField').value;
  closeModal();
  if (done) done(value);
});
$('modalField').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); $('modalOk').click(); }
});

/* ── Wiring ──────────────────────────────────────────────────────────── */

document.addEventListener('click', (event) => {
  if (!event.target.closest?.('#menu')) closeMenu();
  if (!event.target.closest?.('#huePop, #noteHue')) hueOpen(false);
  const tool = event.target.closest?.('.note-tool');
  if (tool) runMark(tool.dataset.mark);
});

/* A press left of a task's words ticks its box. */
document.addEventListener('click', (event) => {
  const li = event.target.closest?.('#noteBody li[data-task]');
  if (!li || event.clientX >= li.getBoundingClientRect().left) return;
  event.preventDefault();
  li.setAttribute('data-task', li.getAttribute('data-task') === 'done' ? 'open' : 'done');
  save();
});

document.addEventListener('input', (event) => {
  if (event.target.id === 'noteBody') {
    autoList(event); autoTask(event); freshTasks(); paintTitle(); save();
    if (hits.length || $('findInput').value) find();
  }
  if (event.target.id === 'noteTitle') save();
});

$('noteTitle').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') { event.preventDefault(); $('noteBody').focus(); }
});

document.addEventListener('paste', (event) => {
  if (event.target.closest?.('#noteBody')) onPaste(event);
});

document.addEventListener('selectionchange', syncTools);

addEventListener('resize', closeMenu);
$('noteScroll').addEventListener('scroll', closeMenu, { passive: true });

document.addEventListener('keydown', (event) => {
  if (!(event.metaKey || event.ctrlKey)) return;
  const k = event.key.toLowerCase();
  if (k === 'f') { event.preventDefault(); openFind(); return; }
  const mark = { b: 'bold', i: 'italic' }[k];
  if (!mark) return;
  event.preventDefault();
  runMark(mark);
});

/* The app pauses: anything typed in the last 600 ms is written now. */
document.addEventListener('visibilitychange', () => { if (document.hidden) save({ now: true }); });

open();
