import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';
import { toast } from '../components/toast.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'CHECKLISTS';
  document.getElementById('centerSub').textContent =
    'Checklist Agent generates ORR/release checklists from plan phases + operational standards.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Checklists</b><div class="tiny">attach to phases/releases</div></div>
        <div class="row"><button class="btn" id="runChecklistAgent">Run Checklist Agent</button></div>
      </div>
      <div class="cardB" id="chkBody"></div>
    </div>`;

  document.getElementById('runChecklistAgent')?.addEventListener('click', () => runAgent('checklist_agent'));

  if (!pid) {
    document.getElementById('chkBody').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  let allChecklists = [];
  try {
    allChecklists = await api.checklists.list(pid);
  } catch {
    document.getElementById('chkBody').innerHTML = '<div class="tiny">Could not load checklists.</div>';
    return;
  }

  function renderChecklists() {
    const container = document.getElementById('chkBody');
    if (allChecklists.length === 0) {
      container.innerHTML = '<div class="tiny">No checklists yet.</div>';
      return;
    }

    container.innerHTML = '';
    allChecklists.forEach((c, ci) => {
      const items = c.items || [];
      const done = items.filter(i => i.checked).length;
      const total = items.length;
      const pct = total > 0 ? Math.round((done / total) * 100) : 0;

      const ev = document.createElement('div');
      ev.className = 'event';
      ev.style.marginBottom = '10px';

      // Header row
      const header = document.createElement('div');
      header.className = 'eventTop';
      header.style.cursor = 'pointer';
      header.innerHTML = `
        <div class="eventTitle">${esc(c.name || 'Untitled')}</div>
        <div class="row" style="gap:6px">
          <span class="pill ${pct === 100 ? 'good' : 'warn'}">${done}/${total}</span>
        </div>`;
      header.addEventListener('click', () => setSelected({ type: 'checklist', id: c.checklistId, data: c }));
      ev.appendChild(header);

      // Status + progress
      const meta = document.createElement('div');
      meta.className = 'tiny';
      meta.style.margin = '6px 0';
      meta.textContent = 'Status: ' + (c.status || '\u2013');
      ev.appendChild(meta);

      const track = document.createElement('div');
      track.className = 'progress-track';
      track.innerHTML = `<div class="progress-fill" style="width:${pct}%"></div>`;
      ev.appendChild(track);

      // Item list
      if (items.length > 0) {
        const list = document.createElement('div');
        list.style.cssText = 'margin-top:8px;display:flex;flex-direction:column;gap:4px;';

        items.forEach((item, idx) => {
          const row = document.createElement('div');
          row.style.cssText = 'display:flex;align-items:center;gap:6px;padding:3px 0;';
          if (item.checked) row.style.opacity = '0.5';

          // Checkbox
          const cb = document.createElement('input');
          cb.type = 'checkbox';
          cb.checked = !!item.checked;
          cb.style.accentColor = 'var(--accent)';
          cb.addEventListener('change', async () => {
            const updatedItems = [...items];
            updatedItems[idx] = { ...updatedItems[idx], checked: cb.checked };
            try {
              await api.checklists.update(pid, c.checklistId, { ...c, items: updatedItems });
              allChecklists[ci] = { ...c, items: updatedItems };
              renderChecklists();
            } catch (err) { toast('update failed: ' + err.message); cb.checked = !cb.checked; }
          });
          row.appendChild(cb);

          // Text
          const text = document.createElement('span');
          text.style.cssText = 'flex:1;font-size:12px;' + (item.checked ? 'text-decoration:line-through;' : '');
          text.textContent = item.text || item.name || item.title || 'Item ' + (idx + 1);
          row.appendChild(text);

          // Assignee chip
          if (item.assignee) {
            const chip = document.createElement('span');
            chip.className = 'chip';
            chip.style.fontSize = '10px';
            chip.textContent = item.assignee;
            row.appendChild(chip);
          }

          // Remove button
          const rm = document.createElement('button');
          rm.className = 'btn danger';
          rm.style.cssText = 'padding:1px 5px;font-size:10px;';
          rm.textContent = '\u00d7';
          rm.title = 'Remove item';
          rm.addEventListener('click', async (e) => {
            e.stopPropagation();
            const updatedItems = items.filter((_, j) => j !== idx);
            try {
              await api.checklists.update(pid, c.checklistId, { ...c, items: updatedItems });
              allChecklists[ci] = { ...c, items: updatedItems };
              toast('item removed');
              renderChecklists();
            } catch (err) { toast('remove failed: ' + err.message); }
          });
          row.appendChild(rm);

          list.appendChild(row);
        });

        ev.appendChild(list);
      }

      // Delete checklist button
      const footer = document.createElement('div');
      footer.style.cssText = 'margin-top:8px;display:flex;justify-content:flex-end;';
      const delBtn = document.createElement('button');
      delBtn.className = 'btn danger';
      delBtn.style.cssText = 'padding:3px 8px;font-size:11px;';
      delBtn.textContent = 'Delete Checklist';
      delBtn.addEventListener('click', async (e) => {
        e.stopPropagation();
        if (!confirm('Delete checklist "' + (c.name || '') + '"?')) return;
        try {
          await api.checklists.delete(pid, c.checklistId);
          allChecklists.splice(ci, 1);
          toast('checklist deleted');
          renderChecklists();
        } catch (err) { toast('delete failed: ' + err.message); }
      });
      footer.appendChild(delBtn);
      ev.appendChild(footer);

      container.appendChild(ev);
    });
  }

  renderChecklists();
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
