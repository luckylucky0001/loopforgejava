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
  ['#/analytics', '分析'], ['#/knowledge', '知识库'], ['#/executors', '执行器'], ['#/settings', '设置'],
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
  [/^#\/analytics$/, analyticsPage], [/^#\/knowledge$/, knowledgePage],
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
    report.truncated ? h('div', { class: 'notice' }, '范围内运行过多，只统计了最近的 20000 个。') : null,
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

route();
