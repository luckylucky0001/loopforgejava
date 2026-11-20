// LoopForge4J web console: dependency-free ES module. All data is rendered through textContent,
// never innerHTML, so run output and catalog text cannot inject markup.

const TOKEN_KEY = 'loopforge.token';
const app = document.getElementById('app');
let me = null;
let stream = null;

// ------------------------------------------------------------------ helpers

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

const svg = (tag, attrs = {}, ...children) => {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  for (const c of children.flat()) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
};

const token = () => localStorage.getItem(TOKEN_KEY);
const badge = (status, label) => h('span', { class: `badge s-${status}` }, label ?? status ?? '—');
const time = (value) => (value ? new Date(value).toLocaleString() : '—');
const json = (value) => JSON.stringify(value, null, 2);
/** Links that come from external systems: only http(s), never javascript: or data: URLs. */
const safeHref = (url) => (typeof url === 'string' && /^https?:\/\//i.test(url) ? url : undefined);

class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `HTTP ${status}`);
    this.status = status;
    this.problems = problem?.problems || [];
  }
}

async function api(path, { method = 'GET', body, raw } = {}) {
  const headers = {};
  if (token()) headers.Authorization = `Bearer ${token()}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 && token() && !path.startsWith('/auth/')) {
    localStorage.removeItem(TOKEN_KEY);
    me = null;
    route();
    throw new ApiError(401, { detail: '登录已过期，请重新登录' });
  }
  if (!response.ok) {
    let problem = null;
    try { problem = await response.json(); } catch { /* not json */ }
    throw new ApiError(response.status, problem);
  }
  if (raw) return response.text();
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function errorBox(error) {
  const lines = [error.message, ...(error.problems || []).map((p) => `• ${p}`)];
  return h('div', { class: 'error panel' }, lines.join('\n'));
}

async function attempt(action, after) {
  try {
    const result = await action();
    if (after) after(result);
    return result;
  } catch (error) {
    alert([error.message, ...(error.problems || [])].join('\n'));
    return undefined;
  }
}

function modal(title, body, actions) {
  const dialog = h('dialog', {});
  const extra = actions(dialog);
  dialog.append(h('h2', {}, title), body, h('div', { class: 'row mt' }, h('span', { class: 'spacer' }),
    h('button', { onclick: () => dialog.close() }, extra ? '取消' : '关闭'), extra));
  document.body.append(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.showModal();
  return dialog;
}

/** Saved revisions of a definition, with view and restore (restore is a new revision). */
async function showRevisions(base, onRestored) {
  const revisions = await api(`${base}/revisions`);
  const viewer = h('pre', { class: 'mono revision-view', hidden: true });
  const body = h('div', {}, revisions.length === 0 ? h('p', { class: 'muted' }, '还没有历史版本') :
    h('table', {}, h('tr', {}, ['版本', '时间', '作者', '变化', ''].map((t) => h('th', {}, t))),
      revisions.map((r, i) => h('tr', {}, h('td', { class: 'mono' }, `r${r.revision}`), h('td', {}, time(r.createdAt)), h('td', {}, r.author || '—'),
        h('td', {}, r.summary || ''),
        h('td', {}, h('div', { class: 'row' },
          h('button', { onclick: () => attempt(() => api(`${base}/revisions/${r.revision}`), (full) => { viewer.hidden = false; viewer.textContent = json(stripMeta(full.payload)); }) }, '查看'),
          i === 0 ? null : h('button', { onclick: () => confirm(`恢复到 r${r.revision}？当前内容会保留在历史里。`)
            && attempt(() => api(`${base}/revisions/${r.revision}/restore`, { method: 'POST' }), (restored) => { dialog.close(); onRestored(restored); }) }, '恢复')))))),
    viewer);
  const dialog = modal('历史版本', body, () => null);
}

/** Downloads an authenticated text response as a file. */
async function download(path, filename) {
  const text = await api(path, { raw: true });
  const link = h('a', { href: URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' })), download: filename });
  document.body.append(link);
  link.click();
  setTimeout(() => { URL.revokeObjectURL(link.href); link.remove(); }, 1000);
}

function prompted(title, fields, submit) {
  const inputs = {};
  const body = h('div', {}, fields.map((f) => {
    const input = f.type === 'textarea'
      ? h('textarea', { rows: f.rows || 6, placeholder: f.placeholder || '' }, f.value || '')
      : f.type === 'select'
        ? h('select', {}, f.options.map((o) => h('option', { value: o, selected: o === f.value }, o)))
        : h('input', { type: f.type || 'text', value: f.value || '', placeholder: f.placeholder || '' });
    inputs[f.name] = input;
    return h('label', { class: 'field' }, h('span', {}, f.label), input);
  }));
  modal(title, body, (dialog) => h('button', { class: 'primary', onclick: async () => {
    const values = Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.value]));
    try {
      await submit(values);
      dialog.close();
    } catch (error) {
      alert([error.message, ...(error.problems || [])].join('\n'));
    }
  } }, '确定'));
}

// ------------------------------------------------------------------ DAG

function layout(nodes, edges) {
  const incoming = new Map(nodes.map((n) => [n.id, []]));
  for (const e of edges) incoming.get(e.target)?.push(e.source);
  const depth = new Map();
  const visit = (id, guard = new Set()) => {
    if (depth.has(id)) return depth.get(id);
    if (guard.has(id)) return 0;
    guard.add(id);
    const d = Math.max(-1, ...(incoming.get(id) || []).map((s) => visit(s, guard))) + 1;
    depth.set(id, d);
    return d;
  };
  nodes.forEach((n) => visit(n.id));
  const columns = [];
  for (const n of nodes) (columns[depth.get(n.id)] ||= []).push(n);
  const pos = new Map();
  columns.forEach((col, x) => col.forEach((n, y) => pos.set(n.id, { x: 20 + x * 210, y: 20 + y * 74 })));
  return { pos, width: 40 + columns.length * 210, height: 40 + Math.max(1, ...columns.map((c) => c.length)) * 74 };
}

function dag(workflow, statuses = {}, onSelect) {
  const nodes = workflow.nodes || [];
  const edges = workflow.edges || [];
  const { pos, width, height } = layout(nodes, edges);
  const root = svg('svg', { width, height, viewBox: `0 0 ${width} ${height}` },
    svg('defs', {}, svg('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' },
      svg('path', { d: 'M0,0 L10,5 L0,10 z', fill: 'currentColor' }))));
  for (const e of edges) {
    const a = pos.get(e.source); const b = pos.get(e.target);
    if (!a || !b) continue;
    const x1 = a.x + 170; const y1 = a.y + 24; const x2 = b.x; const y2 = b.y + 24;
    root.append(svg('path', { class: 'edge', d: `M${x1},${y1} C${x1 + 30},${y1} ${x2 - 30},${y2} ${x2},${y2}` }));
  }
  for (const n of nodes) {
    const p = pos.get(n.id);
    const status = statuses[n.id];
    const g = svg('g', { class: `n-${status || 'none'}${onSelect ? ' clickable' : ''}`, transform: `translate(${p.x},${p.y})` },
      svg('rect', { width: 170, height: 48 }),
      svg('text', { x: 10, y: 20 }, (n.name || n.id).slice(0, 22)),
      svg('text', { x: 10, y: 37, opacity: 0.65 }, `${n.type}${status ? ' · ' + status : ''}`));
    if (onSelect) g.addEventListener('click', () => onSelect(n.id));
    root.append(g);
  }
  return h('div', { class: 'dag' }, root);
}

// ------------------------------------------------------------------ shell and routing

const NAV = [
  ['#/chat', '助手'], ['#/runs', '运行'], ['#/approvals', '待处理'], ['#/workflows', 'SOP'], ['#/agents', '角色'],
  ['#/assets/rules', '规则 / Skills / MCP'], ['#/environments/sandboxes', '沙箱环境'], ['#/repositories', '代码仓库'], ['#/projects', '项目'],
  ['#/analytics', '分析'], ['#/business', '业务域'], ['#/knowledge', '知识库'], ['#/runners', '执行机器'], ['#/executors', '执行器'], ['#/settings', '设置'],
];

function shell(content) {
  const current = location.hash || '#/runs';
  const nav = h('nav', { class: 'side' }, h('div', { class: 'brand' }, 'LoopForge4J'),
    NAV.map(([href, label]) => h('a', { href, class: current.startsWith(href.split('/').slice(0, 2).join('/')) ? 'active' : '' }, label)),
    me?.user?.role === 'admin' || me?.service ? h('a', { href: '#/users', class: current.startsWith('#/users') ? 'active' : '' }, '账户') : null,
    me?.user ? h('a', { href: '#/feedback', class: current.startsWith('#/feedback') ? 'active' : '' }, me.user.role === 'admin' ? '页面反馈' : '我的反馈') : null,
    h('div', { class: 'who' }, me?.user ? `${me.user.displayName || me.user.username}（${me.user.role}）` : me?.service ? '服务令牌' : '',
      h('br'), h('a', { href: '#', onclick: logout }, '退出登录')));
  app.replaceChildren(h('div', { class: 'layout' }, nav, h('main', {}, content)),
    me?.user ? h('button', { class: 'feedback-fab', title: '反馈这个页面的问题', onclick: () => openFeedback() }, '反馈') : null);
}

// ------------------------------------------------------------------ page feedback

const feedbackDraft = { title: '', description: '', element: null, images: [] };

/** The widget: title, description, a picked element and up to five pasted or chosen images. */
function openFeedback() {
  // picking an element closes the card: what was typed is kept until it is submitted
  const title = h('input', { placeholder: '一句话描述问题（必填，120 字以内）', maxlength: 120, value: feedbackDraft.title,
    oninput: (e) => { feedbackDraft.title = e.target.value; } });
  const description = h('textarea', { rows: 5, placeholder: '复现步骤、期望结果……（可选）',
    oninput: (e) => { feedbackDraft.description = e.target.value; } }, feedbackDraft.description);
  const picked = h('div', { class: 'muted' });
  const thumbs = h('div', { class: 'row wrap' });
  const message = h('div', { class: 'error' });
  const showPicked = () => picked.replaceChildren(feedbackDraft.element
    ? h('span', {}, '已选择元素：', h('code', {}, feedbackDraft.element.selector), ' ', h('button', { onclick: () => { feedbackDraft.element = null; showPicked(); } }, '清除'))
    : '未选择页面元素');
  const showThumbs = () => thumbs.replaceChildren(...feedbackDraft.images.map((file, i) => h('span', { class: 'thumb' },
    h('img', { src: URL.createObjectURL(file), alt: file.name || '图片' }),
    h('button', { onclick: () => { feedbackDraft.images.splice(i, 1); showThumbs(); } }, '×'))));
  const addFiles = (files) => {
    for (const file of files) {
      if (!file.type.startsWith('image/')) continue;
      if (feedbackDraft.images.length >= 5) { message.textContent = '最多 5 张图片'; break; }
      if (file.size > 5 * 1024 * 1024) { message.textContent = `${file.name || '图片'} 超过 5MB`; continue; }
      feedbackDraft.images.push(file);
    }
    showThumbs();
  };
  const chooser = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif,image/webp', multiple: true, onchange: (e) => addFiles(e.target.files) });
  const body = h('div', { class: 'feedback-form' },
    h('label', {}, '标题', title), h('label', {}, '描述', description),
    h('div', { class: 'row' }, h('button', { onclick: () => { dialog.close(); pickElement(); } }, '选择页面元素'), picked),
    h('div', {}, h('label', {}, '截图（可直接粘贴，最多 5 张，每张 5MB 以内）', chooser), thumbs),
    h('p', { class: 'muted' }, '会附上当前页面地址（去掉令牌等参数）、窗口大小与浏览器信息；不会包含你的登录凭据。'), message);
  body.addEventListener('paste', (e) => addFiles([...(e.clipboardData?.files || [])]));
  showPicked();
  showThumbs();
  const dialog = modal('反馈问题', body, (d) => h('button', { class: 'primary', onclick: async () => {
    message.textContent = '';
    if (!title.value.trim()) { message.textContent = '请填写标题'; return; }
    const form = new FormData();
    form.append('payload', new Blob([JSON.stringify({
      title: title.value.trim(), description: description.value.trim(), url: location.href, route: location.hash,
      view: document.querySelector('main h1')?.textContent || '', element: feedbackDraft.element || undefined,
      context: { scroll: { x: scrollX, y: scrollY }, viewport: { width: innerWidth, height: innerHeight }, dpr: devicePixelRatio,
        userAgent: navigator.userAgent, language: navigator.language, theme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
        capturedAt: new Date().toISOString() },
    })], { type: 'application/json' }));
    for (const file of feedbackDraft.images) form.append('images', file, file.name || 'screenshot.png');
    const response = await fetch('/api/v1/page-feedback', { method: 'POST', headers: { Authorization: `Bearer ${token()}` }, body: form });
    if (!response.ok) {
      let problem = null;
      try { problem = await response.json(); } catch { /* not json */ }
      message.textContent = problem?.detail || (response.status === 413 ? '提交内容过大，图片总大小请控制在 25MB 以内' : `提交失败（HTTP ${response.status}）`);
      return;
    }
    Object.assign(feedbackDraft, { title: '', description: '', element: null, images: [] });
    d.close();
    alert('感谢反馈，已提交');
  } }, '提交'));
  title.focus();
}

/** Picks an element of the page: hover highlights, click selects, Esc cancels. */
function pickElement() {
  const box = h('div', { class: 'pick-box' });
  const tip = h('div', { class: 'pick-tip' }, '点击要反馈的元素，Esc 取消');
  document.body.append(box, tip);
  let target = null;
  const move = (e) => {
    target = document.elementFromPoint(e.clientX, e.clientY);
    if (!target || target === box || target === tip) return;
    const r = target.getBoundingClientRect();
    Object.assign(box.style, { left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px` });
  };
  const done = () => {
    document.removeEventListener('mousemove', move, true);
    document.removeEventListener('click', click, true);
    document.removeEventListener('keydown', key, true);
    box.remove();
    tip.remove();
    openFeedback();
  };
  const click = (e) => {
    e.preventDefault();
    e.stopPropagation();
    const el = document.elementFromPoint(e.clientX, e.clientY);
    if (el) {
      const r = el.getBoundingClientRect();
      feedbackDraft.element = { selector: cssPath(el), tag: el.tagName, text: (el.innerText || '').slice(0, 200),
        rect: { x: r.left, y: r.top, width: r.width, height: r.height },
        pageRect: { x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height },
        point: { x: e.clientX, y: e.clientY }, inFrame: false };
    }
    done();
  };
  const key = (e) => { if (e.key === 'Escape') { e.preventDefault(); done(); } };
  document.addEventListener('mousemove', move, true);
  document.addEventListener('click', click, true);
  document.addEventListener('keydown', key, true);
}

/** A short CSS path: an id when there is one, otherwise tags with nth-of-type, at most five levels. */
function cssPath(el) {
  const parts = [];
  for (let node = el; node && node.nodeType === 1 && parts.length < 5; node = node.parentElement) {
    if (node.id) { parts.unshift(`#${CSS.escape(node.id)}`); break; }
    let part = node.tagName.toLowerCase();
    const siblings = node.parentElement ? [...node.parentElement.children].filter((c) => c.tagName === node.tagName) : [];
    if (siblings.length > 1) part += `:nth-of-type(${siblings.indexOf(node) + 1})`;
    parts.unshift(part);
  }
  return parts.join(' > ');
}

/** Feedback: administrators see everybody's, others their own. */
async function feedbackPage(offset = 0) {
  const page = await api(`/page-feedback?limit=20&offset=${offset}`);
  const detail = h('div', {});
  const show = async (f) => {
    const images = await Promise.all((f.images || []).map(async (img) => {
      const response = await fetch(img.url, { headers: { Authorization: `Bearer ${token()}` } });
      return response.ok ? h('a', { href: URL.createObjectURL(await response.blob()), target: '_blank', rel: 'noopener' },
        h('img', { class: 'feedback-image', alt: img.fileName || '图片' })) : null;
    }));
    images.filter(Boolean).forEach((a) => { a.firstChild.src = a.href; });
    detail.replaceChildren(h('div', { class: 'panel' }, h('h2', {}, f.title),
      h('p', { class: 'muted' }, `${f.submitter?.displayName || f.submitter?.username || '—'} · ${time(f.createdAt)}`),
      f.description ? h('pre', {}, f.description) : null,
      h('p', {}, '页面：', safeHref(f.url) ? h('a', { href: safeHref(f.url), target: '_blank', rel: 'noopener noreferrer' }, f.url) : (f.route || '—')),
      f.view ? h('p', {}, '视图：', f.view) : null,
      f.element ? h('p', {}, '元素：', h('code', {}, f.element.selector), f.element.text ? `（${f.element.text}）` : '') : null,
      h('p', { class: 'muted' }, `窗口 ${Math.round(f.context?.viewport?.width || 0)}×${Math.round(f.context?.viewport?.height || 0)} · ${f.context?.language || ''} · ${f.context?.userAgent || ''}`),
      h('div', { class: 'row wrap' }, images.filter(Boolean))));
  };
  shell([h('div', { class: 'row' }, h('h1', {}, me?.user?.role === 'admin' ? '页面反馈' : '我的反馈'), h('span', { class: 'spacer' }),
    h('button', { onclick: () => openFeedback() }, '反馈问题')),
  page.items.length === 0 ? h('p', { class: 'muted' }, '还没有反馈。每个页面右下角的「反馈」按钮可以提交问题与截图。') :
    h('table', {}, h('tr', {}, ['标题', '提交人', '页面', '图片', '时间'].map((t) => h('th', {}, t))),
      page.items.map((f) => h('tr', {}, h('td', {}, h('a', { href: '#', onclick: (e) => { e.preventDefault(); show(f); } }, f.title)),
        h('td', {}, f.submitter?.displayName || f.submitter?.username || '—'), h('td', { class: 'mono' }, f.route || '—'),
        h('td', {}, (f.images || []).length), h('td', {}, time(f.createdAt))))),
  h('div', { class: 'row mt' }, offset > 0 ? h('button', { onclick: () => feedbackPage(Math.max(0, offset - 20)) }, '上一页') : null,
    h('span', { class: 'muted' }, `共 ${page.total} 条`), page.hasMore ? h('button', { onclick: () => feedbackPage(offset + 20) }, '下一页') : null),
  detail]);
}

async function logout(event) {
  event?.preventDefault();
  try { await api('/auth/logout', { method: 'POST' }); } catch { /* already gone */ }
  localStorage.removeItem(TOKEN_KEY);
  me = null;
  location.hash = '#/runs';
  route();
}

const ROUTES = [
  [/^#\/runs\/([^/]+)\/workspace$/, workspacePage], [/^#\/runs\/([^/]+)$/, runPage], [/^#\/runs$/, runsPage], [/^#\/approvals$/, approvalsPage],
  [/^#\/workflows\/new$/, () => workflowPage(null)], [/^#\/workflows\/([^/]+)$/, workflowPage], [/^#\/workflows$/, workflowsPage],
  [/^#\/agents\/new$/, () => definitionPage('agents', null)], [/^#\/agents\/([^/]+)$/, (id) => definitionPage('agents', id)],
  [/^#\/agents$/, () => listPage('agents', '角色', ['id', 'name', 'executorId', 'projectId'])],
  [/^#\/assets\/(rules|skills|mcp-servers)\/new$/, (kind) => definitionPage(kind, null)],
  [/^#\/assets\/(rules|skills|mcp-servers)\/([^/]+)$/, (kind, id) => definitionPage(kind, id)],
  [/^#\/assets\/(rules|skills|mcp-servers)$/, assetsPage],
  [/^#\/environments\/(sandboxes|environments|dockerfiles)\/new$/, (kind) => definitionPage(kind, null)],
  [/^#\/environments\/(sandboxes|environments|dockerfiles)\/([^/]+)$/, (kind, id) => definitionPage(kind, id)],
  [/^#\/environments\/(sandboxes|environments|dockerfiles)$/, environmentsPage],
  [/^#\/repositories\/new$/, () => definitionPage('repositories', null)],
  [/^#\/repositories\/([^/]+)$/, (id) => definitionPage('repositories', id)],
  [/^#\/repositories$/, () => listPage('repositories', '代码仓库', ['id', 'name', 'provider', 'cloneUrl', 'projectId'])],
  [/^#\/projects\/([^/]+)\/requirements\/(\d+)\/clarify$/, clarifyPage], [/^#\/projects\/([^/]+)\/requirements\/(\d+)$/, requirementPage], [/^#\/projects\/([^/]+)$/, projectPage], [/^#\/projects$/, projectsPage],
  [/^#\/executors$/, executorsPage], [/^#\/settings$/, settingsPage], [/^#\/users$/, usersPage],
  [/^#\/analytics$/, analyticsPage], [/^#\/business$/, businessPage], [/^#\/knowledge$/, knowledgePage], [/^#\/runners$/, runnersPage],
  [/^#\/chat\/([^/]+)$/, chatPage], [/^#\/chat$/, () => chatPage(null)],
  [/^#\/feedback$/, feedbackPage],
];

const SSO_ERRORS = {
  state: '登录已过期或被打断，请重新登录', provider: '身份提供方拒绝了登录', token: '身份提供方返回的凭证无效',
  domain: '该邮箱域名不允许登录', account: '没有与该身份关联的账户，请联系管理员', disabled: '账户已停用',
};
let ssoError = '';
const SSO_NONCE = 'loopforge.sso.nonce';

/** Starts single sign-on from this tab; only this tab will accept the session that comes back. */
function startSso() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  const nonce = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  try { sessionStorage.setItem(SSO_NONCE, nonce); } catch { /* storage blocked: sign-in will be refused */ }
  location.href = `/api/v1/auth/oidc/start?client_nonce=${nonce}`;
}

async function route() {
  if (stream) { stream.close(); stream = null; }
  if (location.hash.startsWith('#/sso')) {
    // the session token arrives in the fragment; drop it from the address bar and history at once
    const params = new URLSearchParams(location.hash.split('?')[1] || '');
    history.replaceState(null, '', `${location.pathname}#/runs`);
    let expected = null;
    try { expected = sessionStorage.getItem(SSO_NONCE); sessionStorage.removeItem(SSO_NONCE); } catch { /* blocked */ }
    if (params.get('token') && expected && params.get('nonce') === expected) {
      localStorage.setItem(TOKEN_KEY, params.get('token'));
      me = null;
    } else if (params.get('token')) {
      // a session this tab did not ask for (a link someone sent): never switch accounts on it
      ssoError = '这个登录链接不是由本页面发起的，已忽略';
    } else {
      ssoError = SSO_ERRORS[params.get('error')] || '单点登录失败';
    }
  }
  if (!token()) return loginPage();
  if (!me) {
    try { me = await api('/me'); } catch (error) { if (error.status === 401) return loginPage(); }
  }
  const hash = location.hash || '#/runs';
  for (const [pattern, page] of ROUTES) {
    const match = hash.match(pattern);
    if (match) {
      shell(h('p', { class: 'muted' }, '加载中…'));
      try { await page(...match.slice(1).map(decodeURIComponent)); } catch (error) { shell(errorBox(error)); }
      return;
    }
  }
  location.hash = '#/runs';
}

window.addEventListener('hashchange', route);

// ------------------------------------------------------------------ login

async function loginPage() {
  let status = { setupRequired: false };
  try { status = await api('/auth/status'); } catch { /* server unreachable: show login */ }
  const username = h('input', { autocomplete: 'username', placeholder: '用户名' });
  const password = h('input', { type: 'password', autocomplete: status.setupRequired ? 'new-password' : 'current-password', placeholder: '密码（至少 10 位）' });
  const serviceToken = h('input', { type: 'password', placeholder: '或粘贴 API Token' });
  const message = h('div', { class: 'error' });
  const submit = async (event) => {
    event.preventDefault();
    message.textContent = '';
    try {
      if (serviceToken.value.trim()) {
        localStorage.setItem(TOKEN_KEY, serviceToken.value.trim());
      } else {
        const session = await api(status.setupRequired ? '/auth/setup' : '/auth/login', {
          method: 'POST', body: { username: username.value, password: password.value },
        });
        localStorage.setItem(TOKEN_KEY, session.token);
      }
      me = null;
      await route();
    } catch (error) {
      localStorage.removeItem(TOKEN_KEY);
      message.textContent = [error.message, ...(error.problems || [])].join('\n');
    }
  };
  app.replaceChildren(h('div', { class: 'login panel' },
    h('h1', {}, status.setupRequired ? '创建管理员账户' : '登录 LoopForge4J'),
    status.setupRequired ? h('p', { class: 'muted' }, '这是首次启动：第一个账户将成为管理员。') : null,
    h('form', { onsubmit: submit },
      h('label', { class: 'field' }, h('span', {}, '用户名'), username),
      h('label', { class: 'field' }, h('span', {}, '密码'), password),
      status.setupRequired ? null : h('label', { class: 'field' }, h('span', {}, '服务 Token（可选）'), serviceToken),
      message, h('button', { class: 'primary', type: 'submit' }, status.setupRequired ? '创建并登录' : '登录')),
    !status.setupRequired && status.oidc?.enabled
      ? h('div', { class: 'mt' }, h('button', { type: 'button', onclick: startSso }, `使用 ${status.oidc.name || 'SSO'} 登录`)) : null));
  if (ssoError) { message.textContent = ssoError; ssoError = ''; }
}

// ------------------------------------------------------------------ runs

async function runsPage() {
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const runs = await api(`/runs?limit=100${params.get('status') ? '&status=' + params.get('status') : ''}`);
  const workflows = await api('/workflows').catch(() => []);
  const projects = await api('/projects').catch(() => []);
  const machines = await api('/runners').catch(() => []);
  const workflowSelect = h('select', {}, workflows.filter((w) => w.status === 'published').map((w) => h('option', { value: w.id }, `${w.name}（${w.id}）`)));
  const runnerSelect = h('select', {}, h('option', { value: '' }, '在服务端执行'),
    machines.map((m) => h('option', { value: m.id }, `${m.name}${m.shared ? '（共享）' : ''}${m.online ? '' : ' · 离线'}`)));
  const projectSelect = h('select', {}, h('option', { value: '' }, '（无项目）'), projects.map((p) => h('option', { value: p.id }, p.name)));
  const input = h('textarea', { rows: 3, placeholder: '任务说明' });
  const repos = h('input', { placeholder: '仓库 id，逗号分隔（可选）' });
  const start = () => attempt(() => api('/runs', { method: 'POST', body: {
    workflowId: workflowSelect.value, input: input.value, projectId: projectSelect.value || undefined, runnerId: runnerSelect.value || undefined,
    repositories: repos.value.split(',').map((s) => s.trim()).filter(Boolean).map((repositoryId) => ({ repositoryId })),
  } }), (run) => { location.hash = `#/runs/${run.id}`; });
  shell([
    h('h1', {}, '运行'),
    h('div', { class: 'panel' }, h('div', { class: 'row' }, workflowSelect, projectSelect, runnerSelect, repos, h('button', { class: 'primary', onclick: start }, '启动')), h('div', { class: 'mt' }, input)),
    h('div', { class: 'row mb' }, ['', 'running', 'waiting', 'failed', 'succeeded', 'cancelled'].map((s) =>
      h('a', { class: 'btn', href: `#/runs${s ? '?status=' + s : ''}` }, s || '全部'))),
    h('table', {}, h('tr', {}, ['运行', 'SOP', '状态', '节点', 'Token', '创建'].map((t) => h('th', {}, t))),
      runs.map((r) => h('tr', {},
        h('td', {}, h('a', { href: `#/runs/${r.id}`, class: 'mono' }, r.id.slice(0, 16))),
        h('td', {}, r.workflowName || r.workflowId),
        h('td', {}, badge(r.status)),
        h('td', {}, Object.entries(r.nodes || {}).map(([id, s]) => h('span', { class: `badge s-${s} mr`, title: s }, id))),
        h('td', { class: 'mono' }, r.usage?.totalTokens ?? 0),
        h('td', {}, time(r.createdAt))))),
  ]);
}

async function approvalsPage() {
  const runs = await api('/runs?status=waiting&limit=200');
  const details = await Promise.all(runs.map((r) => api(`/runs/${r.id}`).catch(() => null)));
  const rows = [];
  for (const run of details.filter(Boolean)) {
    for (const node of Object.values(run.nodes || {})) {
      if (node.status === 'waiting_approval') rows.push({ run, node });
    }
  }
  shell([h('h1', {}, '待处理'),
    rows.length === 0 ? h('p', { class: 'muted' }, '没有等待人工处理的节点。') :
      h('table', {}, h('tr', {}, ['运行', '节点', '需要处理', '操作'].map((t) => h('th', {}, t))),
        rows.map(({ run, node }) => h('tr', {},
          h('td', {}, h('a', { href: `#/runs/${run.id}` }, run.workflowSnapshot?.name || run.workflowId), h('div', { class: 'muted mono' }, run.id)),
          h('td', {}, node.nodeId, h('div', { class: 'muted' }, node.activity || '')),
          h('td', {}, h('pre', {}, node.gateResult?.actionRequired || node.error || '')),
          h('td', {}, nodeActions(run, node, approvalsPage)))))]);
}

function nodeActions(run, node, refresh) {
  const post = (path, body) => attempt(() => api(`/runs/${run.id}${path}`, { method: 'POST', body }), refresh);
  const actions = [];
  if (node.status === 'waiting_approval') {
    actions.push(h('button', { class: 'primary', onclick: () => prompted('通过', [{ name: 'ctx', label: '补充说明（可选）', type: 'textarea' }],
      (v) => post(`/nodes/${node.nodeId}/approve`, { humanContext: v.ctx || undefined })) }, '通过'));
    const upstream = (run.workflowSnapshot?.nodes || []).map((n) => n.id).filter((id) => id !== node.nodeId);
    if (upstream.length) {
      actions.push(h('button', { onclick: () => prompted('打回', [
        { name: 'target', label: '返工节点', type: 'select', options: upstream, value: upstream[0] },
        { name: 'reason', label: '原因', type: 'textarea' }],
      (v) => post(`/nodes/${node.nodeId}/reject`, { targetId: v.target, reason: v.reason })) }, '打回'));
    }
    actions.push(h('button', { onclick: () => post(`/nodes/${node.nodeId}/decline-retry`) }, '不重试'));
  }
  if (node.status === 'failed') {
    actions.push(h('button', { onclick: () => post('/retry', { nodeId: node.nodeId }) }, '从此重试'));
    actions.push(h('button', { onclick: () => post(`/nodes/${node.nodeId}/pass`) }, '跳过'));
  }
  return h('div', { class: 'row' }, actions);
}

async function runPage(runId) {
  const render = async () => {
    const run = await api(`/runs/${runId}`);
    const artifacts = await api(`/runs/${runId}/artifacts`).catch(() => []);
    const statuses = Object.fromEntries(Object.values(run.nodes || {}).map((n) => [n.nodeId, n.status]));
    const control = (path, label, cls) => h('button', { class: cls, onclick: () => attempt(() => api(`/runs/${runId}/${path}`, { method: 'POST' }), render) }, label);
    const active = !['succeeded', 'failed', 'cancelled'].includes(run.status);
    const detail = h('div', {});
    const showNode = (id) => {
      const n = run.nodes[id];
      detail.replaceChildren(h('div', { class: 'panel' }, h('h2', {}, `节点 ${id}`),
        h('div', { class: 'row' }, badge(n.status), h('span', { class: 'muted' }, `尝试 ${n.attempt} · 返工 ${n.reworkCount} · ${n.provider || ''} ${n.model || ''}`)),
        n.activity ? h('p', {}, n.activity) : null, n.error ? h('div', { class: 'error' }, n.error) : null,
        n.gateResult ? h('pre', { class: 'mono' }, json(n.gateResult)) : null,
        n.output ? h('details', {}, h('summary', {}, '输出'), h('pre', { class: 'mono' }, n.output)) : null,
        nodeActions(run, n, render)));
    };
    const log = h('div', { class: 'log' });
    shell([
      h('div', { class: 'row' }, h('h1', {}, run.workflowSnapshot?.name || run.workflowId), badge(run.status), h('span', { class: 'spacer' }),
        active && run.status === 'running' ? control('pause', '暂停') : null,
        run.status === 'waiting' ? control('resume', '继续') : null,
        active ? control('cancel', '取消', 'danger') : null,
        run.status === 'failed' ? control('retry', '重试') : null,
        h('a', { class: 'btn', href: `#/runs/${encodeURIComponent(runId)}/workspace` }, '工作区'),
        h('button', { onclick: () => attempt(() => download(`/runs/${runId}/logs.txt`, `${runId}-logs.txt`)) }, '下载日志'),
        !active && !run.context?.parentRunId ? h('button', { class: 'danger', onclick: () => confirm('删除这次运行（含子运行、事件、产物与工作区）？')
          && attempt(() => api(`/runs/${runId}`, { method: 'DELETE' }), () => { location.hash = '#/runs'; }) }, '删除') : null),
      h('p', { class: 'muted mono' }, `${run.id} · ${run.context?.projectId ? '项目 ' + run.context.projectId + ' · ' : ''}${run.context?.triggeredBy || ''} · ${time(run.createdAt)}`),
      run.input ? h('details', { class: 'panel' }, h('summary', {}, '任务输入'), h('pre', {}, run.input)) : null,
      dag(run.workflowSnapshot, statuses, showNode),
      detail,
      (run.repositories || []).length ? h('div', { class: 'panel' }, h('h2', {}, '仓库'), h('table', {}, h('tr', {}, ['仓库', '基线', '工作分支', '目录'].map((t) => h('th', {}, t))),
        run.repositories.map((r) => h('tr', {}, h('td', {}, r.name), h('td', { class: 'mono' }, r.baseBranch), h('td', { class: 'mono' }, r.workBranch || '只读'), h('td', { class: 'mono' }, r.workspaceDir))))) : null,
      (run.deliveries || []).length ? h('div', { class: 'panel' }, h('h2', {}, '交付'), run.deliveries.map((p) =>
        h('div', { class: 'row' }, badge(p.state), h('a', { href: safeHref(p.url), target: '_blank', rel: 'noopener noreferrer' }, `#${p.number} ${p.headBranch} → ${p.baseBranch}`))),
        h('button', { onclick: () => attempt(() => api(`/runs/${runId}/deliveries/refresh`, { method: 'POST' }), render) }, '刷新 PR 状态')) : null,
      artifacts.length ? h('div', { class: 'panel' }, h('h2', {}, '产物'), h('table', {}, h('tr', {}, ['节点', '名称', '路径', '大小'].map((t) => h('th', {}, t))),
        artifacts.map((a) => h('tr', {}, h('td', {}, `${a.nodeId}#${a.attempt}`), h('td', {}, h('a', { href: '#', onclick: (e) => { e.preventDefault(); showArtifact(runId, a); } }, a.name)),
          h('td', { class: 'mono' }, a.path), h('td', {}, a.size))))) : null,
      run.workflowSnapshot?.preview?.enabled || (run.workflowSnapshot?.nodes || []).some((n) => n.type === 'preview') ? previewPanel(run) : null,
      browserPanel(`/runs/${encodeURIComponent(runId)}/browser`),
      active ? null : evalPanel(runId),
      h('h2', {}, '事件'), log,
    ]);
    const append = (event) => {
      const line = h('div', { class: `lv-${event.level}` }, `${new Date(event.timestamp).toLocaleTimeString()} ${event.nodeId ? '[' + event.nodeId + '] ' : ''}${event.type} ${event.message || ''}`);
      log.append(line);
      if (log.childElementCount > 2000) log.firstChild.remove();
      log.scrollTop = log.scrollHeight;
    };
    if (stream) stream.close();
    stream = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/stream?access_token=${encodeURIComponent(token())}`);
    let lastStatus = run.status;
    stream.addEventListener('event', (e) => append(JSON.parse(e.data)));
    stream.addEventListener('run', (e) => {
      const summary = JSON.parse(e.data);
      const changed = summary.status !== lastStatus || JSON.stringify(summary.nodes) !== JSON.stringify(statuses);
      lastStatus = summary.status;
      if (changed) { stream.close(); stream = null; render(); }
    });
  };
  await render();
}

async function showArtifact(runId, artifact) {
  const body = await api(`/runs/${runId}/artifacts/${artifact.id}`, { raw: true });
  modal(artifact.name, h('pre', { class: 'mono scroll' }, body), () => h('span'));
}

// ------------------------------------------------------------------ catalog

async function workflowsPage() {
  const workflows = await api('/workflows');
  shell([h('div', { class: 'row' }, h('h1', {}, 'SOP'), h('span', { class: 'spacer' }), h('a', { class: 'btn', href: '#/workflows/new' }, '新建'),
    h('button', { onclick: () => templateLibrary() }, '通用模板'),
    h('button', { onclick: exportCatalog }, '导出目录'), h('button', { onclick: importCatalog }, '导入目录')),
  h('table', {}, h('tr', {}, ['id', '名称', '状态', '节点', '修订', '项目'].map((t) => h('th', {}, t))),
    workflows.map((w) => h('tr', {}, h('td', {}, h('a', { href: `#/workflows/${w.id}`, class: 'mono' }, w.id)),
      h('td', {}, w.name, w.generic ? h('span', { class: 'chip' }, '通用模板') : null,
        (w.tags || []).filter((t) => t.startsWith('generic-source:')).map((t) => h('span', { class: 'chip muted' }, `来自 ${t.slice('generic-source:'.length)}`))),
      h('td', {}, badge(w.status)), h('td', {}, (w.nodes || []).length), h('td', {}, w.revision), h('td', {}, w.projectId || '—'))))]);
}

/** Generic SOP templates: a project's maintainers copy one (with its roles) into the project. */
async function templateLibrary() {
  const [templates, projects] = await Promise.all([api('/workflow-templates'), api('/projects').catch(() => [])]);
  let dialog = null;
  const body = h('div', {}, h('p', { class: 'muted' }, '通用模板不能直接运行。复制到项目后得到一份草稿 SOP，模板用到的角色也会复制到项目里，项目维护者可以自行调整；再次复制会复用项目里已有的角色副本，不覆盖本地修改。'),
    templates.length ? null : h('p', { class: 'muted' }, '还没有通用模板（管理员在 SOP 设置里把全局 SOP 标记为通用模板）。'),
    ...templates.map((t) => {
      const project = h('select', {}, projects.map((p) => h('option', { value: p.id }, p.name || p.id)));
      const adopt = () => prompted(`复制「${t.name}」到项目`, [
        { name: 'id', label: 'SOP id（留空 = 模板 id-项目 id）' }, { name: 'name', label: '名称（留空 = 模板名称）' }],
      (v) => api(`/workflow-templates/${encodeURIComponent(t.id)}/adopt`, { method: 'POST', body: { projectId: project.value, id: v.id || undefined, name: v.name || undefined } })
        .then((w) => { dialog?.close(); location.hash = `#/workflows/${encodeURIComponent(w.id)}`; return w; }));
      return h('div', { class: 'panel' }, h('div', { class: 'row' }, h('strong', {}, t.name), h('span', { class: 'chip' }, SOP_LANES[t.sopLane] || t.sopLane),
        t.preview ? h('span', { class: 'chip' }, '前端预览') : null, h('span', { class: 'mono muted' }, t.id),
        h('span', { class: 'spacer' }), project, h('button', { class: 'primary', disabled: projects.length === 0, onclick: adopt }, '复制到项目')),
      t.description ? h('p', { class: 'muted' }, t.description) : null,
      h('p', { class: 'muted' }, `${t.nodes} 个节点 · 角色：${(t.roles || []).map((r) => r.name).join('、') || '无'} · 沙箱 ${t.sandboxProfileId}`));
    }));
  dialog = modal('通用 SOP 模板', body, () => null);
}

async function exportCatalog() {
  const bundle = await api('/catalog/export');
  const blob = new Blob([json(bundle)], { type: 'application/json' });
  const link = h('a', { href: URL.createObjectURL(blob), download: 'loopforge-catalog.json' });
  document.body.append(link); link.click(); link.remove();
}

function importCatalog() {
  prompted('导入目录', [{ name: 'bundle', label: 'Bundle JSON', type: 'textarea', rows: 14 }, { name: 'overwrite', label: '覆盖已存在的定义', type: 'select', options: ['false', 'true'], value: 'false' }],
    async (v) => { const result = await api(`/catalog/import?overwrite=${v.overwrite}`, { method: 'POST', body: JSON.parse(v.bundle) }); alert(`已写入 ${result.written.length} 项`); return result; });
}

const TEMPLATE = {
  name: '新 SOP', status: 'draft',
  nodes: [{ id: 'build', type: 'script', name: '构建', script: 'echo build' }, { id: 'review', type: 'approval', name: '人工验收' }],
  edges: [{ source: 'build', target: 'review' }],
};

// ------------------------------------------------------------------ SOP designer

const NODE_W = 170;
const NODE_H = 52;
const NODE_LABELS = { agent: 'Agent', script: '脚本', approval: '人工审批', sop: '子 SOP', preview: '预览' };

/** Visual SOP editor: drag nodes, drag from a node's port to connect, edit properties on the right. */
function designer(wf, catalog, onChange) {
  let selected = null; // { kind: 'node' | 'edge', id }
  let drag = null;
  // Definitions written as JSON (or by the API) carry no or default {0,0} positions: lay those out
  // once on load, but never move nodes the user placed.
  const ensurePositions = () => {
    const seen = new Set();
    const unplaced = wf.nodes.some((n) => {
      const key = n.position ? `${n.position.x},${n.position.y}` : null;
      if (key === null || seen.has(key)) return true;
      seen.add(key);
      return false;
    });
    if (!unplaced) return;
    const { pos } = layout(wf.nodes, wf.edges);
    wf.nodes.forEach((n) => { n.position = { ...pos.get(n.id) }; });
  };
  const canvasHost = h('div', { class: 'dag designer-canvas', tabindex: '0' });
  const panel = h('div', { class: 'panel designer-panel' });
  const changed = () => { onChange(wf); render(); };
  const toSvg = (svgEl, event) => {
    const point = svgEl.createSVGPoint();
    point.x = event.clientX;
    point.y = event.clientY;
    return point.matrixTransform(svgEl.getScreenCTM().inverse());
  };
  const uniqueId = (base) => { let i = 1; let id = base; while (wf.nodes.some((n) => n.id === id)) id = `${base}-${++i}`; return id; };

  function render() {
    const maxX = Math.max(860, ...wf.nodes.map((n) => n.position.x + NODE_W + 60));
    const maxY = Math.max(480, ...wf.nodes.map((n) => n.position.y + NODE_H + 60));
    const root = svg('svg', { width: maxX, height: maxY, viewBox: `0 0 ${maxX} ${maxY}` },
      svg('defs', {}, svg('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' },
        svg('path', { d: 'M0,0 L10,5 L0,10 z', fill: 'currentColor' }))));
    const byId = new Map(wf.nodes.map((n) => [n.id, n]));
    for (const e of wf.edges) {
      const a = byId.get(e.source); const b = byId.get(e.target);
      if (!a || !b) continue;
      const x1 = a.position.x + NODE_W; const y1 = a.position.y + NODE_H / 2;
      const x2 = b.position.x; const y2 = b.position.y + NODE_H / 2;
      const d = `M${x1},${y1} C${x1 + 40},${y1} ${x2 - 40},${y2} ${x2},${y2}`;
      const isSel = selected?.kind === 'edge' && selected.id === edgeKey(e);
      const group = svg('g', { class: `edge-group${isSel ? ' selected' : ''}` },
        svg('path', { class: 'edge-hit', d }), svg('path', { class: 'edge', d }));
      group.addEventListener('pointerdown', (ev) => { ev.stopPropagation(); selected = { kind: 'edge', id: edgeKey(e) }; render(); });
      root.append(group);
    }
    for (const n of wf.nodes) {
      const isSel = selected?.kind === 'node' && selected.id === n.id;
      const g = svg('g', { class: `node t-${n.type}${isSel ? ' selected' : ''}`, transform: `translate(${n.position.x},${n.position.y})`, 'data-node': n.id },
        svg('rect', { width: NODE_W, height: NODE_H }),
        svg('text', { x: 10, y: 21 }, (n.name || n.id).slice(0, 20)),
        svg('text', { x: 10, y: 39, opacity: 0.65 }, `${NODE_LABELS[n.type] || n.type} · ${n.id}`.slice(0, 26)),
        svg('circle', { class: 'port', cx: NODE_W, cy: NODE_H / 2, r: 6 }));
      g.addEventListener('pointerdown', (ev) => {
        ev.stopPropagation();
        const p = toSvg(root, ev);
        if (ev.target.classList.contains('port')) {
          drag = { kind: 'connect', from: n.id, line: svg('path', { class: 'edge pending', d: '' }) };
          root.append(drag.line);
        } else {
          drag = { kind: 'move', id: n.id, dx: p.x - n.position.x, dy: p.y - n.position.y, moved: false };
          selected = { kind: 'node', id: n.id };
        }
        root.setPointerCapture(ev.pointerId);
      });
      root.append(g);
    }
    root.addEventListener('pointermove', (ev) => {
      if (!drag) return;
      const p = toSvg(root, ev);
      if (drag.kind === 'move') {
        const n = byId.get(drag.id);
        n.position = { x: Math.max(0, Math.round((p.x - drag.dx) / 10) * 10), y: Math.max(0, Math.round((p.y - drag.dy) / 10) * 10) };
        drag.moved = true;
        const g = root.querySelector(`g[data-node="${CSS.escape(drag.id)}"]`);
        g?.setAttribute('transform', `translate(${n.position.x},${n.position.y})`);
      } else {
        const a = byId.get(drag.from);
        const x1 = a.position.x + NODE_W; const y1 = a.position.y + NODE_H / 2;
        drag.line.setAttribute('d', `M${x1},${y1} L${p.x},${p.y}`);
      }
    });
    root.addEventListener('pointerup', (ev) => {
      const current = drag;
      drag = null;
      if (!current) return;
      if (current.kind === 'connect') {
        const p = toSvg(root, ev);
        const target = [...wf.nodes].reverse().find((n) => p.x >= n.position.x && p.x <= n.position.x + NODE_W && p.y >= n.position.y && p.y <= n.position.y + NODE_H);
        if (target && target.id !== current.from && !wf.edges.some((e) => e.source === current.from && e.target === target.id)) {
          wf.edges.push({ source: current.from, target: target.id });
          selected = { kind: 'edge', id: `${current.from}->${target.id}` };
        }
        changed();
        return;
      }
      if (current.moved) changed(); else render();
    });
    root.addEventListener('pointerdown', () => { selected = null; render(); });
    canvasHost.replaceChildren(root);
    renderPanel();
  }

  const edgeKey = (e) => `${e.source}->${e.target}`;

  function field(label, input) {
    return h('label', { class: 'field' }, h('span', {}, label), input);
  }

  function bind(input, apply) {
    input.addEventListener('change', () => { apply(input.value); changed(); });
    return input;
  }

  function select(value, options, apply) {
    return bind(h('select', {}, options.map(([v, l]) => h('option', { value: v, selected: (value ?? '') === v }, l))), apply);
  }

  function renderPanel() {
    if (selected?.kind === 'edge') {
      const e = wf.edges.find((x) => edgeKey(x) === selected.id);
      if (!e) { selected = null; return renderPanel(); }
      panel.replaceChildren(h('h2', {}, `连线 ${e.source} → ${e.target}`),
        field('条件（when）', bind(h('input', { value: e.when || 'success' }), (v) => { e.when = v.trim() || undefined; })),
        h('button', { class: 'danger', onclick: () => { wf.edges = wf.edges.filter((x) => x !== e); selected = null; changed(); } }, '删除连线'));
      return;
    }
    if (selected?.kind === 'node') {
      const n = wf.nodes.find((x) => x.id === selected.id);
      if (!n) { selected = null; return renderPanel(); }
      const others = wf.nodes.filter((x) => x.id !== n.id).map((x) => [x.id, `${x.name || x.id}（${x.id}）`]);
      const rename = (v) => {
        const next = v.trim();
        if (!next || next === n.id || wf.nodes.some((x) => x.id === next)) return;
        wf.edges.forEach((e) => { if (e.source === n.id) e.source = next; if (e.target === n.id) e.target = next; });
        wf.nodes.forEach((x) => { if (x.rejectTo === n.id) x.rejectTo = next; });
        n.id = next;
        selected = { kind: 'node', id: next };
      };
      const num = (key) => bind(h('input', { type: 'number', value: n[key] ?? '' }), (v) => { n[key] = v === '' ? undefined : Number(v); });
      const typeFields = {
        agent: [field('角色（agentId）', select(n.agentId, [['', '—'], ...catalog.agents.map((a) => [a.id, `${a.name || a.id}（${a.id}）`])], (v) => { n.agentId = v || undefined; })),
          field('执行器（覆盖角色）', select(n.executorId, [['', '按角色'], ...catalog.executors.map((x) => [x.id, x.name || x.id])], (v) => { n.executorId = v || undefined; })),
          field('任务说明（taskPrompt）', bind(h('textarea', { rows: 4 }, n.taskPrompt || ''), (v) => { n.taskPrompt = v || undefined; }))],
        script: [field('语言', select(n.scriptLanguage || 'shell', [['shell', 'shell'], ['python', 'python']], (v) => { n.scriptLanguage = v; })),
          field('脚本', bind(h('textarea', { rows: 6, class: 'mono' }, n.script || ''), (v) => { n.script = v; })),
          field('执行范围', select(n.executionScope || 'run', [['run', '运行工作区'], ['repositories', '每个代码仓库']], (v) => { n.executionScope = v; }))],
        approval: [],
        preview: [field('构建命令（可选）', bind(h('textarea', { rows: 4, class: 'mono', placeholder: '例如 npm ci && npm run build -- --base=./' }, n.script || ''), (v) => { n.script = v.trim() ? v : undefined; })),
          field('语言', select(n.scriptLanguage || 'shell', [['shell', 'shell'], ['python', 'python']], (v) => { n.scriptLanguage = v; })),
          h('div', { class: 'row' },
            field('站点目录', bind(h('input', { value: n.preview?.path || 'dist' }), (v) => { n.preview = { ...(n.preview || {}), path: v.trim() || 'dist' }; })),
            field('入口文件', bind(h('input', { value: n.preview?.index || 'index.html' }), (v) => { n.preview = { ...(n.preview || {}), index: v.trim() || 'index.html' }; }))),
          (() => {
            const box = h('input', { type: 'checkbox', checked: !!n.preview?.spa });
            box.addEventListener('change', () => { n.preview = { ...(n.preview || {}), spa: box.checked }; changed(); });
            return h('label', { class: 'row' }, box, '单页应用（找不到的路由回退到入口文件）');
          })(),
          (() => {
            const shot = h('input', { type: 'checkbox', checked: !!n.preview?.screenshot });
            shot.addEventListener('change', () => { n.preview = { ...(n.preview || {}), screenshot: shot.checked }; changed(); });
            return h('label', { class: 'row' }, shot, '归档后截图（服务器用无头浏览器打开入口页，结果显示在运行页）');
          })(),
          h('p', { class: 'muted' }, '站点用相对路径引用资源（例如 Vite 的 --base=./）；归档上限 2000 个文件、单文件 10 MB、合计 50 MB。')],
        sop: [field('子 SOP', select(n.sopWorkflowId, [['', '—'], ...catalog.workflows.filter((w) => w.id !== wf.id).map((w) => [w.id, `${w.name}（${w.id}）`])], (v) => { n.sopWorkflowId = v || undefined; }))],
      };
      const artifacts = bind(h('textarea', { rows: 3, placeholder: '每行一个：名称:路径，例如 design:docs/design.md' },
        (n.artifactOutputs || []).map((a) => `${a.name}:${a.path}`).join('\n')), (v) => {
        const list = v.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
          const i = line.indexOf(':');
          return i > 0 ? { name: line.slice(0, i).trim(), path: line.slice(i + 1).trim(), required: true } : { name: line, path: line, required: true };
        });
        n.artifactOutputs = list.length ? list : undefined;
      });
      panel.replaceChildren(h('h2', {}, `${NODE_LABELS[n.type] || n.type} 节点`),
        field('id', bind(h('input', { value: n.id }), rename)),
        field('名称', bind(h('input', { value: n.name || '' }), (v) => { n.name = v || undefined; })),
        ...(n.id === 'af-preview-build' ? [h('p', { class: 'muted' }, '系统步骤：由 SOP 的「前端预览」配置生成（执行器、模型、站点目录在 SOP 面板里改）；可以拖到流程中任意位置，关掉前端预览时自动移除。')] : (typeFields[n.type] || [])),
        n.type === 'approval' ? null : field('产物', artifacts),
        field('打回到（rejectTo）', select(n.rejectTo, [['', '—'], ...others], (v) => { n.rejectTo = v || undefined; })),
        field('失败时（onFailure）', select(n.onFailure, [['', '默认'], ['fail', '失败'], ['reject', '打回'], ['human', '转人工'], ['loop', '自修正']], (v) => { n.onFailure = v || undefined; })),
        h('div', { class: 'row' }, field('最多尝试', num('maxAttempts')), field('最多返工', num('maxReworks')), field('超时（秒）', num('timeoutSeconds'))),
        h('button', { class: 'danger', onclick: () => {
          wf.nodes = wf.nodes.filter((x) => x !== n);
          wf.edges = wf.edges.filter((e) => e.source !== n.id && e.target !== n.id);
          wf.nodes.forEach((x) => { if (x.rejectTo === n.id) delete x.rejectTo; });
          selected = null;
          changed();
        } }, '删除节点'));
      return;
    }
    panel.replaceChildren(h('h2', {}, 'SOP'),
      field('名称', bind(h('input', { value: wf.name || '' }), (v) => { wf.name = v; })),
      field('状态', select(wf.status || 'draft', [['draft', '草稿'], ['published', '已发布'], ['archived', '已归档']], (v) => { wf.status = v; })),
      field('项目（空 = 全局）', select(wf.projectId, [['', '全局'], ...catalog.projects.map((p) => [p.id, p.name])], (v) => { wf.projectId = v || undefined; })),
      field('沙箱', select(wf.sandboxProfileId || 'local', [['local', 'local（服务器进程）'], ['docker', 'docker'], ['bwrap', 'bwrap'],
        ...(catalog.sandboxes || []).map((p) => [p.id, `${p.name || p.id}（${p.driver}${p.available ? '' : '，不可用'}）`]),
        ...(['local', 'docker', 'bwrap'].includes(wf.sandboxProfileId || 'local') || (catalog.sandboxes || []).some((p) => p.id === wf.sandboxProfileId) ? [] : [[wf.sandboxProfileId, wf.sandboxProfileId]])],
      (v) => { wf.sandboxProfileId = v || undefined; })),
      field('描述', bind(h('textarea', { rows: 3 }, wf.description || ''), (v) => { wf.description = v || undefined; })),
      field('泳道标签', select(wf.sopLane || '', [['', '自动推断'], ...Object.entries(SOP_LANES)], (v) => { wf.sopLane = v || undefined; })),
      me?.user?.role === 'admin' || me?.service || wf.generic
        ? field('通用模板（只能被复制到项目，不能直接运行；需为全局 SOP 且只用共享角色）',
          select(wf.generic ? 'true' : 'false', [['false', '否'], ['true', '是']], (v) => { wf.generic = v === 'true'; }))
        : null,
      previewContractFields(),
      h('p', { class: 'muted' }, '拖动节点调整位置；从节点右侧的圆点拖到另一个节点即可连线；点选节点或连线后在这里编辑，Delete 键删除。'));
  }

  function previewContractFields() {
    const c = wf.preview || {};
    const set = (patch) => { wf.preview = { ...c, enabled: true, ...patch }; changed(); };
    const box = h('input', { type: 'checkbox', checked: !!c.enabled });
    box.addEventListener('change', () => {
      if (box.checked) wf.preview = { enabled: true, executorId: c.executorId || 'codebuddy', sourcePath: c.sourcePath || 'dist', indexFile: c.indexFile || 'index.html', spaFallback: !!c.spaFallback };
      else wf.preview = undefined;
      changed();
    });
    const head = h('label', { class: 'row' }, box, h('strong', {}, '前端预览'));
    if (!c.enabled) return h('div', { class: 'panel' }, head, h('p', { class: 'muted' }, '勾选后，保存时在流程末尾追加「构建静态站点」步骤：用 AI 产出静态站点并归档，运行页即可打开预览；工作区没有前端工程时自动跳过。'));
    const spa = h('input', { type: 'checkbox', checked: !!c.spaFallback });
    spa.addEventListener('change', () => set({ spaFallback: spa.checked }));
    const shot = h('input', { type: 'checkbox', checked: !!c.captureScreenshot });
    shot.addEventListener('change', () => set({ captureScreenshot: shot.checked || undefined }));
    const days = Math.round((c.retentionSec || 30 * 86400) / 86400);
    return h('div', { class: 'panel' }, head,
      field('构建执行器', select(c.executorId || 'codebuddy', [...new Set(['codebuddy', ...catalog.executors.map((x) => x.id)])].map((id) => [id, (catalog.executors.find((x) => x.id === id)?.name) || id]), (v) => set({ executorId: v }))),
      field('模型', bind(h('input', { value: c.model || '', placeholder: 'default' }), (v) => set({ model: v.trim() || undefined }))),
      h('div', { class: 'row' },
        field('站点目录', bind(h('input', { value: c.sourcePath || 'dist' }), (v) => set({ sourcePath: v.trim() || 'dist' }))),
        field('入口文件', bind(h('input', { value: c.indexFile || 'index.html' }), (v) => set({ indexFile: v.trim() || 'index.html' })))),
      h('label', { class: 'row' }, spa, '单页应用（前端路由回退到入口文件）'),
      h('label', { class: 'row' }, shot, '完成后截图（服务器上的无头浏览器打开入口页）'),
      field('保留天数（最多 30）', bind(h('input', { type: 'number', min: 1, max: 30, value: days }), (v) => set({ retentionSec: Math.min(30, Math.max(1, Number(v) || 30)) * 86400 }))));
  }

  canvasHost.addEventListener('keydown', (ev) => {
    if ((ev.key === 'Delete' || ev.key === 'Backspace') && selected) {
      if (selected.kind === 'node') {
        const id = selected.id;
        wf.nodes = wf.nodes.filter((x) => x.id !== id);
        wf.edges = wf.edges.filter((e) => e.source !== id && e.target !== id);
      } else {
        wf.edges = wf.edges.filter((e) => edgeKey(e) !== selected.id);
      }
      selected = null;
      ev.preventDefault();
      changed();
    }
  });
  const add = (type) => {
    const id = uniqueId(type === 'approval' ? 'review' : type);
    const y = Math.max(20, ...wf.nodes.map((n) => (n.position?.y || 0) + NODE_H + 30));
    const node = { id, type, name: NODE_LABELS[type], position: { x: 20, y: wf.nodes.length ? y : 20 } };
    if (type === 'script') node.script = 'echo ok';
    if (type === 'preview') node.preview = { path: 'dist', index: 'index.html', spa: false };
    wf.nodes.push(node);
    selected = { kind: 'node', id };
    changed();
  };
  const palette = h('div', { class: 'row mb' }, Object.entries(NODE_LABELS).map(([type, label]) => h('button', { onclick: () => add(type) }, `+ ${label}`)),
    h('span', { class: 'spacer' }),
    h('button', { onclick: () => { const { pos } = layout(wf.nodes, wf.edges); wf.nodes.forEach((n) => { n.position = { ...pos.get(n.id) }; }); changed(); } }, '自动布局'));
  ensurePositions();
  render();
  return h('div', {}, palette, h('div', { class: 'designer' }, canvasHost, panel));
}

async function workflowPage(id) {
  const [loaded, agents, executors, workflows, projects, sandboxes] = await Promise.all([
    id ? api(`/workflows/${id}`) : Promise.resolve(JSON.parse(JSON.stringify(TEMPLATE))),
    api('/agents').catch(() => []), api('/executors').catch(() => []), api('/workflows').catch(() => []), api('/projects').catch(() => []),
    api('/sandboxes').catch(() => []),
  ]);
  let wf = stripMeta(loaded);
  wf.nodes ||= [];
  wf.edges ||= [];
  const idInput = h('input', { value: id || '', placeholder: 'workflow id', disabled: !!id });
  const problems = h('div', {});
  const editor = h('textarea', { class: 'code', spellcheck: 'false' });
  const body = h('div', {});
  let mode = 'design';
  const catalog = { agents, executors, workflows, projects, sandboxes };
  const showDesign = () => {
    mode = 'design';
    body.replaceChildren(designer(wf, catalog, (next) => { wf = next; }));
    tabs.replaceChildren(tab('design', '设计'), tab('json', 'JSON'));
  };
  const showJson = () => {
    mode = 'json';
    editor.value = json(wf);
    const preview = h('div', {}, dag(wf));
    editor.oninput = () => {
      try { wf = JSON.parse(editor.value); preview.replaceChildren(dag(wf)); problems.replaceChildren(); } catch (error) {
        problems.replaceChildren(h('div', { class: 'error' }, `JSON 无效：${error.message}`));
      }
    };
    body.replaceChildren(h('div', { class: 'grid2' }, editor, preview));
    tabs.replaceChildren(tab('design', '设计'), tab('json', 'JSON'));
  };
  const tab = (key, label) => h('a', { href: '#', class: mode === key ? 'btn current' : 'btn', onclick: (e) => { e.preventDefault(); if (key === 'design') { try { wf = JSON.parse(editor.value || json(wf)); } catch { /* keep the last valid */ } showDesign(); } else showJson(); } }, label);
  const tabs = h('div', { class: 'row mb' });
  const current = () => ({ ...wf, id: id || idInput.value.trim() });
  const validate = () => attempt(() => api('/workflows/validate', { method: 'POST', body: current() }), (r) =>
    problems.replaceChildren(r.valid ? h('div', { class: 'notice' }, '校验通过') : h('div', { class: 'error' }, r.problems.join('\n'))));
  const save = () => attempt(() => api(`/workflows/${encodeURIComponent(id || idInput.value.trim())}`, { method: 'PUT', body: current() }),
    (saved) => { problems.replaceChildren(h('div', { class: 'notice' }, '已保存')); if (!id) location.hash = `#/workflows/${saved.id}`; });
  const remove = () => confirm(`删除 SOP ${id}？`) && attempt(() => api(`/workflows/${id}`, { method: 'DELETE' }), () => { location.hash = '#/workflows'; });
  const reload = () => workflowPage(id);
  const history = () => attempt(() => showRevisions(`/workflows/${encodeURIComponent(id)}`, reload));
  const publish = () => attempt(() => api(`/workflows/${encodeURIComponent(id)}/publish`, { method: 'POST' }), reload);
  const copy = () => prompted('复制 SOP', [{ name: 'id', label: '新 id', value: `${id}-copy` }, { name: 'name', label: '名称', value: `${loaded.name} 副本` }],
    (v) => api(`/workflows/${encodeURIComponent(id)}/clone`, { method: 'POST', body: v }).then((c) => { location.hash = `#/workflows/${encodeURIComponent(c.id)}`; return c; }));
  shell([h('div', { class: 'row' }, h('h1', {}, id ? `SOP ${loaded.name}` : '新建 SOP'),
    id ? h('span', { class: 'muted' }, `v${loaded.version} · r${loaded.revision} · ${loaded.status}`) : null, h('span', { class: 'spacer' }),
    idInput, h('button', { onclick: validate }, '校验'), h('button', { class: 'primary', onclick: save }, '保存'),
    id ? h('button', { onclick: publish }, '发布') : null, id ? h('button', { onclick: history }, '历史') : null, id ? h('button', { onclick: copy }, '复制') : null,
    id ? h('button', { class: 'danger', onclick: remove }, '删除') : null),
  tabs, problems, body]);
  showDesign();
}

function stripMeta(definition) {
  const { revision, createdAt, updatedAt, ...rest } = definition;
  return rest;
}

const KINDS = {
  agents: { title: '角色', path: '/agents', back: '#/agents', template: { name: '新角色', executorId: 'claude', prompt: '你负责……', rules: [], skills: [], mcpServers: [], hooks: [], verification: [], platformMcp: false, knowledgeBase: false } },
  rules: { title: '规则', path: '/rules', back: '#/assets/rules', template: { name: '新规则', content: '', priority: 0 } },
  skills: { title: 'Skill', path: '/skills', back: '#/assets/skills', template: { name: '新 Skill', description: '', content: '# Skill\n', files: {} } },
  sandboxes: { title: '沙箱配置', path: '/sandboxes', back: '#/environments/sandboxes', template: { name: '新沙箱', driver: 'docker', dockerfileId: '', environmentId: '', readOnlyRootFs: false, environment: {} } },
  environments: { title: '环境', path: '/environments', back: '#/environments/environments', template: { name: '新环境', variables: {}, pathEntries: ['.tools/bin'], installScript: '# 在工作区里安装工具，每个工作区只运行一次\n' } },
  dockerfiles: { title: 'Dockerfile', path: '/dockerfiles', back: '#/environments/dockerfiles', template: { name: '新镜像', content: 'FROM debian:bookworm-slim\nRUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*\n', files: {} } },
  'mcp-servers': { title: 'MCP 服务器', path: '/mcp-servers', back: '#/assets/mcp-servers', template: { name: '新 MCP', transport: 'stdio', command: '', args: [], environment: {} } },
  repositories: { title: '代码仓库', path: '/repositories', back: '#/repositories', template: { name: '', cloneUrl: 'https://github.com/owner/repo.git', defaultBranch: 'main' } },
};

async function listPage(kind, title, columns) {
  const items = await api(KINDS[kind].path);
  const base = KINDS[kind].back;
  shell([h('div', { class: 'row' }, h('h1', {}, title), h('span', { class: 'spacer' }), h('a', { class: 'btn', href: `${base}/new` }, '新建')),
    h('table', {}, h('tr', {}, columns.map((c) => h('th', {}, c))),
      items.map((item) => h('tr', {}, columns.map((c, i) => h('td', { class: i === 0 ? 'mono' : '' },
        i === 0 ? h('a', { href: `${base}/${encodeURIComponent(item.id)}` }, item.id) : String(item[c] ?? '—'))))))]);
}

/** Sandbox profiles (what SOPs select), their environments and Dockerfiles. */
async function environmentsPage(kind) {
  const tabs = h('div', { class: 'row mb' }, [['sandboxes', '沙箱配置'], ['environments', '环境'], ['dockerfiles', 'Dockerfile']].map(([k, label]) =>
    h('a', { class: k === kind ? 'btn current' : 'btn', href: `#/environments/${k}` }, label)));
  if (kind === 'sandboxes') {
    const items = await api('/sandboxes');
    shell([tabs, h('div', { class: 'row' }, h('h1', {}, '沙箱配置'), h('span', { class: 'spacer' }), h('a', { class: 'btn', href: '#/environments/sandboxes/new' }, '新建')),
      h('p', { class: 'muted' }, 'SOP 用 sandboxProfileId 选择：local（服务器进程）、native（bubblewrap 隔离）、docker（Dockerfile 构建的镜像或默认镜像）；环境里的变量、PATH 与安装脚本作用于每条命令。内置的 local / docker / bwrap 由部署配置。'),
      h('table', {}, h('tr', {}, ['id', '名称', '驱动', '镜像', '环境', '隔离', '可用'].map((t) => h('th', {}, t))),
        items.map((p) => h('tr', {}, h('td', { class: 'mono' }, h('a', { href: `#/environments/sandboxes/${encodeURIComponent(p.id)}` }, p.id)), h('td', {}, p.name),
          h('td', { class: 'mono' }, p.driver), h('td', { class: 'mono' }, p.dockerfileId || '—'), h('td', { class: 'mono' }, p.environmentId || '—'),
          h('td', {}, p.isolated ? '是' : '否'), h('td', {}, badge(p.available ? 'succeeded' : 'failed', p.available ? '可用' : '不可用')))))]);
    return;
  }
  await listPage(kind, KINDS[kind].title, kind === 'environments' ? ['id', 'name', 'description'] : ['id', 'name', 'description']);
  document.querySelector('main').prepend(tabs);
}

async function assetsPage(kind) {
  const tabs = h('div', { class: 'row mb' }, [['rules', '规则'], ['skills', 'Skills'], ['mcp-servers', 'MCP 服务器']].map(([k, label]) =>
    h('a', { class: k === kind ? 'btn current' : 'btn', href: `#/assets/${k}` }, label)));
  await listPage(kind, KINDS[kind].title, kind === 'rules' ? ['id', 'name', 'priority', 'projectId'] : kind === 'skills' ? ['id', 'name', 'description', 'projectId'] : ['id', 'name', 'transport', 'command']);
  document.querySelector('main').prepend(tabs);
  if (kind === 'mcp-servers') {
    document.querySelector('main h1')?.after(h('button', { onclick: () => mcpMarket() }, 'MCP 市场'));
  }
  if (kind === 'skills') {
    const status = await api('/skillhub/status').catch(() => ({ configured: false }));
    if (status.configured) {
      const installed = await api('/skillhub/installed').catch(() => []);
      document.querySelector('main h1')?.after(h('button', { onclick: () => skillMarket(installed) }, '从 SkillHub 安装'));
      if (installed.length) {
        document.querySelector('main').append(h('h2', {}, '来自 SkillHub'), h('table', {}, h('tr', {}, ['技能', '来源', '版本', '更新', ''].map((t) => h('th', {}, t))),
          installed.map((s) => h('tr', {}, h('td', { class: 'mono' }, s.skillId), h('td', { class: 'mono' }, `${s.namespace || ''}/${s.slug}`),
            h('td', { class: 'mono' }, s.version), h('td', {}, time(s.updatedAt)),
            h('td', {}, me?.user?.role === 'admin' || me?.service ? h('button', { onclick: () => prompted(`更新 ${s.skillId}`, [{ name: 'version', label: '版本（留空 = 最新）' }],
              (v) => api('/skillhub/update', { method: 'POST', body: { skillId: s.skillId, version: v.version || undefined } }).then(() => assetsPage('skills'))) }, '更新') : null)))));
      }
    }
  }
}

/** The MCP market: entries are added to a project by its maintainers; roles bind the market id. */
async function mcpMarket() {
  const [market, projects] = await Promise.all([api('/mcp-market'), api('/projects').catch(() => [])]);
  const body = h('div', {}, h('p', { class: 'muted' }, '把市场里的 MCP 添加到项目后，角色的 MCP 列表写市场 id（如 github）即可在该项目的运行里使用；GitHub 使用运行负责人在「设置」里的 GitHub Token，没有时使用平台的。'),
    ...market.items.map((item) => {
      const project = h('select', {}, projects.map((p) => h('option', { value: p.id }, p.name)));
      return h('div', { class: 'panel' }, h('div', { class: 'row' }, h('strong', {}, item.name), h('span', { class: 'chip' }, item.category), h('span', { class: 'mono muted' }, item.id),
        h('span', { class: 'spacer' }), project,
        h('button', { class: 'primary', disabled: projects.length === 0, onclick: () => attempt(() => api('/mcp-market/install', { method: 'POST', body: { marketId: item.id, projectId: project.value } }), () => alert('已添加')) }, '添加到项目')),
      h('p', { class: 'muted' }, item.description), h('p', { class: 'mono muted' }, item.url));
    }));
  modal('MCP 市场', body, () => null);
}

/** Search the SkillHub market and install a skill (administrators). */
function skillMarket(installed) {
  const keyword = h('input', { placeholder: '搜索技能' });
  const results = h('div', { class: 'market' });
  const search = () => attempt(() => api(`/skillhub/skills?pageSize=20&keyword=${encodeURIComponent(keyword.value.trim())}`), (page) => {
    const skills = page.skills || [];
    results.replaceChildren(...(skills.length ? skills.map((s) => {
      const ns = s.namespace?.handle || '';
      const done = installed.find((x) => x.slug === s.slug && (x.namespace || '') === ns);
      return h('div', { class: 'market-item' }, h('div', {}, h('strong', {}, s.name || s.slug), h('span', { class: 'muted mono' }, ` ${ns ? ns + '/' : ''}${s.slug} · v${s.version || '?'}`),
        h('div', { class: 'muted' }, s.description_zh || s.description || ''), h('div', { class: 'muted' }, `下载 ${num(s.downloads || 0)} · 收藏 ${num(s.stars || 0)}${s.verified ? ' · 已认证' : ''}`)),
      done ? h('span', { class: 'muted' }, `已安装为 ${done.skillId}`) : h('button', { onclick: () => prompted(`安装 ${s.name || s.slug}`, [
        { name: 'id', label: '技能 id', value: s.slug }, { name: 'version', label: '版本（留空 = 最新）' }],
      (v) => api('/skillhub/install', { method: 'POST', body: { slug: s.slug, namespace: ns || undefined, id: v.id, version: v.version || undefined } })
        .then((r) => { if (r.skipped?.length) alert(`已安装，跳过：\n${r.skipped.join('\n')}`); location.hash = `#/assets/skills/${encodeURIComponent(r.skill.id)}`; return r; })) }, '安装'));
    }) : [h('p', { class: 'muted' }, '没有找到技能')]));
  });
  keyword.addEventListener('keydown', (e) => { if (e.key === 'Enter') search(); });
  modal('SkillHub 技能市场', h('div', {}, h('div', { class: 'row' }, keyword, h('button', { class: 'primary', onclick: search }, '搜索')), results), () => null);
  search();
}

async function definitionPage(kind, id) {
  const meta = KINDS[kind];
  const value = id ? await api(`${meta.path}/${encodeURIComponent(id)}`) : meta.template;
  const idInput = h('input', { value: id || '', placeholder: 'id', disabled: !!id });
  const editor = h('textarea', { class: 'code', spellcheck: 'false' }, json(stripMeta(value)));
  const message = h('div', {});
  const save = () => attempt(() => api(`${meta.path}/${encodeURIComponent(id || idInput.value.trim())}`, { method: 'PUT', body: JSON.parse(editor.value) }),
    () => { message.replaceChildren(h('div', { class: 'notice' }, '已保存')); if (!id) location.hash = `${meta.back}/${encodeURIComponent(idInput.value.trim())}`; });
  const remove = () => confirm(`删除 ${id}？`) && attempt(() => api(`${meta.path}/${encodeURIComponent(id)}`, { method: 'DELETE' }), () => { location.hash = meta.back; });
  const history = () => attempt(() => showRevisions(`${meta.path}/${encodeURIComponent(id)}`, () => definitionPage(kind, id)));
  shell([h('div', { class: 'row' }, h('h1', {}, `${meta.title} ${id || '（新建）'}`), h('span', { class: 'spacer' }), idInput,
    h('button', { class: 'primary', onclick: save }, '保存'), id && kind === 'agents' ? h('button', { onclick: history }, '历史') : null,
    id ? h('button', { class: 'danger', onclick: remove }, '删除') : null),
  kind === 'mcp-servers' ? h('p', { class: 'muted' }, '敏感值写成 ${secret:NAME}，值配置在服务端 loopforge.mcp.secrets。') : null,
  ['agents', 'rules', 'skills'].includes(kind) ? h('p', { class: 'muted' }, 'projectId 留空为共享（管理员维护，所有项目可用）；填写项目 id 则只在该项目可用，由项目维护者管理，创建后不能更改所属项目。') : null,
  kind === 'repositories' ? h('p', { class: 'muted' }, '凭据配置在服务端（loopforge.git），不要写进克隆 URL。') : null,
  message, editor]);
}

async function executorsPage() {
  const executors = await api('/executors');
  const admin = me?.user?.role === 'admin' || me?.service;
  shell([h('h1', {}, '执行器'), h('table', {}, h('tr', {}, ['id', '名称', '类型', '命令', '模型', '可用'].map((t) => h('th', {}, t))),
    executors.map((e) => h('tr', {}, h('td', { class: 'mono' }, e.id), h('td', {}, e.name), h('td', {}, e.kind), h('td', { class: 'mono' }, e.binary || '内置'),
      h('td', {}, e.model || '默认'), h('td', {}, e.available === false ? badge('failed') : badge('succeeded'))))),
    admin ? managedCliPanel() : null]);
}

const MANAGED_STATUS = { updated: '已更新', unchanged: '已是最新', skipped: '跳过', failed: '失败', 'rolled-back': '已回滚', staged: '已暂存' };

/** Platform-managed agent CLIs (upstream managed CLIs): versions, last report, update and rollback. */
function managedCliPanel() {
  const panel = h('div', { class: 'panel' }, h('h2', {}, '托管 CLI'), h('p', { class: 'muted' }, '加载中…'));
  const load = () => api('/admin/managed-clis').then((v) => {
    if (!v.enabled) {
      panel.replaceChildren(h('h2', {}, '托管 CLI'), h('p', { class: 'muted' }, '未启用。开启 loopforge.managed-cli.enabled 后，平台从 npm 安装 Claude Code、Codex、CodeBuddy 的固定版本，每天检查更新（发布满 24 小时、校验 SHA-512 并探测所需参数后才启用），运行开始时锁定版本。'));
      return;
    }
    const rows = v.providers.map((p) => {
      const cur = v.current?.[p.name];
      const prev = v.previous?.[p.name];
      return h('tr', {}, h('td', {}, p.name), h('td', { class: 'mono' }, p.package), h('td', { class: 'mono' }, cur ? cur.version : '未安装'),
        h('td', { class: 'mono' }, prev ? prev.version : '—'), h('td', { class: 'mono' }, v.rejected?.[p.name] || ''),
        h('td', {}, h('button', { disabled: v.updating, onclick: () => attempt(() => api('/admin/managed-clis/update', { method: 'POST', body: { provider: p.name } }), () => setTimeout(load, 1500)) }, '更新'),
          prev ? h('button', { onclick: () => confirm(`把 ${p.name} 回滚到 ${prev.version}？当前版本之后不会再自动安装。`) && attempt(() => api(`/admin/managed-clis/${p.name}/rollback`, { method: 'POST' }), load) }, '回滚') : null));
    });
    const report = v.lastReport;
    panel.replaceChildren(h('div', { class: 'row' }, h('h2', {}, '托管 CLI'), h('span', { class: 'spacer' }),
      v.updating ? h('span', { class: 'muted' }, '更新中…') : null,
      h('button', { disabled: v.updating, onclick: () => attempt(() => api('/admin/managed-clis/update', { method: 'POST', body: {} }), () => setTimeout(load, 1500)) }, '全部更新'),
      h('button', { onclick: load }, '刷新')),
    h('p', { class: 'muted' }, `${v.dailyCheck ? '每日检查' : '已关闭每日检查'} · 下次 ${time(v.nextCheckAt)}${v.lastCheck ? ' · 上次 ' + time(v.lastCheck) : ''}`),
    h('table', {}, h('tr', {}, ['CLI', '包', '当前', '上一个', '已拒绝', ''].map((t) => h('th', {}, t))), rows),
    report ? h('div', { class: 'mt' }, h('strong', {}, `最近一次：${report.action} · ${time(report.finishedAt)}`), report.error ? h('div', { class: 'error' }, report.error) : null,
      h('ul', {}, report.results.map((r) => h('li', {}, `${r.provider}：${MANAGED_STATUS[r.status] || r.status}${r.to ? ` ${r.from || '—'} → ${r.to}` : ''}${r.message ? '（' + r.message + '）' : ''}`)))) : null);
    if (v.updating) setTimeout(() => { if (panel.isConnected) load(); }, 3000);
  }).catch((error) => panel.replaceChildren(h('h2', {}, '托管 CLI'), errorBox(error)));
  load();
  return panel;
}

// ------------------------------------------------------------------ projects

async function projectsPage() {
  const projects = await api('/projects');
  const create = () => prompted('新建项目', [{ name: 'id', label: 'id' }, { name: 'name', label: '名称' }, { name: 'description', label: '描述', type: 'textarea', rows: 3 }],
    (v) => api(`/projects/${encodeURIComponent(v.id)}`, { method: 'PUT', body: { name: v.name, description: v.description } }).then((p) => { location.hash = `#/projects/${p.id}`; return p; }));
  shell([h('div', { class: 'row' }, h('h1', {}, '项目'), h('span', { class: 'spacer' }), me?.user?.role === 'admin' || me?.service ? h('button', { class: 'primary', onclick: create }, '新建') : null),
    projects.length === 0 ? h('p', { class: 'muted' }, '还没有可见的项目。') :
      h('table', {}, h('tr', {}, ['id', '名称', '描述'].map((t) => h('th', {}, t))),
        projects.map((p) => h('tr', {}, h('td', {}, h('a', { href: `#/projects/${p.id}`, class: 'mono' }, p.id)), h('td', {}, p.name), h('td', { class: 'muted' }, p.description || ''))))]);
}

async function projectPage(projectId) {
  const project = await api(`/projects/${projectId}`);
  const [members, requirements, schedules, channels, workflows, tracker, syncLogs, repositories] = await Promise.all([
    api(`/projects/${projectId}/members`), api(`/projects/${projectId}/requirements`),
    api(`/projects/${projectId}/schedules`).catch(() => []), api(`/notifications?projectId=${projectId}`).catch(() => null),
    api('/workflows').catch(() => []), api(`/projects/${projectId}/tracker`).catch(() => null),
    api(`/projects/${projectId}/tracker/logs`).catch(() => []), api('/repositories').catch(() => []),
  ]);
  const refresh = () => projectPage(projectId);
  const sopOptions = workflows.filter((w) => w.status === 'published').map((w) => w.id);
  const newRequirement = () => prompted('新需求', [
    { name: 'title', label: '标题' }, { name: 'description', label: '描述', type: 'textarea' },
    { name: 'priority', label: '优先级', type: 'select', options: ['low', 'medium', 'high', 'urgent'], value: 'medium' },
    { name: 'workflowId', label: '默认 SOP', type: 'select', options: ['', ...sopOptions] },
    { name: 'repositoryIds', label: '仓库 id（逗号分隔）' }],
  (v) => api(`/projects/${projectId}/requirements`, { method: 'POST', body: { ...v, workflowId: v.workflowId || undefined,
    repositoryIds: v.repositoryIds.split(',').map((s) => s.trim()).filter(Boolean) } }).then(refresh));
  const startRequirement = (r) => prompted(`启动需求 #${r.number}`, [
    { name: 'workflowId', label: 'SOP', type: 'select', options: sopOptions, value: r.workflowId || sopOptions[0] },
    { name: 'input', label: '补充说明', type: 'textarea', rows: 3 }],
  (v) => api(`/projects/${projectId}/requirements/${r.number}/runs`, { method: 'POST', body: v }).then((run) => { location.hash = `#/runs/${run.id}`; return run; }));
  const SCHEDULE_KINDS = { sop: '运行 SOP', assistant: 'AI 任务（助手按提示词执行）', dispatch: '自动派发需求' };
  const scheduleCommon = () => [{ name: 'name', label: '名称' }, { name: 'cron', label: 'Cron（分 时 日 月 周）', placeholder: '0 3 * * 1-5' },
    { name: 'zone', label: '时区', value: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }];
  const scheduleFields = {
    sop: () => [{ name: 'workflowId', label: 'SOP', type: 'select', options: sopOptions }, { name: 'input', label: '输入', type: 'textarea', rows: 3 }],
    assistant: () => [{ name: 'prompt', label: '提示词（以你的身份和凭据、完全权限执行，不会等待确认）', type: 'textarea', rows: 6 }],
    dispatch: () => [{ name: 'statuses', label: '派发哪些状态的需求（逗号分隔）', value: 'todo' },
      { name: 'maxDispatch', label: '每次最多派发', type: 'number', value: '5' },
      { name: 'workflowId', label: 'SOP（留空 = 用需求自己绑定的）', type: 'select', options: ['', ...sopOptions] }],
  };
  const newSchedule = () => prompted('新定时任务', [{ name: 'kind', label: '类型', type: 'select', options: Object.keys(SCHEDULE_KINDS), value: 'sop' }],
    (k) => Promise.resolve(prompted(`新定时任务 · ${SCHEDULE_KINDS[k.kind]}`, [...scheduleCommon(), ...scheduleFields[k.kind]()], (v) => {
      const body = { name: v.name, cron: v.cron, zone: v.zone, kind: k.kind, enabled: true, skipIfRunning: true };
      if (k.kind === 'sop') Object.assign(body, { workflowId: v.workflowId, input: v.input });
      if (k.kind === 'assistant') body.prompt = v.prompt;
      if (k.kind === 'dispatch') Object.assign(body, { workflowId: v.workflowId || undefined, maxDispatch: Number(v.maxDispatch) || 5,
        dispatchStatuses: v.statuses.split(',').map((x) => x.trim()).filter(Boolean) });
      return api(`/projects/${projectId}/schedules`, { method: 'POST', body }).then(refresh);
    })));
  const scheduleTarget = (s) => (s.kind === 'assistant' ? `AI：${(s.prompt || '').slice(0, 40)}` : s.kind === 'dispatch'
    ? `派发 ${(s.dispatchStatuses || []).join('/')} → ${s.workflowId || '需求的 SOP'}（≤${s.maxDispatch}）` : s.workflowId);
  const newChannel = () => prompted('新通知', [
    { name: 'name', label: '名称' },
    { name: 'kind', label: '类型（webhook=通用 JSON；wecom/feishu/dingtalk/slack=群机器人）', type: 'select', options: ['webhook', 'wecom', 'feishu', 'dingtalk', 'slack'], value: 'webhook' },
    { name: 'url', label: 'URL（机器人填 Webhook 地址）' }, { name: 'secret', label: '签名密钥（可选；飞书/钉钉为加签密钥）', type: 'password' },
    { name: 'events', label: '事件（逗号分隔，留空=全部）', placeholder: 'run.failed,run.waiting,run.succeeded' },
    { name: 'report', label: 'AI 整体汇报（运行成功时由 AI 总结成果与结论，以运行发起人的凭据生成；失败时发送标准消息）', type: 'select', options: ['false', 'true'], value: 'false' },
    { name: 'instruction', label: '汇报要求（可选）', type: 'textarea', rows: 3, placeholder: '简洁说明需求的最终成果、验证结论和后续注意事项。' },
    { name: 'executorId', label: '汇报执行器（留空 = 助手的执行器）' }, { name: 'model', label: '模型（可选）' }],
  (v) => api('/notifications', { method: 'POST', body: { projectId, name: v.name, kind: v.kind, url: v.url, secret: v.secret || undefined, enabled: true,
    events: v.events.split(',').map((s) => s.trim()).filter(Boolean),
    report: v.report === 'true' ? { enabled: true, instruction: v.instruction || '', executorId: v.executorId || undefined, model: v.model || undefined } : undefined } }).then(refresh));
  const issueRepos = repositories.filter((r) => !r.projectId || r.projectId === projectId).map((r) => r.id);
  const editTracker = () => prompted('外部需求同步', [
    { name: 'issueRepositoryIds', label: `同步 Issue 的仓库（逗号分隔；可选：${issueRepos.join(', ') || '无'}）`, value: (tracker?.issueRepositoryIds || []).join(',') },
    { name: 'tapdWorkspaceId', label: 'TAPD 项目 ID（workspace）', value: tracker?.tapdWorkspaceId || '' },
    { name: 'tapdIterationId', label: 'TAPD 迭代 ID', value: tracker?.tapdIterationId || '' },
    { name: 'autoSync', label: '定时同步', type: 'select', options: ['true', 'false'], value: String(tracker?.autoSync ?? true) },
    { name: 'writeBack', label: '交付后回写 PR 链接', type: 'select', options: ['true', 'false'], value: String(tracker?.writeBack ?? true) }],
  (v) => api(`/projects/${projectId}/tracker`, { method: 'PUT', body: { ...v, autoSync: v.autoSync === 'true', writeBack: v.writeBack === 'true',
    issueRepositoryIds: v.issueRepositoryIds.split(',').map((x) => x.trim()).filter(Boolean) } }).then(refresh));
  const syncNow = () => attempt(() => api(`/projects/${projectId}/tracker/sync`, { method: 'POST' }), refresh);
  const importTapd = () => prompted('导入 TAPD 需求 / 缺陷 / 任务', [{ name: 'url', label: '粘贴详情链接（可带标题）', type: 'textarea', rows: 3 }],
    (v) => api(`/projects/${projectId}/tracker/import-tapd`, { method: 'POST', body: v }).then(refresh));
  const pushRequirement = (r) => prompted(`推送需求 #${r.number}`, [{ name: 'target', label: '目标', type: 'select', options: [...issueRepos, 'tapd'] }],
    (v) => api(`/projects/${projectId}/requirements/${r.number}/external`, { method: 'POST', body: v }).then(refresh));
  const addMember = () => prompted('添加成员', [{ name: 'user', label: '用户名' },
    { name: 'role', label: '角色', type: 'select', options: ['viewer', 'developer', 'maintainer', 'owner'], value: 'developer' }],
  (v) => api(`/projects/${projectId}/members/${encodeURIComponent(v.user)}`, { method: 'PUT', body: { role: v.role } }).then(refresh));

  shell([
    h('h1', {}, project.name), h('p', { class: 'muted' }, project.description || ''),
    businessDomainRow(project, refresh),
    h('div', { class: 'row' }, h('h2', {}, '需求'), h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: newRequirement }, '新需求')),
    h('table', {}, h('tr', {}, ['#', '标题', '状态', '优先级', 'SOP', '最近运行', '操作'].map((t) => h('th', {}, t))),
      requirements.map((r) => h('tr', {}, h('td', {}, r.number), h('td', {}, h('a', { href: `#/projects/${encodeURIComponent(projectId)}/requirements/${r.number}` }, r.title),
        r.external ? h('div', { class: 'muted' }, h('a', { href: safeHref(r.external.url), target: '_blank', rel: 'noopener noreferrer' },
          `${r.external.source} ${r.external.kind}${r.external.number ? ' #' + r.external.number : ' ' + r.external.externalId}`), ` · ${r.external.state || ''}`) : null),
        h('td', {}, badge(r.status)), h('td', {}, r.priority),
        h('td', { class: 'mono' }, r.workflowId || '—'), h('td', {}, r.latestRunId ? h('a', { href: `#/runs/${r.latestRunId}`, class: 'mono' }, r.latestRunId.slice(0, 14)) : '—'),
        h('td', {}, h('div', { class: 'row' }, h('a', { class: 'btn', href: `#/projects/${encodeURIComponent(projectId)}/requirements/${r.number}/clarify` }, '澄清'),
          h('button', { onclick: () => startRequirement(r), disabled: ['closed', 'cancelled', 'done'].includes(r.status) }, '启动'),
          r.external ? null : h('button', { onclick: () => pushRequirement(r) }, '推送')))))),
    playbooksSection(projectId, workflows.filter((w) => w.status === 'published'), refresh),
    projectMcpSection(projectId, refresh),
    h('div', { class: 'row' }, h('h2', {}, '外部需求同步'), h('span', { class: 'spacer' }),
      h('button', { onclick: importTapd }, '导入 TAPD 链接'), h('button', { onclick: syncNow }, '立即同步'), h('button', { onclick: editTracker }, '设置')),
    h('p', { class: 'muted' }, tracker && (tracker.issueRepositoryIds.length || tracker.tapdWorkspaceId)
      ? `Issue 仓库：${tracker.issueRepositoryIds.join(', ') || '无'} · TAPD：${tracker.tapdWorkspaceId ? `${tracker.tapdWorkspaceId} / 迭代 ${tracker.tapdIterationId}` : '未配置'} · ${tracker.autoSync ? '定时同步' : '手动同步'}`
      : '未配置：可从 GitHub / GitLab（工蜂）Issue 与 TAPD 迭代拉取需求。'),
    syncLogs.length === 0 ? null : h('table', {}, h('tr', {}, ['时间', '来源', '结果', '新增', '更新', '移除', '说明'].map((t) => h('th', {}, t))),
      syncLogs.slice(0, 8).map((l) => h('tr', {}, h('td', {}, time(l.at)), h('td', { class: 'mono' }, l.source),
        h('td', {}, badge(l.status === 'ok' ? 'succeeded' : 'failed')), h('td', {}, l.created), h('td', {}, l.updated), h('td', {}, l.removed),
        h('td', { class: 'muted' }, l.message)))),
    h('div', { class: 'row' }, h('h2', {}, '定时任务'), h('span', { class: 'spacer' }), h('button', { onclick: newSchedule }, '新建')),
    h('table', {}, h('tr', {}, ['名称', 'Cron', '内容', '下次', '上次', '操作'].map((t) => h('th', {}, t))),
      schedules.map((s) => h('tr', {}, h('td', {}, s.name, s.enabled ? null : h('span', { class: 'muted' }, '（停用）')), h('td', { class: 'mono' }, `${s.cron} ${s.zone}`), h('td', {}, scheduleTarget(s), s.runAs ? h('div', { class: 'muted' }, `执行身份 ${s.runAs}`) : null),
        h('td', {}, time(s.nextRunAt)), h('td', {}, s.lastRunId ? h('a', { href: `#/runs/${s.lastRunId}` }, time(s.lastRunAt)) : '—', s.lastError ? h('div', { class: 'error' }, s.lastError) : null),
        h('td', {}, h('div', { class: 'row' }, h('button', { onclick: () => attempt(() => api(`/projects/${projectId}/schedules/${s.id}/run`, { method: 'POST' }), refresh) }, '立即运行'),
          h('button', { onclick: () => attempt(() => api(`/projects/${projectId}/schedules/${s.id}/executions`), (rows) => modal(`执行记录 · ${s.name}`,
            rows.length === 0 ? h('p', { class: 'muted' }, '还没有执行过') : h('table', {}, h('tr', {}, ['时间', '触发', '结果', '运行', '说明'].map((t) => h('th', {}, t))),
              rows.map((e) => h('tr', {}, h('td', {}, time(e.firedAt)), h('td', {}, e.trigger === 'cron' ? '定时' : `手动 · ${e.actor || ''}`),
                h('td', {}, badge(e.status === 'started' ? (e.runStatus || 'deleted') : e.status === 'skipped' ? 'waiting' : e.status)),
                h('td', {}, (e.runIds || []).length ? e.runIds.map((id) => h('div', {}, h('a', { href: `#/runs/${id}`, class: 'mono' }, id.slice(0, 14)))) : '—'),
                h('td', { class: 'muted' }, e.sessionId ? h('div', {}, h('a', { href: `#/chat/${e.sessionId}` }, '查看对话')) : null,
                  e.reply ? h('div', { class: 'msg-body' }, e.reply.slice(0, 600)) : null, e.error ? h('div', { class: 'error' }, e.error) : null,
                  e.runStatus === null && e.runId ? '运行已删除' : '')))), () => null)) }, '记录'),
          h('button', { onclick: () => attempt(() => api(`/projects/${projectId}/schedules/${s.id}`, { method: 'PUT', body: { ...s, enabled: !s.enabled } }), refresh) }, s.enabled ? '停用' : '启用'),
          h('button', { class: 'danger', onclick: () => confirm('删除定时任务？') && attempt(() => api(`/projects/${projectId}/schedules/${s.id}`, { method: 'DELETE' }), refresh) }, '删除')))))),
    channels === null ? null : [
      h('div', { class: 'row' }, h('h2', {}, '通知'), h('span', { class: 'spacer' }), h('button', { onclick: newChannel }, '新建')),
      h('table', {}, h('tr', {}, ['名称', '类型', 'URL', '事件', '最近投递', '操作'].map((t) => h('th', {}, t))),
        channels.map((c) => h('tr', {}, h('td', {}, c.name, c.report?.enabled ? h('span', { class: 'chip', title: c.report.instruction || '' }, 'AI 汇报') : null), h('td', {}, c.kind || 'webhook'), h('td', { class: 'mono' }, c.url), h('td', {}, (c.events || []).join(', ') || '全部'),
          h('td', {}, c.lastStatus ? `${c.lastStatus}（${time(c.lastDeliveryAt)}）` : '—'),
          h('td', {}, h('button', { class: 'danger', onclick: () => confirm('删除通知？') && attempt(() => api(`/notifications/${c.id}`, { method: 'DELETE' }), refresh) }, '删除')))))],
    h('div', { class: 'row' }, h('h2', {}, '成员'), h('span', { class: 'spacer' }), h('button', { onclick: addMember }, '添加')),
    h('table', {}, h('tr', {}, ['用户', '角色', '操作'].map((t) => h('th', {}, t))),
      members.map((m) => h('tr', {}, h('td', {}, m.username), h('td', {}, m.role),
        h('td', {}, h('button', { class: 'danger', onclick: () => confirm(`移除 ${m.username}？`) && attempt(() => api(`/projects/${projectId}/members/${m.userId}`, { method: 'DELETE' }), refresh) }, '移除'))))),
  ]);
}

// ------------------------------------------------------------------ settings and accounts

async function settingsPage() {
  if (me?.service) {
    shell([h('h1', {}, '设置'), h('p', { class: 'muted' }, '当前使用服务 Token 登录；个人 Token 与密码需要账户登录。')]);
    return;
  }
  const [tokens, credentials, kinds, myServers] = await Promise.all([api('/me/tokens'), api('/me/credentials'), api('/me/credentials/kinds'), api('/me/mcp-servers').catch(() => [])]);
  const refresh = () => settingsPage();
  const parseVariables = (text) => Object.fromEntries(text.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const i = l.indexOf('=');
    return i > 0 ? [l.slice(0, i).trim(), l.slice(i + 1).trim()] : [l, ''];
  }));
  const editExecutor = (existing) => prompted(existing ? `模型凭据 · ${existing.kind.slice(9)}` : '添加模型凭据', [
    existing ? null : { name: 'kind', label: '执行器', type: 'select', options: kinds.executors, value: 'claude-code' },
    { name: 'apiKey', label: existing ? 'API Key（留空 = 保留已保存的）' : 'API Key', type: 'password' },
    { name: 'baseUrl', label: 'Base URL（模型网关，可选）', value: existing?.baseUrl || '' },
    { name: 'variables', label: `其他变量（每行 NAME=值，可选；${kinds.variables}）${existing?.variables?.length ? '；留空 = 保留：' + existing.variables.join(', ') : ''}`, type: 'textarea', rows: 3 },
  ].filter(Boolean), (v) => {
    const body = { baseUrl: v.baseUrl };
    if (v.apiKey) body.apiKey = v.apiKey;
    if (v.variables.trim()) body.variables = parseVariables(v.variables);
    return api(`/me/credentials/${encodeURIComponent(existing ? existing.kind : `executor:${v.kind}`)}`, { method: 'PUT', body }).then(refresh);
  });
  const editGit = (existing) => prompted(existing ? `Git 凭据 · ${existing.kind.slice(4)}` : '添加 Git 凭据', [
    existing ? null : { name: 'host', label: '主机（如 github.com、git.code.tencent.com）' },
    { name: 'token', label: existing ? 'Token（留空 = 保留已保存的）' : 'Token', type: 'password' },
    { name: 'username', label: '用户名（可选，默认 x-access-token）', value: existing?.username || '' },
  ].filter(Boolean), (v) => {
    const body = { username: v.username };
    if (v.token) body.token = v.token;
    return api(`/me/credentials/${encodeURIComponent(existing ? existing.kind : `git:${v.host.trim().toLowerCase()}`)}`, { method: 'PUT', body }).then(refresh);
  });
  const editSsh = (existing) => prompted(existing ? `Git SSH 密钥 · ${existing.kind.slice(4)}` : '添加 Git SSH 密钥', [
    existing ? null : { name: 'host', label: 'SSH 主机（如 github.com；每个主机一把密钥）' },
    { name: 'privateKey', label: existing ? '私钥（留空 = 保留已保存的）' : '私钥（OpenSSH 或 PEM，无口令；建议单独生成部署用密钥）', type: 'textarea', rows: 8 },
  ].filter(Boolean), (v) => {
    const body = {};
    if (v.privateKey) body.privateKey = v.privateKey;
    return api(`/me/credentials/${encodeURIComponent(existing ? existing.kind : `ssh:${v.host.trim().toLowerCase()}`)}`, { method: 'PUT', body })
      .then((saved) => { refresh(); if (!existing) showPublicKey(saved); return saved; });
  });
  const showPublicKey = (c) => modal(`公钥 · ${c.kind.slice(4)}`, h('div', {},
    h('p', {}, '把这把公钥添加到代码托管平台（账户的 SSH Keys 或仓库的 Deploy Keys）：'),
    h('pre', { class: 'mono wrap' }, c.publicKey || ''),
    c.trusted === false ? h('div', { class: 'error panel' }, `平台还不信任 ${c.kind.slice(4)} 的主机公钥，SSH 连接会被拒绝：请管理员把它加入 loopforge.git.ssh-known-hosts-file，或改用 HTTPS 地址与 Git Token。`) : null), () => null);
  const createToken = () => prompted('新建个人 Token', [{ name: 'name', label: '名称' }, { name: 'days', label: '有效天数（留空=永久）', type: 'number' }],
    async (v) => {
      const expiresAt = v.days ? new Date(Date.now() + Number(v.days) * 86400000).toISOString() : undefined;
      const issued = await api('/me/tokens', { method: 'POST', body: { name: v.name, expiresAt } });
      modal('Token 已创建', h('div', {}, h('p', {}, '只显示这一次，请立即保存：'), h('pre', { class: 'mono' }, issued.token)), () => h('span'));
      refresh();
      return issued;
    });
  const changePassword = () => prompted('修改密码', [{ name: 'current', label: '当前密码', type: 'password' }, { name: 'next', label: '新密码（至少 10 位）', type: 'password' }],
    async (v) => { await api('/me/password', { method: 'POST', body: { currentPassword: v.current, newPassword: v.next } }); alert('密码已修改，请重新登录'); logout(); return true; });
  shell([h('h1', {}, '设置'),
    h('div', { class: 'panel' }, h('div', { class: 'row' }, h('strong', {}, me.user.username), h('span', { class: 'muted' }, me.user.email || ''), h('span', { class: 'spacer' }),
      h('button', { onclick: changePassword }, '修改密码'))),
    h('div', { class: 'row' }, h('h2', {}, '个人凭据'), h('span', { class: 'spacer' }),
      h('button', { onclick: () => editExecutor(null) }, '添加模型凭据'), h('button', { onclick: () => editGit(null) }, '添加 Git 凭据'),
      h('button', { onclick: () => editSsh(null) }, '添加 Git SSH 密钥')),
    h('p', { class: 'muted' }, '你启动的运行、你创建的定时任务、你在助手里的发言和你发起的需求澄清使用这里的凭据；某个执行器配置了个人凭据时完全替代平台的凭据，Git Token 与 SSH 密钥按主机替代。SSH 私钥只在每条命令运行期间通过 SSH agent 提供，不会写进工作区。密钥加密保存，任何接口都不会返回。'),
    credentials.length === 0 ? h('p', { class: 'muted' }, '未配置：使用平台管理员配置的凭据。') :
      h('table', {}, h('tr', {}, ['类型', 'Key / Token', 'Base URL / 用户名', '其他变量', '更新', ''].map((t) => h('th', {}, t))),
        credentials.map((c) => h('tr', {}, h('td', { class: 'mono' }, c.kind,
          c.trusted === false ? h('span', { class: 'chip', title: '平台不信任该主机的公钥，SSH 会被拒绝' }, '主机未受信任') : null),
          h('td', { class: 'mono' }, c.hint || '—'),
          h('td', { class: 'mono' }, c.baseUrl || c.username || '—'), h('td', { class: 'mono' }, (c.variables || []).join(', ') || '—'), h('td', {}, time(c.updatedAt)),
          h('td', {}, h('div', { class: 'row' }, c.kind.startsWith('ssh:') ? h('button', { onclick: () => showPublicKey(c) }, '公钥') : null,
            h('button', { onclick: () => (c.kind.startsWith('git:') ? editGit(c) : c.kind.startsWith('ssh:') ? editSsh(c) : editExecutor(c)) }, '修改'),
            h('button', { class: 'danger', onclick: () => confirm(`删除 ${c.kind}？`) && attempt(() => api(`/me/credentials/${encodeURIComponent(c.kind)}`, { method: 'DELETE' }), refresh) }, '删除')))))),
    h('div', { class: 'row' }, h('h2', {}, '我的 MCP'), h('span', { class: 'spacer' }),
      h('button', { onclick: () => prompted('添加个人 MCP（HTTP）', [{ name: 'name', label: '名称' }, { name: 'url', label: 'URL（https://…）' }, { name: 'description', label: '说明' }],
        (v) => api('/me/mcp-servers', { method: 'POST', body: v }).then(refresh)) }, '添加')),
    h('p', { class: 'muted' }, '只在你负责的运行里、角色绑定了它时使用；密钥不能写在请求头里。'),
    myServers.length === 0 ? null : h('table', {}, h('tr', {}, ['id', '名称', 'URL', ''].map((t) => h('th', {}, t))),
      myServers.map((m) => h('tr', {}, h('td', { class: 'mono' }, m.id), h('td', {}, m.name), h('td', { class: 'mono' }, m.url),
        h('td', {}, h('button', { class: 'danger', onclick: () => attempt(() => api(`/me/mcp-servers/${encodeURIComponent(m.id)}`, { method: 'DELETE' }), refresh) }, '删除'))))),
    h('div', { class: 'row' }, h('h2', {}, 'Token'), h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: createToken }, '新建个人 Token')),
    h('table', {}, h('tr', {}, ['名称', '类型', '前缀', '创建', '最近使用', '过期', ''].map((t) => h('th', {}, t))),
      tokens.map((t) => h('tr', {}, h('td', {}, t.name), h('td', {}, t.kind), h('td', { class: 'mono' }, `${t.prefix}…`), h('td', {}, time(t.createdAt)),
        h('td', {}, time(t.lastUsedAt)), h('td', {}, time(t.expiresAt)),
        h('td', {}, h('button', { class: 'danger', onclick: () => attempt(() => api(`/me/tokens/${t.id}`, { method: 'DELETE' }), refresh) }, '吊销')))))]);
}

async function usersPage() {
  const users = await api('/users');
  const refresh = () => usersPage();
  const create = () => prompted('新账户', [{ name: 'username', label: '用户名' }, { name: 'displayName', label: '显示名' }, { name: 'email', label: '邮箱' },
    { name: 'password', label: '初始密码（至少 10 位）', type: 'password' }, { name: 'role', label: '角色', type: 'select', options: ['member', 'admin'], value: 'member' }],
  (v) => api('/users', { method: 'POST', body: v }).then(refresh));
  const update = (u, change) => attempt(() => api(`/users/${u.id}`, { method: 'PUT', body: change }), refresh);
  shell([h('div', { class: 'row' }, h('h1', {}, '账户'), h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: create }, '新账户')),
    h('table', {}, h('tr', {}, ['用户名', '显示名', '角色', '状态', '创建', '操作'].map((t) => h('th', {}, t))),
      users.map((u) => h('tr', {}, h('td', { class: 'mono' }, u.username), h('td', {}, u.displayName), h('td', {}, u.role), h('td', {}, u.disabled ? badge('cancelled') : badge('succeeded')),
        h('td', {}, time(u.createdAt)),
        h('td', {}, h('div', { class: 'row' },
          h('button', { onclick: () => update(u, { role: u.role === 'admin' ? 'member' : 'admin' }) }, u.role === 'admin' ? '设为成员' : '设为管理员'),
          h('button', { onclick: () => update(u, { disabled: !u.disabled }) }, u.disabled ? '启用' : '停用'),
          h('button', { onclick: () => prompted(`重置 ${u.username} 的密码`, [{ name: 'password', label: '新密码', type: 'password' }], (v) => api(`/users/${u.id}`, { method: 'PUT', body: { password: v.password } })) }, '重置密码'),
          h('button', { class: 'danger', onclick: () => confirm(`删除 ${u.username}？`) && attempt(() => api(`/users/${u.id}`, { method: 'DELETE' }), refresh) }, '删除'))))))]);
}

// ------------------------------------------------------------------ analytics

const analyticsState = { projectId: '', workflowId: '', days: '30', grain: 'day' };
const pct = (v) => `${Math.round((v || 0) * 1000) / 10}%`;
const ms = (v) => (!v ? '—' : v < 60000 ? `${Math.round(v / 100) / 10}s` : v < 3600000 ? `${Math.round(v / 6000) / 10}min` : `${Math.round(v / 360000) / 10}h`);
const num = (v) => (v || 0).toLocaleString();
const usd = (v) => `$${(v || 0).toFixed(2)}`;

function statTile(label, value, detail) {
  return h('div', { class: 'tile' }, h('div', { class: 'tile-label' }, label), h('div', { class: 'tile-value' }, value),
    detail ? h('div', { class: 'tile-detail muted' }, detail) : null);
}

/** One-series column chart: thin columns with rounded data ends, a hit target per bucket, native tooltips. */
function columnChart(points, value, describe) {
  const width = 760; const height = 180; const top = 10; const bottom = 24; const left = 44;
  const plot = height - top - bottom;
  const raw = Math.max(2, ...points.map(value));
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  // a round, even top for the axis (2, 4, 6, 8, 10 × 10^n) so the middle tick is a whole number too
  const max = [2, 4, 6, 8, 10].map((m) => m * magnitude).find((m) => m >= raw);
  const step = (width - left - 8) / Math.max(points.length, 1);
  const barWidth = Math.max(3, Math.min(28, step - 2));
  const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, class: 'chart', role: 'img' });
  for (const tick of [0, 0.5, 1]) {
    const y = top + plot - plot * tick;
    root.append(svg('line', { x1: left, x2: width - 8, y1: y, y2: y, class: 'grid' }),
      svg('text', { x: left - 6, y: y + 4, class: 'axis', 'text-anchor': 'end' }, num(Math.round(max * tick))));
  }
  points.forEach((p, i) => {
    const v = value(p);
    const x = left + i * step + (step - barWidth) / 2;
    const barHeight = plot * (v / max);
    const y = top + plot - barHeight;
    const g = svg('g', { class: 'bucket' }, svg('title', {}, describe(p)));
    g.append(svg('rect', { x: left + i * step, y: top, width: step, height: plot, class: 'hit' }));
    if (v > 0) {
      const r = Math.min(4, barWidth / 2, barHeight);
      g.append(svg('path', { class: 'bar', d: `M${x},${top + plot} V${y + r} Q${x},${y} ${x + r},${y} H${x + barWidth - r} Q${x + barWidth},${y} ${x + barWidth},${y + r} V${top + plot} Z` }));
    }
    if (points.length <= 16 || i % Math.ceil(points.length / 10) === 0) {
      g.append(svg('text', { x: left + i * step + step / 2, y: height - 6, class: 'axis', 'text-anchor': 'middle' }, p.date.length > 7 ? p.date.slice(5) : p.date));
    }
    root.append(g);
  });
  return h('div', { class: 'chart-box' }, root);
}

/** Share of the total as a thin single-hue bar next to the number it encodes. */
function shareBar(value) {
  const width = Math.max(0, Math.min(100, value || 0));
  return h('span', { class: 'share' }, svg('svg', { viewBox: '0 0 100 8', class: 'share-bar', preserveAspectRatio: 'none' },
    svg('rect', { x: 0, y: 0, width: 100, height: 8, class: 'share-track' }), svg('rect', { x: 0, y: 0, width, height: 8, class: 'share-fill' })),
  h('span', { class: 'mono' }, `${Math.round(width * 10) / 10}%`));
}

function breakdownTable(title, rows, unit) {
  return [h('h2', {}, title), rows.length === 0 ? h('p', { class: 'muted' }, '没有数据。') :
    h('table', {}, h('tr', {}, [unit, '成功', '失败', '成功率', '返工', '平均耗时', 'P95', 'Token', 'Token 占比', '费用'].map((t, i) => h('th', {}, i === 0 ? title.replace('按', '') : t))),
      rows.map((b) => h('tr', {}, h('td', { class: 'mono' }, `${b.key}（${b.runs}）`), h('td', {}, b.succeeded), h('td', {}, b.failed), h('td', {}, pct(b.successRate)),
        h('td', {}, b.reworks), h('td', {}, ms(b.averageDurationMs)), h('td', {}, ms(b.p95DurationMs)), h('td', { class: 'mono' }, num(b.tokens)),
        h('td', {}, shareBar(b.tokenSharePct)), h('td', { class: 'mono' }, usd(b.costUsd)))))];
}

/** Weekday × hour, one sequential hue: lightest step near zero, darkest at the busiest hour. */
function heatmap(cells) {
  const days = ['一', '二', '三', '四', '五', '六', '日'];
  const max = Math.max(1, ...cells.map((c) => c.runs));
  const byKey = new Map(cells.map((c) => [`${c.weekday}-${c.hour}`, c]));
  const size = 22; const left = 26; const top = 16;
  const root = svg('svg', { viewBox: `0 0 ${left + 24 * size + 4} ${top + 7 * size + 4}`, class: 'chart heat', role: 'img' });
  for (let hour = 0; hour < 24; hour += 3) root.append(svg('text', { x: left + hour * size + size / 2, y: 11, class: 'axis', 'text-anchor': 'middle' }, hour));
  days.forEach((d, i) => {
    root.append(svg('text', { x: left - 6, y: top + i * size + 15, class: 'axis', 'text-anchor': 'end' }, d));
    for (let hour = 0; hour < 24; hour++) {
      const cell = byKey.get(`${i + 1}-${hour}`);
      const level = cell ? Math.min(6, Math.ceil((cell.runs / max) * 6)) : 0;
      root.append(svg('rect', { x: left + hour * size + 1, y: top + i * size + 1, width: size - 2, height: size - 2, rx: 3, class: `heat-${level}` },
        svg('title', {}, `周${d} ${hour}:00 — ${cell ? `${cell.runs} 次运行，${num(cell.tokens)} Token` : '无运行'}`)));
    }
  });
  return h('div', { class: 'chart-box' }, root);
}

async function analyticsPage() {
  const s = analyticsState;
  const [projects, workflows] = await Promise.all([api('/projects').catch(() => []), api('/workflows').catch(() => [])]);
  const query = new URLSearchParams({ grain: s.grain, zone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    from: new Date(Date.now() - Number(s.days) * 86400000).toISOString() });
  if (s.projectId) query.set('projectId', s.projectId);
  if (s.workflowId) query.set('workflowId', s.workflowId);
  const report = await api(`/analytics?${query}`);
  const sum = report.summary;
  const select = (key, options) => h('select', { onchange: (e) => { s[key] = e.target.value; route(); } },
    options.map(([v, label]) => h('option', { value: v, selected: s[key] === v }, label)));
  const filters = h('div', { class: 'row mb' },
    select('projectId', [['', '全部项目'], ...projects.map((p) => [p.id, p.name])]),
    select('workflowId', [['', '全部 SOP'], ...workflows.map((w) => [w.id, w.name || w.id])]),
    select('days', [['7', '近 7 天'], ['30', '近 30 天'], ['90', '近 90 天'], ['365', '近一年']]),
    select('grain', [['day', '按天'], ['week', '按周'], ['month', '按月']]));
  const trendTable = h('details', {}, h('summary', {}, '表格'), h('table', {}, h('tr', {}, ['日期', '运行', '成功', '失败', '取消', '返工', '平均耗时', 'Token', '费用', '合并'].map((t) => h('th', {}, t))),
    report.trend.map((p) => h('tr', {}, [p.date, p.runs, p.succeeded, p.failed, p.cancelled, p.reworks, ms(p.durationMs), num(p.tokens), usd(p.costUsd), p.mergedPullRequests].map((v) => h('td', {}, v))))));
  shell([
    h('h1', {}, '分析'), filters,
    report.truncated ? h('div', { class: 'notice' }, '范围内运行过多，只统计了最近的 5000 个，请缩小范围。') : null,
    h('div', { class: 'tiles' },
      statTile('运行', num(sum.totalRuns), `进行中 ${sum.activeRuns} · 待处理 ${sum.waitingRuns}`),
      statTile('成功率', pct(sum.successRate), `成功 ${sum.succeededRuns} · 失败 ${sum.failedRuns} · 取消 ${sum.cancelledRuns}`),
      statTile('一次通过率', pct(sum.firstPassRate), `返工 ${sum.totalReworks} 次 · 重试节点 ${sum.retriedNodes}`),
      statTile('耗时 P50 / P95', `${ms(sum.p50DurationMs)} / ${ms(sum.p95DurationMs)}`, `执行 P95 ${ms(sum.executionP95DurationMs)}`),
      statTile('Token', num(sum.totalTokens), `缓存命中 ${pct(sum.cacheHitRate)}`),
      statTile('费用', usd(sum.totalCostUsd), sum.costUnknownRuns ? `${sum.costUnknownRuns} 个运行费用未知` : `覆盖 ${Math.round(sum.costCoveragePct)}%`),
      statTile('PR / MR', num(sum.pullRequests), `已合并 ${sum.mergedPullRequests} · 打开 ${sum.openPullRequests}`)),
    h('h2', {}, '运行趋势'),
    report.trend.length === 0 ? h('p', { class: 'muted' }, '范围内没有运行。') :
      columnChart(report.trend, (p) => p.runs, (p) => `${p.date}：${p.runs} 次运行（成功 ${p.succeeded}，失败 ${p.failed}，取消 ${p.cancelled}）`),
    h('h2', {}, 'Token 趋势'),
    report.trend.length === 0 ? null : columnChart(report.trend, (p) => p.tokens, (p) => `${p.date}：${num(p.tokens)} Token，${usd(p.costUsd)}`),
    trendTable,
    breakdownTable('按 SOP', report.byWorkflow, 'SOP'),
    breakdownTable('按项目', report.byProject, '项目'),
    breakdownTable('按执行器', report.byExecutor, '执行器'),
    breakdownTable('按模型', report.byModel, '模型'),
    h('h2', {}, '失败原因'),
    report.failures.length === 0 ? h('p', { class: 'muted' }, '没有失败的运行。') :
      h('table', {}, h('tr', {}, ['原因', '次数', '示例'].map((t) => h('th', {}, t))),
        report.failures.map((f) => h('tr', {}, h('td', {}, f.message), h('td', {}, f.runs),
          h('td', {}, h('a', { href: `#/runs/${f.sampleRunId}`, class: 'mono' }, `${f.sampleRunId.slice(0, 14)}${f.nodeId ? ` / ${f.nodeId}` : ''}`))))),
    h('h2', {}, '运行时段'), heatmap(report.heatmap),
  ]);
}

// ------------------------------------------------------------------ business domains

const businessState = { domain: 'all', days: '30', grain: 'week' };

async function businessPage() {
  const s = businessState;
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const domains = await api('/business-domains');
  const query = new URLSearchParams({ businessDomainId: s.domain, grain: s.grain, timeZone: zone,
    from: new Date(Date.now() - Number(s.days) * 86400000).toISOString() });
  let report = null;
  let reportError = null;
  try { report = await api(`/business-domain-report?${query}`); } catch (error) { reportError = error; }
  const pick = (key, options) => h('select', { onchange: (e) => { s[key] = e.target.value; route(); } },
    options.map(([v, label]) => h('option', { value: v, selected: s[key] === v }, label)));
  const edit = (item) => {
    const d = item?.domain || { id: '', name: '', description: '', active: true, managers: [], viewers: [] };
    const fields = [
      { name: 'id', label: '标识（字母、数字、_ -）', value: d.id },
      { name: 'name', label: '名称', value: d.name },
      { name: 'description', label: '说明', type: 'textarea', rows: 3, value: d.description || '' },
      { name: 'viewers', label: '查看者（用户名或 id，逗号分隔）', value: d.viewers.join(',') }];
    if (domains.canAdmin) {
      fields.push({ name: 'managers', label: '管理员（用户名或 id，逗号分隔）', value: d.managers.join(',') },
        { name: 'active', label: '状态', type: 'select', options: ['true', 'false'], value: String(d.active) });
    }
    const split = (v) => (v || '').split(',').map((x) => x.trim()).filter(Boolean);
    prompted(item ? `编辑业务域 · ${d.name}` : '新业务域', fields, (v) => api(`/business-domains${item ? '' : '?createOnly=true'}`, { method: 'PUT', body: {
      id: v.id, name: v.name, description: v.description, viewers: split(v.viewers),
      managers: domains.canAdmin ? split(v.managers) : d.managers, active: domains.canAdmin ? v.active === 'true' : d.active, updatedAt: d.updatedAt } }).then(() => route()));
  };
  const m = report?.deliveryMetrics;
  const deliveryTiles = m ? h('div', { class: 'tiles' },
    statTile('完成需求', num(m.completedIssues), m.estimatedCompletionIssues ? `${m.estimatedCompletionIssues} 个完成时间为估算` : `最近 ${time(m.latestCompletedAt)}`),
    statTile('合并 PR / MR', num(m.mergedMrs), m.mergedMrsMissingTime ? `${m.mergedMrsMissingTime} 个缺少合并时间` : `${m.completionToMergeMissingIssues} 个需求尚未合并`),
    statTile('完成 → 合并', ms(m.averageCompletionToMergeMs), `${m.completionToMergeSamples} 个样本 · 先合并后完成 ${m.mergedBeforeCompletionMrs}`),
    statTile('交付周期 P50 / P95', `${ms(m.p50CycleDurationMs)} / ${ms(m.p95CycleDurationMs)}`, `平均 ${ms(m.averageCycleDurationMs)} · ${m.cycleSamples} 个样本`),
    statTile('执行时长', ms(m.averageExecutionDurationMs), `${m.executionSamples} 个样本`),
    statTile('每个需求 Token', num(m.averageTokensPerCompletedIssue), `合计 ${num(m.completedTokens)}`)) : null;
  shell([
    h('div', { class: 'row' }, h('h1', {}, '业务域'), h('span', { class: 'spacer' }), domains.canAdmin ? h('button', { class: 'primary', onclick: () => edit(null) }, '新业务域') : null),
    h('table', {}, h('tr', {}, ['标识', '名称', '状态', '管理员', '查看者', ''].map((t) => h('th', {}, t))),
      domains.items.map((i) => h('tr', {}, h('td', { class: 'mono' }, i.domain.id), h('td', {}, i.domain.name, i.domain.description ? h('div', { class: 'muted' }, i.domain.description) : null),
        h('td', {}, badge(i.domain.active ? 'succeeded' : 'cancelled', i.domain.active ? '启用' : '停用')),
        h('td', { class: 'mono' }, i.domain.managers.length), h('td', { class: 'mono' }, i.domain.viewers.length),
        h('td', {}, i.canManage ? h('button', { onclick: () => edit(i) }, '编辑') : null)))),
    h('h2', {}, '业务域报表'),
    h('div', { class: 'row mb' },
      pick('domain', [['all', '全部可见项目'], ['unclassified', '未归属'], ...domains.items.filter((i) => i.canView).map((i) => [i.domain.id, i.domain.name])]),
      pick('days', [['7', '近 7 天'], ['30', '近 30 天'], ['90', '近 90 天'], ['365', '近一年']]),
      pick('grain', [['day', '按天'], ['week', '按周'], ['month', '按月']])),
    reportError ? errorBox(reportError) : null,
    report ? h('p', { class: 'muted' }, `${report.attribution} · 运行 ${num(report.summary.totalRuns)} · 成功率 ${pct(report.summary.successRate)} · Token ${num(report.summary.totalTokens)} · ${usd(report.summary.totalCostUsd)}`) : null,
    deliveryTiles,
    report && report.deliveryTrend.length ? h('h2', {}, '交付趋势') : null,
    report && report.deliveryTrend.length ? columnChart(report.deliveryTrend, (p) => p.completedIssues, (p) => `${p.date}：完成 ${p.completedIssues} 个需求，合并 ${p.mergedMrs} 个 PR，平均周期 ${ms(p.averageCycleDurationMs)}`) : null,
    report ? h('h2', {}, '按业务域') : null,
    report ? h('table', {}, h('tr', {}, ['业务域', '项目', '运行', '成功', '失败', 'Token', '费用', '涉及需求', '完成', '周期 P50'].map((t) => h('th', {}, t))),
      report.businessDomains.map((d) => h('tr', {}, [d.name, d.projectCount, d.runs, d.success, d.failed, num(d.tokens), usd(d.costUsd), d.uniqueIssues, d.delivered, ms(d.deliveryMetrics.p50CycleDurationMs)].map((v) => h('td', {}, v))))) : null,
    report ? h('h2', {}, '按项目') : null,
    report ? h('table', {}, h('tr', {}, ['项目', '业务域', '运行', '成功', '失败', 'Token', '费用', '完成需求', '合并'].map((t) => h('th', {}, t))),
      report.projects.map((p) => h('tr', {}, h('td', {}, h('a', { href: `#/projects/${encodeURIComponent(p.projectId)}` }, p.name)),
        ...[p.businessDomainId || '未归属', p.runs, p.succeeded, p.failed, num(p.tokens), usd(p.costUsd), p.deliveryMetrics.completedIssues, p.deliveryMetrics.mergedMrs].map((v) => h('td', {}, v))))) : null,
  ]);
}

// ------------------------------------------------------------------ knowledge base

const knowledgeState = { projectId: '' };

async function knowledgePage() {
  const admin = me?.user?.role === 'admin' || me?.service;
  const projects = await api('/projects').catch(() => []);
  if (!knowledgeState.projectId && projects.length) knowledgeState.projectId = projects[0].id;
  const projectId = knowledgeState.projectId;
  const q = projectId ? `projectId=${encodeURIComponent(projectId)}` : '';
  const [settings, candidates, submissions, activity, usage] = await Promise.all([
    admin ? api('/knowledge/settings').catch(() => null) : null,
    api(`/knowledge/candidates?${q}`).catch(() => []), api(`/knowledge/submissions?${q}`).catch(() => []),
    api(`/knowledge/activity?${q}`).catch(() => []), api('/knowledge/usage').catch(() => []),
  ]);
  const refresh = () => knowledgePage();
  const results = h('div', {});
  const queryInput = h('input', { placeholder: '检索团队知识库…', class: 'grow' });
  const search = () => attempt(() => api(`/knowledge/search?${q}&q=${encodeURIComponent(queryInput.value)}`), (r) => {
    const hits = r?.results || [];
    results.replaceChildren(r?.degraded ? h('div', { class: 'notice' }, `语义检索已降级：${r.degrade_reason || ''}`) : '',
      hits.length === 0 ? h('p', { class: 'muted' }, '没有命中。') : h('table', {}, h('tr', {}, ['来源', '路径', '摘要', ''].map((t) => h('th', {}, t))),
        hits.map((hit) => h('tr', {}, h('td', { class: 'mono' }, hit.source), h('td', { class: 'mono' }, `${hit.path}#L${hit.start_line}-L${hit.end_line}`),
          h('td', {}, hit.title ? h('strong', {}, hit.title) : null, h('div', { class: 'muted' }, hit.snippet || '')),
          h('td', {}, h('button', { onclick: () => openDocument(hit.source, hit.path, hit.start_line, hit.end_line) }, '阅读'))))));
  });
  const openDocument = (source, path, start, end) => attempt(() => api(`/knowledge/document?${q}&source=${encodeURIComponent(source)}&path=${encodeURIComponent(path)}&start=${start || 0}&end=${end || 0}`),
    (doc) => modal(`${source}:${path}`, h('div', { class: 'scroll' }, h('p', { class: 'muted mono' }, doc.citation || ''),
      h('pre', { class: 'mono' }, (doc.lines || []).map((l) => `${String(l.number).padStart(4)}  ${l.text}`).join('\n'))), () => h('span')));
  const queue = (c) => attempt(() => api('/knowledge/submissions', { method: 'POST', body: { runId: c.runId, artifactId: c.artifactId } }), refresh);
  const newSubmission = () => prompted('新建沉淀', [{ name: 'title', label: '标题' }, { name: 'source', label: '知识库来源' }, { name: 'path', label: '路径（.md）', placeholder: 'guides/xxx.md' },
    { name: 'content', label: '正文（Markdown）', type: 'textarea', rows: 10 }],
  (v) => api('/knowledge/submissions', { method: 'POST', body: { ...v, projectId } }).then(refresh));
  const review = async (item) => {
    const full = await api(`/knowledge/submissions/${item.id}`);
    prompted(`审核：${full.title}`, [{ name: 'title', label: '标题', value: full.title }, { name: 'source', label: '知识库来源', value: full.source },
      { name: 'path', label: '路径（.md）', value: full.path }, { name: 'content', label: '正文', type: 'textarea', rows: 12, value: full.content },
      { name: 'reviewNote', label: '审核备注', value: full.reviewNote || '' }],
    (v) => api(`/knowledge/submissions/${item.id}`, { method: 'PUT', body: v }).then(refresh));
  };
  const reject = (item) => prompted(`驳回：${item.title}`, [{ name: 'note', label: '原因', type: 'textarea', rows: 3 }],
    (v) => api(`/knowledge/submissions/${item.id}/reject`, { method: 'POST', body: v }).then(refresh));
  const publish = (item) => confirm(`写回知识库 ${item.source}:${item.path}？`) && attempt(() => api(`/knowledge/submissions/${item.id}/publish`, { method: 'POST' }), refresh);

  let settingsPanel = null;
  if (settings) {
    const cfg = settings.settings;
    const enabled = h('input', { type: 'checkbox', checked: cfg.enabled });
    const write = h('input', { type: 'checkbox', checked: cfg.writeEnabled });
    const url = h('input', { value: cfg.url || '', placeholder: 'https://kb.example.com/mcp', class: 'grow' });
    const tokenInput = h('input', { type: 'password', placeholder: cfg.tokenConfigured ? '已配置（留空保持不变）' : '上游 Token' });
    const defaults = h('input', { value: (cfg.defaultSources || []).join(','), placeholder: '默认来源，逗号分隔（留空=全部）' });
    const perProject = h('textarea', { rows: 4 }, json(cfg.projectSources || {}));
    const status = h('span', { class: 'muted' }, settings.ready ? '已接入' : '未接入');
    const body = () => ({ enabled: enabled.checked, writeEnabled: write.checked, url: url.value, token: tokenInput.value || undefined,
      defaultSources: defaults.value.split(',').map((x) => x.trim()).filter(Boolean), projectSources: JSON.parse(perProject.value || '{}') });
    settingsPanel = h('details', { class: 'panel', open: !settings.ready }, h('summary', {}, '接入配置 ', status),
      h('div', { class: 'row mt' }, h('label', {}, enabled, ' 启用'), h('label', {}, write, ' 允许审核后写回'), url, tokenInput),
      h('label', { class: 'field mt' }, h('span', {}, '默认来源'), defaults),
      h('label', { class: 'field' }, h('span', {}, '按项目授权来源（JSON：项目 id → 来源列表）'), perProject),
      h('div', { class: 'row' },
        h('button', { onclick: () => attempt(() => api('/knowledge/check', { method: 'POST', body: { url: url.value, token: tokenInput.value || undefined } }),
          (r) => alert(r.ok ? `连接正常（${r.latencyMs}ms）：${(r.tools || []).join(', ')}` : `连接失败：${r.error}`)) }, '测试连接'),
        h('button', { class: 'primary', onclick: () => attempt(() => api('/knowledge/settings', { method: 'PUT', body: body() }), refresh) }, '保存')));
  }

  shell([
    h('div', { class: 'row' }, h('h1', {}, '知识库'), h('span', { class: 'spacer' }),
      h('select', { onchange: (e) => { knowledgeState.projectId = e.target.value; route(); } },
        admin ? h('option', { value: '', selected: !projectId }, '（全部项目 / 默认来源）') : null,
        projects.map((p) => h('option', { value: p.id, selected: p.id === projectId }, p.name)))),
    settingsPanel,
    h('div', { class: 'row mb' }, queryInput, h('button', { class: 'primary', onclick: search }, '检索')), results,
    h('div', { class: 'row' }, h('h2', {}, '待沉淀产物'), h('span', { class: 'muted' }, '开启知识库的角色把可复用知识写到 knowledge.md')),
    candidates.length === 0 ? h('p', { class: 'muted' }, '还没有 knowledge.md 产物。') :
      h('table', {}, h('tr', {}, ['运行', '节点', 'SOP', '大小', '时间', ''].map((t) => h('th', {}, t))),
        candidates.map((c) => h('tr', {}, h('td', {}, h('a', { href: `#/runs/${c.runId}`, class: 'mono' }, c.runId.slice(0, 14))), h('td', { class: 'mono' }, c.nodeId),
          h('td', { class: 'mono' }, c.workflowId), h('td', {}, `${c.size} B`), h('td', {}, time(c.createdAt)),
          h('td', {}, c.submissionId ? h('span', { class: 'muted' }, '已加入队列') : h('button', { onclick: () => queue(c) }, '加入审核'))))),
    h('div', { class: 'row' }, h('h2', {}, '审核队列'), h('span', { class: 'spacer' }), projectId ? h('button', { onclick: newSubmission }, '手动新建') : null),
    submissions.length === 0 ? h('p', { class: 'muted' }, '队列为空。') :
      h('table', {}, h('tr', {}, ['标题', '状态', '来源 / 路径', '提交人', '更新', '操作'].map((t) => h('th', {}, t))),
        submissions.map((item) => h('tr', {}, h('td', {}, item.title, item.error ? h('div', { class: 'error' }, item.error) : null),
          h('td', {}, badge(item.status === 'published' ? 'succeeded' : item.status === 'rejected' ? 'cancelled' : 'waiting'), ' ', item.status),
          h('td', { class: 'mono' }, `${item.source || '—'} / ${item.path || '—'}`, item.commit ? h('div', { class: 'muted' }, item.commit) : null),
          h('td', {}, item.createdBy || '—'), h('td', {}, time(item.updatedAt)),
          h('td', {}, item.status === 'published' ? null : h('div', { class: 'row' },
            h('button', { onclick: () => attempt(() => review(item)) }, '编辑'), h('button', { onclick: () => reject(item) }, '驳回'),
            h('button', { class: 'primary', onclick: () => publish(item) }, '写回'),
            h('button', { class: 'danger', onclick: () => confirm('删除？') && attempt(() => api(`/knowledge/submissions/${item.id}`, { method: 'DELETE' }), refresh) }, '删除')))))),
    h('h2', {}, '使用知识库的角色'),
    usage.length === 0 ? h('p', { class: 'muted' }, '没有角色开启知识库（角色定义里的 knowledgeBase: true）。') :
      h('div', { class: 'row' }, usage.map((u) => h('a', { class: 'btn', href: `#/agents/${encodeURIComponent(u.agentId)}` }, u.name))),
    h('h2', {}, '最近调用'),
    activity.length === 0 ? h('p', { class: 'muted' }, '还没有调用记录（服务重启后清空）。') :
      h('table', {}, h('tr', {}, ['时间', '工具', '调用方', '查询', '命中', '耗时', '结果'].map((t) => h('th', {}, t))),
        activity.slice(0, 50).map((a) => h('tr', {}, h('td', {}, time(a.at)), h('td', { class: 'mono' }, a.tool),
          h('td', {}, a.agentId ? `${a.agentId}（${(a.runId || '').slice(0, 12)}）` : a.caller || '—'), h('td', {}, a.query || '—'), h('td', {}, a.hits),
          h('td', {}, `${a.latencyMs}ms`), h('td', {}, a.ok ? badge('succeeded') : h('span', { class: 'error' }, a.error || '失败'))))),
  ]);
}

// ------------------------------------------------------------------ execution machines

async function downloadRunner() {
  const response = await fetch('/api/v1/runners/agent.jar', { headers: { Authorization: `Bearer ${token()}` } });
  if (!response.ok) { alert(response.status === 404 ? '本安装未附带执行机器程序，请从发布页下载 loopforge-runner-*-all.jar' : `下载失败（HTTP ${response.status}）`); return; }
  const link = h('a', { href: URL.createObjectURL(await response.blob()), download: 'loopforge-runner.jar' });
  document.body.append(link);
  link.click();
  link.remove();
}

async function runnersPage() {
  const machines = await api('/runners');
  const admin = me?.user?.role === 'admin' || me?.service;
  const refresh = () => runnersPage();
  const register = () => prompted('注册执行机器', [{ name: 'name', label: '名称（例如 alice-macbook）' },
    ...(admin ? [{ name: 'shared', label: '共享执行池（所有项目成员可用）', type: 'select', options: ['false', 'true'], value: 'false' }] : [])],
  async (v) => {
    const created = await api('/runners', { method: 'POST', body: { name: v.name, shared: v.shared === 'true' } });
    const server = location.origin;
    modal('执行机器已注册', h('div', {},
      h('p', {}, 'Token 只显示这一次。在这台机器上（已安装并登录 claude / codex 等 CLI）运行：'),
      h('pre', { class: 'mono' }, `export LOOPFORGE_SERVER_URL=${server}\nexport LOOPFORGE_RUNNER_TOKEN=${created.token}\n# 可选：LOOPFORGE_RUNNER_GIT_HTTPS=github.com=<token>  LOOPFORGE_RUNNER_CAPACITY=2\njava -jar loopforge-runner.jar`),
      h('div', { class: 'row mt' }, h('button', { onclick: downloadRunner }, '下载 loopforge-runner.jar'))), () => h('span'));
    refresh();
    return created;
  });
  shell([
    h('div', { class: 'row' }, h('h1', {}, '执行机器'), h('span', { class: 'spacer' }), h('button', { onclick: downloadRunner }, '下载程序'),
      h('button', { class: 'primary', onclick: register }, '注册')),
    h('p', { class: 'muted' }, '执行机器只向服务端发起连接，认领分配给它的运行，用本机已登录的 CLI 与凭据执行。个人机器只有所有者能使用，共享执行池由管理员注册。'),
    machines.length === 0 ? h('p', { class: 'muted' }, '还没有执行机器。') :
      h('table', {}, h('tr', {}, ['名称', '类型', '状态', '主机', '执行器', '并发', '版本', '最近心跳', ''].map((t) => h('th', {}, t))),
        machines.map((m) => h('tr', {}, h('td', {}, m.name, h('div', { class: 'muted mono' }, m.id)), h('td', {}, m.shared ? '共享执行池' : `个人（${m.owner || '—'}）`),
          h('td', {}, badge(m.online ? 'succeeded' : 'cancelled'), ' ', m.online ? '在线' : '离线'), h('td', { class: 'mono' }, m.host || '—'),
          h('td', {}, (m.executors || []).join(', ') || '—'), h('td', {}, m.capacity || '—'), h('td', { class: 'mono' }, m.version || '—'), h('td', {}, time(m.lastSeenAt)),
          h('td', {}, h('button', { class: 'danger', onclick: () => confirm(`移除 ${m.name}？它的 Token 立即失效。`) && attempt(() => api(`/runners/${m.id}`, { method: 'DELETE' }), refresh) }, '移除'))))),
  ]);
}

// ------------------------------------------------------------------ assistant (Main Bot)

function chatMessage(m) {
  const who = m.role === 'user' ? m.author : 'LoopForge 助手';
  return h('div', { class: `msg ${m.role}` },
    h('div', { class: 'msg-meta muted' }, `${who} · ${time(m.createdAt)}${m.tokens ? ` · ${num(m.tokens)} Token` : ''}`),
    h('div', { class: 'msg-body' }, m.content),
    (m.activity || []).length ? h('details', {}, h('summary', {}, `过程（${m.activity.length}）`), h('div', { class: 'log' }, m.activity.map((a) => h('div', {}, a)))) : null);
}

async function chatPage(id) {
  const [sessions, projects, unread, prefs] = await Promise.all([api('/assistant/sessions'), api('/projects').catch(() => []),
    api('/assistant/sessions/unread').catch(() => ({ sessionIds: [] })), api('/assistant/sessions/preferences').catch(() => ({ steerOnSend: false }))]);
  const unreadIds = new Set(unread.sessionIds || []);
  const current = id ? await api(`/assistant/sessions/${encodeURIComponent(id)}`) : sessions[0] || null;
  if (!id && current) { location.hash = `#/chat/${current.id}`; return; }
  const create = () => prompted('新对话', [
    { name: 'title', label: '标题', value: '新对话' },
    { name: 'projectId', label: '项目（空 = 个人对话，只有自己可见）', type: 'select', options: ['', ...projects.map((p) => p.id)] },
    { name: 'sandboxProfileId', label: '工作区沙箱（空 = 默认）', placeholder: 'docker' }],
  (v) => api('/assistant/sessions', { method: 'POST', body: { title: v.title, projectId: v.projectId || undefined,
    sandboxProfileId: v.sandboxProfileId || undefined } }).then((s) => { location.hash = `#/chat/${s.id}`; return s; }));
  const list = h('div', { class: 'chat-list' }, h('button', { class: 'primary', onclick: create }, '新对话'),
    sessions.map((s) => h('a', { href: `#/chat/${s.id}`, class: current && s.id === current.id ? 'chat-item active' : 'chat-item' },
      h('div', {}, unreadIds.has(s.id) && !(current && s.id === current.id) ? h('span', { class: 'unread-dot', title: '有未读回复' }) : null, s.title,
        s.source === 'scheduled' ? h('span', { class: 'chip' }, '定时') : null),
      h('div', { class: 'muted' }, `${s.projectId || '个人'}${s.status !== 'idle' ? ' · 回复中' : ''}`))));
  if (!current) {
    shell([h('h1', {}, '助手'), h('div', { class: 'chat' }, list,
      h('div', { class: 'panel' }, h('p', {}, '助手可以查需求、建需求、启动 SOP、看运行和产物，也可以在对话专属的工作区里看代码、改代码。它以你的身份操作平台，没有管理员权限。'),
        h('p', { class: 'muted' }, '新建一个对话开始。')))]);
    return;
  }
  const messages = await api(`/assistant/sessions/${current.id}/messages`);
  const feed = h('div', { class: 'chat-feed' }, messages.map(chatMessage));
  const live = h('div', { class: 'log live' });
  const input = h('textarea', { rows: 3, placeholder: '说点什么…（Ctrl+Enter 发送）' });
  let generating = current.status !== 'idle';
  const sendButton = h('button', { class: 'primary' }, '发送');
  const stopButton = h('button', { class: 'danger' }, '停止');
  const sync = () => {
    sendButton.textContent = generating ? (steer.checked ? '插队发送' : '排队发送') : '发送';
    stopButton.disabled = !generating;
    live.hidden = !generating;
  };
  // messages sent while the assistant answers wait here (or stop the reply when steering)
  const queueBox = h('div', { class: 'chat-queue' });
  const showQueue = (items) => queueBox.replaceChildren(...(items || []).map((q) => h('div', { class: 'queued' },
    h('span', { class: 'chip' }, q.priority > 0 ? '插队' : '排队中'), h('span', {}, q.content.length > 120 ? `${q.content.slice(0, 120)}…` : q.content),
    q.speakerId === me?.user?.id ? h('button', { onclick: () => attempt(() => api(`/assistant/sessions/${current.id}/queue/${q.id}`, { method: 'DELETE' })) }, '撤回') : null)));
  const steer = h('input', { type: 'checkbox', checked: !!prefs.steerOnSend });
  steer.addEventListener('change', () => attempt(() => api('/assistant/sessions/preferences', { method: 'PUT', body: { steerOnSend: steer.checked } })));
  const send = () => {
    const content = input.value.trim();
    if (!content) return;
    attempt(() => api(`/assistant/sessions/${current.id}/messages`, { method: 'POST', body: { content } }), (result) => {
      input.value = '';
      if (result?.queued) return;
      generating = true;
      live.replaceChildren();
      sync();
    });
  };
  sendButton.addEventListener('click', send);
  stopButton.addEventListener('click', () => attempt(() => api(`/assistant/sessions/${current.id}/cancel`, { method: 'POST' })));
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send(); });
  const remove = () => confirm('删除这个对话和它的工作区？') && attempt(() => api(`/assistant/sessions/${current.id}`, { method: 'DELETE' }), () => { location.hash = '#/chat'; });
  shell([h('div', { class: 'chat' }, list, h('div', {},
    h('div', { class: 'row' }, h('h1', {}, current.title), h('span', { class: 'spacer' }),
      h('span', { class: 'muted' }, `${current.projectId ? `项目 ${current.projectId}` : '个人对话'} · 沙箱 ${current.sandboxProfileId} · ${num(current.totalTokens)} Token · ${usd(current.costUsd)}`),
      current.requirementId && current.projectId ? h('a', { class: 'chip', href: `#/projects/${encodeURIComponent(current.projectId)}`, title: '对话里开的 PR 会登记到这个需求' }, '已关联需求') : null,
      h('button', { class: 'danger', onclick: remove }, '删除')),
    feed, live, queueBox,
    browserPanel(`/assistant/sessions/${encodeURIComponent(current.id)}/browser`),
    h('details', { class: 'panel' }, h('summary', {}, '工作区终端'), terminalPanel(`/assistant/sessions/${encodeURIComponent(current.id)}/terminal`, '助手回复时不能打开')),
    h('div', { class: 'panel mt' }, input, h('div', { class: 'row mt' },
      h('label', { class: 'muted', title: '回复进行中时发送：打断当前回复并先处理这条（否则排队等待）' }, steer, ' 发送时打断当前回复'),
      h('span', { class: 'spacer' }), stopButton, sendButton))))]);
  steer.addEventListener('change', sync);
  sync();
  api(`/assistant/sessions/${current.id}/queue`).then(showQueue).catch(() => {});
  feed.scrollTop = feed.scrollHeight;
  stream = new EventSource(`/api/v1/assistant/sessions/${current.id}/stream?access_token=${encodeURIComponent(token())}`);
  stream.addEventListener('activity', (e) => { live.append(h('div', {}, String(sseData(e)))); live.scrollTop = live.scrollHeight; });
  stream.addEventListener('message', (e) => { feed.append(chatMessage(sseData(e))); feed.scrollTop = feed.scrollHeight; });
  stream.addEventListener('status', (e) => { generating = sseData(e).status !== 'idle'; if (!generating) live.replaceChildren(); sync(); });
  stream.addEventListener('queue', (e) => showQueue(sseData(e)));
}

// ------------------------------------------------------------------ run evaluation

const EVAL_DIMENSIONS = { control: '控制流健康', latency: '耗时与等待', tools: '工具浪费', tokens: 'Token 效率', recovery: '错误恢复', delivery: '交付完整' };
const REC_STATUS = { pending: '待应用', applied: '已应用', failed: '应用失败' };

/** The quality evaluation of a settled run: score, dimensions, findings and recommendations to apply. */
const PREVIEW_STATUS = { pending: '等待构建', archiving: '归档中', ready: '可打开', skipped: '已跳过', failed: '失败', expired: '已过期' };

function previewPanel(run) {
  const runId = run.id;
  const panel = h('div', { class: 'panel' }, h('h2', {}, '预览'), h('p', { class: 'muted' }, '加载中…'));
  const base = `/runs/${encodeURIComponent(runId)}`;
  const admin = me?.user?.role === 'admin' || me?.service;
  const openLink = (session) => {
    const url = /^https?:\/\/[^/]+\/preview\/[0-9a-f]{32}\/$/.test(session.url) ? session.url : undefined;
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
    linkBox.replaceChildren(...[url ? h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, url) : null,
      h('span', { class: 'muted' }, ` · ${session.backendUrl ? '后端 ' + session.backendUrl + ' · ' : ''}闲置 30 分钟后失效`)].filter(Boolean));
  };
  const linkBox = h('div', { class: 'mono' });
  const load = async () => {
    let p;
    try { p = await api(`${base}/preview`); } catch (error) {
      if (error.status === 404) {
        panel.replaceChildren(h('h2', {}, '预览'), h('p', { class: 'muted' }, run.status === 'succeeded' ? '这次运行没有产出预览。' : '构建步骤完成后，这里会出现可以直接打开的站点。'),
          h('button', { onclick: load }, '刷新'));
        return;
      }
      panel.replaceChildren(h('h2', {}, '预览'), errorBox(error));
      return;
    }
    const backend = h('input', { value: p.backendUrl || '', placeholder: '后端地址（可选），如 https://api.example.com' });
    const head = h('div', { class: 'row' }, h('h2', {}, '预览'), badge(p.status === 'ready' ? 'succeeded' : p.status === 'failed' ? 'failed' : 'pending', PREVIEW_STATUS[p.status] || p.status), h('span', { class: 'spacer' }),
      p.status === 'ready' ? h('button', { class: 'primary', onclick: () => attempt(() => api(`${base}/preview/session`, { method: 'POST', body: { backendUrl: backend.value.trim() || undefined } }), openLink) }, '打开预览') : null,
      p.status === 'ready' ? h('button', { onclick: () => attempt(() => downloadBinary(`${base}/preview/download`, `${runId}-preview.zip`)) }, '下载 zip') : null,
      admin ? h('button', { class: 'danger', onclick: () => confirm('删除这次运行的预览？') && attempt(() => api(`${base}/preview`, { method: 'DELETE' }), load) }, '删除') : null);
    const facts = h('p', { class: 'muted' }, `${p.nodeId ? '节点 ' + p.nodeId + ' · ' : ''}${p.sourcePath}/${p.entryFile}${p.fileCount ? ` · ${p.fileCount} 个文件 · ${Math.max(1, Math.round((p.expandedSize || 0) / 1024))} KB` : ''}${p.spaFallback ? ' · 单页应用' : ''}${p.expiresAt && p.status === 'ready' ? ' · ' + time(p.expiresAt) + ' 到期' : ''}`);
    panel.replaceChildren(...[head, facts,
      p.error ? h('div', { class: p.status === 'failed' ? 'error' : 'muted' }, p.error) : null,
      p.status === 'ready' ? h('div', { class: 'row' }, backend,
        h('button', { onclick: () => attempt(() => api(`${base}/preview/backend`, { method: 'PUT', body: { backendUrl: backend.value.trim() } }), (r) => { if (r.session) openLink(r.session); }) }, '保存为默认后端')) : null,
      p.status === 'ready' ? h('p', { class: 'muted' }, '页面里的接口请求会转到后端地址（路径与参数不变）；预览在隔离的源中运行，读不到控制台的登录信息。') : null,
      p.status === 'ready' && p.screenshot ? previewShot(base, p, load) : null,
      linkBox].filter(Boolean));
    if (p.screenshot === 'pending' && location.hash === page) setTimeout(() => { if (location.hash === page) load(); }, 2000);
  };
  const page = location.hash;
  load();
  return panel;
}

/** The screenshot the server's headless browser took of the entry page. */
function previewShot(base, p, reload) {
  const retake = h('button', { onclick: () => attempt(() => api(`${base}/preview/screenshot`, { method: 'POST' }), reload) }, '重新截图');
  if (p.screenshot === 'pending') return h('p', { class: 'muted' }, '正在截图…');
  if (p.screenshot === 'failed') return h('div', { class: 'row' }, h('span', { class: 'error' }, p.screenshotError || '截图失败'), retake);
  const box = h('div', { class: 'preview-shot' }, h('p', { class: 'muted' }, '加载截图…'));
  fetch(`/api/v1${base}/preview/screenshot`, { headers: token() ? { Authorization: `Bearer ${token()}` } : {} })
    .then(async (response) => {
      if (!response.ok) throw new ApiError(response.status, null);
      const url = URL.createObjectURL(await response.blob());
      box.replaceChildren(h('a', { href: url, target: '_blank', rel: 'noopener' }, h('img', { src: url, alt: '预览截图' })), h('div', { class: 'row' }, h('span', { class: 'muted' }, '入口页截图（1280×800）'), retake));
    })
    .catch(() => box.replaceChildren(h('span', { class: 'muted' }, '截图暂不可用'), retake));
  return box;
}

/**
 * The agent browser of a run or conversation (upstream task-panel browser): the frame the agent
 * sees, refreshed while open, taken over with the mouse and keyboard.
 */
function browserPanel(base) {
  const page = location.hash;
  const frame = h('img', { class: 'browser-frame', alt: '浏览器画面', draggable: false, tabindex: 0, title: '点击、滚动或聚焦后直接输入，即可接管浏览器' });
  const address = h('input', { class: 'mono grow', placeholder: '网址或关键词（localhost:端口 打开开发服务器）' });
  const tabs = h('select', { title: '标签页' });
  const note = h('div', { class: 'muted' });
  const nav = (label, title, operation) => h('button', { title, onclick: () => refresh({ operation }) }, label);
  const back = nav('←', '后退', 'back');
  const forward = nav('→', '前进', 'forward');
  const reload = nav('⟳', '刷新', 'reload');
  const start = h('button', { class: 'primary', onclick: () => refresh({ operation: 'status', ensure: true }) }, '启动浏览器');
  const close = h('button', { class: 'danger', onclick: () => attempt(() => api(base, { method: 'DELETE' }), show) }, '关闭浏览器');
  const toolbar = h('div', { class: 'row' }, back, forward, reload, address, tabs,
    h('button', { title: '新标签页', onclick: () => refresh({ operation: 'newTab' }) }, '＋'), close);
  const stage = h('div', { class: 'browser-stage' }, frame);
  const body = h('div', {}, note);
  const panel = h('details', { class: 'panel' }, h('summary', {}, 'Agent 浏览器'), body);
  let state = null;
  let busy = false;
  let timer = null;
  const show = (s) => {
    state = s;
    if (!s?.available) {
      body.replaceChildren(h('p', { class: 'muted' }, s?.reason || 'Agent 浏览器未启动'), /尚未启动|已关闭/.test(s?.reason || '') ? start : '');
      return;
    }
    if (!body.contains(stage)) body.replaceChildren(toolbar, stage, note);
    if (s.frame) frame.src = `data:image/jpeg;base64,${s.frame}`;
    if (document.activeElement !== address) address.value = s.url || '';
    back.disabled = !s.canGoBack;
    forward.disabled = !s.canGoForward;
    tabs.replaceChildren(...(s.tabs || []).map((t) => h('option', { value: t.id, selected: t.active }, (t.title || t.url || '新标签页').slice(0, 40))));
    note.textContent = [s.error ? `操作失败：${s.error}` : '', (s.blocked || []).length ? `已拦截：${s.blocked.slice(-3).join('、')}` : ''].filter(Boolean).join(' · ');
  };
  const refresh = async (request = { operation: 'status' }) => {
    busy = true;
    try { show(await api(base, { method: 'POST', body: { withFrame: true, ...request } })); } catch (error) { note.textContent = error.message; } finally { busy = false; }
  };
  const loop = () => {
    clearTimeout(timer);
    if (location.hash !== page || !panel.isConnected) return;
    if (panel.open && document.visibilityState === 'visible' && state?.available && !busy) refresh();
    timer = setTimeout(loop, 1000);
  };
  panel.addEventListener('toggle', () => { if (panel.open) { refresh(); loop(); } });
  address.addEventListener('keydown', (e) => { if (e.key === 'Enter' && address.value.trim()) { refresh({ operation: 'navigate', url: address.value.trim() }); frame.focus(); } });
  tabs.addEventListener('change', () => refresh({ operation: 'activate', targetId: tabs.value }));
  const point = (e) => ({ x: e.offsetX / Math.max(1, frame.clientWidth), y: e.offsetY / Math.max(1, frame.clientHeight) });
  const input = (events) => refresh({ operation: 'input', events });
  frame.addEventListener('click', (e) => {
    frame.focus();
    const p = point(e);
    input([{ type: 'mouse', action: 'move', ...p }, { type: 'mouse', action: 'down', ...p, button: 'left', buttons: 1, clickCount: 1 },
      { type: 'mouse', action: 'up', ...p, button: 'left', clickCount: 1 }]);
  });
  let wheel = null;
  frame.addEventListener('wheel', (e) => {
    e.preventDefault();
    const p = point(e);
    if (wheel) { wheel.deltaX += e.deltaX; wheel.deltaY += e.deltaY; return; }
    wheel = { type: 'wheel', ...p, deltaX: e.deltaX, deltaY: e.deltaY };
    setTimeout(() => { const ev = wheel; wheel = null; input([ev]); }, 120);
  }, { passive: false });
  // keys typed on the focused frame go to the page in small batches
  let keys = [];
  frame.addEventListener('keydown', (e) => {
    if (e.ctrlKey || e.metaKey) return;
    e.preventDefault();
    const text = e.key === 'Enter' ? '\r' : e.key.length === 1 ? e.key : undefined;
    const key = { type: 'key', key: e.key, code: e.code, keyCode: e.keyCode, modifiers: (e.altKey ? 1 : 0) | (e.shiftKey ? 8 : 0) };
    keys.push({ ...key, action: 'down', text }, { ...key, action: 'up' });
    if (keys.length === 2) setTimeout(() => { const batch = keys; keys = []; input(batch); }, 60);
  });
  frame.addEventListener('paste', (e) => { const text = e.clipboardData?.getData('text'); if (text) input([{ type: 'text', text }]); });
  return panel;
}

async function downloadBinary(path, filename) {
  const response = await fetch(`/api/v1${path}`, { headers: token() ? { Authorization: `Bearer ${token()}` } : {} });
  if (!response.ok) throw new ApiError(response.status, null);
  const link = h('a', { href: URL.createObjectURL(await response.blob()), download: filename });
  document.body.append(link);
  link.click();
  setTimeout(() => { URL.revokeObjectURL(link.href); link.remove(); }, 1000);
}

function evalPanel(runId) {
  const panel = h('div', { class: 'panel' }, h('h2', {}, '质量评测'), h('p', { class: 'muted' }, '加载中…'));
  const base = `/runs/${encodeURIComponent(runId)}`;
  const page = location.hash;
  const poll = () => setTimeout(() => { if (location.hash === page) load(); }, 2000);
  const start = () => attempt(() => api(`${base}/eval`, { method: 'POST' }), () => load());
  const load = async () => {
    let ev;
    try { ev = await api(`${base}/eval`); } catch (error) { panel.replaceChildren(h('h2', {}, '质量评测'), errorBox(error)); return; }
    const head = h('div', { class: 'row' }, h('h2', {}, '质量评测'), h('span', { class: 'spacer' }),
      ev.status === 'running' ? h('span', { class: 'muted' }, '评测中…') : h('button', { onclick: start }, ev.status ? '重新评测' : '评测这次运行'));
    if (!ev.status) {
      panel.replaceChildren(head, h('p', { class: 'muted' }, '用 AI 按控制流、耗时、工具、Token、错误恢复和交付六个维度给这次运行打分，并给出可以直接应用到 SOP 或角色的改进建议（使用你的凭据）。'));
      return;
    }
    if (ev.status === 'running') { panel.replaceChildren(head, h('p', { class: 'muted' }, `由 ${ev.requestedBy} 发起 · ${time(ev.createdAt)}`)); poll(); return; }
    if (ev.status === 'failed') { panel.replaceChildren(head, h('div', { class: 'error' }, ev.error || '评测失败')); return; }
    const chosen = new Set();
    const recs = (ev.recommendations || []).map((r) => {
      const box = h('input', { type: 'checkbox', disabled: r.status === 'applied' });
      box.addEventListener('change', () => { if (box.checked) chosen.add(r.id); else chosen.delete(r.id); });
      return h('label', { class: 'rec' }, box, h('div', {},
        h('strong', {}, r.title || r.action), ' ', badge(r.status === 'applied' ? 'succeeded' : r.status === 'failed' ? 'failed' : 'pending', REC_STATUS[r.status] || r.status),
        h('span', { class: 'muted' }, ` ${r.target === 'agent' ? `角色 ${r.targetId || ''}` : `SOP 节点 ${r.nodeId || ''}`} · ${r.action}`),
        r.rationale ? h('div', { class: 'muted' }, r.rationale) : null,
        Object.keys(r.set || {}).length ? h('div', { class: 'mono' }, json(r.set)) : null,
        r.gate ? h('div', { class: 'mono' }, `门禁 ${r.gate.id}：${(r.gate.commands || []).join(' && ')}`) : null,
        r.text ? h('div', { class: 'msg-body' }, r.text) : null,
        r.applyError ? h('div', { class: 'error' }, r.applyError) : null));
    });
    const apply = () => chosen.size === 0 ? alert('先选择要应用的建议')
      : attempt(() => api(`${base}/eval/apply`, { method: 'POST', body: { evalId: ev.id, recommendationIds: [...chosen] } }), () => load());
    panel.replaceChildren(head,
      h('div', { class: 'row' }, h('span', { class: 'score' }, String(ev.score)), badge(ev.passed ? 'succeeded' : 'failed', ev.passed ? '达标' : '未达标'),
        h('span', { class: 'muted' }, `准入线 ${ev.threshold} · ${ev.requestedBy} · ${time(ev.finishedAt)} · ${num(ev.totalTokens)} Token`)),
      h('p', {}, ev.summary),
      h('table', {}, h('tr', {}, ['维度', '得分', ''].map((t) => h('th', {}, t))),
        Object.keys(EVAL_DIMENSIONS).filter((k) => k in (ev.dimensions || {})).map((k) => [k, ev.dimensions[k]]).map(([k, v]) => h('tr', {}, h('td', {}, EVAL_DIMENSIONS[k] || k), h('td', { class: 'mono' }, String(v)),
          h('td', {}, h('meter', { min: 0, max: 100, low: 50, high: 70, optimum: 100, value: v }))))),
      (ev.findings || []).length ? h('div', {}, h('h3', {}, '发现'), h('ul', {}, ev.findings.map((f) => h('li', {}, badge(f.severity === 'error' ? 'failed' : f.severity === 'warn' ? 'waiting' : 'pending', { error: '严重', warn: '警告', info: '提示' }[f.severity] || f.severity),
        ` ${f.nodeId ? `[${f.nodeId}] ` : ''}${f.message}`, f.evidence ? h('span', { class: 'muted' }, ` · ${f.evidence}`) : null)))) : null,
      recs.length ? h('div', {}, h('h3', {}, '改进建议'), ...recs,
        h('div', { class: 'row mt' }, h('span', { class: 'muted' }, '应用到当前的 SOP 或角色（不改这次运行）；改 SOP 需要项目维护者，改角色需要管理员，每次修改都留有历史版本。'),
          h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: apply }, '应用所选'))) : null);
  };
  load();
  return panel;
}

// ------------------------------------------------------------------ requirement clarification

/** Server-sent data: JSON for objects, plain text for strings. */
function sseData(event) {
  try { return JSON.parse(event.data); } catch { return event.data; }
}

const CLARIFY_STATUS = { new: '未开始', analyzing: '分析中', cancelling: '停止中', in_progress: '待回答', ready: '已澄清' };
const WRITE_BACK = { succeeded: '已回写原单', failed: '回写失败', skipped: '未回写（非外部需求或已关闭回写）', pending: '待回写' };

/** One analyst result of the history (stored as the protocol JSON). */
function analystEntry(content) {
  let result = null;
  try { result = JSON.parse(content); } catch { /* older or foreign text */ }
  if (!result || typeof result !== 'object') return h('div', { class: 'msg-body' }, content);
  return h('div', {}, result.summary ? h('div', { class: 'msg-body' }, result.summary) : null,
    (result.questions || []).length ? h('ol', {}, result.questions.map((q) => h('li', {}, q.question,
      (q.options || []).length ? h('span', { class: 'muted' }, `（${q.options.join(' / ')}）`) : null))) : null,
    result.ready ? h('div', { class: 'notice' }, '分析师给出了结论') : null);
}

// ------------------------------------------------------------------ run workspace

const CHANGE_LABEL = { M: '修改', A: '新增', D: '删除', R: '重命名', C: '复制', '?': '未跟踪', U: '冲突' };

async function workspacePage(runId) {
  const base = `/runs/${encodeURIComponent(runId)}/workspace`;
  const q = (path) => encodeURIComponent(path);
  const info = await api(base);
  const head = h('div', { class: 'row' }, h('h1', {}, '工作区'), h('span', { class: 'spacer' }), h('a', { class: 'btn', href: `#/runs/${encodeURIComponent(runId)}` }, '返回运行'));
  if (!info.available) {
    const snapshot = info.snapshot;
    shell([head, h('p', { class: 'muted mono' }, runId), h('div', { class: 'notice' }, info.reason || '工作区不可用'),
      snapshot ? h('div', { class: 'panel' }, h('strong', {}, '工作区快照'),
        h('p', { class: 'muted' }, `${time(snapshot.savedAt)} 保存 · ${snapshot.files} 个条目 · ${(snapshot.bytes / 1048576).toFixed(1)} MB（压缩后 ${(snapshot.archiveBytes / 1048576).toFixed(1)} MB）。从某个节点重跑时会自动恢复；下载的压缩包不含密钥、.env 等敏感文件。`),
        h('button', { onclick: () => attempt(async () => {
          const response = await fetch(`/api/v1/runs/${encodeURIComponent(runId)}/workspace/snapshot`, { headers: { Authorization: `Bearer ${token()}` } });
          if (!response.ok) throw new ApiError(response.status, null);
          const link = h('a', { href: URL.createObjectURL(await response.blob()), download: `workspace-${runId}.zip` });
          document.body.append(link); link.click();
          setTimeout(() => { URL.revokeObjectURL(link.href); link.remove(); }, 1000);
        }) }, '下载快照')) : null]);
    return;
  }
  const view = h('div', { class: 'panel ws-view' }, h('p', { class: 'muted' }, '从左侧选择文件、变更或提交。'));
  const showText = (title, text, extra) => view.replaceChildren(h('div', { class: 'row' }, h('strong', { class: 'mono' }, title), h('span', { class: 'spacer' }), ...(extra || [])),
    h('pre', { class: 'mono ws-code' }, text));
  const openFile = (path, ref) => attempt(() => api(`${base}/file?path=${q(path)}${ref ? '&ref=' + q(ref) : ''}`), (f) => {
    const edit = info.readOnly || ref ? null : h('button', { onclick: () => {
      const area = h('textarea', { class: 'mono ws-edit', rows: 24 }, f.content);
      view.replaceChildren(h('div', { class: 'row' }, h('strong', { class: 'mono' }, path), h('span', { class: 'spacer' }),
        h('button', { onclick: () => openFile(path) }, '取消'),
        h('button', { class: 'primary', onclick: () => attempt(() => api(`${base}/file`, { method: 'PUT', body: { path, content: area.value, expectedSha256: f.sha256 } }), () => openFile(path)) }, '保存')),
      h('p', { class: 'muted' }, '保存时若文件已被 Agent 或他人改动会被拒绝，请重新打开后再改。'), area);
    } }, '编辑');
    showText(ref ? `${path} @ ${ref.slice(0, 10)}` : path, f.content, [h('span', { class: 'muted' }, `${f.size} 字节`), edit].filter(Boolean));
  });
  const openDiff = (path) => attempt(() => api(`${base}/diff?path=${q(path)}`), (d) => showText(`${path}（与 HEAD 对比）`, d.diff || '（无差异）', [h('button', { onclick: () => openFile(path) }, '查看当前内容')]));
  const changes = h('div', {}, info.changes.length === 0 ? h('p', { class: 'muted' }, '没有未提交的变更') : info.changes.map((c) => {
    const code = c.index !== ' ' && c.index !== '?' ? c.index : c.worktree;
    return h('div', { class: 'row ws-row' }, h('span', { class: `chip ws-${code === '?' ? 'u' : code}` }, CHANGE_LABEL[code] || code),
      h('a', { href: '#', class: 'mono', onclick: (e) => { e.preventDefault(); if (code === '?') openFile(c.path); else openDiff(c.path); } }, c.path));
  }));
  const tree = h('div', { class: 'ws-tree' }, h('p', { class: 'muted' }, '加载中…'));
  api(`${base}/tree`).then((t) => tree.replaceChildren(...[...t.entries.map((e) => {
    const depth = e.path.split('/').length - 1;
    return e.type === 'directory'
      ? h('div', { class: `ws-dir d${Math.min(depth, 8)}` }, `${e.name}/`)
      : h('div', { class: `d${Math.min(depth, 8)}` }, h('a', { href: '#', class: 'mono', onclick: (ev) => { ev.preventDefault(); openFile(e.path); } }, e.name));
  }), t.truncated ? h('p', { class: 'muted' }, '文件过多，只显示前 5000 项') : null].filter(Boolean))).catch((error) => tree.replaceChildren(errorBox(error)));
  const search = h('input', { placeholder: '搜索文件内容（至少 2 个字符）' });
  const hits = h('div', {});
  search.addEventListener('change', () => attempt(() => api(`${base}/search?q=${q(search.value)}`), (r) => hits.replaceChildren(...[
    ...(r.results.length === 0 ? [h('p', { class: 'muted' }, '没有匹配')] : r.results.map((x) => h('div', { class: 'ws-row' },
      h('a', { href: '#', class: 'mono', onclick: (e) => { e.preventDefault(); openFile(x.path); } }, `${x.path}:${x.line}`), h('div', { class: 'muted mono' }, x.preview)))),
    r.truncated ? h('p', { class: 'muted' }, '结果已截断') : null].filter(Boolean))));
  const history = h('div', {});
  const showCommit = (repo, c) => attempt(() => api(`${base}/commit?repository=${q(repo)}&id=${q(c.id)}`), (detail) => view.replaceChildren(
    h('strong', {}, `${c.id.slice(0, 10)} ${c.message}`),
    h('p', { class: 'muted' }, `${c.author} · ${time(c.timestamp)}`),
    ...detail.files.map((f) => h('div', { class: 'row ws-row' }, h('span', { class: 'chip' }, CHANGE_LABEL[f.status] || f.status),
      h('a', { href: '#', class: 'mono', onclick: (ev) => { ev.preventDefault(); openFile(repo === 'workspace' ? f.path : `${repo}/${f.path}`, c.id); } }, f.path)))));
  const commitRow = (repo, c) => h('div', { class: 'ws-row' },
    h('a', { href: '#', class: 'mono', onclick: (e) => { e.preventDefault(); showCommit(repo, c); } }, `${c.id.slice(0, 8)} ${c.message}`),
    h('span', { class: 'muted' }, ` ${c.author} · ${time(c.timestamp)}`));
  const loadLog = (repo) => attempt(() => api(`${base}/log?repository=${q(repo)}`), (l) => history.replaceChildren(
    h('p', { class: 'muted' }, `${repo} · 分支 ${l.branch || '—'}`), ...l.commits.map((c) => commitRow(repo, c))));
  const repoPicker = h('select', {}, info.repositories.map((r) => h('option', { value: r }, r)));
  repoPicker.addEventListener('change', () => loadLog(repoPicker.value));
  if (info.repositories.length) loadLog(info.repositories[0]);
  shell([head,
    h('p', { class: 'muted mono' }, `${runId} · ${info.runStatus}${info.shared ? ' · 子 SOP 共享父运行的工作区' : ''}${info.readOnly ? ' · 只读' : ''}`),
    info.warning ? h('div', { class: 'notice' }, info.warning) : null,
    h('div', { class: 'ws-layout' },
      h('div', { class: 'ws-side' },
        h('div', { class: 'panel' }, h('h2', {}, `变更（${info.changes.length}）`), changes),
        h('div', { class: 'panel' }, h('h2', {}, '搜索'), search, hits),
        info.repositories.length ? h('div', { class: 'panel' }, h('div', { class: 'row' }, h('h2', {}, '提交'), h('span', { class: 'spacer' }),
          info.repositories.length > 1 ? repoPicker : null), history) : null,
        h('div', { class: 'panel' }, h('h2', {}, '文件'), tree)),
      view),
    info.readOnly || info.shared ? null : terminalPanel(`${base}/terminal`, '在工作区沙箱里运行；有节点运行时不能打开')]);
}

// ------------------------------------------------------------------ workspace terminal

let xtermLoading = null;

/** xterm.js (MIT, vendored under /console/vendor/xterm), loaded the first time a terminal opens. */
function loadXterm() {
  if (window.Terminal) return Promise.resolve(window.Terminal);
  if (!xtermLoading) {
    xtermLoading = new Promise((resolve, reject) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = '/console/vendor/xterm/xterm.css';
      document.head.append(css);
      const script = document.createElement('script');
      script.src = '/console/vendor/xterm/xterm.js';
      script.onload = () => resolve(window.Terminal);
      script.onerror = () => { xtermLoading = null; reject(new Error('终端组件加载失败')); };
      document.head.append(script);
    });
  }
  return xtermLoading;
}

/** A shell in a workspace (upstream workspace terminal): polled while the page is open. */
function terminalPanel(path, hint) {
  const host = h('div', { class: 'term-host' });
  const note = h('span', { class: 'muted' }, hint || '');
  const openButton = h('button', { class: 'primary' }, '打开终端');
  const closeButton = h('button', { class: 'danger', hidden: true }, '关闭终端');
  const page = location.hash;
  let id = null;
  let term = null;
  let timer = null;
  let pending = '';
  let sending = false;
  const stop = (message) => {
    clearTimeout(timer);
    id = null;
    closeButton.hidden = true;
    openButton.hidden = false;
    if (message) note.textContent = message;
  };
  const poll = async () => {
    if (!id) return;
    if (location.hash !== page || !host.isConnected) {
      api(path, { method: 'POST', body: { operation: 'close', id } }).catch(() => {});
      stop();
      return;
    }
    try {
      const r = await api(path, { method: 'POST', body: { operation: 'read', id } });
      if (r.data) term.write(Uint8Array.from(atob(r.data), (c) => c.charCodeAt(0)));
      if (r.exited) { stop('终端已退出'); return; }
    } catch (error) { stop(error.message); return; }
    timer = setTimeout(poll, 250);
  };
  const flush = async () => {
    if (sending || !pending || !id) return;
    sending = true;
    const data = pending;
    pending = '';
    try { await api(path, { method: 'POST', body: { operation: 'input', id, data } }); } catch (error) { note.textContent = error.message; } finally {
      sending = false;
      if (pending) flush();
    }
  };
  openButton.addEventListener('click', () => attempt(async () => {
    const Terminal = await loadXterm();
    if (!term) {
      term = new Terminal({ cursorBlink: true, fontSize: 13, scrollback: 5000 });
      term.open(host);
      term.onData((d) => { pending += d; flush(); });
    }
    term.reset();
    const cols = Math.max(40, Math.min(300, Math.floor((host.clientWidth || 800) / 8.4)));
    const rows = 24;
    term.resize(cols, rows);
    const r = await api(path, { method: 'POST', body: { operation: 'open', cols, rows } });
    id = r.id;
    openButton.hidden = true;
    closeButton.hidden = false;
    note.textContent = `离开页面 ${r.idleSeconds} 秒后自动关闭`;
    term.focus();
    poll();
  }));
  closeButton.addEventListener('click', () => {
    const old = id;
    stop('终端已关闭');
    if (old) api(path, { method: 'POST', body: { operation: 'close', id: old } }).catch(() => {});
  });
  return h('div', { class: 'panel' }, h('div', { class: 'row' }, h('h2', {}, '终端'), h('span', { class: 'spacer' }), note, openButton, closeButton), host);
}

// ------------------------------------------------------------------ requirement lanes

const LANE_STATUS = { not_started: '未开始', active: '进行中', waiting: '等待处理', attention: '需关注', succeeded: '已完成', closed: '已关闭' };
const LANE_BADGE = { not_started: 'pending', active: 'running', waiting: 'waiting', attention: 'failed', succeeded: 'succeeded', closed: 'cancelled' };
const SOP_LANES = { product: '产品', frontend: '前端', backend: '后端', design: '设计', repair: '修复', other: '其他' };

function chainEditor(bindings, workflows, onChange) {
  const rows = bindings.map((b) => ({ ...b, dependsOn: [...(b.dependsOn || [])], handoff: [...(b.handoff || [])] }));
  const box = h('div', {});
  const emit = () => onChange(rows.map((r, i) => ({ id: r.id || undefined, workflowId: r.workflowId, order: i + 1, assignee: r.assignee || undefined,
    dependsOn: r.dependsOn.filter(Boolean), handoff: r.handoff.filter(Boolean) })));
  const render = () => {
    box.replaceChildren(...rows.map((r, i) => {
      const wf = h('select', {}, workflows.map((w) => h('option', { value: w.id, selected: w.id === r.workflowId }, `${w.name}（${w.id}）`)));
      wf.addEventListener('change', () => { r.workflowId = wf.value; r.id = undefined; emit(); });
      const who = h('input', { value: r.assignee || '', placeholder: '负责人' });
      who.addEventListener('change', () => { r.assignee = who.value.trim(); emit(); });
      const deps = h('input', { value: r.dependsOn.join(','), placeholder: '依赖步骤 id（逗号分隔，默认上一步）' });
      deps.addEventListener('change', () => { r.dependsOn = deps.value.split(',').map((x) => x.trim()); emit(); });
      const hand = h('input', { value: r.handoff.join(','), placeholder: '交接产物（逗号分隔，如 prd.md）' });
      hand.addEventListener('change', () => { r.handoff = hand.value.split(',').map((x) => x.trim()); emit(); });
      return h('div', { class: 'row mb' }, h('span', { class: 'mono' }, `${i + 1}. ${r.id || 'sop-' + r.workflowId}`), wf, who, deps, hand,
        h('button', { disabled: i === 0, onclick: () => { [rows[i - 1], rows[i]] = [rows[i], rows[i - 1]]; emit(); render(); } }, '↑'),
        h('button', { class: 'danger', onclick: () => { rows.splice(i, 1); emit(); render(); } }, '移除'));
    }), h('button', { disabled: workflows.length === 0, onclick: () => { rows.push({ workflowId: workflows[0].id, dependsOn: [], handoff: [] }); emit(); render(); } }, '+ SOP 步骤'));
  };
  render();
  return box;
}

function businessDomainRow(project, refresh) {
  const row = h('div', { class: 'row mb' }, h('span', { class: 'muted' }, `业务域：${project.businessDomainId || '未归属'}`));
  api('/business-domains').then((list) => {
    const options = list.items.filter((i) => i.domain.active).map((i) => [i.domain.id, i.domain.name]);
    const assign = () => prompted('业务域归属', [
      { name: 'businessDomainId', label: '业务域', type: 'select', options: ['', ...options.map(([id]) => id)], value: project.businessDomainId || '' },
      { name: 'reason', label: '原因', type: 'textarea', rows: 2 }],
    (v) => api(`/projects/${encodeURIComponent(project.id)}/business-domain`, { method: 'PUT',
      body: { businessDomainId: v.businessDomainId || null, expectedDomainId: project.businessDomainId || null, reason: v.reason } }).then(refresh));
    const history = () => attempt(() => api(`/projects/${encodeURIComponent(project.id)}/business-domain/history`), (rows) => modal('归属历史',
      rows.length === 0 ? h('p', { class: 'muted' }, '还没有调整过') : h('table', {}, h('tr', {}, ['时间', '从', '到', '操作人', '原因'].map((t) => h('th', {}, t))),
        rows.map((r) => h('tr', {}, [time(r.createdAt), r.fromDomainId || '未归属', r.toDomainId || '未归属', r.actor || '—', r.reason || ''].map((x) => h('td', {}, x))))), () => null));
    const name = list.items.find((i) => i.domain.id === project.businessDomainId)?.domain.name;
    row.replaceChildren(h('span', { class: 'muted' }, `业务域：${name || project.businessDomainId || '未归属'}`),
      h('button', { onclick: assign }, project.businessDomainId ? '调整归属' : '设置归属'), h('button', { onclick: history }, '归属历史'));
  }).catch(() => {});
  return row;
}

function projectMcpSection(projectId, refresh) {
  const panel = h('div', {});
  api(`/projects/${encodeURIComponent(projectId)}/mcp-servers`).then((servers) => {
    const add = () => prompted('添加项目 MCP（HTTP）', [{ name: 'name', label: '名称' }, { name: 'url', label: 'URL（https://…）' }, { name: 'description', label: '说明' }],
      (v) => api(`/projects/${encodeURIComponent(projectId)}/mcp-servers`, { method: 'POST', body: v }).then(refresh));
    panel.replaceChildren(h('div', { class: 'row' }, h('h2', {}, 'MCP'), h('span', { class: 'spacer' }), h('button', { onclick: () => mcpMarket() }, 'MCP 市场'), h('button', { onclick: add }, '添加自定义')),
      servers.length === 0 ? h('p', { class: 'muted' }, '项目还没有 MCP：从市场添加，或添加自定义 HTTP MCP。角色在这个项目的运行里通过 id 或市场 id 使用它们。') :
        h('table', {}, h('tr', {}, ['id', '名称', '来源', 'URL', ''].map((t) => h('th', {}, t))),
          servers.map((m) => h('tr', {}, h('td', { class: 'mono' }, m.id), h('td', {}, m.name), h('td', {}, m.placement?.marketId ? `市场 · ${m.placement.marketId}` : '自定义'),
            h('td', { class: 'mono' }, m.url || '—'),
            h('td', {}, h('button', { class: 'danger', onclick: () => confirm(`删除 ${m.id}？`) && attempt(() => api(`/projects/${encodeURIComponent(projectId)}/mcp-servers/${encodeURIComponent(m.id)}`, { method: 'DELETE' }), refresh) }, '删除'))))));
  }).catch(() => panel.replaceChildren());
  return panel;
}

function playbooksSection(projectId, workflows, refresh) {
  const panel = h('div', {}, h('div', { class: 'row' }, h('h2', {}, '流程套餐')));
  api(`/projects/${projectId}/playbooks`).then((books) => {
    const edit = () => {
      let draft = books.map((b) => ({ ...b }));
      const body = h('div', {});
      const render = () => body.replaceChildren(...draft.map((b, i) => {
        const name = h('input', { value: b.name || '' });
        name.addEventListener('change', () => { b.name = name.value; });
        const def = h('input', { type: 'radio', name: 'default-book', checked: !!b.default });
        def.addEventListener('change', () => { draft.forEach((x) => { x.default = x === b; }); });
        return h('div', { class: 'panel' }, h('div', { class: 'row' }, name, h('label', { class: 'row' }, def, '默认'), h('span', { class: 'spacer' }),
          h('button', { class: 'danger', onclick: () => { draft.splice(i, 1); render(); } }, '删除套餐')),
        chainEditor(b.bindings || [], workflows, (next) => { b.bindings = next; }));
      }), h('button', { onclick: () => { draft.push({ name: '新套餐', default: draft.length === 0, bindings: [] }); render(); } }, '+ 套餐'));
      render();
      modal('流程套餐', h('div', {}, h('p', { class: 'muted' }, '新需求默认使用「默认」套餐的 SOP 链；每条 SOP 是需求的一条泳道，可以并行，后面的步骤会在输入里收到前序步骤与预期产物。'), body),
        (dialog) => h('button', { class: 'primary', onclick: () => attempt(() => api(`/projects/${projectId}/playbooks`, { method: 'PUT', body: draft }), () => { dialog.close(); refresh(); }) }, '保存'));
    };
    panel.replaceChildren(h('div', { class: 'row' }, h('h2', {}, '流程套餐'), h('span', { class: 'spacer' }), h('button', { onclick: edit }, '编辑')),
      books.length === 0 ? h('p', { class: 'muted' }, '还没有套餐：新需求只用自己选的 SOP。') : h('table', {}, h('tr', {}, ['套餐', 'SOP 链'].map((t) => h('th', {}, t))),
        books.map((b) => h('tr', {}, h('td', {}, b.name, b.default ? h('span', { class: 'muted' }, '（默认）') : null),
          h('td', { class: 'mono' }, (b.bindings || []).map((x) => x.workflowId).join(' → '))))));
  }).catch(() => panel.replaceChildren());
  return panel;
}

async function requirementPage(projectId, number) {
  const base = `/projects/${encodeURIComponent(projectId)}/requirements/${number}`;
  const [snap, sops, workflows] = await Promise.all([api(`${base}/snapshot`), api(`${base}/sops`), api('/workflows').catch(() => [])]);
  const refresh = () => requirementPage(projectId, number);
  const r = snap.requirement;
  const o = snap.overall;
  const published = workflows.filter((w) => w.status === 'published' && (!w.projectId || w.projectId === projectId));
  const start = (lane, rerun) => prompted(`${rerun ? '重新运行' : '运行'} · ${lane.label}`, [{ name: 'input', label: '补充说明', type: 'textarea', rows: 3 }],
    (v) => api(`${base}/runs`, { method: 'POST', body: { workflowId: lane.workflowId, input: v.input || undefined, rerun } }).then((run) => { location.hash = `#/runs/${run.id}`; return run; }));
  const stages = (list) => h('div', { class: 'row' }, (list || []).map((st) => h('span', { class: `chip st-${st.status}` }, `${st.name}${st.durationMs ? ' · ' + Math.round(st.durationMs / 1000) + 's' : ''}`,
    st.children ? h('span', { class: 'muted' }, ` [${st.children.map((c) => c.name).join(' / ')}]`) : null)));
  const laneCard = (l) => h('div', { class: 'panel' },
    h('div', { class: 'row' }, h('strong', {}, l.label), h('span', { class: 'chip' }, SOP_LANES[l.lane] || l.lane), badge(LANE_BADGE[l.status] || 'pending', LANE_STATUS[l.status] || l.status),
      l.orphan ? h('span', { class: 'muted' }, '（不在 SOP 链中）') : null, h('span', { class: 'spacer' }),
      l.latestRunId ? h('a', { class: 'btn', href: `#/runs/${l.latestRunId}` }, '最近运行') : null,
      !l.started ? h('button', { class: 'primary', onclick: () => start(l, false) }, '运行')
        : ['active', 'waiting'].includes(l.status) ? null : h('button', { onclick: () => start(l, true) }, '重新运行')),
    h('p', { class: 'muted' }, [l.order ? `第 ${l.order} 步` : null, l.assignee ? `负责人 ${l.assignee}` : null, (l.dependsOn || []).length ? `依赖 ${l.dependsOn.join('、')}` : null,
      (l.handoff || []).length ? `交接 ${l.handoff.join('、')}` : null, l.started ? `${l.runCount} 次运行${l.failedRuns ? `（失败 ${l.failedRuns}）` : ''} · ${l.tokens} tokens` : null].filter(Boolean).join(' · ')),
    stages(l.stages));
  let chain = sops.bindings || [];
  const playbook = h('select', {}, [h('option', { value: '' }, '—'), ...sops.playbooks.map((b) => h('option', { value: b.id, selected: b.id === sops.playbookId }, b.name))]);
  shell([
    h('div', { class: 'row' }, h('h1', {}, `#${r.number} ${r.title}`), badge(r.status), h('span', { class: 'spacer' }),
      h('a', { class: 'btn', href: `#/projects/${encodeURIComponent(projectId)}/requirements/${number}/clarify` }, '澄清'),
      h('a', { class: 'btn', href: `#/projects/${encodeURIComponent(projectId)}` }, '返回项目')),
    r.description ? h('details', { class: 'panel' }, h('summary', {}, '描述'), h('pre', {}, r.description)) : null,
    h('p', { class: 'muted' }, `整体 ${LANE_STATUS[o.status] || o.status} · ${o.runCount} 次运行 · 完成 ${o.succeededLanes} 条泳道 · 进行中 ${o.activeLanes} · ${o.tokens} tokens${o.cycleDurationMs ? ' · 周期 ' + Math.round(o.cycleDurationMs / 60000) + ' 分钟' : ''}`),
    (snap.stages || []).length ? h('div', { class: 'panel' }, h('h2', {}, '阶段'), stages(snap.stages.map((st) => ({ name: st.label, status: st.status })))) : null,
    h('h2', {}, 'SOP 泳道'),
    snap.lanes.length === 0 ? h('p', { class: 'muted' }, '还没有绑定 SOP：在下方选择套餐或添加 SOP 步骤。') : null,
    ...snap.lanes.map(laneCard),
    h('div', { class: 'panel' }, h('h2', {}, 'SOP 链'),
      h('div', { class: 'row mb' }, h('span', {}, '套餐'), playbook,
        h('button', { onclick: () => attempt(() => api(`${base}/sops`, { method: 'PUT', body: { playbookId: playbook.value } }), refresh), disabled: sops.playbooks.length === 0 }, '应用套餐')),
      chainEditor(chain, published, (next) => { chain = next; }),
      h('div', { class: 'row' }, h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: () => attempt(() => api(`${base}/sops`, { method: 'PUT', body: { bindings: chain } }), refresh) }, '保存 SOP 链'))),
  ]);
}

async function clarifyPage(projectId, number) {
  const base = `/projects/${encodeURIComponent(projectId)}/requirements/${number}`;
  const [requirement, initial] = await Promise.all([api(base), api(`${base}/clarification`)]);
  let state = initial;
  const header = h('div', {});
  const history = h('div', { class: 'chat-feed clarify-feed' });
  const live = h('div', { class: 'log live' });
  const questionsBox = h('div', {});
  const conclusion = h('div', {});
  const notes = h('textarea', { rows: 3, placeholder: '补充说明（可选）：回答之外想告诉分析师的内容' });
  const startButton = h('button', { class: 'primary' }, '开始澄清');
  const stopButton = h('button', { class: 'danger' }, '停止');
  const answers = new Map();

  const running = () => state.status === 'analyzing' || state.status === 'cancelling';
  const renderHeader = () => header.replaceChildren(...[
    h('div', { class: 'row' }, h('h1', {}, `澄清需求 #${requirement.number} ${requirement.title}`), h('span', { class: 'spacer' }),
      h('a', { class: 'btn', href: `#/projects/${encodeURIComponent(projectId)}` }, '返回项目')),
    h('p', { class: 'muted' }, `${CLARIFY_STATUS[state.status] || state.status} · 沙箱 ${state.sandboxProfileId || '—'} · ${num(state.totalTokens || 0)} Token · ${usd(state.costUsd || 0)}`,
      state.writeBack ? ` · ${WRITE_BACK[state.writeBack] || state.writeBack}` : '', state.writeBackError ? `：${state.writeBackError}` : ''),
    state.error ? h('div', { class: 'error' }, state.error, state.pending ? `（未完成的回答：${state.pending}）` : '') : null,
    h('details', {}, h('summary', {}, '需求描述'), h('div', { class: 'msg-body' }, requirement.description || '（无）'))].filter(Boolean));
  const renderHistory = () => {
    history.replaceChildren(...state.history.map((m) => h('div', { class: `msg ${m.role === 'assistant' ? 'assistant' : 'user'}` },
      h('div', { class: 'msg-meta muted' }, `${m.role === 'assistant' ? '需求分析师' : m.author || '用户'} · ${time(m.at)}`),
      m.role === 'assistant' ? analystEntry(m.content) : h('div', { class: 'msg-body' }, m.content))));
    if (!state.history.length) history.append(h('p', { class: 'muted' }, '分析师会读取需求和绑定仓库的代码（只读），只追问阻塞开工的问题；能按现有代码惯例假设的直接写进结论。'));
    history.scrollTop = history.scrollHeight;
  };
  const renderQuestions = () => {
    answers.clear();
    if (state.status !== 'in_progress' || !state.questions.length) { questionsBox.replaceChildren(); return; }
    questionsBox.replaceChildren(h('h2', {}, '待回答'), ...state.questions.map((q) => {
      const chosen = new Set();
      answers.set(q.id, { question: q.question, chosen, text: '' });
      const text = h('input', { placeholder: q.freeText ? '填写回答' : '其他（可选）' });
      text.addEventListener('input', () => { answers.get(q.id).text = text.value; });
      const options = (q.options || []).map((option) => {
        const input = h('input', { type: q.multiSelect ? 'checkbox' : 'radio', name: `q-${q.id}`, value: option });
        input.addEventListener('change', () => {
          if (!q.multiSelect) chosen.clear();
          if (input.checked) chosen.add(option); else chosen.delete(option);
        });
        return h('label', { class: 'option' }, input, ` ${option}`);
      });
      return h('div', { class: 'panel question' }, h('strong', {}, q.question), h('div', { class: 'row wrap' }, options), text);
    }));
  };
  const renderConclusion = () => {
    if (running() || (!state.summary && state.status !== 'ready')) { conclusion.replaceChildren(); return; }
    const editor = h('textarea', { rows: 10 }, state.summary || '');
    const save = (ready) => attempt(() => api(`${base}/clarification`, { method: 'PUT', body: { summary: editor.value, ready } }), (next) => { state = next; render(); });
    conclusion.replaceChildren(h('h2', {}, state.status === 'ready' ? '结论（已写入需求描述）' : '当前理解'), editor,
      h('div', { class: 'row mt' }, h('span', { class: 'spacer' }),
        h('button', { onclick: () => save(false) }, '保存草稿'),
        h('button', { class: 'primary', onclick: () => save(true) }, state.status === 'ready' ? '更新结论' : '确认为结论')));
  };
  const message = () => {
    const parts = [];
    for (const { question, chosen, text } of answers.values()) {
      const picked = [...chosen, ...(text.trim() ? [text.trim()] : [])];
      if (picked.length) parts.push(`问：${question}\n答：${picked.join('；')}`);
    }
    if (notes.value.trim()) parts.push(`补充：${notes.value.trim()}`);
    return parts.join('\n\n');
  };
  const render = () => {
    renderHeader(); renderHistory(); renderQuestions(); renderConclusion();
    startButton.textContent = state.status === 'new' ? '开始澄清' : state.status === 'ready' ? '继续澄清' : '提交回答';
    startButton.disabled = running();
    stopButton.hidden = !running();
    if (!running()) live.replaceChildren();
  };
  startButton.addEventListener('click', () => attempt(() => api(`${base}/clarification`, { method: 'POST', body: { message: message() || undefined } }),
    (next) => { state = next; notes.value = ''; live.replaceChildren(h('div', {}, '分析中…')); render(); }));
  stopButton.addEventListener('click', () => attempt(() => api(`${base}/clarification/cancel`, { method: 'POST' })));
  const reset = () => confirm('清空澄清对话并删除分析工作区？需求描述里已有的结论会保留。')
    && attempt(() => api(`${base}/clarification`, { method: 'DELETE' }), () => clarifyPage(projectId, number));
  shell([header, history, live, questionsBox,
    h('div', { class: 'panel mt' }, notes, h('div', { class: 'row mt' }, h('button', { onclick: reset }, '重新开始'), h('span', { class: 'spacer' }), stopButton, startButton)),
    conclusion]);
  render();
  stream = new EventSource(`/api/v1${base}/clarification/stream?access_token=${encodeURIComponent(token())}`);
  stream.addEventListener('activity', (e) => { live.append(h('div', {}, String(sseData(e)))); live.scrollTop = live.scrollHeight; });
  stream.addEventListener('done', (e) => { state = sseData(e); render(); });
  stream.addEventListener('status', (e) => { if (sseData(e).status === 'analyzing' && !running()) { state = { ...state, status: 'analyzing' }; render(); } });
}

route();
