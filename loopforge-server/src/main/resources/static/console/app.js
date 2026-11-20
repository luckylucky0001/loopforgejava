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
  ['#/assets/rules', '规则 / Skills / MCP'], ['#/repositories', '代码仓库'], ['#/projects', '项目'],
  ['#/analytics', '分析'], ['#/knowledge', '知识库'], ['#/runners', '执行机器'], ['#/executors', '执行器'], ['#/settings', '设置'],
];

function shell(content) {
  const current = location.hash || '#/runs';
  const nav = h('nav', { class: 'side' }, h('div', { class: 'brand' }, 'LoopForge4J'),
    NAV.map(([href, label]) => h('a', { href, class: current.startsWith(href.split('/').slice(0, 2).join('/')) ? 'active' : '' }, label)),
    me?.user?.role === 'admin' || me?.service ? h('a', { href: '#/users', class: current.startsWith('#/users') ? 'active' : '' }, '账户') : null,
    h('div', { class: 'who' }, me?.user ? `${me.user.displayName || me.user.username}（${me.user.role}）` : me?.service ? '服务令牌' : '',
      h('br'), h('a', { href: '#', onclick: logout }, '退出登录')));
  app.replaceChildren(h('div', { class: 'layout' }, nav, h('main', {}, content)));
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
  [/^#\/runs\/([^/]+)$/, runPage], [/^#\/runs$/, runsPage], [/^#\/approvals$/, approvalsPage],
  [/^#\/workflows\/new$/, () => workflowPage(null)], [/^#\/workflows\/([^/]+)$/, workflowPage], [/^#\/workflows$/, workflowsPage],
  [/^#\/agents\/new$/, () => definitionPage('agents', null)], [/^#\/agents\/([^/]+)$/, (id) => definitionPage('agents', id)],
  [/^#\/agents$/, () => listPage('agents', '角色', ['id', 'name', 'executorId'])],
  [/^#\/assets\/(rules|skills|mcp-servers)\/new$/, (kind) => definitionPage(kind, null)],
  [/^#\/assets\/(rules|skills|mcp-servers)\/([^/]+)$/, (kind, id) => definitionPage(kind, id)],
  [/^#\/assets\/(rules|skills|mcp-servers)$/, assetsPage],
  [/^#\/repositories\/new$/, () => definitionPage('repositories', null)],
  [/^#\/repositories\/([^/]+)$/, (id) => definitionPage('repositories', id)],
  [/^#\/repositories$/, () => listPage('repositories', '代码仓库', ['id', 'name', 'provider', 'cloneUrl', 'projectId'])],
  [/^#\/projects\/([^/]+)\/requirements\/(\d+)\/clarify$/, clarifyPage], [/^#\/projects\/([^/]+)$/, projectPage], [/^#\/projects$/, projectsPage],
  [/^#\/executors$/, executorsPage], [/^#\/settings$/, settingsPage], [/^#\/users$/, usersPage],
  [/^#\/analytics$/, analyticsPage], [/^#\/knowledge$/, knowledgePage], [/^#\/runners$/, runnersPage],
  [/^#\/chat\/([^/]+)$/, chatPage], [/^#\/chat$/, () => chatPage(null)],
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
    h('button', { onclick: exportCatalog }, '导出目录'), h('button', { onclick: importCatalog }, '导入目录')),
  h('table', {}, h('tr', {}, ['id', '名称', '状态', '节点', '修订', '项目'].map((t) => h('th', {}, t))),
    workflows.map((w) => h('tr', {}, h('td', {}, h('a', { href: `#/workflows/${w.id}`, class: 'mono' }, w.id)), h('td', {}, w.name), h('td', {}, badge(w.status)),
      h('td', {}, (w.nodes || []).length), h('td', {}, w.revision), h('td', {}, w.projectId || '—'))))]);
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
const NODE_LABELS = { agent: 'Agent', script: '脚本', approval: '人工审批', sop: '子 SOP' };

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
        ...(typeFields[n.type] || []),
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
      field('沙箱', bind(h('input', { value: wf.sandboxProfileId || 'local', placeholder: 'local / docker / bwrap' }), (v) => { wf.sandboxProfileId = v.trim() || undefined; })),
      field('描述', bind(h('textarea', { rows: 3 }, wf.description || ''), (v) => { wf.description = v || undefined; })),
      h('p', { class: 'muted' }, '拖动节点调整位置；从节点右侧的圆点拖到另一个节点即可连线；点选节点或连线后在这里编辑，Delete 键删除。'));
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
  const [loaded, agents, executors, workflows, projects] = await Promise.all([
    id ? api(`/workflows/${id}`) : Promise.resolve(JSON.parse(JSON.stringify(TEMPLATE))),
    api('/agents').catch(() => []), api('/executors').catch(() => []), api('/workflows').catch(() => []), api('/projects').catch(() => []),
  ]);
  let wf = stripMeta(loaded);
  wf.nodes ||= [];
  wf.edges ||= [];
  const idInput = h('input', { value: id || '', placeholder: 'workflow id', disabled: !!id });
  const problems = h('div', {});
  const editor = h('textarea', { class: 'code', spellcheck: 'false' });
  const body = h('div', {});
  let mode = 'design';
  const catalog = { agents, executors, workflows, projects };
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

async function assetsPage(kind) {
  const tabs = h('div', { class: 'row mb' }, [['rules', '规则'], ['skills', 'Skills'], ['mcp-servers', 'MCP 服务器']].map(([k, label]) =>
    h('a', { class: k === kind ? 'btn current' : 'btn', href: `#/assets/${k}` }, label)));
  await listPage(kind, KINDS[kind].title, kind === 'rules' ? ['id', 'name', 'priority'] : kind === 'skills' ? ['id', 'name', 'description'] : ['id', 'name', 'transport', 'command']);
  document.querySelector('main').prepend(tabs);
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
  kind === 'repositories' ? h('p', { class: 'muted' }, '凭据配置在服务端（loopforge.git），不要写进克隆 URL。') : null,
  message, editor]);
}

async function executorsPage() {
  const executors = await api('/executors');
  shell([h('h1', {}, '执行器'), h('table', {}, h('tr', {}, ['id', '名称', '类型', '命令', '模型', '可用'].map((t) => h('th', {}, t))),
    executors.map((e) => h('tr', {}, h('td', { class: 'mono' }, e.id), h('td', {}, e.name), h('td', {}, e.kind), h('td', { class: 'mono' }, e.binary || '内置'),
      h('td', {}, e.model || '默认'), h('td', {}, e.available === false ? badge('failed') : badge('succeeded')))))]);
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
    { name: 'events', label: '事件（逗号分隔，留空=全部）', placeholder: 'run.failed,run.waiting,run.succeeded' }],
  (v) => api('/notifications', { method: 'POST', body: { projectId, name: v.name, kind: v.kind, url: v.url, secret: v.secret || undefined, enabled: true,
    events: v.events.split(',').map((s) => s.trim()).filter(Boolean) } }).then(refresh));
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
    h('div', { class: 'row' }, h('h2', {}, '需求'), h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: newRequirement }, '新需求')),
    h('table', {}, h('tr', {}, ['#', '标题', '状态', '优先级', 'SOP', '最近运行', '操作'].map((t) => h('th', {}, t))),
      requirements.map((r) => h('tr', {}, h('td', {}, r.number), h('td', {}, r.title,
        r.external ? h('div', { class: 'muted' }, h('a', { href: safeHref(r.external.url), target: '_blank', rel: 'noopener noreferrer' },
          `${r.external.source} ${r.external.kind}${r.external.number ? ' #' + r.external.number : ' ' + r.external.externalId}`), ` · ${r.external.state || ''}`) : null),
        h('td', {}, badge(r.status)), h('td', {}, r.priority),
        h('td', { class: 'mono' }, r.workflowId || '—'), h('td', {}, r.latestRunId ? h('a', { href: `#/runs/${r.latestRunId}`, class: 'mono' }, r.latestRunId.slice(0, 14)) : '—'),
        h('td', {}, h('div', { class: 'row' }, h('a', { class: 'btn', href: `#/projects/${encodeURIComponent(projectId)}/requirements/${r.number}/clarify` }, '澄清'),
          h('button', { onclick: () => startRequirement(r), disabled: ['closed', 'cancelled', 'done'].includes(r.status) }, '启动'),
          r.external ? null : h('button', { onclick: () => pushRequirement(r) }, '推送')))))),
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
        channels.map((c) => h('tr', {}, h('td', {}, c.name), h('td', {}, c.kind || 'webhook'), h('td', { class: 'mono' }, c.url), h('td', {}, (c.events || []).join(', ') || '全部'),
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
  const [tokens, credentials, kinds] = await Promise.all([api('/me/tokens'), api('/me/credentials'), api('/me/credentials/kinds')]);
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
      h('button', { onclick: () => editExecutor(null) }, '添加模型凭据'), h('button', { onclick: () => editGit(null) }, '添加 Git 凭据')),
    h('p', { class: 'muted' }, '你启动的运行、你创建的定时任务、你在助手里的发言和你发起的需求澄清使用这里的凭据；某个执行器配置了个人凭据时完全替代平台的凭据，Git Token 按主机替代。密钥加密保存，任何接口都不会返回。'),
    credentials.length === 0 ? h('p', { class: 'muted' }, '未配置：使用平台管理员配置的凭据。') :
      h('table', {}, h('tr', {}, ['类型', 'Key / Token', 'Base URL / 用户名', '其他变量', '更新', ''].map((t) => h('th', {}, t))),
        credentials.map((c) => h('tr', {}, h('td', { class: 'mono' }, c.kind), h('td', { class: 'mono' }, c.hint || '—'),
          h('td', { class: 'mono' }, c.baseUrl || c.username || '—'), h('td', { class: 'mono' }, (c.variables || []).join(', ') || '—'), h('td', {}, time(c.updatedAt)),
          h('td', {}, h('div', { class: 'row' }, h('button', { onclick: () => (c.kind.startsWith('git:') ? editGit(c) : editExecutor(c)) }, '修改'),
            h('button', { class: 'danger', onclick: () => confirm(`删除 ${c.kind}？`) && attempt(() => api(`/me/credentials/${encodeURIComponent(c.kind)}`, { method: 'DELETE' }), refresh) }, '删除')))))),
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
  const [sessions, projects] = await Promise.all([api('/assistant/sessions'), api('/projects').catch(() => [])]);
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
      h('div', {}, s.title), h('div', { class: 'muted' }, `${s.projectId || '个人'}${s.status !== 'idle' ? ' · 回复中' : ''}`))));
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
    sendButton.disabled = generating;
    stopButton.disabled = !generating;
    live.hidden = !generating;
  };
  const send = () => {
    const content = input.value.trim();
    if (!content || generating) return;
    attempt(() => api(`/assistant/sessions/${current.id}/messages`, { method: 'POST', body: { content } }), () => {
      input.value = '';
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
      h('button', { class: 'danger', onclick: remove }, '删除')),
    feed, live,
    h('div', { class: 'panel mt' }, input, h('div', { class: 'row mt' }, h('span', { class: 'spacer' }), stopButton, sendButton))))]);
  sync();
  feed.scrollTop = feed.scrollHeight;
  stream = new EventSource(`/api/v1/assistant/sessions/${current.id}/stream?access_token=${encodeURIComponent(token())}`);
  stream.addEventListener('activity', (e) => { live.append(h('div', {}, String(sseData(e)))); live.scrollTop = live.scrollHeight; });
  stream.addEventListener('message', (e) => { feed.append(chatMessage(sseData(e))); feed.scrollTop = feed.scrollHeight; });
  stream.addEventListener('status', (e) => { generating = sseData(e).status !== 'idle'; if (!generating) live.replaceChildren(); sync(); });
}

// ------------------------------------------------------------------ run evaluation

const EVAL_DIMENSIONS = { control: '控制流健康', latency: '耗时与等待', tools: '工具浪费', tokens: 'Token 效率', recovery: '错误恢复', delivery: '交付完整' };
const REC_STATUS = { pending: '待应用', applied: '已应用', failed: '应用失败' };

/** The quality evaluation of a settled run: score, dimensions, findings and recommendations to apply. */
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
