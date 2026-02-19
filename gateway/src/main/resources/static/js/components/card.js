/**
 * Build a card HTML string.
 * @param {object} opts
 * @param {string} opts.title
 * @param {string} [opts.subtitle]
 * @param {string} [opts.headerRight] - raw HTML for right side of header
 * @param {string} opts.body - raw HTML for card body
 * @returns {string}
 */
export function card({ title, subtitle, headerRight, body }) {
  return `<div class="card">
    <div class="cardH">
      <div><b>${esc(title)}</b>${subtitle ? `<div class="tiny">${esc(subtitle)}</div>` : ''}</div>
      ${headerRight || ''}
    </div>
    <div class="cardB">${body}</div>
  </div>`;
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s || '';
  return d.innerHTML;
}
