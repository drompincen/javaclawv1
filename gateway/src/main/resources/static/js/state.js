const _listeners = [];

// Read project from URL param (?project=<id>) first, fall back to localStorage
function _initProjectId() {
  const params = new URLSearchParams(window.location.search);
  return params.get('project') || localStorage.getItem('jc_projectId') || null;
}

// Read view from URL param (?view=<key>) first, fall back to 'intake'
function _initView() {
  const params = new URLSearchParams(window.location.search);
  return params.get('view') || 'intake';
}

const state = {
  currentProjectId: _initProjectId(),
  currentView: _initView(),
  selectedEntity: null,   // { type, id, data }
  stepCount: 0,
  wsConnected: false,
};

export function getState() { return state; }

export function setProject(projectId) {
  state.currentProjectId = projectId;
  if (projectId) localStorage.setItem('jc_projectId', projectId);
  else localStorage.removeItem('jc_projectId');
  state.selectedEntity = null;
  _updateUrl();
  _notify('project');
}

export function setView(view) {
  state.currentView = view;
  state.selectedEntity = null;
  _updateUrl();
  _notify('view');
}

function _updateUrl() {
  const params = new URLSearchParams();
  if (state.currentProjectId) params.set('project', state.currentProjectId);
  if (state.currentView && state.currentView !== 'intake') params.set('view', state.currentView);
  const qs = params.toString();
  const url = window.location.pathname + (qs ? '?' + qs : '');
  window.history.replaceState(null, '', url);
}

export function setSelected(entity) {
  state.selectedEntity = entity;
  _notify('selected');
}

export function incrementStep() {
  state.stepCount++;
  _notify('step');
}

export function setWsConnected(connected) {
  state.wsConnected = connected;
  _notify('ws');
}

/**
 * Register a listener. Called with (changeType: string).
 * Returns unsubscribe function.
 */
export function onChange(fn) {
  _listeners.push(fn);
  return () => {
    const idx = _listeners.indexOf(fn);
    if (idx >= 0) _listeners.splice(idx, 1);
  };
}

function _notify(changeType) {
  for (const fn of _listeners) {
    try { fn(changeType); } catch (e) { console.error('state listener error', e); }
  }
}
