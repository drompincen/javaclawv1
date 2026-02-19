const _listeners = [];

const state = {
  currentProjectId: localStorage.getItem('jc_projectId') || null,
  currentView: 'intake',
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
  _notify('project');
}

export function setView(view) {
  state.currentView = view;
  state.selectedEntity = null;
  _notify('view');
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
