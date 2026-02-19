import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';

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

  let html = '<div style="margin-bottom:10px"><button class="btn primary" id="addLinkGlobal">+ Add Link</button></div>';
  html += '<div class="grid2">';
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
      <div class="cardH">
        <div><b>${esc(key)}</b><div class="tiny">${esc(desc)}</div></div>
        <button class="btn" style="padding:4px 8px;font-size:14px" data-add-cat="${esc(key)}" title="Add link to ${esc(key)}">+</button>
      </div>
      <div class="cardB">${linksHtml}</div>
    </div>`;
  });
  html += '</div>';
  body.innerHTML = html;

  // Wire global add button
  document.getElementById('addLinkGlobal')?.addEventListener('click', () => renderForm(null, null));

  // Wire per-category "+" buttons
  body.querySelectorAll('[data-add-cat]').forEach(btn => {
    btn.addEventListener('click', () => renderForm(null, btn.dataset.addCat));
  });

  // Wire click handlers on link items
  body.querySelectorAll('[data-link-id]').forEach(el => {
    el.addEventListener('click', (e) => {
      if (e.target.tagName === 'A') return; // let the link open
      const link = allLinks.find(l => l.linkId === el.dataset.linkId);
      if (link) setSelected({ type: 'link', id: link.linkId, data: link });
    });
  });
}

// Listen for edit requests from the inspector
document.addEventListener('linkhub:edit', (e) => {
  renderForm(e.detail, null);
});

function renderForm(existingLink, defaultCategory) {
  // Remove any existing modal
  document.querySelector('.modal-overlay')?.remove();

  const pid = getState().currentProjectId;
  if (!pid) return;

  const isEdit = !!existingLink;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';

  const catOptions = CATEGORIES.map(c =>
    `<option value="${esc(c.key)}" ${(existingLink?.category || defaultCategory) === c.key ? 'selected' : ''}>${esc(c.key)}</option>`
  ).join('');

  overlay.innerHTML = `<div class="modal-box">
    <div style="margin-bottom:12px"><b>${isEdit ? 'Edit Link' : 'Add Link'}</b></div>
    <div style="display:flex;flex-direction:column;gap:8px">
      <div>
        <div class="tiny" style="margin-bottom:4px">URL *</div>
        <input type="text" id="linkUrl" value="${esc(existingLink?.url || '')}" style="width:100%" placeholder="https://..."/>
      </div>
      <div>
        <div class="tiny" style="margin-bottom:4px">Title</div>
        <input type="text" id="linkTitle" value="${esc(existingLink?.title || '')}" style="width:100%" placeholder="Link title"/>
      </div>
      <div>
        <div class="tiny" style="margin-bottom:4px">Category</div>
        <select class="select" id="linkCategory" style="width:100%">
          <option value="">— select —</option>
          ${catOptions}
        </select>
      </div>
      <div>
        <div class="tiny" style="margin-bottom:4px">Description</div>
        <textarea id="linkDesc" style="min-height:60px">${esc(existingLink?.description || '')}</textarea>
      </div>
      <div class="row">
        <label style="display:flex;align-items:center;gap:6px;font-size:12px;cursor:pointer">
          <input type="checkbox" id="linkPinned" ${existingLink?.pinned ? 'checked' : ''}/> Pinned
        </label>
      </div>
      <div class="hr"></div>
      <div class="row">
        <button class="btn primary" id="linkSave">${isEdit ? 'Save' : 'Create'}</button>
        <button class="btn ghost" id="linkCancel">Cancel</button>
      </div>
    </div>
  </div>`;

  document.body.appendChild(overlay);

  // Focus URL field
  overlay.querySelector('#linkUrl')?.focus();

  // Close on overlay click
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });

  // Close on Escape
  const escHandler = (e) => { if (e.key === 'Escape') { overlay.remove(); document.removeEventListener('keydown', escHandler); } };
  document.addEventListener('keydown', escHandler);

  // Cancel
  overlay.querySelector('#linkCancel').addEventListener('click', () => overlay.remove());

  // Save
  overlay.querySelector('#linkSave').addEventListener('click', async () => {
    const url = overlay.querySelector('#linkUrl').value.trim();
    if (!url) { toast('URL is required'); return; }

    const data = {
      url,
      title: overlay.querySelector('#linkTitle').value.trim() || url,
      category: overlay.querySelector('#linkCategory').value || 'Uncategorized',
      description: overlay.querySelector('#linkDesc').value.trim(),
      pinned: overlay.querySelector('#linkPinned').checked,
    };

    try {
      if (isEdit) {
        await api.links.update(pid, existingLink.linkId, { ...existingLink, ...data });
        toast('link updated');
      } else {
        await api.links.create(pid, data);
        toast('link created');
      }
      overlay.remove();
      render();
    } catch (e) {
      toast('save failed: ' + e.message);
    }
  });
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
