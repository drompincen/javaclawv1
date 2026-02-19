export function pill(text, kind = '') {
  return `<span class="pill ${kind}">${esc(text)}</span>`;
}

export function badge(text, kind = '') {
  return `<span class="badge ${kind}">${esc(text)}</span>`;
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
