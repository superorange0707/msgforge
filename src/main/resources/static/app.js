// The console. No build step and no library: one page, one script, the HTTP API.
// State is one object; each part of the page has a render function that paints it from the state.
const $ = id => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const esc = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const KINDS = [['empty', 'Empty'], ['garbage', 'Garbage'], ['truncated', 'Truncated'], ['field-removed', 'Field missing'], ['type-changed', 'Wrong type']];
const blankPlan = () => ({ mix: false, parts: [], extra: [], watches: [], kinds: KINDS.slice(0, 3).map(k => k[0]), unlimited: false, all: false, check: false });
const state = {
  about: {}, controls: new Set(), packs: [], types: [], connections: [], status: {}, scenarios: [], monitors: [], runs: [], messages: [], generators: [], saved: null,
  mode: 'send', page: 'envs', env: null,
  pack: null, name: null, text: '', rendered: '', vars: {},   // the workbench: a template, its text, the sample shown, the field controls
  send: { queue: '', headers: '', raw: false, check: false, arrive: '' },
  plan: blankPlan(), preset: null, liveRun: null, live: null, sentRun: null,
  monitor: null, open: null, picked: null,
  edit: { pack: null, name: null, saved: '' },                // Settings › Templates: the template in the editor
};

const ICONS = {
  send: '<path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4z"/>',
  gauge: '<path d="M12 14l4-4"/><path d="M3.3 19a10 10 0 1 1 17.4 0"/>',
  inbox: '<path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.5 5h13L22 12v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6z"/>',
  chart: '<path d="M3 3v18h18"/><path d="m7 15 4-4 3 3 6-6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  cog: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4z"/>',
  play: '<path d="m6 4 14 8-14 8z"/>',
  square: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  download: '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/>',
  plus: '<path d="M5 12h14M12 5v14"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.3-4.9L3 9"/><path d="M3 4v5h5"/><path d="M4 13a8 8 0 0 0 14.3 4.9L21 15"/><path d="M21 20v-5h-5"/>',
  braces: '<path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5a2 2 0 0 0 2 2h1"/><path d="M16 21h1a2 2 0 0 0 2-2v-5a2 2 0 0 1 2-2 2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1"/>',
  trash: '<path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="m6 7 1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12"/><path d="M10 11v6M14 11v6"/>',
  wand: '<path d="m4 20 10.5-10.5"/><path d="M15 3v3M13.5 4.5h3M20 9v2.4M18.8 10.2h2.4M8.5 4v2M7.5 5h2"/>',
  sliders: '<path d="M4 6h8M16 6h4M4 12h2M10 12h10M4 18h10M18 18h2"/><circle cx="14" cy="6" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
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
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.className = '', bad ? 6000 : 1600);
}
const failed = e => toast(e.message, true);

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
function remember() {
  try { localStorage.setItem('console', JSON.stringify({ mode: state.mode, page: state.page, env: state.env, pack: state.pack, name: state.name, send: state.send, monitor: state.monitor })); } catch (_) {}
}
const time = iso => new Date(iso).toLocaleTimeString([], { hour12: false });
const num = v => (+v).toLocaleString();
const rate = v => v >= 100 ? Math.round(v).toLocaleString() : (+(+v).toFixed(v >= 10 ? 1 : 2)).toString();
const lasted = ms => ms >= 3600000 ? Math.floor(ms / 3600000) + ' h ' + String(Math.floor(ms / 60000) % 60).padStart(2, '0') + ' min'
  : ms >= 60000 ? Math.floor(ms / 60000) + ' min ' + String(Math.floor(ms / 1000) % 60).padStart(2, '0') + ' s' : (ms / 1000).toFixed(1) + ' s';
// ponytail: "a=1, b=2" text. A value cannot contain a comma; switch to a key/value table if that is needed.
function kv(text) {
  const out = {};
  for (const part of text.split(',')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}
// One name=value a line: a value may hold commas, spaces and {{expressions}}.
function kvLines(text) {
  const out = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf('=');
    if (i > 0 && line.slice(0, i).trim()) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return out;
}
const kvText = obj => Object.entries(obj || {}).map(([k, v]) => k + '=' + v).join(', ');
const whole = v => v === '' || v == null || isNaN(+v) ? null : Math.trunc(+v);
const toggle = (button, on) => button.setAttribute(button.getAttribute('role') === 'switch' ? 'aria-checked' : 'aria-pressed', on);
const isOn = button => (button.getAttribute('aria-checked') || button.getAttribute('aria-pressed')) === 'true';
const xButton = (label, click) => { const x = el('button', 'x'); x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', label); x.title = label; x.onclick = click; return x; };

// ---- menus: a button, and a panel under it that closes on a choice, a click elsewhere or Escape
let openMenu = null;
function closeMenu(refocus) {
  if (!openMenu) return;
  const { menu, button } = openMenu; openMenu = null;
  menu.hidden = true; button.setAttribute('aria-expanded', 'false');
  if (refocus) button.focus();
}
function menuOn(button, menu, fill) {
  button.insertAdjacentHTML('beforeend', icon('down'));
  button.onclick = e => {
    e.stopPropagation();
    if (openMenu?.menu === menu) return closeMenu();
    closeMenu(); fill(); menu.hidden = false; button.setAttribute('aria-expanded', 'true'); openMenu = { menu, button };
    (menu.querySelector('[aria-checked="true"], [aria-current="true"]') || menu.querySelector('button'))?.focus();
  };
  menu.addEventListener('keydown', e => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = [...menu.querySelectorAll('button')], at = items.indexOf(document.activeElement);
    items[(at + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus();
  });
}
function menuItem(menu, label, { cls = '', small, tag, checked, click, before } = {}) {
  const b = el('button', 'item ' + cls); b.type = 'button';
  if (before) b.append(before);
  b.append(el('span', '', label));
  if (small) b.append(el('small', '', small));
  if (tag) b.append(el('span', 'tag ' + tag.toLowerCase(), tag));
  if (checked != null) { b.setAttribute('role', 'menuitemradio'); b.setAttribute('aria-checked', checked); }
  b.onclick = () => { closeMenu(true); click(); };
  menu.append(b);
  return b;
}
document.addEventListener('click', e => { if (openMenu && !openMenu.menu.contains(e.target)) closeMenu(); });

// ---- syntax colours. Spans only change colour, never width, so the editor's overlay stays aligned with its textarea.
const PH = '(?<!\\\\)\\{\\{[^{}\\n]*\\}\\}';
const RX = {
  xml: new RegExp(`(${PH})|(<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>)|(<\\/?)([\\w:.-]+)|(\\/?>)|([\\w:.-]+)(?==["'{])|("(?:[^"<>\\n{]|\\{(?!\\{)|${PH})*")`, 'g'),
  json: new RegExp(`(${PH})|("(?:[^"\\\\\\n{]|\\\\.|\\{(?!\\{)|${PH})*")(\\s*:)?|(-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)|\\b(true|false|null)\\b|([{}\\[\\],:])`, 'g'),
  text: new RegExp(`(${PH})`, 'g'),
};
const PH_SPLIT = new RegExp(`(${PH})`);
const span = (cls, html) => `<span class="${cls}">${html}</span>`;
// A placeholder that names one of the pack's fields is painted apart from a generator: it is the part a tester chooses.
let fieldNames = new Set();
const ph = p => span('ph' + (fieldNames.has((/\{\{\s*(?:\w+\s*=\s*)?([\w.-]+)/.exec(p) || [])[1]) ? ' f' : ''), esc(p));
const withPh = (cls, s) => span(cls, s.split(PH_SPLIT).map((p, i) => i % 2 ? ph(p) : esc(p)).join(''));
const langOf = (name, text) => /\.xml$/i.test(name || '') ? 'xml' : /\.json$/i.test(name || '') ? 'json'
  : /^\s*</.test(text) ? 'xml' : /^\s*[\[{]/.test(text) ? 'json' : 'text';
function paint(m, lang) {
  if (m[1]) return ph(m[1]);
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
function highlight(text, lang, fields) {
  fieldNames = new Set(fields || []);
  const rx = RX[lang]; rx.lastIndex = 0;
  let out = '', last = 0, m;
  while ((m = rx.exec(text))) {
    if (!m[0]) { rx.lastIndex++; continue; }
    out += esc(text.slice(last, m.index)) + paint(m, lang);
    last = m.index + m[0].length;
  }
  return out + esc(text.slice(last));
}
// A numbered listing, coloured line by line (a comment that runs over several lines keeps its colour on the first only).
function listing(box, text, lang, fields) {
  box.classList.remove('err');
  box.innerHTML = text.split('\n').map((line, i) => `<div><span class="n">${i + 1}</span><span>${highlight(line, lang, fields)}</span></div>`).join('');
}

// ---- environments: a connection is an environment. The one chosen is where Send and Load send.
const conn = id => state.connections.find(c => c.id === id);
const receivers = () => state.connections.filter(c => state.types.find(t => t.id === c.type)?.canReceive);
const typeLabel = c => c.type === 'dry-run' ? 'Offline' : c.typeLabel;
// A queue written as ${MSGFORGE_NAME} is shown by the name it stands for, and sent as it was written.
const showQ = (id, written) => conn(id)?.queues?.[written] || written || '';
const writeQ = (id, shown) => (Object.entries(conn(id)?.queues || {}).find(([, s]) => s === shown.trim()) || [shown.trim()])[0];
const queuesOf = id => (conn(id)?.destinations || []).map(d => showQ(id, d));
// Text the server put together from names as they are written ("${MSGFORGE_ENV} / ${MSGFORGE_REJECT_QUEUE}"), with each
// name a connection knows replaced by what it stands for. Only for showing.
function pretty(text) {
  if (!text || !text.includes('${')) return text || '';
  for (const c of state.connections) {
    if (c.title !== c.name) text = text.split(c.name).join(c.title);
    for (const [written, shown] of Object.entries(c.queues || {})) text = text.split(written).join(shown);
  }
  return text;
}
const statusClass = id => { const s = state.status[id]; return 'dot' + (!s ? '' : s.ok ? ' ok' : ' bad'); };
async function checkEnv(id) {
  try { state.status[id] = await api('/connections/' + encodeURIComponent(id) + '/check', { method: 'POST' }); } catch (_) { return; }
  renderEnv(); if (state.mode === 'settings') renderEnvCards();
}
function renderEnv() {
  if (!conn(state.env)) state.env = state.connections[0]?.id || null;
  const c = conn(state.env);
  $('envName').textContent = c ? c.title : 'No environment';
  $('env').querySelector('.dot').className = statusClass(state.env);
  $('env').title = c ? 'Environment: ' + c.title + (state.status[c.id]?.error ? '\n' + state.status[c.id].error : '') : 'Environment';
  $('queues').replaceChildren(...queuesOf(state.env).map(q => new Option(q)));
  const list = queuesOf(state.env);
  for (const id of ['sQueue', 'lQueue']) if (!$(id).value || $(id).dataset.env !== state.env && !list.includes($(id).value)) $(id).value = list[0] || '';
  for (const id of ['sQueue', 'lQueue']) $(id).dataset.env = state.env || '';
}
function fillEnvMenu() {
  const menu = $('envMenu'); menu.replaceChildren(el('h5', '', 'Environment'));
  for (const c of state.connections) {
    const dot = el('i', statusClass(c.id));
    menuItem(menu, c.title, { before: dot, small: typeLabel(c) + (c.summary && !c.summary.includes('${') ? ' · ' + c.summary : ''), checked: c.id === state.env, click: () => pickEnv(c.id) });
  }
  menu.append(el('hr'));
  menuItem(menu, 'Manage environments…', { click: () => go('settings', 'envs') });
}
function pickEnv(id) {
  state.env = id; renderEnv(); remember();
  if (state.mode === 'settings') renderEnvCards();
}
async function loadConnections() {
  [state.types, state.connections, state.status] = await Promise.all([state.types.length ? state.types : api('/types'), api('/connections'), api('/connections/status')]);
  renderEnv();
}

// ---- the workbench's message: a template, a control for each of its pack fields, and a sample
const pack = id => state.packs.find(p => p.id === (id ?? state.pack));
const tplUrl = (p, t) => `/packs/${encodeURIComponent(p)}/templates/${encodeURIComponent(t)}`;
const tplParts = name => { const ext = (name.match(/\.(xml|json)$/i) || [])[1]; return [ext ? name.slice(0, -ext.length - 1) : name, ext ? ext.toUpperCase() : null]; };
const human = name => { const s = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').toLowerCase(); return s[0].toUpperCase() + s.slice(1); };
/** What a pack field's expression offers a tester: a choice, a range, or nothing a control could set. */
function fieldKind(expr) {
  const m = /^\s*\{\{\s*(pick|decimal|int)((?:\s+(?:"[^"]*"|[^\s}"]+))+)\s*\}\}\s*$/.exec(expr || '');
  if (!m) return null;
  const args = [...m[2].matchAll(/"([^"]*)"|(\S+)/g)].map(a => a[1] ?? a[2]);
  if (m[1] === 'pick') return { kind: 'pick', choices: [...new Set(args)] };
  if (args.length < 2 || isNaN(+args[0]) || isNaN(+args[1])) return null;
  return { kind: 'range', gen: m[1], min: args[0], max: args[1], scale: m[1] === 'decimal' ? args[2] ?? '2' : null };
}
const usesField = (text, name) => new RegExp('\\{\\{\\s*(?:\\w+\\s*=\\s*)?' + name.replace(/[.-]/g, '\\$&') + '\\s*\\}\\}').test(text);
function renderTemplatePick() {
  const b = $('tpl'), chevron = b.querySelector('svg');
  const [base, ext] = state.name ? tplParts(state.name) : ['No template', null];
  b.replaceChildren(el('span', '', base));
  if (ext) b.append(el('span', 'tag ' + ext.toLowerCase(), ext));
  b.append(chevron);
  $('editTpl').disabled = $('reroll').disabled = !state.name;
}
function fillTemplateMenu() {
  const menu = $('tplMenu'); menu.replaceChildren();
  for (const p of state.packs) {
    if (!p.templates.length) continue;
    menu.append(el('h5', '', p.name));
    for (const t of p.templates) {
      const [base, ext] = tplParts(t);
      menuItem(menu, base, { cls: 'mono', tag: ext, checked: p.id === state.pack && t === state.name, click: () => pickTemplate(p.id, t) });
    }
  }
  if (menu.children.length) menu.append(el('hr'));
  menuItem(menu, 'Paste a message…', { click: () => openPaste() });
  menuItem(menu, 'Manage templates…', { click: () => go('settings', 'templates') });
}
async function pickTemplate(packId, name, vars) {
  try { state.text = await api(tplUrl(packId, name)); } catch (e) { return failed(e); }
  Object.assign(state, { pack: packId, name, vars: vars || {} });
  renderTemplatePick(); renderFields(); remember();
  await preview();
}
// Overrides that have no control of their own are typed, one name=value a line, under More: any variable can be fixed.
function syncVars() {
  const text = Object.entries(state.vars).filter(([k]) => !state.controls.has(k)).map(([k, v]) => k + '=' + v).join('\n');
  for (const id of ['sVars', 'lVars']) { $(id).value = text; $(id).rows = Math.min(6, Math.max(2, text.split('\n').length)); }
  if (text) $('sMore').open = true;
}
function typedVars(e) {
  const typed = kvLines(e.target.value);
  for (const k of Object.keys(state.vars)) if (!state.controls.has(k)) delete state.vars[k];
  Object.assign(state.vars, typed);
  renderFields(); previewSoon();   // a name that has a control shows there from now on
}
function renderFields() {
  const box = $('fields'); box.replaceChildren();
  const fields = pack()?.fields || {};
  state.controls = new Set();
  for (const [name, expr] of Object.entries(fields)) {
    const kind = fieldKind(expr);
    if (!kind || !usesField(state.text, name)) continue;
    state.controls.add(name);
    const field = el('label', 'field'); field.append(el('span', '', human(name)));
    const set = () => { field.classList.toggle('set', name in state.vars); previewSoon(); };
    if (kind.kind === 'pick') {
      const sel = el('select'); sel.append(new Option('Any', ''), ...kind.choices.map(c => new Option(c, c)));
      // an override that is none of the choices (typed in an older preset, say) is kept and shown
      if (state.vars[name] != null && !kind.choices.includes(state.vars[name])) sel.append(new Option(state.vars[name], state.vars[name]));
      sel.value = state.vars[name] ?? '';
      sel.onchange = () => { if (sel.value) state.vars[name] = sel.value; else delete state.vars[name]; set(); };
      field.append(sel);
    } else {
      // "{{decimal 5 50 2}}" as an override narrows the range for this run; one number on both sides fixes the value
      const now = /^\{\{\s*\w+\s+(\S+)\s+(\S+)/.exec(state.vars[name] || '') || (state.vars[name] != null ? [0, state.vars[name], state.vars[name]] : [0, kind.min, kind.max]);
      const lo = el('input'), hi = el('input'), step = kind.scale ? 'any' : '1';
      for (const [input, value, what] of [[lo, now[1], 'from'], [hi, now[2], 'to']]) {
        input.type = 'number'; input.step = step; input.value = value; input.setAttribute('aria-label', human(name) + ' ' + what);
        input.onchange = () => {
          const a = lo.value === '' ? kind.min : lo.value, b = hi.value === '' ? kind.max : hi.value;
          if (+a === +kind.min && +b === +kind.max) delete state.vars[name];
          else state.vars[name] = +a === +b ? String(a) : `{{${kind.gen} ${Math.min(a, b)} ${Math.max(a, b)}${kind.scale ? ' ' + kind.scale : ''}}}`;
          set();
        };
      }
      field.append(lo, el('i', '', '–'), hi);
    }
    field.classList.toggle('set', name in state.vars);
    box.append(field);
  }
  syncVars();
}
let previewTimer;
const previewSoon = () => { clearTimeout(previewTimer); previewTimer = setTimeout(preview, 200); };
async function preview() {
  const box = $('code');
  if (!state.name) { box.replaceChildren(); state.rendered = ''; return; }
  const asked = state.pack + '/' + state.name;
  try {
    const text = (await api('/preview', json({ pack: state.pack, body: state.text, vars: state.vars }))).rendered;
    if (asked !== state.pack + '/' + state.name) return;   // another template was picked meanwhile
    state.rendered = text; listing(box, text.replace(/\n$/, ''), langOf(state.name, text));
  } catch (e) { state.rendered = ''; box.textContent = e.message; box.classList.add('err'); }
}
async function loadPacks() {
  state.packs = await api('/packs');
  if (!pack()?.templates.includes(state.name)) {
    const first = state.packs.find(p => p.templates.length);
    if (first) await pickTemplate(first.id, first.templates[0]); else { Object.assign(state, { pack: null, name: null, text: '' }); renderTemplatePick(); renderFields(); preview(); }
  }
  if (state.mode === 'settings') renderPacks();
}

// ---- modes
const VIEW = { send: 'bench', load: 'bench', rejects: 'rejects', reports: 'reports', settings: 'settings' };
function go(mode, page) {
  if (mode !== 'settings' || state.mode === 'settings' || !page) { /* plain switch */ }
  state.mode = mode; if (page) state.page = page;
  document.querySelectorAll('.modes button').forEach(b => { if (b.dataset.mode === mode) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); });
  $('gear').setAttribute('aria-pressed', mode === 'settings');
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('on', v.id === VIEW[mode]));
  $('sendForm').hidden = mode !== 'send'; $('loadForm').hidden = mode !== 'load';
  if (mode === 'send' || mode === 'load') { renderRaw(); renderRecent(); renderPresets(); renderLive(); }
  if (mode === 'rejects') renderRejects();
  if (mode === 'reports') loadReports();
  if (mode === 'settings') renderSettings();
  remember();
}
document.querySelectorAll('.modes button').forEach(b => b.onclick = () => go(b.dataset.mode));
$('gear').onclick = () => go(state.mode === 'settings' ? state.lastMode || 'send' : (state.lastMode = state.mode, 'settings'));

// ---- send: one message
function renderRaw() {
  const raw = state.send.raw && state.mode === 'send';
  $('code').hidden = raw; $('rawText').hidden = !raw;
  for (const e of $('fields').querySelectorAll('select, input')) e.disabled = raw;
  $('reroll').disabled = raw || !state.name;
  toggle($('sRaw'), state.send.raw); toggle($('sCheck'), state.send.check);
  $('sArriveBox').hidden = !state.send.check;
}
async function send(e) {
  e.preventDefault();
  const raw = state.send.raw;
  if (!state.env) return toast('Add an environment first.', true);
  if (!raw && !state.name) return toast('Pick a template first.', true);
  const queue = writeQ(state.env, $('sQueue').value);
  try {
    const run = await api('/runs', json({
      pack: state.pack, template: state.name, connection: state.env, destination: queue, count: 1, rate: 0, headers: kv($('sHeaders').value),
      ...(raw ? { raw: true, body: $('rawText').value } : { vars: state.vars }),
      expect: state.send.check ? { connection: state.env, destination: writeQ(state.env, $('sArrive').value || $('sQueue').value) } : null,
    }));
    state.sentRun = run.id; state.send.queue = $('sQueue').value; remember();
    await refresh(); if (!raw) preview();
  } catch (err) { failed(err); }
}
// What became of a single send, in a word.
function outcome(r) {
  if (r.status === 'RUNNING') return ['sending', ''];
  if (r.status === 'VERIFYING') return ['waiting', ''];
  if (r.status === 'FAILED') return ['failed', 'bad'];
  if (r.status === 'CANCELLED') return ['cancelled', 'dim'];
  if (r.failed) return ['failed', 'bad'];
  if (!r.verdict) return ['sent', 'ok'];
  if (r.verdict === 'PASS') return ['delivered', 'ok'];
  const c = r.checks[0] || {};
  return [c.rejected ? 'rejected' : c.misrouted ? 'misrouted' : 'missing', 'bad'];
}
function renderRecent() {
  const singles = state.runs.filter(r => r.count === 1).slice(0, 6);
  $('recentBox').hidden = !singles.length;
  $('recent').replaceChildren(...singles.map(r => {
    const [word, cls] = outcome(r), row = el('button', 'li'); row.type = 'button';
    row.append(el('span', 't', time(r.startedAt)), el('code', '', r.label), el('span', cls, word));
    row.title = r.error || r.targets.map(t => pretty(t.connection) + ' → ' + showQ(t.connectionId, t.destination)).join(', ');
    row.onclick = () => openRun(r.id);
    return row;
  }));
}

// ---- load: a plan. The form is a preset being edited; a preset is a saved scenario.
function fillPlan(s) {
  const main = s.targets?.[0] || {}, here = id => !id || id === main.connection ? state.env : id;   // the preset's own connection is replaced by the environment chosen
  const plan = state.plan = blankPlan();
  $('lQueue').value = main.destination ? showQ(here(main.connection), main.destination) : queuesOf(state.env)[0] || '';
  plan.extra = (s.targets || []).slice(1).map(t => ({ connection: here(t.connection), destination: showQ(here(t.connection), t.destination) }));
  plan.all = s.mode === 'all';
  const parts = s.parts || [];
  plan.mix = parts.length > 1;
  plan.parts = parts.map(p => ({ pack: p.pack, template: p.template, weight: p.weight || 1, vars: p.vars || {},
    toConn: p.expect ? here(p.expect.connection) : null, to: p.expect ? showQ(here(p.expect.connection), p.expect.destination) : '' }));
  $('lArrive').value = !plan.mix && parts[0]?.expect ? plan.parts[0].to : '';
  $('lCount').value = s.count || ''; $('lRate').value = s.rate || '';
  const load = s.load || {};
  $('lDuration').value = load.durationSeconds || ''; $('lRamp').value = load.rampUpSeconds || '';
  $('lSenders').value = load.senders === 0 ? '' : load.senders ?? (s.id ? 1 : '');   // a saved preset without the setting means one sender; a new form lets the run decide
  plan.unlimited = !!load.unlimited;
  $('lSeed').value = s.seed ?? ''; $('lHeaders').value = kvText(s.headers); $('lTimeout').value = s.timeoutSeconds || ''; $('lMatch').value = s.match || '';
  const invalid = s.invalid || {};
  $('lInvalid').value = invalid.percent || 0;
  plan.kinds = invalid.kinds?.length ? [...invalid.kinds] : invalid.percent ? KINDS.map(k => k[0]) : plan.kinds;
  plan.check = !!invalid.expect;
  $('lReject').value = invalid.expect ? showQ(here(invalid.expect.connection), invalid.expect.destination) : '';
  plan.watches = (s.watches || []).map(w => ({ connection: here(w.connection), destination: showQ(here(w.connection), w.destination), buckets: [...(w.buckets || [])] }));
  state.preset = s.id || null;
  renderPlan(); renderPresets();
  // the workbench shows the preset's (first) template, with the values the preset fixes
  if (parts[0] && pack(parts[0].pack)?.templates.includes(parts[0].template)) pickTemplate(parts[0].pack, parts[0].template, plan.mix ? {} : { ...parts[0].vars });
}
function templateSelect(part, changed) {
  const sel = el('select');
  for (const p of state.packs) {
    if (!p.templates.length) continue;
    const group = document.createElement('optgroup'); group.label = p.name;
    p.templates.forEach(t => group.append(new Option(t, p.id + '/' + t))); sel.append(group);
  }
  const current = part.pack + '/' + part.template;
  if (![...sel.options].some(o => o.value === current)) sel.append(new Option(part.template + ' (missing)', current));
  sel.value = current; sel.setAttribute('aria-label', 'Template');
  sel.onchange = () => { const at = sel.value.indexOf('/'); part.pack = sel.value.slice(0, at); part.template = sel.value.slice(at + 1); changed?.(); };
  return sel;
}
function renderPlan() {
  const plan = state.plan;
  const percent = +$('lInvalid').value;
  $('lInvalidOut').textContent = percent + '%'; $('lInvalid').style.setProperty('--v', percent + '%');
  $('lKinds').replaceChildren(...KINDS.map(([id, label]) => {
    const b = el('button', 'chip', label); b.type = 'button'; b.setAttribute('aria-pressed', plan.kinds.includes(id));
    b.onclick = () => { const now = plan.kinds.includes(id) ? plan.kinds.filter(k => k !== id) : [...plan.kinds, id]; if (now.length) { plan.kinds = now; renderPlan(); } };
    return b;
  }));
  toggle($('lCheck'), plan.check); $('lRejectBox').hidden = !plan.check;
  toggle($('lUnlimited'), plan.unlimited); toggle($('lAll'), plan.all);
  $('lCount').disabled = $('lDuration').disabled = plan.unlimited;
  $('lCount').placeholder = plan.unlimited ? 'until stopped' : $('lDuration').value ? 'until time' : '';
  $('lDuration').placeholder = plan.unlimited ? 'until stopped' : '–';
  // the mix: several templates in weighted shares, each with the queue it should arrive on
  $('mix').hidden = !plan.mix; $('lArriveBox').hidden = plan.mix; $('lVarsBox').hidden = plan.mix; $('mixStart').hidden = plan.mix;
  $('mixRows').replaceChildren(...plan.parts.map((part, i) => {
    const row = el('div', 'mixrow'), weight = el('input', 'w'), to = el('input', 'to');
    weight.type = 'number'; weight.min = 1; weight.max = 1000; weight.value = part.weight; weight.setAttribute('aria-label', 'Weight'); weight.title = 'Weight: this template\'s share of the messages';
    weight.oninput = () => part.weight = Math.max(1, +weight.value || 1);
    to.value = part.to; to.placeholder = 'not checked'; to.setAttribute('list', 'queues'); to.setAttribute('aria-label', 'Expected on'); to.autocomplete = 'off';
    to.title = part.to; to.oninput = () => { part.to = to.title = to.value; part.toConn = part.toConn || state.env; };
    const x = xButton('Remove template', () => { plan.parts.splice(i, 1); if (plan.parts.length < 2) plan.mix = plan.parts.length > 1; renderPlan(); });
    row.append(templateSelect(part), weight, to, x);
    return row;
  }));
  $('lTargets').replaceChildren(...plan.extra.map((t, i) => {
    const row = el('div', 'mixrow trow'), cSel = el('select'), q = el('input');
    cSel.append(...state.connections.map(c => new Option(c.title, c.id))); cSel.value = t.connection; cSel.setAttribute('aria-label', 'Environment'); cSel.onchange = () => t.connection = cSel.value;
    q.value = t.destination; q.required = true; q.placeholder = 'queue'; q.setAttribute('aria-label', 'Queue'); q.autocomplete = 'off'; q.oninput = () => t.destination = q.value;
    row.append(cSel, q, xButton('Remove queue', () => { plan.extra.splice(i, 1); renderPlan(); }));
    return row;
  }));
  $('lWatches').replaceChildren(...plan.watches.map((w, i) => {
    const row = el('div', 'mixrow trow'), cSel = el('select'), q = el('input');
    cSel.append(...receivers().map(c => new Option(c.title, c.id)));
    if (![...cSel.options].some(o => o.value === w.connection)) cSel.append(new Option(w.connection + ' (missing)', w.connection));
    cSel.value = w.connection; cSel.setAttribute('aria-label', 'Environment'); cSel.onchange = () => w.connection = cSel.value;
    q.value = w.destination; q.required = true; q.placeholder = 'queue'; q.setAttribute('list', 'queues'); q.setAttribute('aria-label', 'Watched queue'); q.autocomplete = 'off'; q.oninput = () => w.destination = q.value;
    row.append(cSel, q, xButton('Stop watching this queue', () => { plan.watches.splice(i, 1); renderPlan(); }), bucketChips(w.buckets));
    return row;
  }));
}
function renderPresets() {
  $('presets').hidden = !state.scenarios.length;
  $('presets').replaceChildren(...state.scenarios.map(s => {
    const b = el('button', '', s.name); b.type = 'button'; b.title = s.name; b.setAttribute('aria-pressed', s.id === state.preset);
    b.onclick = () => fillPlan(s);
    return b;
  }));
}
/** The form as a scenario: what Start runs and what Save as preset saves. */
function collectPlan() {
  const plan = state.plan, env = state.env, q = id => writeQ(env, $(id).value), percent = +$('lInvalid').value;
  plan.watches.forEach(w => w.buckets.flush?.());
  const parts = plan.mix
    ? plan.parts.map(p => ({ pack: p.pack, template: p.template, weight: p.weight || 1, vars: p.vars || {}, expect: p.to.trim() ? { connection: p.toConn || env, destination: writeQ(p.toConn || env, p.to) } : null }))
    : [{ pack: state.pack, template: state.name, weight: 1, vars: {}, expect: $('lArrive').value.trim() ? { connection: env, destination: q('lArrive') } : null }];
  return {
    parts, targets: [{ connection: env, destination: q('lQueue') }, ...plan.extra.map(t => ({ connection: t.connection || env, destination: writeQ(t.connection || env, t.destination) }))],
    mode: plan.all ? 'all' : 'spread', count: plan.unlimited ? 0 : whole($('lCount').value) || 0, rate: +$('lRate').value || 0, headers: kv($('lHeaders').value),
    timeoutSeconds: whole($('lTimeout').value), match: $('lMatch').value.trim() || null, seed: whole($('lSeed').value),
    load: { rampUpSeconds: whole($('lRamp').value), durationSeconds: plan.unlimited ? null : whole($('lDuration').value), senders: $('lSenders').value === '' ? 0 : whole($('lSenders').value), unlimited: plan.unlimited || null },
    invalid: percent ? { percent, kinds: plan.kinds, expect: plan.check && $('lReject').value.trim() ? { connection: env, destination: q('lReject') } : null } : null,
    watches: plan.watches.filter(w => w.destination.trim()).map(w => ({ connection: w.connection || env, destination: writeQ(w.connection || env, w.destination), buckets: [...w.buckets] })),
  };
}
async function start(e) {
  e.preventDefault();
  if (!state.env) return toast('Add an environment first.', true);
  if (!state.plan.mix && !state.name) return toast('Pick a template first.', true);
  try {
    const run = await api('/runs', json({ ...collectPlan(), vars: state.plan.mix ? {} : state.vars, label: state.scenarios.find(s => s.id === state.preset)?.name || null }));
    state.liveRun = run.id; state.live = null;
    await refresh();
  } catch (err) { failed(err); }
}
async function savePreset() {
  if (!$('loadForm').reportValidity()) return;
  const current = state.scenarios.find(s => s.id === state.preset);
  const name = await ask({ title: 'Save as preset', label: 'Name', value: current?.name || '', ok: 'Save' });
  if (!name) return;
  const scenario = collectPlan();
  // what the field controls fix belongs to the preset: a scenario keeps overrides per template
  if (!state.plan.mix) scenario.parts[0].vars = { ...state.vars };
  const same = state.scenarios.find(s => s.name === name);   // the same name again replaces that preset
  try {
    const saved = await api(same ? '/scenarios/' + same.id : '/scenarios', json({ ...scenario, name }, same ? 'PUT' : 'POST'));
    state.preset = saved.id; await loadScenarios(); toast('Saved');
  } catch (err) { failed(err); }
}
async function loadScenarios() { state.scenarios = await api('/scenarios'); renderPresets(); if (state.mode === 'settings') renderPresetTable(); }

// The run below the message: the one started here, or else the latest that was more than a single send.
const liveId = () => state.runs.some(r => r.id === state.liveRun) ? state.liveRun : state.runs.find(r => r.count !== 1)?.id || null;
const going = r => r.status === 'RUNNING' || r.status === 'VERIFYING';
function sparkline(series, target) {
  const w = 800, h = 112;
  let max = Math.max(1, target || 0), end = 1;
  for (const p of series) { max = Math.max(max, p.sent / p.seconds, p.failed / p.seconds, p.target || 0); end = Math.max(end, p.t + p.seconds); }
  const top = max * 1.25, x = t => t * w / end, y = v => h - 6 - v * (h - 14) / top;
  // each point sits in the middle of the stretch it covers; the line runs level to both edges from the first and the last
  const line = f => [[0, f(series[0])], ...series.map(p => [p.t + p.seconds / 2, f(p)]), [end, f(series.at(-1))]].map(([t, v]) => x(t).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
  const sent = line(p => p.sent / p.seconds);
  let svg = [0, .5, 1].map(f => `<line class="grid" x1="0" x2="${w}" y1="${y(max * f).toFixed(1)}" y2="${y(max * f).toFixed(1)}"/>`).join('');
  if (series.some(p => p.target != null)) svg += `<polyline class="target" points="${line(p => p.target || 0)}"/>`;
  svg += `<polygon class="area" points="0,${h} ${sent} ${w},${h}"/><polyline class="line" points="${sent}"/>`;
  if (series.some(p => p.failed)) svg += `<polyline class="failed" points="${line(p => p.failed / p.seconds)}"/>`;
  return `<svg class="spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" aria-label="Messages a second over the run"></svg>`.replace('></svg>', '>' + svg + '</svg>');
}
/** Where a run's messages ended up, added over its checks. */
function flowOf(r) {
  const sum = f => r.checks.reduce((n, c) => n + f(c), 0);
  return [
    ['delivered', 'c-ok', sum(c => c.invalid ? 0 : c.arrived)],
    ['rejected as expected', 'c-accent', sum(c => c.invalid ? c.arrived : 0)],
    ['valid but rejected', 'c-bad', sum(c => c.rejected)],
    ['accepted although invalid', 'c-bad', sum(c => c.accepted)],
    ['accepted, unrecognisable', 'c-bad', sum(c => c.unrecognised || 0)],
    ['misrouted', 'c-warn', sum(c => c.misrouted)],
    ['missing', 'c-none', sum(c => c.missing)],
  ];
}
function renderLive() {
  const box = $('live'), d = state.live, id = liveId();
  box.hidden = state.mode !== 'load' || !d || d.run.id !== id;
  const active = state.runs.find(r => r.id === state.liveRun && going(r)) || state.runs.find(r => going(r) && r.count !== 1);
  $('stop').disabled = !active; $('stop').dataset.run = active?.id || '';
  if (box.hidden) return;
  const r = d.run, ended = !going(r), shown = r.status === 'DONE' && r.verdict ? r.verdict : r.status;
  const head = el('div', 'livehead'), title = el('h3'), open = el('button', '', r.label); open.type = 'button'; open.title = 'The run in full, with its reports'; open.onclick = () => openRun(r.id);
  title.append(open);
  const meta = [r.rate ? (r.targetRate != null && !ended && r.targetRate < r.rate ? 'ramping to ' : '') + rate(r.rate) + '/s' : 'as fast as possible',
    r.durationSeconds ? Math.min(r.durationSeconds, Math.round(r.elapsedMs / 1000)) + ' s of ' + r.durationSeconds : lasted(r.elapsedMs), r.count ? num(r.count) + ' messages' : null, 'run ' + r.id];
  head.append(title, el('span', 'pill ' + shown, shown === 'VERIFYING' ? 'WAITING' : shown), el('span', 'meta', meta.filter(Boolean).join(' · ')));
  const stats = el('div', 'stats'), stat = (value, unit, label, bad) => { const s = el('div', 'stat' + (bad ? ' bad' : '')), b = el('b', '', value); if (unit) b.append(el('small', '', unit)); s.append(b, el('span', '', label)); return s; };
  stats.append(stat(num(r.sent), '', 'sent'), stat(rate(r.actualRate), '/s', 'rate'), stat(r.latency ? String(r.latency.p95) : '–', r.latency ? ' ms' : '', 'p95'),
    stat(num(r.failed), '', 'failed', r.failed > 0), stat(num(r.invalid), '', 'invalid'));
  box.replaceChildren(head, stats);
  if (d.series.length) box.insertAdjacentHTML('beforeend', sparkline(d.series, r.rate));
  if (r.checks.length) {
    const flow = flowOf(r), bar = el('div', 'flow'), legend = el('div', 'legend');
    for (const [label, cls, n] of flow) {
      if (n) { const part = el('i', cls); part.style.flex = n; bar.append(part); }
      if (n || ['delivered', 'missing'].includes(label) || label === 'rejected as expected' && r.invalid) { const s = el('span'); s.append(el('i', cls), el('b', '', num(n)), label); legend.append(s); }
    }
    box.append(bar, legend);
  }
  if (r.error) box.append(el('div', 'error', r.error));
  else if (r.errors.length) box.append(el('div', 'error', r.errors.slice(0, 3).map(e => num(e.count) + ' × ' + e.message).join('\n')));
}

// ---- buckets: how a watched queue sorts what arrives. One expression each: /a/json/pointer, header:Name, or a regular expression.
const bucketOf = text => text.startsWith('/') ? { pointer: text } : /^header:/i.test(text) ? { header: text.slice(7).trim() } : { regex: text };
const bucketText = b => b.pointer || (b.header ? 'header:' + b.header : b.regex);
// A chip row over the given array of buckets. Typed text becomes a bucket on Enter; one saved with a name keeps it.
function bucketChips(buckets) {
  const row = el('div', 'chiprow'), input = el('input');
  input.placeholder = '/reason, header:name or a regular expression'; input.autocomplete = 'off'; input.setAttribute('aria-label', 'Group by: add an expression, then Enter');
  const draw = () => {
    row.querySelectorAll('.chipx').forEach(c => c.remove());
    buckets.forEach((b, i) => {
      const chip = el('span', 'chipx'), x = el('button');
      chip.append(el('span', '', (b.name ? b.name + ' = ' : '') + bucketText(b))); chip.title = bucketText(b);
      x.type = 'button'; x.innerHTML = icon('x'); x.setAttribute('aria-label', 'Remove ' + bucketText(b));
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

// ---- tables and charts
function table(head, rows, onRow) {
  const t = el('table'), tr = el('tr');
  head.forEach(h => { const th = el('th', typeof h === 'object' ? 'n' : '', typeof h === 'object' ? h.n : h); th.scope = 'col'; tr.append(th); });
  t.append(tr);
  rows.forEach((row, at) => {
    const r = el('tr');
    row.forEach((c, i) => r.append(c instanceof Node ? c : el('td', typeof head[i] === 'object' ? 'n' : '', c == null ? '' : typeof c === 'number' ? c.toLocaleString() : c)));
    if (onRow) { r.className = 'go-to'; r.tabIndex = 0; r.onclick = e => { if (!e.target.closest('a, button')) onRow(at); }; r.onkeydown = e => { if (e.key === 'Enter' && e.target === r) onRow(at); }; }
    t.append(r);
  });
  return t;
}
const cell = (cls, ...children) => { const td = el('td', cls); td.append(...children); return td; };
const niceMax = v => { const p = 10 ** Math.floor(Math.log10(v)); return [1, 2, 4, 5, 8, 10].find(s => v <= s * p) * p; };
const clock = s => { s = Math.round(s); const mm = String(Math.floor(s / 60) % 60), ss = String(s % 60).padStart(2, '0'); return s >= 3600 ? Math.floor(s / 3600) + ':' + mm.padStart(2, '0') + ':' + ss : mm + ':' + ss; };
// The numbers in the drawing are the server's; nothing in it is text from a message.
function rateChart(series) {
  const w = 960, h = 250, l = 52, r = 12, t = 12, b = 28, box = el('div');
  let max = 1, end = 1;
  for (const p of series) { max = Math.max(max, p.sent / p.seconds, p.failed / p.seconds, p.target || 0); end = Math.max(end, p.t + p.seconds); }
  max = niceMax(max);
  const x = time => l + time * (w - l - r) / end, y = v => h - b - v * (h - t - b) / max;
  // each point sits in the middle of the stretch it covers; the line runs level to both edges from the first and the last
  const path = f => [[0, f(series[0])], ...series.map(p => [p.t + p.seconds / 2, f(p)]), [end, f(series.at(-1))]].map(([time, v], i) => (i ? 'L' : 'M') + x(time).toFixed(1) + ' ' + y(v).toFixed(1)).join('');
  const sent = path(p => p.sent / p.seconds), anyFailed = series.some(p => p.failed), anyTarget = series.some(p => p.target != null);
  const x0 = l, x1 = w - r;
  let svg = '';
  for (let i = 0; i <= 4; i++) {
    const gy = (h - b - (h - t - b) * i / 4).toFixed(1);
    svg += `<line class="grid" x1="${l}" x2="${w - r}" y1="${gy}" y2="${gy}"/><text x="${l - 8}" y="${+gy + 4}" text-anchor="end">${rate(max * i / 4)}</text>`;
  }
  // a label at whole seconds only, at most seven of them: a four-second run is not 0:01, 0:01, 0:03, 0:03
  const step = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200].find(s => end / s <= 6) || 86400;
  for (let at = 0; at <= end; at += step) svg += `<text x="${x(at).toFixed(1)}" y="${h - 8}" text-anchor="middle">${clock(at)}</text>`;
  svg += `<path class="area" d="${sent}L${x1.toFixed(1)} ${h - b}L${x0.toFixed(1)} ${h - b}Z"/>`;
  if (anyTarget) svg += `<path class="target" d="${path(p => p.target || 0)}"/>`;
  svg += `<path class="sent" d="${sent}"/>`;
  if (anyFailed) svg += `<path class="failed" d="${path(p => p.failed / p.seconds)}"/>`;
  const last = series.at(-1);
  box.innerHTML = `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img"></svg>`;
  box.firstChild.innerHTML = svg;
  box.firstChild.setAttribute('aria-label', 'Messages a second over the run. Latest: ' + rate(last.sent / last.seconds) + ' sent a second'
    + (last.target != null ? ', target ' + rate(last.target) : '') + (anyFailed ? ', ' + rate(last.failed / last.seconds) + ' failed a second' : ''));
  const legend = el('div', 'legend'), entry = (cls, text) => { const s = el('span'); s.append(el('i', 'l ' + cls), text); return s; };
  legend.append(entry('', 'sent a second'));
  if (anyTarget) legend.append(entry('target', 'target'));
  if (anyFailed) legend.append(entry('failed', 'failed a second'));
  box.append(legend);
  return box;
}
function barsOf(t) {
  const bars = el('div', 'bars'), most = Math.max(1, ...t.values.map(v => v.count));
  for (const v of t.values) {
    const bar = el('div', 'bar'), name = el('span', '', v.value), track = el('span', 'track'), fill = el('i');
    name.title = v.value; fill.style.width = (100 * v.count / most) + '%'; track.append(fill);
    bar.append(name, track, el('b', '', num(v.count))); bars.append(bar);
  }
  if (!t.values.length) bars.append(el('span', 'dim', 'Nothing yet'));
  return bars;
}
// What a run made of an arrival, for a list of arrivals. "not ours" is everything no run recognised.
const OUTCOME = { 'invalid-expected': ['invalid, expected', 'ok'], 'valid-expected': ['valid, expected', 'ok'], 'valid-unexpected': ['was valid', 'bad'], 'invalid-unexpected': ['invalid, not expected here', 'bad'] };
function matchCell(a) {
  if (!a.run) return el('td', 'dim', 'not ours');
  const [word, cls] = OUTCOME[a.outcome] || ['', ''], link = el('button', 'ghost', a.run + (a.message != null ? ' #' + a.message : ''));
  link.type = 'button'; link.style.cssText = 'height:auto;padding:0'; link.onclick = () => openRun(a.run);
  return cell('', link, el('span', cls, ' · ' + word));
}

// ---- one run in full: numbers, the rate over time, what arrived where, and the reports
const tile = (label, value, bad) => { const t = el('div', 'tile' + (bad ? ' bad' : '')); t.append(el('b', '', value), el('span', '', label)); return t; };
const box = (title, ...children) => { const p = el('section', 'box'); if (title) p.append(el('h4', '', title)); p.append(...children); return p; };
function watchBox(w, id) {
  const head = el('div', 'whead'), p = el('section', 'box');
  head.append(el('h3', '', pretty(w.connection + ' / ' + w.destination)), el('span', '', num(w.count) + ' arrived · ' + rate(w.rate) + '/s' + (w.lastAt ? ' · last ' + time(w.lastAt) : '')));
  p.append(head);
  for (const t of w.tables) { p.append(el('h4', '', t.name), barsOf(t)); }
  if (w.recent.length && w.tables.length) {
    const scroll = el('div', 'scroll'), matched = w.recent.some(a => a.message != null); scroll.style.marginTop = '12px';
    scroll.append(table(['Received', ...w.tables.map(t => t.name), ...(matched ? [{ n: 'Message' }] : []), { n: 'Bytes' }],
      w.recent.map(a => [time(a.at), ...a.values, ...(matched ? [a.message == null ? '' : '#' + a.message] : []), a.bytes])));
    p.append(scroll);
  }
  if (w.samples?.length) {
    const d = el('details', 'samples'), key = w.connection + '/' + w.destination; d.append(el('summary', '', 'Newest bodies'));
    d.open = watchBox.open.has(key); d.ontoggle = () => watchBox.open[d.open ? 'add' : 'delete'](key);
    w.samples.forEach(s => d.append(el('pre', '', s)));
    p.append(d);
  }
  return p;
}
watchBox.open = new Set();
function renderRunDetail(d) {
  const r = d.run, ended = !going(r), shown = r.status === 'DONE' && r.verdict ? r.verdict : r.status;
  const tiles = el('div', 'tiles');
  tiles.append(tile('sent', num(r.sent)), tile('failed', num(r.failed), r.failed > 0));
  if (Object.keys(r.faults).length) tiles.append(tile('invalid', num(r.invalid)));
  tiles.append(tile(r.targetRate != null ? 'target ' + rate(r.targetRate) + '/s' : 'rate', rate(r.actualRate) + '/s'), tile('sending', lasted(r.elapsedMs)));
  if (r.latency) tiles.append(tile('send p50', r.latency.p50 + ' ms'), tile('send p95', r.latency.p95 + ' ms'), tile('send p99', r.latency.p99 + ' ms'));
  const parts = [tiles];
  if (r.error) parts.push(el('div', 'result bad', r.error));
  if (d.series.length && r.sent + r.failed > 1) parts.push(box(null, rateChart(d.series)));
  const side = el('div', 'grid-auto');
  if (r.checks.length) side.append(box('Arrivals', table(['Template', 'Expected on', { n: 'Sent' }, 'Result'], r.checks.map(c => {
    const result = el('td', !ended ? '' : c.arrived === c.sent ? 'ok' : 'bad');
    result.append(num(c.arrived) + (c.invalid ? ' rejected as expected' : ' arrived'));
    if (c.accepted) result.append(', ' + c.accepted + ' accepted although invalid');
    if (c.unrecognised) result.append(', ' + c.unrecognised + ' accepted, unrecognisable');
    if (c.rejected) result.append(', ' + c.rejected + ' valid but rejected');
    if (ended && c.misrouted) result.append(', ' + c.misrouted + ' misrouted');
    if (ended && c.missing) result.append(', ' + c.missing + ' missing');
    if (c.medianMs != null) result.append(' · ' + c.medianMs + ' ms');
    return [c.template + (c.invalid ? ' (invalid)' : ''), pretty(c.expected), c.sent, result];
  }))));
  if (Object.keys(r.faults).length) side.append(box('Invalid messages', table(['Kind', { n: 'Messages' }], Object.entries(r.faults).map(([k, n]) => [(KINDS.find(x => x[0] === k) || [k, k])[1], n]))));
  if (r.errors.length) side.append(box('Send errors', table(['Error', { n: 'Count' }, { n: 'Last' }], r.errors.map(e => [e.message, e.count, time(e.lastAt)]))));
  if (side.children.length) parts.push(side);
  const ids = Object.fromEntries(state.connections.map(c => [c.name, c.id]));
  d.watches.filter(w => w.count || w.tables.length).forEach(w => parts.push(watchBox(w, ids[w.connection])));
  // the messages of this run that are still kept, with what was sent
  const kept = state.messages.filter(m => m.runId === r.id);
  if (kept.length) {
    const wrap = el('div', 'sentlist'), list = el('div', 'list'), body = el('pre', 'ink'); body.id = 'sentBody';
    if (!kept.some(m => keyOf(m) === state.picked)) state.picked = keyOf(kept[0]);
    for (const m of kept) {
      const b = el('button', 'li'); b.type = 'button'; if (keyOf(m) === state.picked) b.setAttribute('aria-current', 'true');
      b.append(el('span', 't', '#' + m.n), el('code', '', pretty(m.destination)), m.fault ? el('span', 'bad', m.fault) : el('span', 't', time(m.at)));
      b.onclick = () => { state.picked = keyOf(m); shownKey = null; refreshOpen(); };
      list.append(b);
    }
    wrap.append(list, body); parts.push(box('Sent', wrap));
  }
  parts.push(el('div', 'plan', [r.targets.map(t => pretty(t.connection) + ' → ' + showQ(t.connectionId, t.destination)).join(', '), r.templates.join(', '),
    r.rate ? rate(r.rate) + '/s' + (r.rampUpSeconds ? ' reached over ' + r.rampUpSeconds + ' s' : '') : 'as fast as possible',
    r.count ? num(r.count) + (r.count === 1 ? ' message' : ' messages') : null, r.durationSeconds ? 'for ' + r.durationSeconds + ' s' : null,
    r.senders + (r.senders === 1 ? ' sender' : ' senders'), 'seed ' + r.seed, 'run ' + r.id].filter(Boolean).join(' · ')));
  $('detailTitle').textContent = r.label;
  $('detailChip').className = 'verdict ' + shown; $('detailChip').textContent = shown;
  if ($('detailActs').dataset.for !== r.id) {
    $('detailActs').dataset.for = r.id;
    $('detailActs').replaceChildren(...[['html', 'HTML'], ['json', 'JSON'], ['csv', 'CSV'], ['series', 'Series']].map(([format, label]) => {
      const a = el('a', 'btn', label); a.href = '/api/runs/' + encodeURIComponent(r.id) + '/report?format=' + format; a.download = '';
      a.title = format === 'series' ? 'The rate over time as CSV' : 'Download the report as ' + label; return a;
    }));
  }
  // rebuilt each second, so where the user has scrolled to is put back
  const bodyBox = $('detailBody'), top = bodyBox.scrollTop, inner = [...bodyBox.querySelectorAll('.scroll, .list, pre')].map(s => s.scrollTop), was = $('sentBody')?.innerHTML;
  bodyBox.replaceChildren(...parts);
  bodyBox.scrollTop = top; bodyBox.querySelectorAll('.scroll, .list, pre').forEach((s, i) => s.scrollTop = inner[i] || 0);
  if ($('sentBody')) { if (was && shownKey === state.picked) $('sentBody').innerHTML = was; showPicked(); }
  if (!$('detail').open) $('detail').showModal();
}
const keyOf = m => m.runId + '#' + m.n + '#' + m.target;
let shownKey = null;
async function showPicked() {
  const want = state.picked, m = state.messages.find(m => keyOf(m) === want), body = $('sentBody');
  if (!m || !body || shownKey === want && body.innerHTML) return;
  try {
    const full = await api(`/messages/${encodeURIComponent(m.runId)}/${m.n}/${m.target}`);
    if (want !== state.picked || !$('sentBody')) return;
    const meta = Object.entries(full.headers || {}).map(([k, v]) => k + ': ' + v);
    $('sentBody').innerHTML = (meta.length ? span('t-com', esc(meta.join('\n'))) + '\n\n' : '') + highlight(full.body, langOf(null, full.body));
    shownKey = want;
  } catch (e) { if ($('sentBody')) $('sentBody').textContent = e.message; }
}
async function refreshOpen() {
  const open = state.open;
  if (!open) return;
  try { const d = await api('/runs/' + encodeURIComponent(open)); if (state.open === open) renderRunDetail(d); }
  catch (e) { if (e.status === 404 && state.open === open) { $('detail').close(); toast('That run is no longer kept.', true); } }
}
function openRun(id) { state.open = id; shownKey = null; try { history.replaceState(null, '', '#run=' + id); } catch (_) {} refreshOpen(); }
$('detail').addEventListener('close', () => { state.open = null; $('detailActs').dataset.for = ''; try { history.replaceState(null, '', location.pathname); } catch (_) {} });

// ---- rejects: the monitors as a page
const monitorName = m => m.name || showQ(m.connectionId, m.watch.destination);
const monitorAct = (m, path, method) => api('/monitors/' + m.id + path, { method }).then(loadMonitors, failed);
function renderRejects() {
  const page = $('rejects'), list = state.monitors;
  const total = list.reduce((n, m) => n + m.watch.count, 0);
  $('rejectCount').hidden = !total; $('rejectCount').textContent = total > 9999 ? Math.floor(total / 1000) + 'k' : num(total);
  if (state.mode !== 'rejects') return;
  if (!list.length) {
    const empty = el('div', 'card empty'), add = el('button', 'btn primary'); add.innerHTML = icon('plus'); add.append('Reject queue'); add.onclick = () => openMonitorForm();
    empty.append(el('span', '', 'No queue is being watched'), add);
    return page.replaceChildren(empty);
  }
  if (!list.some(m => m.id === state.monitor)) state.monitor = list[0].id;
  const m = list.find(x => x.id === state.monitor), w = m.watch, head = el('div', 'pagehead');
  if (list.length > 1) for (const other of list) {
    const b = el('button', 'chip pickone', monitorName(other)); b.setAttribute('aria-pressed', other.id === m.id);
    b.onclick = () => { state.monitor = other.id; remember(); renderRejects(); };
    head.append(b);
  } else head.append(el('h2', '', monitorName(m)));
  const state_ = el('span', 'state plain ' + (m.state === 'up' ? 'up' : m.state === 'down' || m.error ? 'down' : ''), { up: 'Watching', down: 'Down', stopped: 'Stopped' }[m.state] || 'Starting');
  const onoff = el('button', 'ghost', m.state === 'stopped' ? 'Start' : 'Stop');
  onoff.onclick = () => m.state === 'stopped' ? monitorAct(m, '/start', 'POST') : monitorAct(m, '', 'DELETE');
  head.append(el('span', 'sp'), el('span', 'hint', pretty(w.connection + ' / ' + w.destination)), state_, onoff);
  const parts = [head];
  if (m.error) parts.push(el('div', 'error', m.error + (m.retryAt ? (/[.!?]$/.test(m.error) ? '' : '.') + ' Next try at ' + time(m.retryAt) + '.' : '')));
  const kpis = el('div', 'kpis'), kpi = (value, unit, ...label) => { const k = el('div', 'card kpi'), b = el('b', '', value), s = el('span'); if (unit) b.append(el('small', '', unit)); s.append(...label); k.append(b, s); return k; };
  const reset = el('button', '', 'reset'); reset.type = 'button'; reset.setAttribute('aria-label', 'Reset the count'); reset.onclick = () => monitorAct(m, '/reset', 'POST');
  kpis.append(kpi(num(w.count), '', 'since ' + time(w.since) + ' · ', reset), kpi(rate(w.rate), '/s', 'last 10 s'), kpi(num(w.total), '', 'since it started'), kpi(w.lastAt ? time(w.lastAt) : '–', '', 'last arrival'));
  parts.push(kpis);
  if (w.tables.length) {
    const charts = el('div', 'two-col');
    for (const t of w.tables) { const c = el('div', 'card big'); c.append(el('h4', '', 'By ' + t.name), barsOf(t)); charts.append(c); }
    parts.push(charts);
  }
  const recent = el('div', 'card big');
  if (w.recent.length) recent.append(table(['Received', ...w.tables.map(t => t.name), { n: 'Bytes' }, 'Matched run'], w.recent.map(a => [time(a.at), ...a.values, a.bytes, matchCell(a)])));
  else recent.append(el('div', 'empty', 'Nothing has arrived'));
  parts.push(recent);
  const top = page.scrollTop; page.replaceChildren(...parts); page.scrollTop = top;
}
let monitorsAsked = 0;
async function loadMonitors() {
  // The list is asked for every second and after every action. An answer that was overtaken by a later question is dropped,
  // or a monitor just stopped would be painted as watching again by the answer to the question asked before the stop.
  const asked = ++monitorsAsked, answer = await api('/monitors');
  if (asked !== monitorsAsked) return;
  state.monitors = answer; renderRejects();
  if (state.mode === 'settings' && state.page === 'monitors') renderMonitorTable();
}
let newBuckets = [];
function openMonitorForm() {
  const known = receivers();
  if (!known.length) return toast('No environment that can receive.', true);
  newBuckets = [];
  $('mConn').replaceChildren(...known.map(c => new Option(c.title, c.id)));
  if (known.some(c => c.id === state.env)) $('mConn').value = state.env;
  const dests = () => $('mDests').replaceChildren(...queuesOf($('mConn').value).map(d => new Option(d)));
  $('mConn').onchange = dests; dests();
  $('mDest').value = $('mName').value = ''; toggle($('mSave'), true);
  $('mBuckets').replaceWith(Object.assign(bucketChips(newBuckets), { id: 'mBuckets' }));
  $('monitorDialog').showModal(); $('mDest').focus();
}
async function startMonitor(e) {
  e.preventDefault();
  newBuckets.flush();
  try {
    const m = await api('/monitors', json({ name: $('mName').value.trim() || null, connection: $('mConn').value, destination: writeQ($('mConn').value, $('mDest').value), buckets: [...newBuckets], save: isOn($('mSave')) }));
    $('monitorDialog').close(); state.monitor = m.id; await loadMonitors();
  } catch (err) { failed(err); }
}

// ---- reports: the runs msgforge still has, and the reports it saved
const resultOf = r => r.status === 'DONE' && r.verdict ? r.verdict : r.status;
async function loadReports() {
  if (state.saved === null) { try { state.saved = await api('/reports'); } catch (_) { state.saved = { enabled: false, reports: [] }; } }
  renderReports();
  if (state.mode === 'reports') api('/reports').then(s => { state.saved = s; if (state.mode === 'reports') renderReports(); }, () => {});
}
function renderReports() {
  if (state.mode !== 'reports') return;
  const page = $('reports'), runs = el('div', 'card big'), links = (base, files) => {
    const td = el('td', 'acts');
    for (const [label, href] of files) { const a = el('a', 'ghost'); a.innerHTML = icon('download'); a.append(label); a.href = href; a.download = ''; td.append(a); }
    return td;
  };
  if (state.runs.length) runs.append(table(['Started', 'Run', { n: 'Sent' }, { n: 'Rate' }, { n: 'Invalid' }, 'Result', ''], state.runs.map(r => {
    const result = el('span', 'verdict ' + resultOf(r), resultOf(r));
    return [new Date(r.startedAt).toLocaleString([], { hour12: false }), r.label, r.sent, r.count === 1 ? '–' : rate(r.actualRate) + '/s', r.sent && r.invalid ? Math.round(100 * r.invalid / r.sent) + '%' : '–', cell('', result),
      links('', [['HTML', 'html'], ['JSON', 'json'], ['CSV', 'csv']].map(([l, f]) => [l, '/api/runs/' + encodeURIComponent(r.id) + '/report?format=' + f]))];
  }), i => openRun(state.runs[i].id)));
  else runs.append(el('div', 'empty', 'Nothing has run yet'));
  const parts = [runs], saved = state.saved;
  if (saved?.enabled && saved.reports.length) {
    const names = { html: 'HTML', json: 'JSON', csv: 'CSV' }, card = el('div', 'card big');
    card.append(el('h4', '', 'Saved'), table(['Started', 'Run', ''], saved.reports.map(r => [new Date(r.at).toLocaleString([], { hour12: false }), (r.label || 'run') + ' · ' + r.id,
      links('', r.files.map(f => [f.endsWith('.series.csv') ? 'Series' : names[f.split('.').pop()], '/api/reports/' + encodeURIComponent(f)]))])));
    card.querySelector('h4').style.marginBottom = '12px';
    parts.push(card);
  }
  const top = page.scrollTop; page.replaceChildren(...parts); page.scrollTop = top;
}

// ---- settings
function renderSettings() {
  document.querySelectorAll('.subnav button').forEach(b => { if (b.dataset.page === state.page) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); });
  document.querySelectorAll('.spage').forEach(s => s.classList.toggle('on', s.id === 'sp-' + state.page));
  if (state.page === 'envs') renderEnvCards();
  if (state.page === 'templates') { renderPacks(); if (!state.edit.name && state.name) openTemplate(state.pack, state.name); else edited(); }
  if (state.page === 'presets') renderPresetTable();
  if (state.page === 'monitors') renderMonitorTable();
}
document.querySelectorAll('.subnav button').forEach(b => b.onclick = async () => { if (state.page === 'templates' && !await leaveOk()) return; go('settings', b.dataset.page); });

// environments: a card each, and the form behind Edit
function renderEnvCards() {
  $('envCards').replaceChildren(...state.connections.map(c => {
    const card = el('div', 'card envcard' + (c.id === state.env ? ' on' : '')), top = el('div', 'top2'), s = state.status[c.id];
    top.append(el('h3', '', c.title), el('span', 'typebadge', typeLabel(c)), el('span', 'state ' + (!s ? '' : s.ok ? 'up' : 'down'), !s ? 'Not checked' : s.ok ? 'Connected' : 'Unreachable'));
    if (s?.error) top.lastChild.title = s.error;
    const dl = el('dl'), type = state.types.find(t => t.id === c.type);
    for (const f of type?.fields || []) {
      const value = c.settings[f.key], secret = c.secretsSet.includes(f.key);
      if (value == null && !secret) continue;
      // a value that comes from the environment is named, not shown; a stored secret is neither
      const from = /^\$\{(\w+)\}$/.exec(value || ''), dd = el('dd', from ? 'from' : '', secret ? '••••••' : from ? 'from ' + from[1] : value);
      dd.title = secret ? '' : value; dl.append(el('dt', '', f.label), dd);
    }
    if (c.destinations.length) { const dd = el('dd', '', c.destinations.map(d => showQ(c.id, d)).join(', ')); dd.title = dd.textContent; dl.append(el('dt', '', 'Queues'), dd); }
    const row = el('div', 'row'), test = el('button', 'btn', 'Test'), edit = el('button', 'ghost', 'Edit');
    test.onclick = async () => { test.disabled = true; test.textContent = 'Testing…'; await checkEnv(c.id); const now = state.status[c.id]; if (now && !now.ok) toast(now.error, true); };
    edit.onclick = () => openConn(c);
    row.append(test, edit); card.append(top, dl, row);
    return card;
  }));
  if (!state.connections.length) $('envCards').append(el('div', 'card empty', 'No environment yet'));
}
let editing = null;
const blankConnection = () => ({ id: null, name: '', type: (state.types.find(t => t.id !== 'dry-run' && t.fields.length) || state.types[0]).id, settings: {}, secretsSet: [], destinations: [] });
function openConn(c) {
  editing = structuredClone(c || blankConnection());
  $('connTitle').textContent = c ? c.title : 'New environment';
  $('cName').value = editing.name;
  $('cType').replaceChildren(...state.types.map(t => new Option(t.id === 'dry-run' ? 'Offline (no broker)' : t.label, t.id)));
  if (!state.types.some(t => t.id === editing.type)) $('cType').append(new Option(editing.typeLabel, editing.type));
  $('cType').value = editing.type;
  renderTypeFields(); renderChips();
  $('cResult').hidden = true; $('cDelete').hidden = !editing.id;
  $('connDialog').showModal(); if (!c) $('cName').focus();
}
function renderTypeFields() {
  const type = state.types.find(t => t.id === $('cType').value), fields = $('cFields'); fields.replaceChildren();
  fields.hidden = !type?.fields.length;
  if (!type) return;   // not installed: nothing is known about its settings
  for (const f of type.fields) {
    const stored = f.secret && editing.type === type.id && editing.secretsSet.includes(f.key);
    const label = el('label', 'inp'), input = el('input'), caption = el('span', '', f.label);
    label.append(caption, input);
    input.dataset.key = f.key; input.autocomplete = 'off'; input.required = f.required && !stored;
    input.placeholder = stored ? 'Saved. Type to replace' : f.placeholder;
    if (f.secret) { input.type = 'password'; input.autocomplete = 'new-password'; } else input.value = editing.settings[f.key] || '';
    if (stored) {
      const clear = el('button', 'clear', 'Clear'); clear.type = 'button';
      clear.onclick = () => { input.dataset.clear = '1'; input.placeholder = f.placeholder; input.required = f.required; clear.remove(); };
      caption.append(clear);
    }
    fields.append(label);
  }
}
function renderChips() {
  const row = $('cDests'), input = $('cDestInput');
  row.querySelectorAll('.chipx').forEach(c => c.remove());
  editing.destinations.forEach((d, i) => {
    const chip = el('span', 'chipx'), x = el('button'); chip.append(el('span', '', d));
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
    delete state.status[saved.id];
    if (!c.id && state.connections.length === 0) state.env = saved.id;
    await loadConnections(); $('connDialog').close(); renderEnvCards(); toast('Saved'); checkEnv(saved.id);
  } catch (err) { showResult(false, err.message); }
}
async function testConnection() {
  if (!$('connForm').reportValidity()) return;
  const btn = $('cTest'); btn.disabled = true; btn.textContent = 'Testing…';
  try { const r = await api('/connections/test', json(collectConnection())); showResult(r.ok, r.ok ? 'Connected' : r.error); }
  catch (err) { showResult(false, err.message); }
  finally { btn.disabled = false; btn.textContent = 'Test'; }
}
async function deleteConnection() {
  if (!await ask({ title: 'Delete environment?', text: editing.name + ' and its queues are removed.', ok: 'Delete', danger: true })) return;
  try { await api('/connections/' + editing.id, { method: 'DELETE' }); await loadConnections(); $('connDialog').close(); renderEnvCards(); toast('Deleted'); }
  catch (err) { showResult(false, err.message); }
}

// templates: the packs and their templates on the left, the editor on the right
const src = $('src');
const dirty = () => !!state.edit.name && src.value !== state.edit.saved;
async function leaveOk() {
  return !dirty() || await ask({ title: 'Discard changes?', text: state.edit.name + ' has unsaved edits.', ok: 'Discard', danger: true });
}
function renderPacks() {
  const list = $('packs'); list.replaceChildren();
  for (const p of state.packs) {
    const head = el('h5'), edit = el('button'), add = el('button');
    edit.innerHTML = icon('sliders'); edit.title = 'Pack fields'; edit.setAttribute('aria-label', 'Fields of ' + p.name); edit.onclick = () => openPack(p);
    add.innerHTML = icon('plus'); add.title = 'New template'; add.setAttribute('aria-label', 'New template in ' + p.name); add.onclick = () => newTemplate(p.id);
    head.append(el('span', '', p.name), ...(p.error ? [edit] : [edit, add])); list.append(head);
    if (p.error) list.append(el('div', 'packerr', p.error));
    for (const t of p.templates) {
      const [base, ext] = tplParts(t), b = el('button', 'item mono'); b.type = 'button';
      b.append(el('span', '', base)); if (ext) b.append(el('span', 'tag ' + ext.toLowerCase(), ext));
      if (p.id === state.edit.pack && t === state.edit.name) b.setAttribute('aria-current', 'true');
      b.title = t; b.onclick = () => openTemplate(p.id, t);
      list.append(b);
    }
    // the pack's fields under its templates: what a template can name, and what the workbench offers as controls
    if (Object.keys(p.fields || {}).length) list.append(el('h5', '', 'Fields'));
    for (const [name, expr] of Object.entries(p.fields || {})) {
      const kind = fieldKind(expr), b = el('button', 'item'); b.type = 'button';
      b.append(el('span', 'mono', name), el('small', '', kind ? kind.kind === 'pick' ? 'pick ' + kind.choices.length : kind.gen : expr)); b.title = expr;
      b.onclick = () => openPack(p);
      list.append(b);
    }
  }
}
async function openTemplate(packId, name) {
  if (!await leaveOk()) return;
  try { const text = await api(tplUrl(packId, name)); showTemplate(packId, name, text, text); } catch (e) { failed(e); }
}
function showTemplate(packId, name, text, saved) {
  state.edit = { pack: packId, name, saved };
  src.value = text; src.scrollTop = src.scrollLeft = 0;
  renderPacks(); edited();
}
// text: a message to start from. Nothing is written until Save.
async function newTemplate(packId) {
  packId = packId || state.edit.pack || state.pack || state.packs[0]?.id;
  if (!packId) return toast('Create a pack first.', true);
  if (!await leaveOk()) return;
  const name = await ask({ title: 'New template', text: pack(packId).name, label: 'File name', placeholder: 'order.json', ok: 'Create' });
  if (!name) return;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) return toast('Use letters, digits, dot, dash and underscore.', true);
  if (pack(packId).templates.includes(name)) { state.edit.saved = src.value; await openTemplate(packId, name); return toast(name + ' already exists. It is open now.'); }
  showTemplate(packId, name, '', null);
  src.focus();
}
let gutterLines = 0;
function edited() {
  const text = src.value, e = state.edit;
  $('hl').innerHTML = highlight(text, langOf(e.name, text), Object.keys(pack(e.pack)?.fields || {})) + '\n';
  const lines = text.split('\n').length;
  if (lines !== gutterLines) { gutterLines = lines; $('gutter').textContent = Array.from({ length: lines }, (_, i) => i + 1).join('\n'); }
  src.disabled = !e.name;
  $('save').disabled = !dirty();
  $('deleteTemplate').disabled = !e.name || e.saved === null;
  $('insert').disabled = $('recognise').disabled = !e.name;
  const file = $('file'); file.replaceChildren();
  if (e.name) {
    file.append(el('span', 'dim', (pack(e.pack)?.name || e.pack) + ' /'), el('span', 'nm', e.name));
    if (dirty()) { const dot = el('i', 'unsaved'); dot.title = 'Not saved'; file.append(dot); }
  }
}
// createOnly: the server answers 409 if the name is taken, which on a file system that ignores case includes Order.json for order.json
const putTemplate = (packId, name, body, createOnly) => api(tplUrl(packId, name),
  { method: 'PUT', headers: { 'content-type': 'text/plain;charset=UTF-8', ...(createOnly ? { 'If-None-Match': '*' } : {}) }, body });
async function save() {
  const e = state.edit;
  if (!dirty()) return;
  try {
    const body = src.value;
    await putTemplate(e.pack, e.name, body, e.saved === null);   // null: a new template, never saved
    e.saved = body; edited(); await loadPacks(); renderPacks(); toast('Saved');
    if (e.pack === state.pack && e.name === state.name) { state.text = body; renderFields(); preview(); }   // the workbench shows this one
  } catch (err) { failed(err); }
}
async function deleteTemplate() {
  const e = state.edit;
  if (!await ask({ title: 'Delete template?', text: e.pack + ' / ' + e.name + ' is removed from disk.', ok: 'Delete', danger: true })) return;
  try {
    await api(tplUrl(e.pack, e.name), { method: 'DELETE' });
    state.edit = { pack: null, name: null, saved: '' }; src.value = '';
    await loadPacks(); renderPacks(); edited(); toast('Deleted');
  } catch (err) { failed(err); }
}
// A real message as a template: ids, times and whatever else has to differ per message become placeholders,
// and whole values that are one of the pack's field choices become that field. The result is an unsaved edit.
async function recognise() {
  const e = state.edit;
  if (!e.name) return;
  try {
    const found = await api('/recognise?pack=' + encodeURIComponent(e.pack) + '&fields=true', { method: 'POST', headers: { 'content-type': 'text/plain;charset=UTF-8' }, body: src.value });
    const n = found.replacements.length, fromPack = found.replacements.filter(r => r.field);
    if (found.template !== src.value) { src.value = found.template; edited(); }
    toast(n ? (n === 1 ? '1 value became a placeholder' : n + ' values became placeholders') + (fromPack.length ? ', ' + fromPack.length + ' from the pack' : '') : 'Nothing that should differ per message');
  } catch (err) { failed(err); }
}
const GENERATORS = [['uuid', 'UUID'], ['id', '32-char id'], ['now', 'UTC timestamp'], ['today', 'UTC date'], ['seq', 'counter'],
  ['int 1 100', 'whole number'], ['decimal 1 1000 2', 'decimal'], ['pick A B C', 'one of'], ['digits 6', 'digits'], ['alnum 8', 'letters and digits'], ['iban GB', 'IBAN']];
function fillInsertMenu() {
  const menu = $('insertMenu'); menu.replaceChildren();
  const add = (expr, hint) => menuItem(menu, `{{${expr}}}`, { cls: 'mono', small: hint, click: () => { src.focus(); src.setRangeText(`{{${expr}}}`, src.selectionStart, src.selectionEnd, 'end'); edited(); } });
  const fields = Object.keys(pack(state.edit.pack)?.fields || {});
  if (fields.length) { menu.append(el('h5', '', pack(state.edit.pack).name)); fields.forEach(f => add(f)); }
  if (state.generators.length) { menu.append(el('h5', '', 'Plugins')); state.generators.forEach(g => add(g)); }
  menu.append(el('h5', '', 'Generators')); GENERATORS.forEach(g => add(g[0], g[1]));
  const r = $('insert').getBoundingClientRect();
  menu.style.top = Math.min(r.bottom + 6, innerHeight - 420) + 'px'; menu.style.left = Math.max(8, Math.min(r.left, innerWidth - 372)) + 'px';
}

// packs: a name, a folder, and the fields its templates share
let packEditing = null;   // null while creating
function fieldRow(key = '', value = '') {
  const row = el('div', 'frow'), k = el('input'), v = el('input'), x = xButton('Remove field', () => row.remove());
  k.value = key; k.placeholder = 'name'; k.setAttribute('aria-label', 'Field name');
  v.value = value; v.placeholder = '{{pick A B C}}'; v.setAttribute('aria-label', 'Field value');
  x.className = 'iconbtn';
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
    await loadPacks(); $('packDialog').close(); renderPacks(); edited(); renderFields(); preview(); toast('Saved');
  } catch (err) { failed(err); }
}
async function deletePack() {
  const p = packEditing;
  if (!await ask({ title: 'Delete pack?', text: `${p.name} and its ${p.templates.length} template(s) are removed from disk.`, ok: 'Delete', danger: true })) return;
  try {
    await api('/packs/' + encodeURIComponent(p.id), { method: 'DELETE' });
    if (state.edit.pack === p.id) { state.edit = { pack: null, name: null, saved: '' }; src.value = ''; }
    $('packDialog').close(); await loadPacks(); renderPacks(); edited(); toast('Deleted');
  } catch (err) { failed(err); }
}

// presets: the saved scenarios. Editing one is the Load form with it filled in.
function renderPresetTable() {
  const card = $('presetTable');
  if (!state.scenarios.length) return card.replaceChildren(el('div', 'empty', 'No preset yet'));
  card.replaceChildren(table(['Name', 'Templates', 'Plan', 'Invalid', 'Checks', ''], state.scenarios.map(s => {
    const load = s.load || {}, parts = s.parts || [], acts = el('td', 'acts'), run = el('button', 'ghost'), edit = el('button', 'ghost', 'Edit'), del = el('button', 'ghost danger');
    const plan = [(s.rate ? rate(s.rate) + '/s' : 'as fast as possible'), s.count ? num(s.count) : load.durationSeconds ? load.durationSeconds + ' s' : load.unlimited ? 'until stopped' : ''].filter(Boolean).join(' · ');
    const checks = [parts.filter(p => p.expect).length ? new Set(parts.filter(p => p.expect).map(p => p.expect.destination)).size + ' queue' + (new Set(parts.filter(p => p.expect).map(p => p.expect.destination)).size > 1 ? 's' : '') : null, s.invalid?.expect ? 'rejects' : null].filter(Boolean).join(', ');
    run.innerHTML = icon('play'); run.append('Run'); run.onclick = () => api(`/scenarios/${s.id}/run`, { method: 'POST' }).then(r => { state.liveRun = r.id; state.live = null; fillPlan(s); go(s.count === 1 ? 'send' : 'load'); refresh(); }, failed);
    edit.onclick = () => { fillPlan(s); go('load'); };
    del.innerHTML = icon('trash'); del.setAttribute('aria-label', 'Delete ' + s.name); del.title = 'Delete';
    del.onclick = async () => { if (await ask({ title: 'Delete preset?', text: s.name, ok: 'Delete', danger: true })) api('/scenarios/' + s.id, { method: 'DELETE' }).then(loadScenarios, failed); };
    acts.append(run, edit, del);
    return [s.name, cell('', el('code', '', parts.map(p => tplParts(p.template)[0] + (parts.length > 1 ? ' ×' + (p.weight || 1) : '')).join(' · '))), plan,
      s.invalid?.percent ? s.invalid.percent + '%' + (s.invalid.kinds?.length && s.invalid.kinds.length < 5 ? ', ' + s.invalid.kinds.length + ' kinds' : ', all kinds') : '–', checks || el('td', 'dim', 'none'), acts];
  })));
}

// reject queues: the monitors as configuration
function renderMonitorTable() {
  const card = $('monitorTable');
  if (!state.monitors.length) return card.replaceChildren(el('div', 'empty', 'No queue is being watched'));
  card.replaceChildren(table(['Queue', 'Environment', 'Group by', 'State', ''], state.monitors.map(m => {
    const acts = el('td', 'acts'), saved = el('button', 'switch'), onoff = el('button', 'ghost', m.state === 'stopped' ? 'Start' : 'Stop');
    saved.setAttribute('role', 'switch'); saved.setAttribute('aria-checked', m.saved); saved.append(el('i', 't'), 'Saved'); saved.title = 'Keep it in msgforge.json: it then starts with msgforge';
    saved.onclick = () => monitorAct(m, '/saved', m.saved ? 'DELETE' : 'PUT');
    onoff.onclick = () => m.state === 'stopped' ? monitorAct(m, '/start', 'POST') : monitorAct(m, '', 'DELETE');
    acts.append(saved, onoff);
    const state_ = el('span', 'state plain ' + (m.state === 'up' ? 'up' : m.state === 'down' || m.error ? 'down' : ''), { up: 'Watching', down: 'Down', stopped: 'Stopped' }[m.state] || 'Starting');
    if (m.error) state_.title = m.error;
    const queue = el('td'); queue.append(el('code', '', showQ(m.connectionId, m.watch.destination))); if (m.name) queue.append(' ' + m.name);
    return [queue, conn(m.connectionId)?.title || pretty(m.watch.connection), cell('', el('code', '', m.watch.tables.map(t => t.name).join(' · ') || '–')), cell('', state_), acts];
  }), i => { state.monitor = state.monitors[i].id; go('rejects'); }));
}

// ---- paste a message: what msgforge recognises in it is shown beside it, and saved as a template
let pasteTimer, pasteAsked = 0, pasteTemplate = '';
function openPaste() {
  const packs = state.packs.filter(p => !p.error);
  if (!packs.length) return toast('Create a pack first.', true);
  $('pastePack').replaceChildren(...packs.map(p => new Option(p.name, p.id)));
  $('pastePack').value = packs.some(p => p.id === state.pack) ? state.pack : packs[0].id;
  $('pastePackBox').hidden = packs.length < 2;
  $('paste').value = ''; $('pasteName').value = ''; $('recog').replaceChildren(); $('found').textContent = ''; pasteTemplate = '';
  $('pasteDialog').showModal(); $('paste').focus();
}
// A file is a message like one pasted: its text goes in the box, and its name is the name offered.
async function pasteFrom(file) {
  if (!file) return;
  if (file.size > 1024 * 1024) return toast(file.name + ' is larger than 1 MB.', true);
  const text = await file.text();
  if (text.includes('\u0000') || text.includes('\ufffd')) return toast(file.name + ' is not text.', true);
  $('paste').value = text;
  const name = file.name.replace(/[^A-Za-z0-9._-]/g, '-').replace(/^[^A-Za-z0-9]+/, '');
  if (name) $('pasteName').value = name;
  recognisePaste();
}
async function recognisePaste() {
  const text = $('paste').value, packId = $('pastePack').value, asked = ++pasteAsked, found = $('found');
  if (!text.trim()) { $('recog').replaceChildren(); found.textContent = ''; pasteTemplate = ''; return; }
  try {
    const r = await api('/recognise?pack=' + encodeURIComponent(packId) + '&fields=true', { method: 'POST', headers: { 'content-type': 'text/plain;charset=UTF-8' }, body: text });
    if (asked !== pasteAsked) return;
    pasteTemplate = r.template;
    listing($('recog'), r.template.replace(/\n$/, ''), langOf(null, r.template), Object.keys(pack(packId)?.fields || {}));
    const n = r.replacements.length, fields = r.replacements.filter(x => x.field).length;
    found.className = 'found'; found.textContent = n ? (n === 1 ? '1 value recognised' : n + ' values recognised') + (fields ? ' · ' + fields + (fields === 1 ? ' matches' : ' match') + ' the pack\'s fields' : '') : 'Nothing recognised: it is saved as it is';
    if (!$('pasteName').value) {   // a name that is free, with the extension the text asks for
      const ext = { xml: 'xml', json: 'json', text: 'txt' }[langOf(null, text)], taken = pack(packId).templates;
      let name; for (let i = 1; taken.includes(name = 'message' + (i > 1 ? '-' + i : '') + '.' + ext); i++);
      $('pasteName').value = name;
    }
  } catch (err) { if (asked === pasteAsked) { found.className = 'found bad'; found.textContent = err.message; } }
}
async function savePaste(e) {
  e.preventDefault();
  const packId = $('pastePack').value, name = $('pasteName').value.trim();
  if (!$('paste').value.trim()) return toast('Paste a message first.', true);
  try {
    await putTemplate(packId, name, pasteTemplate || $('paste').value, true);
    $('pasteDialog').close(); await loadPacks(); await pickTemplate(packId, name); toast('Saved');
    if (state.mode === 'settings') { state.edit.saved = src.value; await openTemplate(packId, name); }
  } catch (err) { toast(err.status === 409 ? name + ' already exists: give it another name.' : err.message, true); }
}

// ---- keeping the page current
async function refresh() {
  try {
    const [runs, messages] = await Promise.all([api('/runs'), api('/messages')]);
    state.runs = runs; state.messages = messages;
    // A run opening its connection is how the console learns how the environment is doing, besides Test: nothing checks by itself.
    const busy = runs.length + ':' + runs.filter(going).length + ':' + (runs[0]?.id || '');
    if (busy !== state.busy) {
      state.busy = busy;
      api('/connections/status').then(s => { state.status = s; renderEnv(); if (state.mode === 'settings') renderEnvCards(); }, () => {});
    }
    // a single send that failed says why, once
    const mine = runs.find(r => r.id === state.sentRun);
    if (mine && !going(mine)) { state.sentRun = null; if (mine.status === 'FAILED') toast(mine.error, true); }
    if (state.mode === 'send') renderRecent();
    if (state.mode === 'reports') renderReports();
    const id = liveId();
    if (state.mode === 'load' && id && (!state.live || state.live.run.id !== id || going(state.live.run) || going(runs.find(r => r.id === id)))) {
      try { const d = await api('/runs/' + encodeURIComponent(id)); if (liveId() === id) state.live = d; } catch (_) {}
    }
    renderLive();
  } catch (_) { /* server restarting: keep the last view and try again */ }
}
function tick() { refresh(); loadMonitors().catch(() => {}); refreshOpen(); }

// ---- theme
function paintTheme() { $('theme').innerHTML = icon(document.documentElement.dataset.theme === 'light' ? 'moon' : 'sun'); }
$('theme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('theme', next); } catch (_) {}
  paintTheme();
};

// ---- wiring
menuOn($('env'), $('envMenu'), fillEnvMenu);
menuOn($('tpl'), $('tplMenu'), fillTemplateMenu);
$('insert').onclick = e => { e.stopPropagation(); const menu = $('insertMenu'); if (openMenu?.menu === menu) return closeMenu(); closeMenu(); fillInsertMenu(); menu.hidden = false; $('insert').setAttribute('aria-expanded', 'true'); openMenu = { menu, button: $('insert') }; menu.querySelector('button').focus(); };
$('editTpl').onclick = async () => { if (state.edit.name && !await leaveOk()) return; state.edit.saved = src.value; go('settings', 'templates'); openTemplate(state.pack, state.name); };
$('reroll').onclick = preview;
$('sendForm').addEventListener('submit', send);
$('sRaw').onclick = () => { state.send.raw = !state.send.raw; if (state.send.raw) $('rawText').value = state.rendered; renderRaw(); remember(); if (state.send.raw) $('rawText').focus(); };
$('sCheck').onclick = () => { state.send.check = !state.send.check; renderRaw(); remember(); };
$('sQueue').onchange = () => { state.send.queue = $('sQueue').value; remember(); };
$('sArrive').onchange = () => { state.send.arrive = $('sArrive').value; remember(); };
$('sHeaders').onchange = () => { state.send.headers = $('sHeaders').value; remember(); };
$('loadForm').addEventListener('submit', start);
$('stop').onclick = () => { const id = $('stop').dataset.run; if (id) api('/runs/' + id, { method: 'DELETE' }).then(refresh, failed); };
$('lInvalid').oninput = renderPlan;
$('lDuration').oninput = renderPlan;
$('lCheck').onclick = () => { state.plan.check = !state.plan.check; renderPlan(); if (state.plan.check) $('lReject').focus(); };
$('lUnlimited').onclick = () => { state.plan.unlimited = !state.plan.unlimited; renderPlan(); };
$('lAll').onclick = () => { state.plan.all = !state.plan.all; renderPlan(); };
$('savePreset').onclick = savePreset;
const firstPart = () => ({ pack: state.pack, template: state.name, weight: 1, vars: { ...state.vars }, toConn: state.env, to: $('lArrive').value });
$('mixStart').onclick = () => { if (!state.name) return toast('Pick a template first.', true); state.plan.parts = [firstPart(), { ...firstPart(), vars: {}, to: '' }]; state.plan.mix = true; renderPlan(); };
$('mixAdd').onclick = () => { state.plan.parts.push({ ...(state.plan.parts.at(-1) || firstPart()), weight: 1, vars: {}, to: '' }); renderPlan(); };
$('lTargetAdd').onclick = () => { state.plan.extra.push({ connection: state.env, destination: '' }); renderPlan(); };
$('lWatchAdd').onclick = () => { state.plan.watches.push({ connection: receivers().some(c => c.id === state.env) ? state.env : receivers()[0]?.id || state.env, destination: '', buckets: [] }); renderPlan(); };
$('newEnv').onclick = () => openConn(null);
$('cType').onchange = () => { $('cFields').querySelectorAll('input:not([type=password])').forEach(i => editing.settings[i.dataset.key] = i.value); renderTypeFields(); };
$('cDestInput').addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); flushDestination(); } });
$('connForm').addEventListener('submit', saveConnection);
$('cTest').onclick = testConnection;
$('cDelete').onclick = deleteConnection;
src.addEventListener('input', edited);
src.addEventListener('scroll', () => { $('hl').scrollTop = $('gutter').scrollTop = src.scrollTop; $('hl').scrollLeft = src.scrollLeft; });
let tabLeaves = false;   // Tab indents, which would trap keyboard users in the editor: Escape, then Tab, moves on
src.addEventListener('keydown', e => {
  if (e.key === 'Escape') { tabLeaves = true; return; }
  if (e.key === 'Tab' && !tabLeaves) { e.preventDefault(); src.setRangeText('  ', src.selectionStart, src.selectionEnd, 'end'); edited(); }
  if (e.key !== 'Shift') tabLeaves = false;
});
$('save').onclick = save;
$('deleteTemplate').onclick = deleteTemplate;
$('recognise').onclick = recognise;
$('newTpl').onclick = () => newTemplate();
$('newPack').onclick = () => openPack(null);
$('pasteBtn').onclick = openPaste;
$('pAdd').onclick = () => fieldRow().focus();
$('packForm').addEventListener('submit', savePack);
$('pDelete').onclick = deletePack;
$('newPreset').onclick = () => { fillPlan({}); go('load'); $('lRate').focus(); };
$('newMonitor').onclick = openMonitorForm;
$('monitorForm').addEventListener('submit', startMonitor);
$('mSave').onclick = () => toggle($('mSave'), !isOn($('mSave')));
$('paste').addEventListener('input', () => { clearTimeout(pasteTimer); pasteTimer = setTimeout(recognisePaste, 250); });
$('pastePack').onchange = recognisePaste;
$('pasteOpen').onclick = () => $('pasteFile').click();
$('pasteFile').onchange = () => { pasteFrom($('pasteFile').files[0]); $('pasteFile').value = ''; };
{
  const drop = $('pasteDialog').querySelector('.dsplit');
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); pasteFrom(e.dataTransfer.files[0]); });
}
$('sVars').onchange = typedVars; $('lVars').onchange = typedVars;
$('pasteForm').addEventListener('submit', savePaste);
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeMenu(true);
  if (!(e.metaKey || e.ctrlKey) || document.querySelector('dialog[open]')) return;
  if (e.key === 's' && state.mode === 'settings' && state.page === 'templates') { e.preventDefault(); save(); }
  if (e.key === 'Enter' && (state.mode === 'send' || state.mode === 'load')) { e.preventDefault(); $(state.mode === 'send' ? 'sendForm' : 'loadForm').requestSubmit(); }
});
window.addEventListener('beforeunload', e => { if (dirty()) e.preventDefault(); });

(async () => {
  paintTheme();
  if (!/Mac|iPhone|iPad/.test(navigator.platform)) document.querySelectorAll('kbd').forEach(k => k.textContent = k.textContent.replace('⌘', 'Ctrl+'));
  let kept = {};
  try { kept = JSON.parse(localStorage.getItem('console') || '{}') || {}; } catch (_) {}
  if (kept.send) Object.assign(state.send, { queue: String(kept.send.queue || ''), headers: String(kept.send.headers || ''), raw: false, check: !!kept.send.check, arrive: String(kept.send.arrive || '') });
  Object.assign(state, { env: kept.env || null, pack: kept.pack || null, name: kept.name || null, monitor: kept.monitor || null, page: ['envs', 'templates', 'presets', 'monitors'].includes(kept.page) ? kept.page : 'envs' });
  $('sQueue').value = state.send.queue; $('sHeaders').value = state.send.headers; $('sArrive').value = state.send.arrive;
  api('/console').then(about => {
    state.about = about;
    if (about.title) { $('brand').textContent = about.title; document.title = about.title; }
  }, () => {});
  // Each part of the page loads on its own: one that fails is reported and the rest still work.
  const wanted = state.pack && state.name ? [state.pack, state.name] : null; state.pack = state.name = null;
  const loads = await Promise.allSettled([loadConnections(), api('/packs').then(p => state.packs = p), loadScenarios(), api('/generators').then(g => state.generators = g)]);
  try {
    // Where this browser left off; on a first visit the template of the first preset, which is what the installation is about; else the first there is.
    const lead = state.scenarios[0]?.parts?.[0];
    if (wanted && pack(wanted[0])?.templates.includes(wanted[1])) await pickTemplate(wanted[0], wanted[1]);
    else if (lead && pack(lead.pack)?.templates.includes(lead.template)) await pickTemplate(lead.pack, lead.template);
    else await loadPacks();
  } catch (e) { loads.push({ reason: e }); }
  const problems = loads.filter(l => l.reason).map(l => l.reason.message);
  if (problems.length) toast(problems.join(' · '), true);
  renderTemplatePick(); renderPlan();
  go(['send', 'load', 'rejects', 'reports'].includes(kept.mode) ? kept.mode : 'send');
  await refresh(); loadMonitors().catch(() => {}); setInterval(tick, 1000);
  const linked = /^#run=([0-9a-f]{8})$/.exec(location.hash);   // a link to one run opens it
  if (linked) openRun(linked[1]);
})();
