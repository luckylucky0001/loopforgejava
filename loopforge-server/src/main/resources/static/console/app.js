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
const badge = (status) => h('span', { class: `badge s-${status}` }, status ?? '—');
const time = (value) => (value ? new Date(value).toLocaleString() : '—');
const json = (value) => JSON.stringify(value, null, 2);

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
  dialog.append(h('h2', {}, title), body, h('div', { class: 'row mt' }, h('span', { class: 'spacer' }),
    h('button', { onclick: () => dialog.close() }, '取消'), actions(dialog)));
  document.body.append(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.showModal();
  return dialog;
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
  ['#/runs', '运行'], ['#/approvals', '待处理'], ['#/workflows', 'SOP'], ['#/agents', '角色'],
  ['#/assets/rules', '规则 / Skills / MCP'], ['#/repositories', '代码仓库'], ['#/projects', '项目'],
  ['#/executors', '执行器'], ['#/settings', '设置'],
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
  [/^#\/projects\/([^/]+)$/, projectPage], [/^#\/projects$/, projectsPage],
  [/^#\/executors$/, executorsPage], [/^#\/settings$/, settingsPage], [/^#\/users$/, usersPage],
];

async function route() {
  if (stream) { stream.close(); stream = null; }
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
      message, h('button', { class: 'primary', type: 'submit' }, status.setupRequired ? '创建并登录' : '登录'))));
}

// ------------------------------------------------------------------ runs

async function runsPage() {
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const runs = await api(`/runs?limit=100${params.get('status') ? '&status=' + params.get('status') : ''}`);
  const workflows = await api('/workflows').catch(() => []);
  const projects = await api('/projects').catch(() => []);
  const workflowSelect = h('select', {}, workflows.filter((w) => w.status === 'published').map((w) => h('option', { value: w.id }, `${w.name}（${w.id}）`)));
  const projectSelect = h('select', {}, h('option', { value: '' }, '（无项目）'), projects.map((p) => h('option', { value: p.id }, p.name)));
  const input = h('textarea', { rows: 3, placeholder: '任务说明' });
  const repos = h('input', { placeholder: '仓库 id，逗号分隔（可选）' });
  const start = () => attempt(() => api('/runs', { method: 'POST', body: {
    workflowId: workflowSelect.value, input: input.value, projectId: projectSelect.value || undefined,
    repositories: repos.value.split(',').map((s) => s.trim()).filter(Boolean).map((repositoryId) => ({ repositoryId })),
  } }), (run) => { location.hash = `#/runs/${run.id}`; });
  shell([
    h('h1', {}, '运行'),
    h('div', { class: 'panel' }, h('div', { class: 'row' }, workflowSelect, projectSelect, repos, h('button', { class: 'primary', onclick: start }, '启动')), h('div', { class: 'mt' }, input)),
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
        run.status === 'failed' ? control('retry', '重试') : null),
      h('p', { class: 'muted mono' }, `${run.id} · ${run.context?.projectId ? '项目 ' + run.context.projectId + ' · ' : ''}${run.context?.triggeredBy || ''} · ${time(run.createdAt)}`),
      run.input ? h('details', { class: 'panel' }, h('summary', {}, '任务输入'), h('pre', {}, run.input)) : null,
      dag(run.workflowSnapshot, statuses, showNode),
      detail,
      (run.repositories || []).length ? h('div', { class: 'panel' }, h('h2', {}, '仓库'), h('table', {}, h('tr', {}, ['仓库', '基线', '工作分支', '目录'].map((t) => h('th', {}, t))),
        run.repositories.map((r) => h('tr', {}, h('td', {}, r.name), h('td', { class: 'mono' }, r.baseBranch), h('td', { class: 'mono' }, r.workBranch || '只读'), h('td', { class: 'mono' }, r.workspaceDir))))) : null,
      (run.deliveries || []).length ? h('div', { class: 'panel' }, h('h2', {}, '交付'), run.deliveries.map((p) =>
        h('div', { class: 'row' }, badge(p.state), h('a', { href: p.url, target: '_blank', rel: 'noopener noreferrer' }, `#${p.number} ${p.headBranch} → ${p.baseBranch}`))),
        h('button', { onclick: () => attempt(() => api(`/runs/${runId}/deliveries/refresh`, { method: 'POST' }), render) }, '刷新 PR 状态')) : null,
      artifacts.length ? h('div', { class: 'panel' }, h('h2', {}, '产物'), h('table', {}, h('tr', {}, ['节点', '名称', '路径', '大小'].map((t) => h('th', {}, t))),
        artifacts.map((a) => h('tr', {}, h('td', {}, `${a.nodeId}#${a.attempt}`), h('td', {}, h('a', { href: '#', onclick: (e) => { e.preventDefault(); showArtifact(runId, a); } }, a.name)),
          h('td', { class: 'mono' }, a.path), h('td', {}, a.size))))) : null,
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

async function workflowPage(id) {
  const workflow = id ? await api(`/workflows/${id}`) : { ...TEMPLATE };
  const idInput = h('input', { value: id || '', placeholder: 'workflow id', disabled: !!id });
  const editor = h('textarea', { class: 'code', spellcheck: 'false' }, json(stripMeta(workflow)));
  const preview = h('div', {});
  const problems = h('div', {});
  const refresh = () => {
    try { preview.replaceChildren(dag(JSON.parse(editor.value))); problems.replaceChildren(); }
    catch (error) { problems.replaceChildren(h('div', { class: 'error' }, `JSON 无效：${error.message}`)); }
  };
  editor.addEventListener('input', () => { clearTimeout(editor.timer); editor.timer = setTimeout(refresh, 300); });
  const parsed = () => ({ ...JSON.parse(editor.value), id: id || idInput.value.trim() });
  const validate = () => attempt(() => api('/workflows/validate', { method: 'POST', body: parsed() }), (r) =>
    problems.replaceChildren(r.valid ? h('div', { class: 'notice' }, '校验通过') : h('div', { class: 'error' }, r.problems.join('\n'))));
  const save = () => attempt(() => api(`/workflows/${encodeURIComponent(id || idInput.value.trim())}`, { method: 'PUT', body: parsed() }),
    (saved) => { location.hash = `#/workflows/${saved.id}`; if (id) route(); });
  const remove = () => confirm(`删除 SOP ${id}？`) && attempt(() => api(`/workflows/${id}`, { method: 'DELETE' }), () => { location.hash = '#/workflows'; });
  shell([h('div', { class: 'row' }, h('h1', {}, id ? `SOP ${workflow.name}` : '新建 SOP'), h('span', { class: 'spacer' }),
    idInput, h('button', { onclick: validate }, '校验'), h('button', { class: 'primary', onclick: save }, '保存'), id ? h('button', { class: 'danger', onclick: remove }, '删除') : null),
  h('p', { class: 'muted' }, '节点类型：agent（agentId）、script（script / scriptLanguage）、approval、sop（sopWorkflowId）。发布：status = "published"。'),
  problems, h('div', { class: 'grid2' }, editor, preview)]);
  refresh();
}

function stripMeta(definition) {
  const { revision, createdAt, updatedAt, ...rest } = definition;
  return rest;
}

const KINDS = {
  agents: { title: '角色', path: '/agents', back: '#/agents', template: { name: '新角色', executorId: 'claude', prompt: '你负责……', rules: [], skills: [], mcpServers: [], hooks: [], verification: [] } },
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
  shell([h('div', { class: 'row' }, h('h1', {}, `${meta.title} ${id || '（新建）'}`), h('span', { class: 'spacer' }), idInput,
    h('button', { class: 'primary', onclick: save }, '保存'), id ? h('button', { class: 'danger', onclick: remove }, '删除') : null),
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
  const [members, requirements, schedules, channels, workflows] = await Promise.all([
    api(`/projects/${projectId}/members`), api(`/projects/${projectId}/requirements`),
    api(`/projects/${projectId}/schedules`).catch(() => []), api(`/notifications?projectId=${projectId}`).catch(() => null),
    api('/workflows').catch(() => []),
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
  const newSchedule = () => prompted('新定时任务', [
    { name: 'name', label: '名称' }, { name: 'cron', label: 'Cron（分 时 日 月 周）', placeholder: '0 3 * * 1-5' },
    { name: 'zone', label: '时区', value: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' },
    { name: 'workflowId', label: 'SOP', type: 'select', options: sopOptions }, { name: 'input', label: '输入', type: 'textarea', rows: 3 }],
  (v) => api(`/projects/${projectId}/schedules`, { method: 'POST', body: { ...v, enabled: true, skipIfRunning: true } }).then(refresh));
  const newChannel = () => prompted('新 Webhook', [
    { name: 'name', label: '名称' }, { name: 'url', label: 'URL' }, { name: 'secret', label: '签名密钥（可选）', type: 'password' },
    { name: 'events', label: '事件（逗号分隔，留空=全部）', placeholder: 'run.failed,run.waiting,run.succeeded' }],
  (v) => api('/notifications', { method: 'POST', body: { projectId, name: v.name, url: v.url, secret: v.secret || undefined, enabled: true,
    events: v.events.split(',').map((s) => s.trim()).filter(Boolean) } }).then(refresh));
  const addMember = () => prompted('添加成员', [{ name: 'user', label: '用户名' },
    { name: 'role', label: '角色', type: 'select', options: ['viewer', 'developer', 'maintainer', 'owner'], value: 'developer' }],
  (v) => api(`/projects/${projectId}/members/${encodeURIComponent(v.user)}`, { method: 'PUT', body: { role: v.role } }).then(refresh));

  shell([
    h('h1', {}, project.name), h('p', { class: 'muted' }, project.description || ''),
    h('div', { class: 'row' }, h('h2', {}, '需求'), h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: newRequirement }, '新需求')),
    h('table', {}, h('tr', {}, ['#', '标题', '状态', '优先级', 'SOP', '最近运行', '操作'].map((t) => h('th', {}, t))),
      requirements.map((r) => h('tr', {}, h('td', {}, r.number), h('td', {}, r.title), h('td', {}, badge(r.status)), h('td', {}, r.priority),
        h('td', { class: 'mono' }, r.workflowId || '—'), h('td', {}, r.latestRunId ? h('a', { href: `#/runs/${r.latestRunId}`, class: 'mono' }, r.latestRunId.slice(0, 14)) : '—'),
        h('td', {}, h('button', { onclick: () => startRequirement(r), disabled: ['closed', 'cancelled', 'done'].includes(r.status) }, '启动'))))),
    h('div', { class: 'row' }, h('h2', {}, '定时任务'), h('span', { class: 'spacer' }), h('button', { onclick: newSchedule }, '新建')),
    h('table', {}, h('tr', {}, ['名称', 'Cron', 'SOP', '下次', '上次', '操作'].map((t) => h('th', {}, t))),
      schedules.map((s) => h('tr', {}, h('td', {}, s.name, s.enabled ? null : h('span', { class: 'muted' }, '（停用）')), h('td', { class: 'mono' }, `${s.cron} ${s.zone}`), h('td', { class: 'mono' }, s.workflowId),
        h('td', {}, time(s.nextRunAt)), h('td', {}, s.lastRunId ? h('a', { href: `#/runs/${s.lastRunId}` }, time(s.lastRunAt)) : '—', s.lastError ? h('div', { class: 'error' }, s.lastError) : null),
        h('td', {}, h('div', { class: 'row' }, h('button', { onclick: () => attempt(() => api(`/projects/${projectId}/schedules/${s.id}/run`, { method: 'POST' }), refresh) }, '立即运行'),
          h('button', { onclick: () => attempt(() => api(`/projects/${projectId}/schedules/${s.id}`, { method: 'PUT', body: { ...s, enabled: !s.enabled } }), refresh) }, s.enabled ? '停用' : '启用'),
          h('button', { class: 'danger', onclick: () => confirm('删除定时任务？') && attempt(() => api(`/projects/${projectId}/schedules/${s.id}`, { method: 'DELETE' }), refresh) }, '删除')))))),
    channels === null ? null : [
      h('div', { class: 'row' }, h('h2', {}, '通知'), h('span', { class: 'spacer' }), h('button', { onclick: newChannel }, '新建')),
      h('table', {}, h('tr', {}, ['名称', 'URL', '事件', '最近投递', '操作'].map((t) => h('th', {}, t))),
        channels.map((c) => h('tr', {}, h('td', {}, c.name), h('td', { class: 'mono' }, c.url), h('td', {}, (c.events || []).join(', ') || '全部'),
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
  const tokens = await api('/me/tokens');
  const refresh = () => settingsPage();
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

route();
