import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { runAgent } from '../panels/agents.js';

export async function render() {
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
    </div>`;

  document.getElementById('runReminderAgent')?.addEventListener('click', () => runAgent('reminder_agent'));

  if (!pid) {
    document.getElementById('remTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const rems = await api.reminders.list(pid);
    const rows = rems.map(r => ({
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
      (row) => setSelected({ type: 'reminder', id: row._raw.reminderId, data: row._raw })
    );
  } catch {
    document.getElementById('remTableContainer').innerHTML = '<div class="tiny">Could not load reminders.</div>';
  }
}
