import * as api from '../api.js';
import { getState, setSelected } from '../state.js';

const CATEGORIES = [
  { key: 'Architecture', desc: 'Latest diagrams, ADRs, design docs' },
  { key: 'Minutes',      desc: 'Meeting notes + decisions' },
  { key: 'Agendas',      desc: 'Upcoming sessions + prep' },
  { key: 'Non-prod',     desc: 'Dev/Test endpoints, dashboards' },
  { key: 'Prod',         desc: 'Production links + monitoring' },
  { key: 'Repos',        desc: 'Git repos + branches' },
  { key: 'Dev Setup',    desc: 'New developer bootstrap' },
  { key: 'Runbooks',     desc: 'Operational procedures' },
  { key: 'ORR Planning', desc: 'Operational readiness checklists' },
];

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'LINKHUB';
  document.getElementById('centerSub').textContent = 'Important links organized by category.';

  const body = document.getElementById('centerBody');

  if (!pid) {
    body.innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  let allLinks = [];
  try {
    allLinks = await api.links.list(pid);
  } catch { /* endpoint may not exist yet */ }

  // Group by category
  const byCategory = {};
  allLinks.forEach(l => {
    const cat = l.category || 'Uncategorized';
    if (!byCategory[cat]) byCategory[cat] = [];
    byCategory[cat].push(l);
  });

  let html = '<div class="grid2">';
  CATEGORIES.forEach(({ key, desc }) => {
    const catLinks = byCategory[key] || [];
    let linksHtml = '';
    if (catLinks.length === 0) {
      linksHtml = '<div class="tiny">No links yet</div>';
    } else {
      linksHtml = catLinks.map(l =>
        `<div class="tiny" style="cursor:pointer" data-link-id="${esc(l.linkId)}">
          ${l.pinned ? '\u{1F4CC} ' : '\u2022 '}
          <a href="${esc(l.url)}" target="_blank" style="color:var(--accent)">${esc(l.title || l.url)}</a>
        </div>`
      ).join('');
    }
    html += `<div class="card">
      <div class="cardH"><div><b>${esc(key)}</b><div class="tiny">${esc(desc)}</div></div></div>
      <div class="cardB">${linksHtml}</div>
    </div>`;
  });
  html += '</div>';
  body.innerHTML = html;

  // Wire click handlers on link items
  body.querySelectorAll('[data-link-id]').forEach(el => {
    el.addEventListener('click', (e) => {
      if (e.target.tagName === 'A') return; // let the link open
      const link = allLinks.find(l => l.linkId === el.dataset.linkId);
      if (link) setSelected({ type: 'link', id: link.linkId, data: link });
    });
  });
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
