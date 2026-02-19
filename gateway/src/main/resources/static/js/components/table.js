/**
 * Render a <table class="table"> into the given container.
 * @param {HTMLElement} container
 * @param {string[]} columns  - header labels
 * @param {Array<object>} rows - each row is an object with keys matching columns (lowercase)
 * @param {function} [onRowClick] - called with row object when clicked
 */
export function renderTable(container, columns, rows, onRowClick) {
  const keys = columns.map(c => c.toLowerCase().replace(/[^a-z0-9]/g, ''));
  let html = '<table class="table"><thead><tr>';
  columns.forEach(c => { html += `<th>${esc(c)}</th>`; });
  html += '</tr></thead><tbody>';
  if (rows.length === 0) {
    html += `<tr><td colspan="${columns.length}" class="tiny">No data.</td></tr>`;
  }
  html += '</tbody></table>';
  container.innerHTML = html;

  const tbody = container.querySelector('tbody');
  rows.forEach((row, idx) => {
    const tr = document.createElement('tr');
    keys.forEach(k => {
      const td = document.createElement('td');
      td.textContent = row[k] != null ? String(row[k]) : '';
      tr.appendChild(td);
    });
    if (onRowClick) {
      tr.style.cursor = 'pointer';
      tr.addEventListener('click', () => onRowClick(row, idx));
    }
    tbody.appendChild(tr);
  });
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
