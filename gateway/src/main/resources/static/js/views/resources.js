import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'RESOURCES';
  document.getElementById('centerSub').textContent =
    'Team members, capacity, and availability. Paste names to bulk-add.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Team Resources</b><div class="tiny">click to inspect</div></div>
        <div class="row">
          <label class="toggle" style="padding:4px 8px"><input type="checkbox" id="resourceProjectOnly"/><span class="tiny">This project only</span></label>
        </div>
      </div>
      <div class="cardB" id="resourceTableContainer"></div>
    </div>
    <div style="height:10px"></div>
    <div class="card">
      <div class="cardH"><div><b>Paste-to-add</b><div class="tiny">one name per line</div></div></div>
      <div class="cardB">
        <textarea id="resourceNames" placeholder="Jane Doe&#10;John Smith&#10;..." style="min-height:80px"></textarea>
        <div class="row" style="margin-top:8px;">
          <button class="btn primary" id="addMissingBtn">Add Missing</button>
        </div>
      </div>
    </div>`;

  let allResources = [];
  try {
    allResources = await api.resources.list();
  } catch {
    document.getElementById('resourceTableContainer').innerHTML = '<div class="tiny">Could not load resources.</div>';
    return;
  }

  function renderFiltered(projectOnly) {
    const filtered = projectOnly && pid
      ? allResources.filter(r => r.projectId === pid)
      : allResources;
    const rows = filtered.map(r => ({
      name: r.name || '',
      role: r.role || '',
      capacity: r.capacity != null ? r.capacity : '',
      availability: r.availability != null ? r.availability : '',
      email: r.email || '',
      project: r.projectId || '',
      _raw: r
    }));
    renderTable(
      document.getElementById('resourceTableContainer'),
      ['name', 'role', 'capacity', 'availability', 'email', 'project'],
      rows,
      (row) => setSelected({ type: 'resource', id: row._raw.resourceId, data: row._raw })
    );

    // Post-process availability cells to add FREE/BUSY pills
    const tableEl = document.getElementById('resourceTableContainer').querySelector('table');
    if (tableEl) {
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const cells = tr.querySelectorAll('td');
        const availCell = cells[3]; // availability is the 4th column
        if (!availCell) return;
        const val = filtered[i]?.availability;
        if (val != null) {
          const pill = document.createElement('span');
          pill.className = 'pill ' + (val >= 0.5 ? 'good' : 'bad');
          pill.textContent = val >= 0.5 ? 'FREE' : 'BUSY';
          pill.style.marginLeft = '6px';
          pill.style.fontSize = '10px';
          availCell.appendChild(pill);
        }
      });
    }
  }

  renderFiltered(false);

  document.getElementById('resourceProjectOnly')?.addEventListener('change', (e) => {
    renderFiltered(e.target.checked);
  });

  document.getElementById('addMissingBtn')?.addEventListener('click', async () => {
    const ta = document.getElementById('resourceNames');
    const raw = (ta?.value || '').trim();
    if (!raw) { toast('paste names first'); return; }

    const names = raw.split('\n').map(n => n.trim()).filter(Boolean);
    const existingNames = new Set(allResources.map(r => (r.name || '').toLowerCase()));
    const missing = names.filter(n => !existingNames.has(n.toLowerCase()));

    if (missing.length === 0) {
      toast('all names already exist');
      return;
    }

    try {
      for (const name of missing) {
        const created = await api.resources.create({
          name,
          role: 'ENGINEER',
          capacity: 100,
          availability: 1.0,
          projectId: pid || null
        });
        allResources.push(created);
      }
      ta.value = '';
      toast(`added ${missing.length} resource(s)`);
      renderFiltered(document.getElementById('resourceProjectOnly')?.checked || false);
    } catch (e) {
      toast('add failed: ' + e.message);
    }
  });
}
