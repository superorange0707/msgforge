const $ = id => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const esc = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const state = {
  packs: [], pack: null, name: null, saved: '',
  types: [], connections: [], targets: [{ connection: null, destination: null }], mode: 'spread',
  expect: { on: false, connection: null, destination: null, timeout: 10, match: '' },
  load: { on: false, rampUp: '', duration: '', senders: '', seed: '', unlimited: false },
  invalid: { on: false, percent: 5, kinds: null, connection: '', destination: null },
  raw: false, monitors: [], open: null,
  messages: [], picked: null, runs: [], sentTab: false, scenarios: [], generators: [],
};
const KINDS = [['empty', 'Empty'], ['garbage', 'Garbage'], ['truncated', 'Truncated'], ['field-removed', 'Field removed'], ['type-changed', 'Type changed']];

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/>',
  plus: '<path d="M5 12h14M12 5v14"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2.5"/><path d="M5 16H4.5A1.5 1.5 0 0 1 3 14.5v-10A1.5 1.5 0 0 1 4.5 3h10A1.5 1.5 0 0 1 16 4.5V5"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.3-4.9L3 9"/><path d="M3 4v5h5"/><path d="M4 13a8 8 0 0 0 14.3 4.9L21 15"/><path d="M21 20v-5h-5"/>',
  braces: '<path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5a2 2 0 0 0 2 2h1"/><path d="M16 21h1a2 2 0 0 0 2-2v-5a2 2 0 0 1 2-2 2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1"/>',
  send: '<path d="m21 3-9.5 9.5"/><path d="M21 3 14.5 21l-3-8.5L3 9.5z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  trash: '<path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="m6 7 1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12"/><path d="M10 11v6M14 11v6"/>',
  upload: '<path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M5 20h14"/>',
  bookmark: '<path d="M6 4h12v17l-6-4-6 4z"/>',
  play: '<path d="M7 4.5v15l12-7.5z"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  wand: '<path d="m4 20 10.5-10.5"/><path d="M15 3v3M13.5 4.5h3M20 9v2.4M18.8 10.2h2.4M8.5 4v2M7.5 5h2"/>',
  sliders: '<path d="M4 6h8M16 6h4M4 12h2M10 12h10M4 18h10M18 18h2"/><circle cx="14" cy="6" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
  gauge: '<path d="M4.5 18a9 9 0 1 1 15 0"/><path d="m12 13 4-5"/><circle cx="12" cy="13" r="1.2"/>',
  alert: '<path d="M12 4 2.8 19.5h18.4z"/><path d="M12 10v4.5M12 17.2v.3"/>',
  eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  archive: '<rect x="3" y="4" width="18" height="5" rx="1.5"/><path d="M5 9v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"/><path d="M10 13h4"/>',
  download: '<path d="M12 4v11"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/>',
};
const icon = name => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;
document.querySelectorAll('[data-icon]').forEach(e => e.insertAdjacentHTML('afterbegin', icon(e.dataset.icon)));
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => b.closest('dialog').close());

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, opts);
  if (!res.ok) {
    let msg = res.status + ' ' + res.statusText;
    try { msg = (await res.json()).error || msg; } catch (_) {}
    throw Object.assign(new Error(msg), { status: res.status });
  }
  return (res.headers.get('content-type') || '').includes('json') ? res.json() : res.text();
}
const json = (body, method = 'POST') => ({ method, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });

let toastTimer;
function toast(msg, bad) {
  const t = $('toast');
  // a modal dialog covers the page, so the toast moves into whichever dialog is on top
  ([...document.querySelectorAll('dialog[open]')].at(-1) || document.body).append(t);
  t.textContent = msg; t.className = 'on' + (bad ? ' bad' : '');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.className = '', bad ? 5000 : 1600);
}

/** A small dialog instead of prompt()/confirm(). Resolves to the typed text, true (no input), or null. */
function ask({ title, text = '', label = '', value = '', placeholder = '', ok = 'OK', danger = false }) {
  return new Promise(resolve => {
    const d = $('ask'), input = $('askInput');
    $('askTitle').textContent = title;
    $('askText').textContent = text; $('askText').hidden = !text;
    $('askField').hidden = !label; $('askLabel').textContent = label;
    input.value = value; input.placeholder = placeholder; input.required = !!label;
    $('askOk').textContent = ok; $('askOk').className = 'btn ' + (danger ? 'solid-danger' : 'primary');
    $('askForm').onsubmit = e => { e.preventDefault(); d.onclose = null; d.close(); resolve(label ? input.value.trim() : true); };
    $('askCancel').onclick = () => d.close();
    d.onclose = () => resolve(null);
    d.showModal();
    if (label) input.select();
  });
}

// ---- syntax colours. Spans only change colour, never width, so the overlay stays aligned with the textarea.
const PH = '(?<!\\\\)\\{\\{[^{}\\n]*\\}\\}';
const RX = {
  xml: new RegExp(`(${PH})|(<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>)|(<\\/?)([\\w:.-]+)|(\\/?>)|([\\w:.-]+)(?==["'{])|("(?:[^"<>\\n{]|\\{(?!\\{)|${PH})*")`, 'g'),
  json: new RegExp(`(${PH})|("(?:[^"\\\\\\n{]|\\\\.|\\{(?!\\{)|${PH})*")(\\s*:)?|(-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)|\\b(true|false|null)\\b|([{}\\[\\],:])`, 'g'),
  text: new RegExp(`(${PH})`, 'g'),
};
const PH_SPLIT = new RegExp(`(${PH})`);
const span = (cls, html) => `<span class="${cls}">${html}</span>`;
const withPh = (cls, s) => span(cls, s.split(PH_SPLIT).map((p, i) => i % 2 ? span('ph', esc(p)) : esc(p)).join(''));
const langOf = (name, text) => /\.xml$/i.test(name || '') ? 'xml' : /\.json$/i.test(name || '') ? 'json'
  : /^\s*</.test(text) ? 'xml' : /^\s*[\[{]/.test(text) ? 'json' : 'text';
function paint(m, lang) {
  if (m[1]) return span('ph', esc(m[1]));
  if (lang === 'xml') {
    if (m[2]) return withPh('t-com', m[2]);
    if (m[3]) return span('t-pun', esc(m[3])) + span('t-tag', esc(m[4]));
    if (m[5]) return span('t-pun', esc(m[5]));
    if (m[6]) return span('t-attr', esc(m[6]));
    if (m[7]) return withPh('t-str', m[7]);
  } else if (lang === 'json') {
    if (m[2]) return withPh(m[3] ? 't-key' : 't-str', m[2]) + (m[3] ? span('t-pun', esc(m[3])) : '');
    if (m[4]) return span('t-num', m[4]);
    if (m[5]) return span('t-kw', m[5]);
    if (m[6]) return span('t-pun', m[6]);
  }
  return esc(m[0]);
}
function highlight(text, lang) {
  const rx = RX[lang]; rx.lastIndex = 0;
  let out = '', last = 0, m;
  while ((m = rx.exec(text))) {
    if (!m[0]) { rx.lastIndex++; continue; }
    out += esc(text.slice(last, m.index)) + paint(m, lang);
    last = m.index + m[0].length;
  }
  return out + esc(text.slice(last));
}

// ---- packs & templates
const src = $('src');
const pack = () => state.packs.find(p => p.id === state.pack);
const tplUrl = (p, t) => `/packs/${encodeURIComponent(p)}/templates/${encodeURIComponent(t)}`;
function renderPacks() {
  const box = $('packs'), q = $('filter').value.trim().toLowerCase(); box.replaceChildren();
  for (const p of state.packs) {
    const templates = p.templates.filter(t => !q || t.toLowerCase().includes(q));
    if (q && !templates.length && !p.error) continue;
    const head = el('div', 'pack'); head.append(el('span', '', p.name));
    const edit = el('button'); edit.innerHTML = icon('sliders'); edit.title = 'Pack settings'; edit.setAttribute('aria-label', 'Settings for ' + p.name);
    edit.onclick = () => openPack(p);
    const add = el('button'); add.innerHTML = icon('plus'); add.title = 'New template'; add.setAttribute('aria-label', 'New template in ' + p.name);
    add.onclick = () => newTemplate(p.id);
    const up = el('button'); up.innerHTML = icon('upload'); up.title = 'Import files (or drop them, or paste a message)'; up.setAttribute('aria-label', 'Import files into ' + p.name);
    up.onclick = () => { importInto = p.id; $('importFile').value = ''; $('importFile').click(); };
    const acts = el('div', 'acts'); acts.append(...(p.error ? [edit] : [edit, up, add]));
    head.append(acts); box.append(head);
    if (p.error) box.append(el('div', 'packerr', p.error));
    for (const t of templates) {
      const ext = (t.match(/\.(xml|json)$/i) || [])[1];
      const b = el('button', 'tpl' + (p.id === state.pack && t === state.name ? ' on' : ''));
      b.append(el('span', 'name', ext ? t.slice(0, -ext.length - 1) : t));
      if (ext) b.append(el('span', 'tag ' + ext.toLowerCase(), ext.toUpperCase()));
      b.title = t; b.onclick = () => openTemplate(p.id, t);
      box.append(b);
    }
  }
  if (q && !box.children.length) box.append(el('div', 'empty', 'No match'));
}
async function loadPacks() { state.packs = await api('/packs'); renderPacks(); }
const dirty = () => src.value !== state.saved;
async function leaveOk() {
  return !state.name || !dirty() || await ask({ title: 'Discard changes?', text: state.name + ' has unsaved edits.', ok: 'Discard', danger: true });
}
async function openTemplate(packId, name) {
  if (!await leaveOk()) return;
  try { const text = await api(tplUrl(packId, name)); show(packId, name, text, text); } catch (e) { toast(e.message, true); }
}
// text: a message pasted on the page. It starts the template, recognised like an imported file, and nothing is written until Save.
async function newTemplate(packId, text = '') {
  if (!packId) return toast('Create a pack first.', true);
  if (!await leaveOk()) return;
  let guess = '';
  if (text) {   // the first name that is free: taking an existing one would open that template and drop the paste
    const ext = { '<': 'xml', '{': 'json', '[': 'json' }[text.trimStart()[0]] || 'txt', taken = state.packs.find(p => p.id === packId).templates;
    for (let n = 1; taken.includes(guess = 'message' + (n > 1 ? '-' + n : '') + '.' + ext); n++);
  }
  const name = await ask({ title: 'New template', label: 'File name', value: guess, placeholder: 'order.json', ok: 'Create' });
  if (!name) return;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) return toast('Use letters, digits, dot, dash and underscore.', true);
  if (state.packs.find(p => p.id === packId).templates.includes(name)) {   // an empty editor under that name would overwrite it on save
    state.saved = src.value;   // leaving the open template was confirmed above; do not ask again
    await openTemplate(packId, name);
    return toast(name + ' already exists. It is open now.');
  }
  show(packId, name, text, null);
  src.focus();
  if (text) await recognise(true);
}
function show(packId, name, text, saved) {
  Object.assign(state, { pack: packId, name, saved });
  src.value = text; src.scrollTop = src.scrollLeft = 0;
  renderPacks(); edited(); preview();
}
async function openFirst() {
  const first = state.packs.find(p => p.templates.length);
  if (first) { const text = await api(tplUrl(first.id, first.templates[0])); show(first.id, first.templates[0], text, text); }
  else show(null, null, '', '');
}
let gutterLines = 0;
function edited() {
  const text = src.value;
  $('hl').innerHTML = highlight(text, langOf(state.name, text)) + '\n';
  const lines = text.split('\n').length;
  if (lines !== gutterLines) { gutterLines = lines; $('gutter').textContent = Array.from({ length: lines }, (_, i) => i + 1).join('\n'); }
  src.disabled = !state.name;
  $('save').disabled = !state.name || !dirty();
  $('deleteTemplate').disabled = !state.name || state.saved === null;
  $('insert').disabled = $('recognise').disabled = !state.name;
  const file = $('file'); file.replaceChildren();
  if (state.name) {
    file.append(el('span', 'dim', state.pack + ' /'), el('span', 'nm', state.name));
    if (dirty()) file.append(el('i', 'dot'));
  }
}
// createOnly: the server answers 409 if the name is taken, which on a file system that ignores case includes Order.json for order.json
const putTemplate = (packId, name, body, createOnly) => api(tplUrl(packId, name),
  { method: 'PUT', headers: { 'content-type': 'text/plain;charset=UTF-8', ...(createOnly ? { 'If-None-Match': '*' } : {}) }, body });
async function save() {
  if (!state.name || !dirty()) return;
  try {
    const body = src.value;
    await putTemplate(state.pack, state.name, body, state.saved === null);   // null: a new template, never saved
    state.saved = body; edited(); await loadPacks(); toast('Saved');
  } catch (e) { toast(e.message, true); }
}
async function deleteTemplate() {
  if (!await ask({ title: 'Delete template?', text: state.pack + ' / ' + state.name + ' is removed from disk.', ok: 'Delete', danger: true })) return;
  try { await api(tplUrl(state.pack, state.name), { method: 'DELETE' }); state.saved = src.value; await loadPacks(); await openFirst(); toast('Deleted'); }
  catch (e) { toast(e.message, true); }
}

// ---- import: message files from disk become templates (button on a pack, or drop or paste them anywhere on the page)
let importInto = null;
async function importFiles(packId, files) {
  if (!packId) return toast('Create a pack first.', true);
  if (!await leaveOk()) return;
  const target = state.packs.find(p => p.id === packId);
  let last = null;
  for (const file of files) {
    const name = file.name.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^[^A-Za-z0-9]+/, '');
    if (!name) { toast(file.name + ' has no usable name.', true); continue; }
    if (file.size > 1_000_000) { toast(file.name + ' is larger than 1 MB.', true); continue; }
    try {
      const body = await file.text();
      try { await putTemplate(packId, name, body, true); } catch (e) {
        if (e.status !== 409) throw e;
        if (!await ask({ title: 'Replace template?', text: target.name + ' already has ' + name + '.', ok: 'Replace', danger: true })) continue;
        await putTemplate(packId, name, body, false);
      }
      last = name;
    } catch (e) { toast(e.message, true); }
  }
  if (!last) return;
  state.saved = src.value;   // the open template was confirmed above; do not ask again
  await loadPacks(); await openTemplate(packId, last);
  if (await recognise(true) === 0) toast('Imported');   // otherwise recognise has said what it did, or what went wrong
}

// A real message as a template: ids, times and whatever else has to differ per message become placeholders.
// The file is not touched: the result is an unsaved edit, to be saved or discarded like any other.
// After an import only what is evidently per message is replaced. Asked for with the wand, values that are one of the
// pack's field choices are replaced too: that changes what a sample stands for, so it is the user's step, and is named.
// Returns how many values were replaced, or null when it failed (and has said so).
async function recognise(afterImport) {
  if (!state.name) return 0;
  try {
    const found = await api('/recognise?pack=' + encodeURIComponent(state.pack) + (afterImport ? '' : '&fields=true'),
      { method: 'POST', headers: { 'content-type': 'text/plain;charset=UTF-8' }, body: src.value });
    const n = found.replacements.length, fromPack = found.replacements.filter(r => r.field);
    if (n || found.template !== src.value) { src.value = found.template; edited(); previewSoon(); }
    if (n) toast((n === 1 ? '1 value became a placeholder' : n + ' values became placeholders')
      + (fromPack.length ? ', ' + fromPack.length + ' from the pack: ' + [...new Set(fromPack.map(r => r.field))].join(', ') : ''));
    else if (!afterImport) toast('Nothing that should differ per message');
    return n;
  } catch (e) { toast(e.message, true); return null; }
}

// ---- pack settings
let packEditing = null;   // null while creating
function fieldRow(key = '', value = '') {
  const row = el('div', 'frow'), k = el('input'), v = el('input'), x = el('button', 'iconbtn');
  k.value = key; k.placeholder = 'name'; k.setAttribute('aria-label', 'Field name');
  v.value = value; v.placeholder = '{{pick A B C}}'; v.setAttribute('aria-label', 'Field value');
  x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', 'Remove field'); x.onclick = () => row.remove();
  row.append(k, v, x); $('pFields').append(row);
  return k;
}
function openPack(p) {
  packEditing = p || null;
  $('packTitle').textContent = p ? p.name : 'New pack';
  $('pName').value = p ? p.name : ''; $('pId').value = p ? p.id : ''; $('pId').disabled = !!p;
  $('pFields').replaceChildren();
  Object.entries(p ? p.fields : {}).forEach(([k, v]) => fieldRow(k, v));
  $('pDelete').hidden = !p;
  $('packDialog').showModal();
}
async function savePack(e) {
  e.preventDefault();
  const fields = {};
  for (const row of $('pFields').children) {
    const [k, v] = row.querySelectorAll('input');
    if (k.value.trim()) fields[k.value.trim()] = v.value;
  }
  try {
    const id = $('pId').value.trim();
    if (packEditing) await api('/packs/' + encodeURIComponent(id), json({ name: $('pName').value, fields }, 'PUT'));
    else await api('/packs', json({ id, name: $('pName').value, fields }));
    await loadPacks(); $('packDialog').close(); preview(); toast('Saved');
  } catch (err) { toast(err.message, true); }
}
async function deletePack() {
  const p = packEditing;
  if (!await ask({ title: 'Delete pack?', text: `${p.name} and its ${p.templates.length} template(s) are removed from disk.`, ok: 'Delete', danger: true })) return;
  try {
    await api('/packs/' + encodeURIComponent(p.id), { method: 'DELETE' });
    $('packDialog').close(); await loadPacks();
    if (state.pack === p.id) { state.saved = src.value; await openFirst(); }
    toast('Deleted');
  } catch (err) { toast(err.message, true); }
}

// ---- insert menu
const GENERATORS = [['uuid', 'UUID'], ['id', '32-char id'], ['now', 'UTC timestamp'], ['today', 'UTC date'], ['seq', 'counter'],
  ['int 1 100', 'whole number'], ['decimal 1 1000 2', 'decimal'], ['pick A B C', 'one of'], ['digits 6', 'digits'], ['alnum 8', 'letters and digits'], ['iban GB', 'IBAN']];
function openMenu() {
  const menu = $('menu'); menu.replaceChildren();
  const add = (expr, hint) => {
    const b = el('button', '', `{{${expr}}}`); b.type = 'button'; if (hint) b.append(el('span', '', hint));
    b.onclick = () => { closeMenu(); src.focus(); src.setRangeText(`{{${expr}}}`, src.selectionStart, src.selectionEnd, 'end'); edited(); previewSoon(); };
    menu.append(b);
  };
  const fields = Object.keys(pack()?.fields || {});
  if (fields.length) { menu.append(el('h6', '', pack().name)); fields.forEach(f => add(f)); }
  if (state.generators.length) { menu.append(el('h6', '', 'Plugins')); state.generators.forEach(g => add(g)); }
  menu.append(el('h6', '', 'Generators')); GENERATORS.forEach(g => add(g[0], g[1]));
  const r = $('insert').getBoundingClientRect();
  menu.hidden = false;
  menu.style.top = Math.min(r.bottom + 6, innerHeight - menu.offsetHeight - 8) + 'px';
  menu.style.left = Math.max(8, Math.min(r.left, innerWidth - 272)) + 'px';
  $('insert').setAttribute('aria-expanded', 'true');
  menu.querySelector('button').focus();
}
function closeMenu(refocus) {
  if ($('menu').hidden) return;
  $('menu').hidden = true; $('insert').setAttribute('aria-expanded', 'false');
  if (refocus) $('insert').focus();
}

// ---- preview
// ponytail: "a=1, b=2" text. A value cannot contain a comma; switch to a key/value table if that is needed.
function kv(text) {
  const out = {};
  for (const part of text.split(',')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}
const kvText = obj => Object.entries(obj || {}).map(([k, v]) => k + '=' + v).join(', ');
const vars = () => kv($('vars').value);
let previewTimer;
const previewSoon = () => { clearTimeout(previewTimer); previewTimer = setTimeout(preview, 250); };
async function preview() {
  const out = $('preview');
  if (!state.pack) { out.textContent = ''; out.dataset.raw = ''; return; }
  if (state.raw) { out.dataset.raw = src.value; out.textContent = src.value; out.classList.remove('err'); return; }   // as typed is what goes out
  try {
    const text = (await api('/preview', json({ pack: state.pack, body: src.value, vars: vars() }))).rendered;
    out.dataset.raw = text; out.innerHTML = highlight(text, langOf(state.name, text)); out.classList.remove('err');
  } catch (e) { out.dataset.raw = ''; out.textContent = e.message; out.classList.add('err'); }
}
function tab(sent) {
  state.sentTab = sent;
  $('tabPreview').classList.toggle('on', !sent); $('tabSent').classList.toggle('on', sent);
  $('tabPreview').setAttribute('aria-selected', !sent); $('tabSent').setAttribute('aria-selected', sent);
  $('preview').hidden = sent; $('sent').hidden = !sent; $('reroll').hidden = sent;
  if (sent) showPicked();
}
async function copy() {
  const text = state.sentTab ? $('sentBody').dataset.raw : $('preview').dataset.raw;
  if (!text) return;
  try { await navigator.clipboard.writeText(text); toast('Copied'); } catch (_) { toast('Copy is blocked in this browser.', true); }
}

// ---- connections: the send form. A run goes to one or more targets (connection + destination).
const connOf = id => state.connections.find(c => c.id === id);
const pressed = (button, on) => { button.classList.toggle('on', on); button.setAttribute('aria-pressed', on); };
const NEW_DESTINATION = ' new';   // saved destinations are trimmed, so a leading space cannot clash with one
function remember() {
  try { localStorage.setItem('send', JSON.stringify({ targets: state.targets, mode: state.mode, expect: state.expect, load: state.load, invalid: state.invalid, count: $('count').value, rate: $('rate').value })); } catch (_) {}
}
async function loadConnections() {
  [state.types, state.connections] = await Promise.all([state.types.length ? state.types : api('/types'), api('/connections')]);
  renderTargets();
}
function renderTargets() {
  const box = $('targets'); box.replaceChildren();
  state.targets.forEach((t, i) => {
    if (!connOf(t.connection)) t.connection = state.connections[0]?.id || null;
    const c = connOf(t.connection), list = c?.destinations || [];
    if (!list.includes(t.destination)) t.destination = list[0] || null;

    const cSel = el('select'); cSel.required = true; cSel.setAttribute('aria-label', 'Connection');
    cSel.append(...state.connections.map(x => new Option(x.name, x.id)));
    if (!state.connections.length) cSel.append(new Option('Add a connection', ''));
    cSel.value = t.connection || '';
    cSel.onchange = () => { t.connection = cSel.value || null; t.destination = null; renderTargets(); };

    const dSel = el('select'); dSel.required = true; dSel.setAttribute('aria-label', 'Destination');
    dSel.append(...list.map(x => new Option(x, x)));
    if (!list.length) dSel.append(new Option('No destinations yet', ''));
    if (c) dSel.append(new Option('+ New destination…', NEW_DESTINATION));
    dSel.value = t.destination || '';
    dSel.onchange = () => { if (dSel.value === NEW_DESTINATION) addDestination(t); else { t.destination = dSel.value || null; remember(); } };

    const block = el('div', 'target'), row = el('div', 'pair'), cField = el('label', 'field grow'), dField = el('label', 'field');
    cField.append(el('span', '', state.targets.length > 1 ? 'Connection ' + (i + 1) : 'Connection'), cSel);
    dField.append(el('span', '', 'Destination'), dSel);
    row.append(cField);
    if (state.targets.length > 1) {
      const x = el('button', 'sq'); x.type = 'button'; x.innerHTML = icon('x'); x.title = 'Remove target'; x.setAttribute('aria-label', 'Remove target ' + (i + 1));
      x.onclick = () => { state.targets.splice(i, 1); renderTargets(); };
      row.append(x);
    }
    block.append(row);
    if (c?.summary) {
      const line = el('div', 'conn'), led = el('i', 'led'); led.dataset.conn = c.id;
      line.append(led, el('span', '', c.summary)); block.append(line);
    }
    block.append(dField); box.append(block);
  });
  $('mode').hidden = state.targets.length < 2;
  $('mode').querySelectorAll('button').forEach(b => pressed(b, b.dataset.mode === state.mode));
  $('addTarget').disabled = !state.connections.length || state.targets.length >= 10;
  paintLeds(); renderExpect(); remember();
}
// The expectation: where the open template's messages should arrive. Only connections whose type can receive are offered.
const receivers = () => state.connections.filter(c => state.types.find(t => t.id === c.type)?.canReceive);
function renderExpect() {
  const x = state.expect, list = receivers(), box = $('expect');
  if (!list.length) x.on = false;
  $('verify').disabled = !list.length; $('verify').setAttribute('aria-pressed', x.on);
  box.hidden = !x.on; box.replaceChildren();   // emptied while off, so a hidden required control cannot block Send
  renderInvalid();
  if (!x.on) return;
  if (!list.some(c => c.id === x.connection)) x.connection = list[0].id;
  const dests = connOf(x.connection).destinations;
  if (!dests.includes(x.destination)) x.destination = dests[0] || null;
  const cSel = el('select'), dSel = el('select'), time = el('input'), match = el('input');
  match.value = x.match; match.placeholder = '{{uuid}}'; match.autocomplete = 'off'; match.oninput = () => { x.match = match.value; remember(); };
  match.title = 'What identifies a message when it arrives. Empty uses the first uuid or id in the body.';
  cSel.append(...list.map(c => new Option(c.name, c.id))); cSel.value = x.connection;
  cSel.onchange = () => { x.connection = cSel.value; x.destination = null; renderExpect(); remember(); };
  dSel.append(...dests.map(d => new Option(d, d))); if (!dests.length) dSel.append(new Option('No destinations yet', ''));
  dSel.value = x.destination || ''; dSel.required = true; dSel.onchange = () => { x.destination = dSel.value || null; remember(); };
  time.type = 'number'; time.min = 1; time.max = 600; time.required = true; time.value = x.timeout;
  time.oninput = () => { x.timeout = +time.value || 10; remember(); };
  const field = (label, control) => { const f = el('label', 'field'); f.append(el('span', '', label), control); return f; };
  const row = el('div', 'two'); row.append(field('Timeout (s)', time), field('Match', match));
  box.append(field('Arrives on', cSel), field('Destination', dSel), row);
}
const expectation = () => state.expect.on ? { connection: state.expect.connection, destination: state.expect.destination } : null;

// The load plan and the invalid share: two blocks that stay folded away until asked for.
const whole = v => v === '' || v == null || isNaN(+v) ? null : Math.trunc(+v);
function renderLoad() {
  const l = state.load;
  pressed($('loadToggle'), l.on); $('load').hidden = !l.on;
  for (const [id, key] of [['rampUp', 'rampUp'], ['duration', 'duration'], ['senders', 'senders'], ['seed', 'seed']]) if ($(id).value !== String(l[key])) $(id).value = l[key];
  const endless = l.on && l.unlimited;
  pressed($('unlimited'), l.unlimited);
  $('count').disabled = $('duration').disabled = endless;   // an endless run has neither
}
function kindButtons(box, chosen, changed) {
  box.replaceChildren(...KINDS.map(([id, label]) => {
    const b = el('button', '', label); b.type = 'button'; b.setAttribute('aria-pressed', chosen().includes(id));
    b.onclick = () => { const now = chosen().includes(id) ? chosen().filter(k => k !== id) : [...chosen(), id]; if (now.length) { changed(now); b.setAttribute('aria-pressed', now.includes(id)); } };
    return b;
  }));
}
// Where the invalid messages should arrive, offered once the run verifies at all: a reject queue, or nowhere.
function arriveOn(box, where, changed) {
  const cSel = el('select'), dSel = el('select'), known = receivers();
  cSel.append(new Option('Invalid arrive nowhere', ''), ...known.map(c => new Option('Invalid arrive on ' + c.name, c.id))); withMissing(cSel, where.connection);
  cSel.value = where.connection || ''; cSel.setAttribute('aria-label', 'Where invalid messages arrive');
  cSel.onchange = () => { where.connection = cSel.value; where.destination = null; changed(); };
  box.replaceChildren(cSel);
  if (!where.connection) return;
  const dests = connOf(where.connection)?.destinations || [];
  if (!where.destination) where.destination = dests[0] || null;
  dSel.append(...dests.map(d => new Option(d, d))); withMissing(dSel, where.destination); if (!dSel.options.length) dSel.append(new Option('No destinations yet', ''));
  dSel.value = where.destination || ''; dSel.required = true; dSel.setAttribute('aria-label', 'Destination for invalid messages');
  dSel.onchange = () => { where.destination = dSel.value || null; remember(); };
  box.append(dSel);
}
function renderInvalid() {
  const v = state.invalid;
  if (!v.kinds || !v.kinds.length) v.kinds = KINDS.map(k => k[0]);
  pressed($('invalidToggle'), v.on); $('invalid').hidden = !v.on;
  $('invalidPercent').value = v.percent; $('invalidOut').textContent = v.percent + '%';
  kindButtons($('invalidKinds'), () => v.kinds, now => { v.kinds = now; remember(); });
  const box = $('invalidExpect'); box.hidden = !(v.on && state.expect.on); box.replaceChildren();
  if (box.hidden) return;
  const cField = el('label', 'field'), dField = el('label', 'field'), holder = el('div');
  arriveOn(holder, v, () => { renderInvalid(); remember(); });
  const [cSel, dSel] = holder.children;
  cField.append(el('span', '', 'Invalid arrive on'), cSel); cSel.options[0].text = 'Nowhere'; [...cSel.options].slice(1).forEach(o => o.text = o.text.replace('Invalid arrive on ', ''));
  box.append(cField);
  if (dSel) { dField.append(el('span', '', 'Destination'), dSel); box.append(dField); }
}
const loadPlan = () => state.load.on ? { rampUpSeconds: whole(state.load.rampUp), durationSeconds: state.load.unlimited ? null : whole(state.load.duration),
  senders: whole(state.load.senders), unlimited: state.load.unlimited || null } : null;
const invalidShare = () => state.invalid.on && state.invalid.percent > 0 ? { percent: state.invalid.percent, kinds: state.invalid.kinds,
  expect: state.expect.on && state.invalid.connection ? { connection: state.invalid.connection, destination: state.invalid.destination } : null } : null;
const seed = () => state.load.on ? whole(state.load.seed) : null;
function paintLeds() {   // separate from renderTargets so the once-a-second refresh never rebuilds an open dropdown
  document.querySelectorAll('#targets .led').forEach(led => {
    const last = state.runs.find(r => r.targets.some(t => t.connectionId === led.dataset.conn));
    led.className = 'led' + (!last ? '' : last.status === 'FAILED' ? ' bad' : last.status === 'RUNNING' || last.status === 'VERIFYING' ? ' live' : last.sent ? ' ok' : '');
  });
}
async function addDestination(target) {
  const c = connOf(target.connection);
  const name = await ask({ title: 'New destination', text: c.name, label: 'Queue name', placeholder: 'DEV.QUEUE.1', ok: 'Add' });
  if (!name) return renderTargets();
  try {
    await api('/connections/' + c.id, json({ ...c, destinations: [...c.destinations, name] }, 'PUT'));
    target.destination = name; await loadConnections();
  } catch (e) { toast(e.message, true); renderTargets(); }
}
async function send(ev) {
  ev.preventDefault();
  if (!state.name) return toast('Pick a template first.', true);
  try {
    const run = await api('/runs', json({
      pack: state.pack, template: state.name, body: src.value, targets: state.targets, mode: state.mode,
      count: state.load.on && state.load.unlimited ? 0 : +$('count').value, rate: +$('rate').value || 0, vars: vars(), headers: kv($('headers').value),
      expect: expectation(), timeoutSeconds: state.expect.on ? state.expect.timeout : null, match: state.expect.on && state.expect.match.trim() || null,
      load: loadPlan(), invalid: invalidShare(), seed: seed(), raw: state.raw || null,
    }));
    refresh();
    if (state.load.on) openRun(run.id);   // a load run is watched, not waited for
  } catch (e) { toast(e.message, true); }
}

// ---- connections: the settings dialog
let editing = null;
const blankConnection = () => ({ id: null, name: '', type: (state.types.find(t => t.id !== 'dry-run' && t.fields.length) || state.types[0]).id, settings: {}, secretsSet: [], destinations: [] });
function openSettings() {
  editing = structuredClone(connOf(state.targets[0].connection) || blankConnection());
  renderConnList(); renderConnForm();
  $('settings').showModal();
}
function renderConnList() {
  const box = $('connList'); box.replaceChildren();
  const item = (name, type, on, click) => {
    const b = el('button', 'citem' + (on ? ' on' : '')); b.type = 'button';
    b.append(el('b', '', name)); if (type) b.append(el('span', '', type));
    b.onclick = click; box.append(b);
  };
  for (const c of state.connections) item(c.name, c.typeLabel, editing.id === c.id, () => { editing = structuredClone(c); renderConnList(); renderConnForm(); });
  if (!editing.id) item('New connection', '', true, () => {});
}
function renderConnForm() {
  $('cName').value = editing.name;
  $('cType').replaceChildren(...state.types.map(t => new Option(t.label, t.id)));
  if (!state.types.some(t => t.id === editing.type)) $('cType').append(new Option(editing.typeLabel, editing.type));
  $('cType').value = editing.type;
  renderTypeFields(); renderChips();
  $('cResult').hidden = true; $('cDelete').hidden = !editing.id;
}
function renderTypeFields() {
  const type = state.types.find(t => t.id === $('cType').value), box = $('cFields'); box.replaceChildren();
  box.hidden = !type?.fields.length;
  if (!type) return;   // not installed: nothing is known about its settings
  for (const f of type.fields) {
    const stored = f.secret && editing.type === type.id && editing.secretsSet.includes(f.key);
    const label = el('label', 'field'), input = el('input');
    label.append(el('span', '', f.label), input);
    input.dataset.key = f.key; input.autocomplete = 'off'; input.required = f.required && !stored;
    input.placeholder = stored ? 'Saved. Type to replace' : f.placeholder;
    if (f.secret) { input.type = 'password'; input.autocomplete = 'new-password'; } else input.value = editing.settings[f.key] || '';
    if (stored) {
      const clear = el('button', 'clear', 'Clear'); clear.type = 'button';
      clear.onclick = () => { input.dataset.clear = '1'; input.placeholder = f.placeholder; input.required = f.required; clear.remove(); };
      label.firstChild.append(clear);
    }
    box.append(label);
  }
}
function renderChips() {
  const row = $('cDests'), input = $('cDestInput');
  row.querySelectorAll('.chipx').forEach(c => c.remove());
  editing.destinations.forEach((d, i) => {
    const chip = el('span', 'chipx', d), x = el('button');
    x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', 'Remove ' + d);
    x.onclick = () => { editing.destinations.splice(i, 1); renderChips(); };
    chip.append(x); row.insertBefore(chip, input);
  });
}
function flushDestination() {
  const v = $('cDestInput').value.trim();
  if (v && !editing.destinations.includes(v)) editing.destinations.push(v);
  $('cDestInput').value = ''; renderChips();
}
function collectConnection() {
  flushDestination();
  const settings = {};
  $('cFields').querySelectorAll('input').forEach(i => {
    if (i.type === 'password' && !i.value && !i.dataset.clear) return;   // untouched secret: leave it out so the saved one is kept
    settings[i.dataset.key] = i.value;
  });
  return { id: editing.id, name: $('cName').value, type: $('cType').value, settings, destinations: editing.destinations };
}
function showResult(ok, text) { const r = $('cResult'); r.hidden = false; r.className = 'result ' + (ok ? 'ok' : 'bad'); r.textContent = text; }
async function saveConnection(e) {
  e.preventDefault();
  const c = collectConnection();
  try {
    const saved = await api(c.id ? '/connections/' + c.id : '/connections', json(c, c.id ? 'PUT' : 'POST'));
    if (!c.id && state.targets.length === 1) Object.assign(state.targets[0], { connection: saved.id, destination: null });
    await loadConnections();
    editing = structuredClone(state.connections.find(x => x.id === saved.id));
    renderConnList(); renderConnForm(); toast('Saved');
  } catch (err) { showResult(false, err.message); }
}
async function testConnection() {
  if (!$('connForm').reportValidity()) return;
  const btn = $('cTest'); btn.disabled = true; btn.textContent = 'Testing…';
  try { const r = await api('/connections/test', json(collectConnection())); showResult(r.ok, r.ok ? 'Connected' : r.error); }
  catch (err) { showResult(false, err.message); }
  finally { btn.disabled = false; btn.textContent = 'Test connection'; }
}
async function deleteConnection() {
  if (!await ask({ title: 'Delete connection?', text: editing.name + ' and its destinations are removed.', ok: 'Delete', danger: true })) return;
  try {
    await api('/connections/' + editing.id, { method: 'DELETE' });
    await loadConnections();
    editing = structuredClone(state.connections[0] || blankConnection());
    renderConnList(); renderConnForm(); toast('Deleted');
  } catch (err) { showResult(false, err.message); }
}

// ---- buckets: how a watched destination sorts what arrives. One expression each: /a/json/pointer, header:Name, or a regular expression.
const bucketOf = text => text.startsWith('/') ? { pointer: text } : /^header:/i.test(text) ? { header: text.slice(7).trim() } : { regex: text };
const bucketText = b => b.name || b.pointer || (b.header ? 'header:' + b.header : b.regex);
// A chip row over the given array of buckets. Typed text becomes a bucket on Enter; one saved with a name keeps it.
function bucketChips(buckets) {
  const row = el('div', 'chiprow'), input = el('input');
  input.placeholder = '/reason, header:name or a regular expression, then Enter'; input.autocomplete = 'off'; input.setAttribute('aria-label', 'Add a bucket');
  const draw = () => {
    row.querySelectorAll('.chipx').forEach(c => c.remove());
    buckets.forEach((b, i) => {
      const chip = el('span', 'chipx', bucketText(b)), x = el('button');
      x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', 'Remove bucket ' + bucketText(b));
      x.onclick = () => { buckets.splice(i, 1); draw(); };
      chip.append(x); row.insertBefore(chip, input);
    });
  };
  buckets.flush = () => { const v = input.value.trim(); if (v && buckets.length < 5) buckets.push(bucketOf(v)); input.value = ''; draw(); };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); buckets.flush(); } });
  input.addEventListener('blur', buckets.flush);
  row.append(input); draw();
  return row;
}

// ---- scenarios: saved runs, with several templates in weighted shares
let sc = null;
async function loadScenarios() { state.scenarios = await api('/scenarios'); renderScenarios(); }
function renderScenarios() {
  const box = $('scenarios'); box.replaceChildren();
  for (const s of state.scenarios) {
    const row = el('div', 'scn'), open = el('button', 'tpl'), play = el('button', 'play');
    open.append(el('span', 'name', s.name)); open.title = 'Edit ' + s.name; open.onclick = () => openScenario(s);
    play.innerHTML = icon('play'); play.title = 'Run ' + s.name; play.setAttribute('aria-label', 'Run ' + s.name);
    play.onclick = () => api(`/scenarios/${s.id}/run`, { method: 'POST' }).then(refresh, e => toast(e.message, true));
    row.append(open, play); box.append(row);
  }
}
// A saved value that is no longer on offer stays selectable and says so, until the user picks another.
const withMissing = (select, value) => { if (value && ![...select.options].some(o => o.value === value)) select.append(new Option(value + ' (missing)', value)); };
const removeButton = (label, click) => { const x = el('button', 'iconbtn'); x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', label); x.onclick = click; return x; };
function openScenario(s) {
  const firstPack = state.packs.find(p => p.templates.length), firstConn = state.connections[0];
  sc = structuredClone(s || { id: null, name: '', parts: [], targets: [], mode: 'spread', count: 10, rate: 5, headers: {} });
  sc.load = sc.load || {}; sc.watches = sc.watches || [];
  sc.invalid = { percent: 0, kinds: KINDS.map(k => k[0]), ...(sc.invalid || {}) };
  if (!sc.invalid.kinds || !sc.invalid.kinds.length) sc.invalid.kinds = KINDS.map(k => k[0]);
  sc.invalidGoes = { connection: sc.invalid.expect?.connection || '', destination: sc.invalid.expect?.destination || null };
  if (!sc.parts.length) sc.parts.push({ pack: state.pack || firstPack?.id || '', template: state.name || firstPack?.templates[0] || '', weight: 1, vars: {}, expect: null });
  if (!sc.targets.length) sc.targets.push({ connection: firstConn?.id || null, destination: firstConn?.destinations[0] || null });
  $('scTitle').textContent = sc.id ? sc.name : 'New scenario';
  $('scName').value = sc.name; $('scCount').value = sc.count; $('scRate').value = sc.rate; $('scHeaders').value = kvText(sc.headers);
  $('scTimeout').value = Math.min(600, Math.max(1, sc.timeoutSeconds || 10)); $('scMatch').value = sc.match || '';
  $('scRampUp').value = sc.load.rampUpSeconds ?? ''; $('scDuration').value = sc.load.durationSeconds ?? ''; $('scSenders').value = sc.load.senders ?? ''; $('scSeed').value = sc.seed ?? '';
  $('scInvalidPercent').value = sc.invalid.percent;
  $('scDelete').hidden = !sc.id;
  renderScenario(); $('scenarioDialog').showModal();
  if (!sc.id) $('scName').focus();
}
function renderScenario() {
  const parts = $('scParts'), targets = $('scTargets'); parts.replaceChildren(); targets.replaceChildren();
  const shares = () => { const total = sc.parts.reduce((n, p) => n + (p.weight || 1), 0); parts.querySelectorAll('.pct').forEach((e, i) => e.textContent = Math.round(100 * (sc.parts[i].weight || 1) / total) + '%'); };
  sc.parts.forEach((part, i) => {
    const row = el('div', 'srow'), sel = el('select'), weight = el('input'), over = el('input');
    for (const p of state.packs) {
      if (!p.templates.length) continue;
      const group = document.createElement('optgroup'); group.label = p.name;
      p.templates.forEach(t => group.append(new Option(t, p.id + '/' + t))); sel.append(group);
    }
    const current = part.pack + '/' + part.template;
    if (![...sel.options].some(o => o.value === current)) sel.append(new Option(current + ' (missing)', current));
    sel.value = current; sel.setAttribute('aria-label', 'Template');
    sel.onchange = () => { const at = sel.value.indexOf('/'); part.pack = sel.value.slice(0, at); part.template = sel.value.slice(at + 1); };
    weight.type = 'number'; weight.min = 1; weight.max = 1000; weight.value = part.weight || 1; weight.title = 'Weight: this template\'s share of the messages'; weight.setAttribute('aria-label', 'Weight');
    weight.oninput = () => { part.weight = Math.max(1, +weight.value || 1); shares(); };
    over.placeholder = 'currency=EUR'; over.value = kvText(part.vars); over.setAttribute('aria-label', 'Overrides'); over.oninput = () => part.vars = kv(over.value);
    const x = removeButton('Remove template', () => { sc.parts.splice(i, 1); renderScenario(); }); x.disabled = sc.parts.length < 2;
    row.append(sel, weight, el('span', 'pct'), over, x); parts.append(row);

    // under each template: where its messages should arrive, or nowhere in particular
    const exp = part.expect, known = receivers(), erow = el('div', 'trow erow'), arrow = el('span', 'arrow', '↳'), eConn = el('select'), eDest = el('select');
    arrow.setAttribute('aria-hidden', 'true');
    eConn.append(new Option('Not verified', ''), ...known.map(c => new Option(c.name, c.id))); withMissing(eConn, exp?.connection);
    eConn.value = exp ? exp.connection : ''; eConn.setAttribute('aria-label', 'Arrives on');
    eConn.onchange = () => { part.expect = eConn.value ? { connection: eConn.value, destination: null } : null; renderScenario(); };
    eDest.hidden = !exp; eDest.setAttribute('aria-label', 'Arrival destination');
    if (exp) {
      const dests = connOf(exp.connection)?.destinations || [];
      if (!exp.destination) exp.destination = dests[0] || null;
      eDest.append(...dests.map(d => new Option(d, d))); withMissing(eDest, exp.destination); if (!eDest.options.length) eDest.append(new Option('No destinations yet', ''));
      eDest.value = exp.destination || ''; eDest.required = true; eDest.onchange = () => exp.destination = eDest.value;
    }
    erow.append(arrow, eConn, eDest); parts.append(erow);
  });
  shares();
  const verifies = sc.parts.some(p => p.expect);
  // the plan: an endless run has neither a count nor a duration
  pressed($('scUnlimited'), !!sc.load.unlimited); $('scCount').disabled = $('scDuration').disabled = !!sc.load.unlimited;
  // invalid messages: the share, and once there is one, which kinds and where they should arrive
  const percent = +$('scInvalidPercent').value; $('scInvalidOut').textContent = percent + '%'; $('scInvalid').hidden = !percent;
  kindButtons($('scInvalidKinds'), () => sc.invalid.kinds, now => sc.invalid.kinds = now);
  $('scInvalidExpect').hidden = !verifies; $('scInvalidExpect').replaceChildren();
  if (verifies) {
    const holder = el('div'), arrow = el('span', 'arrow', '↳'); arrow.setAttribute('aria-hidden', 'true');
    arriveOn(holder, sc.invalidGoes, renderScenario);
    $('scInvalidExpect').append(arrow, ...holder.children);
  }
  // other destinations to listen on, and what to sort their arrivals by
  const watches = $('scWatches'); watches.replaceChildren(); $('scWatchNote').hidden = !sc.watches.length;
  sc.watches.forEach((w, i) => {
    const known = receivers();
    if (!w.connection) w.connection = known[0]?.id || '';
    const list = connOf(w.connection)?.destinations || [];
    const row = el('div', 'wrow'), cSel = el('select'), dest = el('input'), names = el('datalist');
    cSel.append(...known.map(c => new Option(c.name, c.id))); withMissing(cSel, w.connection);
    cSel.value = w.connection; cSel.required = true; cSel.setAttribute('aria-label', 'Watched connection'); cSel.onchange = () => { w.connection = cSel.value; renderScenario(); };
    names.id = 'scWatchDests' + i; names.append(...list.map(d => new Option(d)));
    dest.value = w.destination || ''; dest.required = true; dest.placeholder = 'ORDERS.REJECTED'; dest.setAttribute('list', names.id); dest.autocomplete = 'off';
    dest.setAttribute('aria-label', 'Watched destination'); dest.oninput = () => w.destination = dest.value;
    w.buckets = w.buckets || [];
    row.append(cSel, dest, removeButton('Remove watched destination', () => { sc.watches.splice(i, 1); renderScenario(); }), names, bucketChips(w.buckets));
    watches.append(row);
  });
  $('scVerify').hidden = !verifies;
  sc.targets.forEach((t, i) => {
    // A new row has nothing chosen yet and takes the first of each. A saved one keeps what it says, even if that is gone.
    if (!t.connection) t.connection = state.connections[0]?.id || null;
    const list = connOf(t.connection)?.destinations || [];
    if (!t.destination) t.destination = list[0] || null;
    const row = el('div', 'trow'), cSel = el('select'), dSel = el('select');
    cSel.append(...state.connections.map(c => new Option(c.name, c.id))); withMissing(cSel, t.connection);
    cSel.value = t.connection || ''; cSel.required = true; cSel.setAttribute('aria-label', 'Connection');
    cSel.onchange = () => { t.connection = cSel.value; t.destination = null; renderScenario(); };
    dSel.append(...list.map(d => new Option(d, d))); withMissing(dSel, t.destination); if (!dSel.options.length) dSel.append(new Option('No destinations yet', ''));
    dSel.value = t.destination || ''; dSel.required = true; dSel.setAttribute('aria-label', 'Destination'); dSel.onchange = () => t.destination = dSel.value;
    const x = removeButton('Remove target', () => { sc.targets.splice(i, 1); renderScenario(); }); x.disabled = sc.targets.length < 2;
    row.append(cSel, dSel, x); targets.append(row);
  });
  $('scMode').hidden = sc.targets.length < 2;
  $('scMode').querySelectorAll('button').forEach(b => pressed(b, b.dataset.mode === sc.mode));
}
async function saveScenario(thenRun) {
  if (!$('scForm').reportValidity()) return;
  const percent = +$('scInvalidPercent').value, goes = sc.invalidGoes, verifies = sc.parts.some(p => p.expect);
  sc.watches.forEach(w => w.buckets.flush?.());
  const body = { ...sc, name: $('scName').value, count: sc.load.unlimited ? 0 : +$('scCount').value, rate: +$('scRate').value || 0, headers: kv($('scHeaders').value),
    timeoutSeconds: +$('scTimeout').value || 10, match: $('scMatch').value.trim() || null, seed: whole($('scSeed').value),
    load: { rampUpSeconds: whole($('scRampUp').value), durationSeconds: sc.load.unlimited ? null : whole($('scDuration').value), senders: whole($('scSenders').value), unlimited: sc.load.unlimited || null },
    invalid: percent ? { percent, kinds: sc.invalid.kinds, expect: verifies && goes.connection ? { connection: goes.connection, destination: goes.destination } : null } : null,
    watches: sc.watches.map(w => ({ connection: w.connection, destination: w.destination, buckets: [...w.buckets] })) };
  delete body.invalidGoes;
  try {
    const saved = await api(sc.id ? '/scenarios/' + sc.id : '/scenarios', json(body, sc.id ? 'PUT' : 'POST'));
    await loadScenarios(); $('scenarioDialog').close();
    if (thenRun) { await api(`/scenarios/${saved.id}/run`, { method: 'POST' }); refresh(); } else toast('Saved');
  } catch (e) { toast(e.message, true); }
}
async function deleteScenario() {
  if (!await ask({ title: 'Delete scenario?', text: sc.name, ok: 'Delete', danger: true })) return;
  try { await api('/scenarios/' + sc.id, { method: 'DELETE' }); await loadScenarios(); $('scenarioDialog').close(); toast('Deleted'); }
  catch (e) { toast(e.message, true); }
}
async function scenarioFromForm() {   // the send form as a one-template scenario, ready to name and extend
  if (!state.name) return toast('Pick a template first.', true);
  if (dirty()) {   // a scenario names the template file; it does not carry the editor's text
    if (!await ask({ title: 'Save the template first?', text: 'A scenario uses the saved ' + state.name + ', not the edits in the editor.', ok: 'Save' })) return;
    await save();
    if (dirty()) return;
  }
  openScenario({ id: null, name: '', parts: [{ pack: state.pack, template: state.name, weight: 1, vars: vars(), expect: expectation() }], targets: structuredClone(state.targets),
    mode: state.mode, count: +$('count').value || 0, rate: +$('rate').value || 0, headers: kv($('headers').value), timeoutSeconds: state.expect.timeout, match: state.expect.on ? state.expect.match.trim() : '',
    load: loadPlan() || {}, invalid: invalidShare(), seed: seed() });
}

// ---- runs, sent messages, throughput
const time = iso => new Date(iso).toLocaleTimeString([], { hour12: false });
const took = r => r.finishedAt ? ' · ' + ((new Date(r.finishedAt) - new Date(r.startedAt)) / 1000).toFixed(1) + 's' : '';
function renderRuns() {
  const box = $('runs'), have = new Map([...box.children].map(c => [c.dataset.id, c])), rows = [];
  for (const r of state.runs) {
    let row = have.get(r.id);
    if (!row) {   // build once, then only update, so progress bars animate instead of restarting
      row = el('div'); row.dataset.id = r.id;
      const top = el('div', 'top'), x = el('button', 'x');
      x.innerHTML = icon('x'); x.title = 'Cancel'; x.setAttribute('aria-label', 'Cancel run');
      x.onclick = () => api('/runs/' + r.id, { method: 'DELETE' }).then(refresh, e => toast(e.message, true));
      const name = el('button', 'name', r.label); name.type = 'button'; name.title = 'Statistics, chart and reports'; name.onclick = () => openRun(r.id);
      top.append(name, el('span', 'chip'), x);
      const meter = el('div', 'meter'); meter.append(el('i'));
      const where = el('span', '', r.targets.length === 1 ? r.targets[0].connection + ' → ' + r.targets[0].destination
        : r.targets.length + ' targets · ' + (r.mode === 'all' ? 'copy to all' : 'spread'));
      where.title = r.targets.map(t => t.connection + ' → ' + t.destination).join('\n');
      const sub = el('div', 'sub'); sub.append(where, el('span'));
      row.append(top, meter, sub, el('div', 'facts'), el('div', 'checks'), el('div', 'error'));
    }
    have.delete(r.id); rows.push(row);
    const ended = r.status !== 'RUNNING' && r.status !== 'VERIFYING';
    const shown = r.status === 'DONE' && r.verdict ? r.verdict : r.status;   // a completed run that verified shows its verdict
    row.className = 'run ' + r.status + (r.verdict ? ' ' + r.verdict : '') + (!r.count && !r.durationSeconds ? ' endless' : '');
    const chip = row.querySelector('.chip'); chip.className = 'chip ' + shown; chip.textContent = shown;
    row.querySelector('.x').hidden = ended;
    const checks = row.querySelector('.checks'); checks.hidden = !r.checks.length;
    checks.replaceChildren(...r.checks.map(c => {
      // where it was expected is what a failed check is read for, so it is never cut short: the tally moves to a line of its own instead
      const line = el('div', 'check'), where = el('span', '', (r.templates.length > 1 ? c.template + ' ' : '') + (c.invalid ? 'invalid ' : '') + '→ ' + c.expected), tally = el('span', 'tally', c.arrived + ' / ' + c.sent);
      // until the run ends a message that has not arrived is only late, so the counts are not shown as errors yet
      if (c.accepted) tally.append(el('b', '', ' · ' + c.accepted + ' accepted'));
      if (c.rejected) tally.append(el('b', '', ' · ' + c.rejected + ' rejected'));
      if (ended && c.misrouted) tally.append(el('b', '', ' · ' + c.misrouted + ' misrouted'));
      if (ended && c.missing) tally.append(el('b', '', ' · ' + c.missing + ' missing'));
      if (c.medianMs != null) { tally.append(' · ' + c.medianMs + ' ms'); tally.title = 'median ' + c.medianMs + ' ms, longest ' + c.maxMs + ' ms'; }
      line.append(where, tally); return line;
    }));
    // progress by count, else by time; a run with neither shows that it is going
    const part = r.count ? (r.sent + r.failed) / r.count : r.durationSeconds ? r.elapsedMs / 1000 / r.durationSeconds : 1;
    row.querySelector('.meter i').style.width = (ended && !r.count ? 100 : Math.min(100, 100 * part)) + '%';
    row.querySelector('.sub span:last-child').textContent = r.sent.toLocaleString() + (r.count ? ' / ' + r.count.toLocaleString() : '') + took(r);
    // one line of what a load run is read for; a single send has nothing to say here
    const facts = row.querySelector('.facts'), busy = r.sent + r.failed > 20 || r.failed || r.invalid;
    facts.hidden = !busy; facts.replaceChildren();
    if (busy) {
      facts.append(rate(r.actualRate) + '/s' + (r.rate ? ' of ' + rate(r.rate) : '') + (r.latency ? ' · p95 ' + r.latency.p95 + ' ms' : ''));
      if (r.invalid) facts.append(' · ' + r.invalid.toLocaleString() + ' invalid');
      if (r.failed) facts.append(el('b', '', ' · ' + r.failed.toLocaleString() + ' failed'));
    }
    const err = row.querySelector('.error'); err.hidden = !r.error; err.textContent = r.error || '';
  }
  have.forEach(row => row.remove());
  rows.forEach((row, i) => { if (box.children[i] !== row) box.insertBefore(row, box.children[i] || null); });
}
const rate = v => v >= 100 ? Math.round(v).toLocaleString() : (+(+v).toFixed(v >= 10 ? 1 : 2)).toString();
const key = m => m.runId + '#' + m.n + '#' + m.target;
function renderSent() {
  const list = $('sentList'), body = $('sentBody'); list.replaceChildren();
  $('sentCount').textContent = state.messages.length || '';
  if (!state.messages.length) { list.append(el('div', 'empty', 'Nothing sent yet')); body.textContent = ''; body.dataset.raw = ''; shownKey = null; return; }
  if (!state.messages.some(m => key(m) === state.picked)) state.picked = key(state.messages[0]);
  for (const m of state.messages) {
    const b = el('button', 'row' + (key(m) === state.picked ? ' on' : ''));
    b.append(el('span', 'n', '#' + m.n), el('span', 'd', m.destination));
    if (m.fault) b.append(el('span', 'tag bad', m.fault));
    b.append(el('span', 't', time(m.at)));
    b.title = m.connection + ' → ' + m.destination;
    b.onclick = () => { state.picked = key(m); renderSent(); };
    list.append(b);
  }
  showPicked();
}
let shownKey = null;
async function showPicked() {
  const want = state.picked, m = state.messages.find(m => key(m) === want), body = $('sentBody');
  if (!state.sentTab || !m || shownKey === want) return;   // nobody is looking, or it is already there
  try {
    const full = await api(`/messages/${encodeURIComponent(m.runId)}/${m.n}/${m.target}`), text = full.body;
    if (want !== state.picked) return;   // another one was picked while this was on its way
    const meta = [m.connection + ' → ' + m.destination + '   ' + m.template + (m.fault ? '   invalid: ' + m.fault : ''), ...Object.entries(full.headers || {}).map(([k, v]) => k + ': ' + v)];
    body.dataset.raw = text; body.innerHTML = span('t-com', esc(meta.join('\n'))) + '\n\n' + highlight(text, langOf(null, text));
    shownKey = want;
  } catch (e) { if (want === state.picked) { body.dataset.raw = ''; body.textContent = e.message; } }
}

// ---- one run, or one monitor, in full: numbers, the rate over time, and what arrived on each watched destination
const lasted = ms => ms >= 3600000 ? Math.floor(ms / 3600000) + ' h ' + String(Math.floor(ms / 60000) % 60).padStart(2, '0') + ' min'
  : ms >= 60000 ? Math.floor(ms / 60000) + ' min ' + String(Math.floor(ms / 1000) % 60).padStart(2, '0') + ' s' : (ms / 1000).toFixed(1) + ' s';
const tile = (label, value, bad) => { const t = el('div', 'tile' + (bad ? ' bad' : '')); t.append(el('b', '', value), el('span', '', label)); return t; };
const panel = (title, ...children) => { const p = el('section', 'panel'); if (title) p.append(el('h3', '', title)); p.append(...children); return p; };
function table(head, rows) {
  const t = el('table', 't'), tr = el('tr');
  head.forEach(h => { const th = el('th', typeof h === 'object' ? 'n' : '', typeof h === 'object' ? h.n : h); th.scope = 'col'; tr.append(th); });
  t.append(tr);
  for (const row of rows) {
    const r = el('tr');
    row.forEach((c, i) => r.append(c instanceof Node ? c : el('td', typeof head[i] === 'object' ? 'n' : '', c == null ? '' : typeof c === 'number' ? c.toLocaleString() : c)));
    t.append(r);
  }
  return t;
}
const niceMax = v => { const p = 10 ** Math.floor(Math.log10(v)); return [1, 2, 4, 5, 8, 10].find(s => v <= s * p) * p; };
const clock = s => { s = Math.round(s); const mm = String(Math.floor(s / 60) % 60), ss = String(s % 60).padStart(2, '0'); return s >= 3600 ? Math.floor(s / 3600) + ':' + mm.padStart(2, '0') + ':' + ss : mm + ':' + ss; };
// The numbers in the drawing are the server's; nothing in it is text from a message.
function rateChart(series) {
  const w = 960, h = 250, l = 52, r = 12, t = 12, b = 28, box = el('div');
  let max = 1, end = 1;
  for (const p of series) { max = Math.max(max, p.sent / p.seconds, p.failed / p.seconds, p.target || 0); end = Math.max(end, p.t + p.seconds); }
  max = niceMax(max);
  const x = p => l + (p.t + p.seconds / 2) * (w - l - r) / end, y = v => h - b - v * (h - t - b) / max;
  // one point is drawn as a level line across, where a path of one point would draw nothing
  const path = f => series.length === 1 ? `M${l} ${y(f(series[0])).toFixed(1)}L${w - r} ${y(f(series[0])).toFixed(1)}`
    : series.map((p, i) => (i ? 'L' : 'M') + x(p).toFixed(1) + ' ' + y(f(p)).toFixed(1)).join('');
  const sent = path(p => p.sent / p.seconds), anyFailed = series.some(p => p.failed), anyTarget = series.some(p => p.target != null);
  const x0 = series.length === 1 ? l : x(series[0]), x1 = series.length === 1 ? w - r : x(series.at(-1));
  let svg = '';
  for (let i = 0; i <= 4; i++) {
    const gy = (h - b - (h - t - b) * i / 4).toFixed(1);
    svg += `<line class="grid" x1="${l}" x2="${w - r}" y1="${gy}" y2="${gy}"/><text x="${l - 8}" y="${+gy + 4}" text-anchor="end">${rate(max * i / 4)}</text>`;
  }
  for (let i = 0; i <= 6; i++) svg += `<text x="${(l + (w - l - r) * i / 6).toFixed(1)}" y="${h - 8}" text-anchor="middle">${clock(end * i / 6)}</text>`;
  svg += `<path class="area" d="${sent}L${x1.toFixed(1)} ${h - b}L${x0.toFixed(1)} ${h - b}Z"/>`;
  if (anyTarget) svg += `<path class="target" d="${path(p => p.target || 0)}"/>`;
  svg += `<path class="sent" d="${sent}"/>`;
  if (anyFailed) svg += `<path class="failed" d="${path(p => p.failed / p.seconds)}"/>`;
  const last = series.at(-1);
  box.innerHTML = `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img"></svg>`;
  box.firstChild.innerHTML = svg;
  box.firstChild.setAttribute('aria-label', 'Messages a second over the run. Latest: ' + rate(last.sent / last.seconds) + ' sent a second'
    + (last.target != null ? ', target ' + rate(last.target) : '') + (anyFailed ? ', ' + rate(last.failed / last.seconds) + ' failed a second' : ''));
  const legend = el('div', 'legend'), entry = (cls, text) => { const s = el('span'); s.append(el('i', cls), text); return s; };
  legend.append(entry('', 'sent a second'));
  if (anyTarget) legend.append(entry('target', 'target'));
  if (anyFailed) legend.append(entry('failed', 'failed a second'));
  box.append(legend);
  return box;
}
// A watched destination: counts, a table per bucket, the newest arrivals without their bodies, and a few bodies as a sample.
function watchPanel(w) {
  const head = el('div', 'whead'), p = el('section', 'panel');
  head.append(el('h3', '', w.connection + ' / ' + w.destination), el('span', '', w.count.toLocaleString() + ' arrived · ' + rate(w.rate) + '/s' + (w.lastAt ? ' · last ' + time(w.lastAt) : '')));
  p.append(head);
  for (const t of w.tables) {
    const bars = el('div', 'bars'), most = Math.max(1, ...t.values.map(v => v.count));
    bars.append(el('h4', '', t.name));
    for (const v of t.values) { const bar = el('i'); bar.style.width = (100 * v.count / most) + '%'; const name = el('span', '', v.value); name.title = v.value; bars.append(name, bar, el('b', '', v.count.toLocaleString())); }
    if (!t.values.length) bars.append(el('span', '', 'Nothing yet'));
    p.append(bars);
  }
  if (w.recent.length && w.tables.length) {   // without buckets a row would say a time and a size, and the count above says as much
    const scroll = el('div', 'scroll');
    const matched = w.recent.some(a => a.message != null);   // a monitor, and a run that does not verify, recognise no message
    scroll.append(table(['Time', ...w.tables.map(t => t.name), ...(matched ? [{ n: 'Message' }] : []), { n: 'Bytes' }],
      w.recent.map(a => [time(a.at), ...a.values, ...(matched ? [a.message == null ? '' : '#' + a.message] : []), a.bytes])));
    p.append(scroll);
  }
  if (w.samples?.length) {
    const d = el('details', 'samples'); d.append(el('summary', '', 'Newest bodies'));
    d.open = watchPanel.open.has(w.connection + '/' + w.destination);
    d.ontoggle = () => watchPanel.open[d.open ? 'add' : 'delete'](w.connection + '/' + w.destination);
    w.samples.forEach(s => d.append(el('pre', '', s)));
    p.append(d);
  }
  return p;
}
watchPanel.open = new Set();
function showDetail(title, chip, acts, children) {
  $('detailTitle').textContent = title;
  $('detailChip').className = 'chip ' + (chip || ''); $('detailChip').textContent = chip || ''; $('detailChip').hidden = !chip;
  if ($('detailActs').dataset.for !== state.open?.id) { $('detailActs').replaceChildren(...acts()); $('detailActs').dataset.for = state.open.id; }
  // rebuilt each second, so where the user has scrolled to is put back
  const body = $('detailBody'), top = body.scrollTop, inner = [...body.querySelectorAll('.scroll')].map(s => s.scrollTop);
  body.replaceChildren(...children);
  body.scrollTop = top; body.querySelectorAll('.scroll').forEach((s, i) => s.scrollTop = inner[i] || 0);
  if (!$('detail').open) $('detail').showModal();
}
function renderRunDetail(d) {
  const r = d.run, ended = r.status !== 'RUNNING' && r.status !== 'VERIFYING', shown = r.status === 'DONE' && r.verdict ? r.verdict : r.status;
  const tiles = el('div', 'tiles');
  tiles.append(tile('sent', r.sent.toLocaleString()), tile('failed', r.failed.toLocaleString(), r.failed > 0));
  if (Object.keys(r.faults).length) tiles.append(tile('invalid', r.invalid.toLocaleString()));
  tiles.append(tile(r.targetRate != null ? 'rate · target ' + rate(r.targetRate) + '/s' : 'rate', rate(r.actualRate) + '/s'), tile('sending', lasted(r.elapsedMs)));
  if (r.latency) tiles.append(tile('send p50', r.latency.p50 + ' ms'), tile('send p95', r.latency.p95 + ' ms'), tile('send p99', r.latency.p99 + ' ms'));
  const parts = [tiles];
  if (r.error) parts.push(el('div', 'result bad', r.error));
  if (d.series.length) parts.push(panel(null, rateChart(d.series)));
  const side = el('div', 'grid2');
  if (r.checks.length) side.append(panel('Arrivals', table(['Template', 'Expected on', { n: 'Sent' }, 'Result'], r.checks.map(c => {
    const result = el('td', !ended ? '' : c.arrived === c.sent ? 'ok' : 'bad');
    result.append(c.arrived.toLocaleString() + (c.invalid ? ' rejected as expected' : ' arrived'));
    if (c.accepted) result.append(el('b', '', ', ' + c.accepted + ' accepted although invalid'));
    if (c.rejected) result.append(el('b', '', ', ' + c.rejected + ' valid but rejected'));
    if (ended && c.misrouted) result.append(el('b', '', ', ' + c.misrouted + ' misrouted'));
    if (ended && c.missing) result.append(el('b', '', ', ' + c.missing + ' missing'));
    if (c.medianMs != null) result.append(' · ' + c.medianMs + ' ms');
    return [c.template + (c.invalid ? ' (invalid)' : ''), c.expected, c.sent, result];
  }))));
  if (Object.keys(r.faults).length) side.append(panel('Invalid messages', table(['Kind', { n: 'Messages' }], Object.entries(r.faults).map(([k, n]) => [k, n]))));
  if (r.errors.length) side.append(panel('Send errors', table(['Error', { n: 'Count' }, { n: 'Last' }], r.errors.map(e => [e.message, e.count, time(e.lastAt)]))));
  if (side.children.length) parts.push(side);
  d.watches.filter(w => w.count || w.tables.length).forEach(w => parts.push(watchPanel(w)));
  parts.push(el('div', 'plan', [r.targets.map(t => t.connection + ' → ' + t.destination).join(', '), r.templates.join(', '),
    r.rate ? rate(r.rate) + '/s' + (r.rampUpSeconds ? ' reached over ' + r.rampUpSeconds + ' s' : '') : 'as fast as possible',
    r.count ? r.count.toLocaleString() + ' messages' : null, r.durationSeconds ? 'for ' + r.durationSeconds + ' s' : null,
    r.senders + (r.senders === 1 ? ' sender' : ' senders'), 'seed ' + r.seed].filter(Boolean).join(' · ')));
  showDetail(r.label, shown, () => [['html', 'HTML'], ['json', 'JSON'], ['csv', 'CSV'], ['series', 'Series']].map(([format, label]) => {
    const a = el('a', 'btn', label); a.href = '/api/runs/' + encodeURIComponent(r.id) + '/report?format=' + format; a.download = '';
    a.title = format === 'series' ? 'The rate over time as CSV' : 'Download the report as ' + label; return a;
  }), parts);
}
function renderMonitorDetail(m) {
  const parts = [];
  if (m.error) parts.push(el('div', 'result bad', m.error + (m.retryAt ? ' Next try at ' + time(m.retryAt) + '.' : '')));
  parts.push(watchPanel(m.watch), el('p', 'note', 'Since ' + time(m.watch.since)));
  // the buttons are built once per monitor; what they say is repainted every time
  showDetail(m.name || m.watch.connection + ' / ' + m.watch.destination, { up: 'RUNNING', down: 'FAILED', stopped: 'CANCELLED' }[m.state] || 'VERIFYING', () => {
    const save = el('button', 'btn hold', 'Saved'), reset = el('button', 'btn', 'Reset'), stop = el('button', 'btn');
    save.type = reset.type = stop.type = 'button'; save.id = 'monitorSaved'; stop.id = 'monitorStop'; save.title = 'Keep it in msgforge.json: it then starts with msgforge';
    const failed = e => toast(e.message, true), now = () => state.monitors.find(x => x.id === m.id);
    save.onclick = () => api('/monitors/' + m.id + '/saved', { method: now()?.saved ? 'DELETE' : 'PUT' }).then(loadMonitors).then(refreshOpen, failed);
    reset.onclick = () => api('/monitors/' + m.id + '/reset', { method: 'POST' }).then(loadMonitors).then(refreshOpen, failed);
    // a saved monitor that is stopped stays, and can be started again; one that is not saved is gone when stopped
    stop.onclick = () => (now()?.state === 'stopped' ? api('/monitors/' + m.id + '/start', { method: 'POST' }) : api('/monitors/' + m.id, { method: 'DELETE' })).then(loadMonitors).then(refreshOpen, failed);
    return [save, reset, stop];
  }, parts);
  $('detailChip').textContent = { up: 'LISTENING', down: 'DOWN', stopped: 'STOPPED' }[m.state] || 'STARTING';
  $('monitorSaved')?.setAttribute('aria-pressed', m.saved);
  if ($('monitorStop')) { $('monitorStop').textContent = m.state === 'stopped' ? 'Start' : 'Stop'; $('monitorStop').className = 'btn' + (m.state === 'stopped' ? '' : ' danger'); }
}
async function refreshOpen() {
  const open = state.open;
  if (!open) return;
  try {
    if (open.kind === 'run') { const d = await api('/runs/' + encodeURIComponent(open.id)); if (state.open === open) renderRunDetail(d); }
    else { const m = state.monitors.find(m => m.id === open.id); if (m) renderMonitorDetail(m); else $('detail').close(); }
  } catch (e) { if (e.status === 404 && state.open === open) { $('detail').close(); toast('That run is no longer kept.', true); } }
}
function openRun(id) { state.open = { kind: 'run', id }; try { history.replaceState(null, '', '#run=' + id); } catch (_) {} refreshOpen(); }
function openMonitor(id) { state.open = { kind: 'monitor', id }; refreshOpen(); }
$('detail').addEventListener('close', () => { state.open = null; $('detailActs').dataset.for = ''; try { history.replaceState(null, '', location.pathname); } catch (_) {} });

// ---- monitors: a destination watched outside any run
let newBuckets = [];
let monitorsAsked = 0;
async function loadMonitors() {
  // The list is asked for every second and after every action. An answer that was overtaken by a later question is dropped,
  // or a monitor just stopped would be painted as listening again by the answer to the question asked before the stop.
  const asked = ++monitorsAsked, answer = await api('/monitors');
  if (asked !== monitorsAsked) return;
  state.monitors = answer;
  const box = $('monitors'), have = new Map([...box.children].map(c => [c.dataset.id, c]));
  for (const m of state.monitors) {
    let row = have.get(m.id);
    if (!row) {
      row = el('div', 'scn'); row.dataset.id = m.id;
      const open = el('button', 'tpl'), stop = el('button', 'play');
      open.append(el('span', 'name', m.name || m.watch.destination)); open.title = m.watch.connection + ' / ' + m.watch.destination; open.onclick = () => openMonitor(m.id);
      // the button stops a monitor that is listening and starts one that was stopped; taking a saved one out of the file is the Saved button in its view
      stop.onclick = () => (state.monitors.find(x => x.id === m.id)?.state === 'stopped' ? api('/monitors/' + m.id + '/start', { method: 'POST' }) : api('/monitors/' + m.id, { method: 'DELETE' }))
        .then(loadMonitors, e => toast(e.message, true));
      row.append(open, el('span', 'badge'), stop); box.append(row);
    }
    have.delete(m.id);
    row.classList.toggle('bad', m.state === 'down'); row.classList.toggle('off', m.state === 'stopped');
    row.querySelector('.badge').textContent = m.state === 'down' ? 'down' : m.state === 'stopped' ? 'stopped' : m.watch.count.toLocaleString();
    const stopped = m.state === 'stopped', toggle = row.querySelector('.play'), what = m.name || m.watch.destination;
    if (toggle.dataset.stopped !== String(stopped)) {   // only when it changes, so the button under the pointer is not rebuilt every second
      toggle.dataset.stopped = stopped; toggle.innerHTML = icon(stopped ? 'play' : 'x');
      toggle.title = stopped ? 'Start watching' : 'Stop watching'; toggle.setAttribute('aria-label', (stopped ? 'Start watching ' : 'Stop watching ') + what);
    }
    row.querySelector('.tpl').title = m.watch.connection + ' / ' + m.watch.destination + (m.error ? '\n' + m.error : '');
  }
  have.forEach(row => row.remove());
}
function openMonitorForm() {
  const known = receivers();
  if (!known.length) return toast('No connection that can receive.', true);
  newBuckets = [];
  $('mConn').replaceChildren(...known.map(c => new Option(c.name, c.id)));
  const dests = () => $('mDests').replaceChildren(...(connOf($('mConn').value)?.destinations || []).map(d => new Option(d)));
  $('mConn').onchange = dests; dests();
  $('mDest').value = $('mName').value = ''; pressed($('mSave'), false);
  $('mBuckets').replaceWith(Object.assign(bucketChips(newBuckets), { id: 'mBuckets' }));
  $('monitorDialog').showModal(); $('mDest').focus();
}
async function startMonitor(e) {
  e.preventDefault();
  newBuckets.flush();
  try {
    const m = await api('/monitors', json({ name: $('mName').value.trim() || null, connection: $('mConn').value, destination: $('mDest').value, buckets: [...newBuckets],
      save: $('mSave').getAttribute('aria-pressed') === 'true' }));
    $('monitorDialog').close(); await loadMonitors(); openMonitor(m.id);
  } catch (err) { toast(err.message, true); }
}

// ---- saved reports
async function openReports() {
  const body = $('reportsBody'); body.replaceChildren();
  try {
    const saved = await api('/reports');
    if (!saved.enabled) body.append(el('div', 'empty', 'Start msgforge with MSGFORGE_REPORTS set to a folder, and every run that ends is saved there.'));
    else if (!saved.reports.length) body.append(el('div', 'empty', 'No run has ended yet'));
    else {
      const names = { html: 'HTML', json: 'JSON', csv: 'CSV' };
      body.append(table(['Started', 'Run', ''], saved.reports.map(r => {
        const links = el('td', 'n reports');
        for (const f of r.files) { const a = el('a', '', f.endsWith('.series.csv') ? 'Series' : names[f.split('.').pop()]); a.href = '/api/reports/' + encodeURIComponent(f); a.download = ''; links.append(a); }
        return [new Date(r.at).toLocaleString([], { hour12: false }), (r.label || 'run') + ' · ' + r.id, links];
      })), el('p', 'note', 'The newest ' + saved.keep + ' runs are kept.'));
    }
  } catch (e) { body.append(el('div', 'result bad', e.message)); }
  $('reportsDialog').showModal();
}

const samples = Array(60).fill(0), seen = new Map();
let total = 0, shown = 0, pending = 0, primed = false, newest = '';
function drawChart() {
  const w = 300, h = 58, max = Math.max(1, ...samples), step = w / (samples.length - 1);
  const line = samples.map((v, i) => (i ? 'L' : 'M') + (i * step).toFixed(1) + ' ' + (h - 3 - (v / max) * (h - 14)).toFixed(1)).join('');
  $('chartLine').setAttribute('d', line); $('chartArea').setAttribute('d', `${line}L${w} ${h}L0 ${h}Z`);
}
function tween() {
  shown += (total - shown) * .22;
  if (total - shown < .5) shown = total;
  $('total').textContent = Math.round(shown).toLocaleString();
  if (shown !== total) requestAnimationFrame(tween);
}
async function refresh() {
  try {
    const [runs, messages] = await Promise.all([api('/runs'), api('/messages')]);
    state.runs = runs;
    for (const r of runs) {
      const before = seen.get(r.id) || 0;
      if (r.sent > before) { total += r.sent - before; if (primed) pending += r.sent - before; }
      seen.set(r.id, r.sent);
    }
    primed = true;
    renderRuns(); paintLeds(); requestAnimationFrame(tween);
    loadMonitors().then(refreshOpen, () => {});
    const top = messages.length ? key(messages[0]) : '';
    if (top !== newest) {   // follow the newest message unless the user is reading an older one
      const following = !state.picked || !state.messages.length || state.picked === key(state.messages[0]);
      state.messages = messages; newest = top;
      if (following) state.picked = null;
      renderSent();
    }
  } catch (_) { /* server restarting: keep the last view and try again */ }
}
function tick() {   // one chart sample per second
  samples.push(pending); samples.shift();
  $('liveRate').textContent = pending >= 1000 ? (pending / 1000).toFixed(1) + 'k' : pending.toLocaleString();
  pending = 0; drawChart(); refresh();
}

// ---- theme
function paintTheme() { $('theme').innerHTML = icon(document.documentElement.dataset.theme === 'light' ? 'moon' : 'sun'); }
$('theme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('theme', next); } catch (_) {}
  paintTheme();
};

// ---- wiring
src.addEventListener('input', () => { edited(); previewSoon(); });
src.addEventListener('scroll', () => { $('hl').scrollTop = $('gutter').scrollTop = src.scrollTop; $('hl').scrollLeft = src.scrollLeft; });
let tabLeaves = false;   // Tab indents, which would trap keyboard users in the editor: Escape, then Tab, moves on
src.addEventListener('keydown', e => {
  if (e.key === 'Escape') { tabLeaves = true; return; }
  if (e.key === 'Tab' && !tabLeaves) { e.preventDefault(); src.setRangeText('  ', src.selectionStart, src.selectionEnd, 'end'); edited(); previewSoon(); }
  if (e.key !== 'Shift') tabLeaves = false;
});
$('save').onclick = save;
$('deleteTemplate').onclick = deleteTemplate;
$('reroll').onclick = preview;
$('copy').onclick = copy;
$('tabPreview').onclick = () => tab(false);
$('tabSent').onclick = () => tab(true);
$('filter').addEventListener('input', renderPacks);
$('newPack').onclick = () => openPack(null);
$('importFile').onchange = e => importFiles(importInto, [...e.target.files]);
let dragDepth = 0;
const hasFiles = e => [...(e.dataTransfer?.types || [])].includes('Files');
addEventListener('dragenter', e => { if (hasFiles(e) && ++dragDepth === 1) document.body.classList.add('dropping'); });
addEventListener('dragleave', e => { if (hasFiles(e) && --dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('dropping'); } });
addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
addEventListener('drop', e => {
  if (!hasFiles(e)) return;
  e.preventDefault(); dragDepth = 0; document.body.classList.remove('dropping');
  if (!document.querySelector('dialog[open]')) importFiles(state.pack || state.packs[0]?.id, [...e.dataTransfer.files]);
});
// A message pasted outside any text field becomes a new template. Pasted into an empty editor, it is recognised the same way.
addEventListener('paste', e => {
  if (e.target.closest?.('input, textarea, select, [contenteditable]') || document.querySelector('dialog[open]')) return;
  const packId = state.pack || state.packs[0]?.id, text = e.clipboardData.getData('text/plain');
  if (e.clipboardData.files.length) importFiles(packId, [...e.clipboardData.files]);
  else if (text.trim()) newTemplate(packId, text);
});
src.addEventListener('paste', () => { if (!src.value.trim()) setTimeout(() => recognise(true)); });
$('pAdd').onclick = () => fieldRow().focus();
$('packForm').addEventListener('submit', savePack);
$('pDelete').onclick = deletePack;
$('recognise').onclick = () => recognise(false);
$('insert').onclick = e => { e.stopPropagation(); $('menu').hidden ? openMenu() : closeMenu(); };
document.addEventListener('click', e => { if (!$('menu').contains(e.target)) closeMenu(); });

$('form').addEventListener('submit', send);
$('count').onchange = $('rate').onchange = remember;
$('manage').onclick = openSettings;
$('addTarget').onclick = () => { state.targets.push({ connection: state.targets.at(-1).connection, destination: null }); renderTargets(); };
$('mode').querySelectorAll('button').forEach(b => b.onclick = () => { state.mode = b.dataset.mode; renderTargets(); });
$('vars').addEventListener('input', previewSoon);
$('verify').onclick = () => { state.expect.on = !state.expect.on; renderExpect(); remember(); };
$('loadToggle').onclick = () => { state.load.on = !state.load.on; renderLoad(); remember(); };
$('invalidToggle').onclick = () => { state.invalid.on = !state.invalid.on; renderInvalid(); remember(); };
$('unlimited').onclick = () => { state.load.unlimited = !state.load.unlimited; renderLoad(); remember(); };
for (const id of ['rampUp', 'duration', 'senders', 'seed']) $(id).oninput = () => { state.load[id] = $(id).value; remember(); };
$('invalidPercent').oninput = () => { state.invalid.percent = +$('invalidPercent').value; $('invalidOut').textContent = state.invalid.percent + '%'; remember(); };
$('raw').onclick = () => { state.raw = !state.raw; $('raw').setAttribute('aria-pressed', state.raw); $('vars').disabled = state.raw; preview(); };
$('newMonitor').onclick = openMonitorForm;
$('monitorForm').addEventListener('submit', startMonitor);
$('mSave').onclick = () => pressed($('mSave'), $('mSave').getAttribute('aria-pressed') !== 'true');
$('reports').onclick = openReports;
$('scUnlimited').onclick = () => { sc.load.unlimited = !sc.load.unlimited; renderScenario(); };
$('scInvalidPercent').oninput = renderScenario;
$('scAddWatch').onclick = () => { sc.watches.push({ connection: '', destination: '', buckets: [] }); renderScenario(); };
$('saveScenario').onclick = scenarioFromForm;
$('newScenario').onclick = () => openScenario(null);
$('scAddPart').onclick = () => { sc.parts.push({ ...sc.parts.at(-1), weight: 1, vars: {}, expect: null }); renderScenario(); };
$('scAddTarget').onclick = () => { sc.targets.push({ connection: sc.targets.at(-1).connection, destination: null }); renderScenario(); };
$('scMode').querySelectorAll('button').forEach(b => b.onclick = () => { sc.mode = b.dataset.mode; renderScenario(); });
$('scForm').addEventListener('submit', e => { e.preventDefault(); saveScenario(false); });
$('scRun').onclick = () => saveScenario(true);
$('scDelete').onclick = deleteScenario;

$('newConn').onclick = () => { editing = blankConnection(); renderConnList(); renderConnForm(); $('cName').focus(); };
$('cType').onchange = () => { $('cFields').querySelectorAll('input:not([type=password])').forEach(i => editing.settings[i.dataset.key] = i.value); renderTypeFields(); };
$('cDestInput').addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); flushDestination(); } });
$('connForm').addEventListener('submit', saveConnection);
$('cTest').onclick = testConnection;
$('cDelete').onclick = deleteConnection;

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeMenu(true);
  if (!(e.metaKey || e.ctrlKey) || document.querySelector('dialog[open]')) return;
  if (e.key === 's') { e.preventDefault(); save(); }
  if (e.key === 'Enter') { e.preventDefault(); $('form').requestSubmit(); }
});
window.addEventListener('beforeunload', e => { if (state.name && dirty()) e.preventDefault(); });

(async () => {
  paintTheme(); drawChart();
  if (!/Mac|iPhone|iPad/.test(navigator.platform)) document.querySelectorAll('kbd').forEach(k => k.textContent = k.textContent.replace('⌘', 'Ctrl+'));
  try {
    const kept = JSON.parse(localStorage.getItem('send') || '{}');
    if (Array.isArray(kept.targets) && kept.targets.length) state.targets = kept.targets.slice(0, 10).map(t => ({ connection: t.connection || null, destination: t.destination || null }));
    if (kept.mode === 'all') state.mode = 'all';
    if (kept.expect) Object.assign(state.expect, { on: !!kept.expect.on, connection: kept.expect.connection || null, destination: kept.expect.destination || null, timeout: +kept.expect.timeout || 10, match: String(kept.expect.match || '') });
    if (kept.load) for (const k of Object.keys(state.load)) if (typeof kept.load[k] === typeof state.load[k]) state.load[k] = kept.load[k];
    if (kept.invalid) Object.assign(state.invalid, { on: !!kept.invalid.on, percent: Math.min(100, Math.max(0, +kept.invalid.percent || 0)),
      kinds: Array.isArray(kept.invalid.kinds) ? kept.invalid.kinds.filter(k => KINDS.some(x => x[0] === k)) : null,
      connection: String(kept.invalid.connection || ''), destination: kept.invalid.destination || null });
    if (kept.count != null && kept.count !== '') $('count').value = kept.count;
    if (kept.rate != null && kept.rate !== '') $('rate').value = kept.rate;
  } catch (_) {}
  // Each part of the page loads on its own: one that fails is reported and the rest still work.
  const loads = await Promise.allSettled([loadPacks(), loadConnections(), loadScenarios(), api('/generators').then(g => state.generators = g)]);
  try { await openFirst(); } catch (e) { loads.push({ reason: e }); }
  const failed = loads.filter(l => l.reason).map(l => l.reason.message);
  if (failed.length) toast(failed.join(' · '), true);
  renderLoad(); renderInvalid();
  renderSent(); await refresh(); setInterval(tick, 1000);
  const linked = /^#run=([0-9a-f]{8})$/.exec(location.hash);   // a link to one run opens it
  if (linked) openRun(linked[1]);
})();
