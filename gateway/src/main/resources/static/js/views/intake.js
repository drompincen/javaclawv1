import * as api from '../api.js';
import { getState, setSelected, incrementStep } from '../state.js';
import { toast } from '../components/toast.js';
import { addLog } from '../panels/activity.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'INTAKE';
  document.getElementById('centerSub').textContent =
    'Paste text or provide file paths. Agents organize into threads, ideas, plans, objectives, reminders, checklists, reconciliation.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Freeform intake</b><div class="tiny">Commands: <span class="chip accent">use project &lt;name&gt;</span></div></div>
      </div>
      <div class="cardB">
        <textarea id="intakeText" placeholder="Paste Confluence/Jira/Smartsheet/link dumps here..."></textarea>
        <div class="row" style="margin-top:8px;">
          <button class="btn" data-intent="threads">Thread Agent</button>
          <button class="btn" data-intent="ideas">Extract ideas</button>
          <button class="btn" data-intent="plan">Create/update plan</button>
          <button class="btn" data-intent="objectives">Align objectives</button>
          <button class="btn" data-intent="reminders">Create reminders</button>
          <button class="btn" data-intent="checklists">Create checklists</button>
          <button class="btn" data-intent="reconcile">Reconcile everything</button>
          <button class="btn primary" id="intakeSend">Send</button>
        </div>
      </div>
    </div>
    <div style="height:10px"></div>
    <div class="grid2">
      <div class="card">
        <div class="cardH"><div><b>Recent intake</b><div class="tiny">select to inspect</div></div></div>
        <div class="cardB"><div class="timeline" id="intakeTimeline"><div class="tiny">Loading...</div></div></div>
      </div>
      <div class="card">
        <div class="cardH"><div><b>Outcome expectations</b><div class="tiny">what "done" looks like</div></div></div>
        <div class="cardB">
          <div class="tiny"><b>Thread Agent</b>: names threads by topic + continuity; links to sprint objectives<br/>
          <b>Plan Agent</b>: phases + entry/exit + milestones; maps to epics/initiatives<br/>
          <b>Reminder Agent</b>: normalized schedules; attaches to milestones + checklists<br/>
          <b>Checklist Agent</b>: ORR/release lists; owners; status<br/>
          <b>Reconcile Agent</b>: objectives ↔ milestones ↔ ticket dump alignment; drift deltas</div>
          <div class="hr"></div>
          <div class="chips"><span class="chip">Mongo change streams</span><span class="chip">Event types</span><span class="chip">Checker loop</span></div>
        </div>
      </div>
    </div>`;

  // Intent buttons — "threads" triggers the pipeline directly
  body.querySelectorAll('[data-intent]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const ta = document.getElementById('intakeText');
      const intent = btn.dataset.intent;

      if (intent === 'threads') {
        const raw = (ta?.value || '').trim();
        if (!raw) { toast('paste content first'); return; }
        if (!pid) { toast('select a project first'); return; }
        try {
          const result = await api.intake.startPipeline({ projectId: pid, content: raw });
          ta.value = '';
          incrementStep();
          toast('pipeline started');
          addLog('INTAKE_PIPELINE_STARTED: ' + (result.pipelineId || ''), 'info');
        } catch (e) {
          toast('pipeline failed: ' + e.message);
          addLog('INTAKE_ERROR: ' + e.message, 'bad');
        }
        return;
      }

      if (ta && !ta.value.startsWith('Intent:')) ta.value = `Intent: ${intent}\n` + ta.value;
      toast('intent set: ' + intent);
      addLog('INTAKE_INTENT: ' + intent, 'info');
    });
  });

  // Send button — uses intake pipeline
  const sendBtn = document.getElementById('intakeSend');
  if (sendBtn) {
    sendBtn.addEventListener('click', async () => {
      const ta = document.getElementById('intakeText');
      const raw = (ta?.value || '').trim();
      if (!raw) { toast('nothing to send'); return; }
      if (!pid) { toast('select a project first'); return; }

      try {
        const result = await api.intake.startPipeline({ projectId: pid, content: raw });
        ta.value = '';
        incrementStep();
        toast('pipeline started');
        addLog('INTAKE_PIPELINE_STARTED: ' + (result.pipelineId || ''), 'info');
        addLog('Triage → Thread creation → Distillation', 'warn');
      } catch (e) {
        toast('pipeline failed: ' + e.message);
        addLog('INTAKE_ERROR: ' + e.message, 'bad');
      }
    });
  }

  // Load recent sessions as intake timeline
  if (pid) {
    try {
      const allSessions = await api.sessions.list();
      const sessionList = allSessions.filter(s => s.projectId === pid);
      const tl = document.getElementById('intakeTimeline');
      if (sessionList.length === 0) {
        tl.innerHTML = '<div class="tiny">No intake yet for this project.</div>';
      } else {
        tl.innerHTML = '';
        sessionList.slice(0, 10).forEach(s => {
          const ev = document.createElement('div');
          ev.className = 'event';
          ev.style.cursor = 'pointer';
          const preview = esc((s.lastMessage || s.sessionId || '').substring(0, 80));
          const time = s.updatedAt ? new Date(s.updatedAt).toLocaleTimeString() : '';
          ev.innerHTML = `<div class="eventTop"><div style="min-width:0"><div class="eventTitle">${preview || 'Session'}</div><div class="tiny">${time}</div></div><span class="pill">${s.status || 'IDLE'}</span></div>`;
          ev.addEventListener('click', () => setSelected({ type: 'session', id: s.sessionId, data: s }));
          tl.appendChild(ev);
        });
      }
    } catch { /* sessions endpoint not available */ }
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
