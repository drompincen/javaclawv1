import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedChecklistId = null;

export async function render() {
  selectedChecklistId = null;
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
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

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
      ev.style.cursor = 'pointer';

      // Header row
      const header = document.createElement('div');
      header.className = 'eventTop';
      header.innerHTML = `
        <div class="eventTitle">${esc(c.name || 'Untitled')}</div>
        <div class="row" style="gap:6px">
          <span class="pill ${pct === 100 ? 'good' : 'warn'}">${done}/${total}</span>
          <button class="btn danger chk-delete-btn" style="padding:2px 6px;font-size:11px" title="Delete checklist">\u00d7</button>
        </div>`;
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

      // Click to show reading pane with full items
      ev.addEventListener('click', (e) => {
        if (e.target.closest('.chk-delete-btn')) return;
        if (selectedChecklistId === c.checklistId) {
          selectedChecklistId = null;
          document.getElementById('readingPane').style.display = 'none';
        } else {
          selectedChecklistId = c.checklistId;
          setSelected({ type: 'checklist', id: c.checklistId, data: c });
          renderReadingPane(pid, c, ci, allChecklists, renderChecklists);
        }
      });

      // Delete checklist button
      ev.querySelector('.chk-delete-btn').addEventListener('click', async (e) => {
        e.stopPropagation();
        if (!confirm('Delete checklist "' + (c.name || '') + '"?')) return;
        try {
          await api.checklists.delete(pid, c.checklistId);
          allChecklists.splice(ci, 1);
          toast('checklist deleted');
          document.dispatchEvent(new CustomEvent('jc:data-changed'));
          renderChecklists();
        } catch (err) { toast('delete failed: ' + err.message); }
      });

      container.appendChild(ev);
    });
  }

  renderChecklists();
}

function renderReadingPane(pid, c, ci, allChecklists, rerenderList) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const items = c.items || [];
  const done = items.filter(i => i.checked).length;
  const total = items.length;
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;

  let html = `<div class="reading-pane-divider">Checklist Detail \u2014 ${done}/${total} complete</div>`;
  html += `<div class="reading-pane">`;
  html += `<b style="font-size:14px">${esc(c.name || 'Untitled')}</b>`;
  html += `<div class="tiny" style="margin:4px 0">Status: ${c.status || '\u2013'}</div>`;
  html += `<div class="progress-track" style="margin-bottom:10px"><div class="progress-fill" style="width:${pct}%"></div></div>`;

  html += `<div id="rpChecklistItems"></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  // Render interactive items
  const itemsContainer = pane.querySelector('#rpChecklistItems');
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
        rerenderList();
        renderReadingPane(pid, allChecklists[ci], ci, allChecklists, rerenderList);
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
        rerenderList();
        renderReadingPane(pid, allChecklists[ci], ci, allChecklists, rerenderList);
      } catch (err) { toast('remove failed: ' + err.message); }
    });
    row.appendChild(rm);

    itemsContainer.appendChild(row);
  });

  if (items.length === 0) {
    itemsContainer.innerHTML = '<div class="tiny">No items in this checklist.</div>';
  }
}
