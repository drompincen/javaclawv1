import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { runAgent } from '../panels/agents.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedReminderId = null;

export async function render() {
  selectedReminderId = null;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'REMINDERS';
  document.getElementById('centerSub').textContent =
    'Reminder Agent extracts schedules from intake + milestones; normalizes recurrence.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Reminders</b><div class="tiny">normalized schedules</div></div>
        <div class="row"><button class="btn" id="runReminderAgent">Run Reminder Agent</button></div>
      </div>
      <div class="cardB" id="remTableContainer"></div>
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

  document.getElementById('runReminderAgent')?.addEventListener('click', () => runAgent('reminder_agent'));

  if (!pid) {
    document.getElementById('remTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  let allReminders = [];
  try {
    allReminders = await api.reminders.list(pid);
    const rows = allReminders.map(r => ({
      title: r.message || '',
      schedule: r.triggerAt || '',
      type: r.type || 'TIME_BASED',
      recurring: r.recurring ? 'Yes' : 'No',
      _raw: r
    }));
    renderTable(
      document.getElementById('remTableContainer'),
      ['title', 'schedule', 'type', 'recurring'],
      rows,
      (row) => {
        const r = row._raw;
        if (selectedReminderId === r.reminderId) {
          selectedReminderId = null;
          document.getElementById('readingPane').style.display = 'none';
        } else {
          selectedReminderId = r.reminderId;
          setSelected({ type: 'reminder', id: r.reminderId, data: r });
          renderReadingPane(r);
        }
      }
    );

    // Append delete buttons
    const tableEl = document.getElementById('remTableContainer').querySelector('table');
    if (tableEl) {
      const thead = tableEl.querySelector('thead tr');
      if (thead) { const th = document.createElement('th'); th.textContent = ''; thead.appendChild(th); }
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const td = document.createElement('td');
        if (!allReminders[i]) { tr.appendChild(td); return; }
        const btn = document.createElement('button');
        btn.className = 'btn danger';
        btn.style.cssText = 'padding:2px 6px;font-size:11px;';
        btn.textContent = '\u00d7';
        btn.title = 'Delete reminder';
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('Delete this reminder?')) return;
          try {
            await api.reminders.delete(allReminders[i].reminderId);
            toast('reminder deleted');
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
            render();
          } catch (err) { toast('delete failed: ' + err.message); }
        });
        td.appendChild(btn);
        tr.appendChild(td);
      });
    }
  } catch {
    document.getElementById('remTableContainer').innerHTML = '<div class="tiny">Could not load reminders.</div>';
  }
}

function renderReadingPane(d) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  let html = `<div class="reading-pane-divider">Reminder Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<b style="font-size:14px">Reminder</b>`;
  html += `<div class="hr"></div>`;
  html += `<div class="tiny" style="white-space:pre-wrap">${esc(d.message || '')}</div>`;
  html += `<div class="hr"></div>`;
  html += `<div class="tiny">triggerAt: ${d.triggerAt || '\u2013'}</div>`;
  html += `<div class="tiny">type: ${d.type || '\u2013'}</div>`;
  html += `<div class="tiny">recurring: ${d.recurring ? 'Yes' : 'No'}</div>`;
  html += `<div class="tiny">triggered: ${d.triggered ? 'Yes' : 'No'}</div>`;
  html += `<div class="hr"></div>`;
  html += `<div class="row"><button class="btn danger" id="rpDelReminder">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpDelReminder')?.addEventListener('click', async () => {
    if (!confirm('Delete this reminder?')) return;
    try {
      await api.reminders.delete(d.reminderId);
      toast('reminder deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}
