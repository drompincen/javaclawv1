let _ws = null;
let _listeners = [];
let _reconnectTimer = null;

export function connect() {
  if (_ws && (_ws.readyState === WebSocket.OPEN || _ws.readyState === WebSocket.CONNECTING)) return;
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  _ws = new WebSocket(`${protocol}//${location.host}/ws`);

  _ws.onopen = () => {
    _dispatch({ _type: 'ws_status', connected: true });
  };

  _ws.onclose = () => {
    _dispatch({ _type: 'ws_status', connected: false });
    clearTimeout(_reconnectTimer);
    _reconnectTimer = setTimeout(connect, 2000);
  };

  _ws.onerror = () => {};

  _ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);
      _dispatch(msg);
    } catch { /* ignore malformed */ }
  };
}

export function subscribeSession(sessionId) {
  _send({ type: 'SUBSCRIBE_SESSION', sessionId });
}

export function subscribeProject(projectId) {
  _send({ type: 'SUBSCRIBE_PROJECT', projectId });
}

export function unsubscribe(sessionId) {
  _send({ type: 'UNSUBSCRIBE', sessionId });
}

export function onEvent(fn) {
  _listeners.push(fn);
  return () => { _listeners = _listeners.filter(l => l !== fn); };
}

function _send(obj) {
  if (_ws && _ws.readyState === WebSocket.OPEN) {
    _ws.send(JSON.stringify({ ...obj, ts: new Date().toISOString() }));
  }
}

function _dispatch(msg) {
  for (const fn of _listeners) {
    try { fn(msg); } catch (e) { console.error('ws listener error', e); }
  }
}
