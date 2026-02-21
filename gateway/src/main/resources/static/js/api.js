const BASE = '';

async function get(path) {
  const res = await fetch(BASE + path);
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`);
  return res.json();
}
async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: body != null ? JSON.stringify(body) : undefined
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}`);
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}
async function put(path, body) {
  const res = await fetch(BASE + path, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`PUT ${path} → ${res.status}`);
  return res.json();
}
async function del(path) {
  const res = await fetch(BASE + path, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) throw new Error(`DELETE ${path} → ${res.status}`);
}

function projectPath(pid) { return `/api/projects/${pid}`; }

export const projects = {
  list:   ()           => get('/api/projects'),
  get:    (id)         => get(`/api/projects/${id}`),
  create: (data)       => post('/api/projects', data),
  update: (id, data)   => put(`/api/projects/${id}`, data),
  delete: (id)         => del(`/api/projects/${id}`),
};

export const threads = {
  list:   (pid)        => get(`${projectPath(pid)}/threads`),
  get:    (pid, id)    => get(`${projectPath(pid)}/threads/${id}`),
  update: (pid, id, d) => put(`${projectPath(pid)}/threads/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/threads/${id}`),
  merge:  (pid, data)  => post(`${projectPath(pid)}/threads/merge`, data),
};

export const tickets = {
  list:   (pid)        => get(`${projectPath(pid)}/tickets`),
  get:    (pid, id)    => get(`${projectPath(pid)}/tickets/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/tickets`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/tickets/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/tickets/${id}`),
};

export const ideas = {
  list:   (pid)        => get(`${projectPath(pid)}/ideas`),
  get:    (pid, id)    => get(`${projectPath(pid)}/ideas/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/ideas`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/ideas/${id}`, d),
};

export const objectives = {
  list:   (pid)        => get(`${projectPath(pid)}/objectives`),
  get:    (pid, id)    => get(`${projectPath(pid)}/objectives/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/objectives`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/objectives/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/objectives/${id}`),
};

export const phases = {
  list:   (pid)        => get(`${projectPath(pid)}/phases`),
  get:    (pid, id)    => get(`${projectPath(pid)}/phases/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/phases`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/phases/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/phases/${id}`),
};

export const checklists = {
  list:   (pid)        => get(`${projectPath(pid)}/checklists`),
  get:    (pid, id)    => get(`${projectPath(pid)}/checklists/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/checklists`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/checklists/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/checklists/${id}`),
};

export const links = {
  list:   (pid, params) => {
    const q = new URLSearchParams();
    if (params?.category) q.set('category', params.category);
    if (params?.bundleId) q.set('bundleId', params.bundleId);
    const qs = q.toString();
    return get(`${projectPath(pid)}/links${qs ? '?' + qs : ''}`);
  },
  get:    (pid, id)    => get(`${projectPath(pid)}/links/${id}`),
  create: (pid, data)  => post(`${projectPath(pid)}/links`, data),
  update: (pid, id, d) => put(`${projectPath(pid)}/links/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/links/${id}`),
};

export const reminders = {
  list:   (pid)        => get(`${projectPath(pid)}/reminders`),
  create: (pid, data)  => post(`${projectPath(pid)}/reminders`, data),
  delete: (id)         => del(`/api/reminders/${id}`),
};

export const reconciliations = {
  list:   (pid)        => get(`${projectPath(pid)}/reconciliations`),
  get:    (pid, id)    => get(`${projectPath(pid)}/reconciliations/${id}`),
};

export const deltaPacks = {
  list:   (pid)        => get(`${projectPath(pid)}/delta-packs`),
  get:    (pid, id)    => get(`${projectPath(pid)}/delta-packs/${id}`),
};

export const blindspots = {
  list:   (pid)        => get(`${projectPath(pid)}/blindspots`),
  get:    (pid, id)    => get(`${projectPath(pid)}/blindspots/${id}`),
  update: (pid, id, d) => put(`${projectPath(pid)}/blindspots/${id}`, d),
  delete: (pid, id)    => del(`${projectPath(pid)}/blindspots/${id}`),
};

export const resources = {
  list:   ()          => get('/api/resources'),
  create: (data)      => post('/api/resources', data),
  update: (id, data)  => put(`/api/resources/${id}`, data),
  delete: (id)        => del(`/api/resources/${id}`),
};

export const intake = {
  startPipeline: (data) => post('/api/intake/pipeline', data),
};

export const uploads = {
  send: async (projectId, files) => {
    const fd = new FormData();
    fd.append('projectId', projectId);
    files.forEach(f => fd.append('files', f));
    const res = await fetch(BASE + '/api/intake/upload', { method: 'POST', body: fd });
    if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
    return res.json();
  }
};

function qs(params) {
  if (!params) return '';
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v != null) q.set(k, String(v)); });
  const s = q.toString();
  return s ? '?' + s : '';
}

export const schedules = {
  list:   (params) => get('/api/schedules' + qs(params)),
  get:    (id)     => get(`/api/schedules/${id}`),
  update: (id, d)  => put(`/api/schedules/${id}`, d),
};

export const executions = {
  future: (params) => get('/api/executions/future' + qs(params)),
  past:   (params) => get('/api/executions/past' + qs(params)),
  trigger:(data)   => post('/api/executions/trigger', data),
  cancel: (id)     => post(`/api/executions/future/${id}/cancel`),
};

export const sessions = {
  list:   ()           => get('/api/sessions'),
  get:    (id)         => get(`/api/sessions/${id}`),
  create: (data)       => post('/api/sessions', data || {}),
  run:    (id)         => post(`/api/sessions/${id}/run`),
  pause:  (id)         => post(`/api/sessions/${id}/pause`),
  resume: (id)         => post(`/api/sessions/${id}/resume`),
  messages: (id)       => get(`/api/sessions/${id}/messages`),
  sendMessage: (id, d) => post(`/api/sessions/${id}/messages`, d),
};

export const agents = {
  list: () => get('/api/agents'),
};

export const logs = {
  metrics: () => get('/api/logs/llm-interactions/metrics'),
};

export const memories = {
  list: (pid) => get(`${projectPath(pid)}/memories`),
};

export const ask = {
  query: async (pid, question) => {
    try { return await post('/api/ask', { projectId: pid, question }); }
    catch { return null; }
  }
};
