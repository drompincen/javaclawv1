import * as api from '../api.js';
import { getState, setSelected, incrementStep } from '../state.js';
import { toast } from '../components/toast.js';
import { addLog } from '../panels/activity.js';

let pendingFiles = [];

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1048576).toFixed(1) + ' MB';
}

function renderChips() {
  const container = document.getElementById('fileChips');
  if (!container) return;
  container.innerHTML = '';
  pendingFiles.forEach((f, i) => {
    const chip = document.createElement('span');
    chip.className = 'fileChip';
    chip.innerHTML = `${esc(f.name)} <span class="tiny">(${formatSize(f.size)})</span><span class="remove" data-idx="${i}">&times;</span>`;
    container.appendChild(chip);
  });
  container.querySelectorAll('.remove').forEach(el => {
    el.addEventListener('click', (e) => {
      pendingFiles.splice(Number(e.target.dataset.idx), 1);
      renderChips();
    });
  });
}

function addFiles(fileList) {
  for (const f of fileList) {
    if (!pendingFiles.some(p => p.name === f.name && p.size === f.size)) {
      pendingFiles.push(f);
    }
  }
  renderChips();
}

export async function render() {
  pendingFiles = [];
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
        <div class="dropzone" id="dropZone">Drop files here or click to browse
          <input type="file" id="fileInput" multiple accept=".xlsx,.xls,.csv,.json,.txt,.md,.html,.pdf,.png,.jpg,.jpeg" style="display:none">
        </div>
        <div class="fileChips" id="fileChips"></div>
        <div class="row" style="margin-top:8px;">
          <button class="btn primary" id="intakeSend" title="Send to Pipeline (Ctrl+Enter)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2L11 13"/><path d="M22 2L15 22L11 13L2 9L22 2Z"/></svg></button>
        </div>
      </div>
    </div>
    <div style="height:10px"></div>
    <div class="card">
      <div class="cardH"><div><b>Recent intake</b><div class="tiny">select to inspect</div></div></div>
      <div class="cardB"><div class="timeline" id="intakeTimeline"><div class="tiny">Loading...</div></div></div>
    </div>`;

  // Ctrl+Enter to send
  const ta = document.getElementById('intakeText');
  if (ta) {
    ta.addEventListener('keydown', (e) => {
      if (e.ctrlKey && e.key === 'Enter') {
        e.preventDefault();
        document.getElementById('intakeSend')?.click();
      }
    });
  }

  // Drop zone events
  const dropZone = document.getElementById('dropZone');
  const fileInput = document.getElementById('fileInput');
  if (dropZone && fileInput) {
    dropZone.addEventListener('click', () => fileInput.click());
    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropZone.classList.remove('dragover');
      if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files);
    });
    fileInput.addEventListener('change', () => {
      if (fileInput.files.length) addFiles(fileInput.files);
      fileInput.value = '';
    });
  }

  // Send button — triggers the full intake pipeline
  const sendBtn = document.getElementById('intakeSend');
  if (sendBtn) {
    sendBtn.addEventListener('click', async () => {
      const ta = document.getElementById('intakeText');
      const raw = (ta?.value || '').trim();
      if (!raw && pendingFiles.length === 0) { toast('nothing to send'); return; }
      if (!pid) { toast('select a project first'); return; }

      try {
        let filePaths = [];

        // Upload files first if any
        if (pendingFiles.length > 0) {
          const uploaded = await api.uploads.send(pid, pendingFiles);
          filePaths = uploaded.map(u => u.filePath);
          addLog('UPLOADED ' + uploaded.length + ' file(s)', 'info');
        }

        const result = await api.intake.startPipeline({
          projectId: pid,
          content: raw || null,
          filePaths: filePaths.length > 0 ? filePaths : null
        });
        ta.value = '';
        pendingFiles = [];
        renderChips();
        incrementStep();
        toast('pipeline started');
        addLog('INTAKE_PIPELINE_STARTED: ' + (result.pipelineId || ''), 'info');
        addLog('Triage \u2192 Thread creation \u2192 Distillation', 'warn');
      } catch (e) {
        toast('pipeline failed: ' + e.message);
        addLog('INTAKE_ERROR: ' + e.message, 'bad');
      }
    });
  }

  // Load recent pipeline sessions + project stats
  if (pid) {
    try {
      const allSessions = await api.sessions.list();
      const pipelineSessions = allSessions.filter(s => s.projectId === pid && s.metadata?.type === 'pipeline');
      const tl = document.getElementById('intakeTimeline');

      // Also load thread count for context
      let threadCount = 0;
      try { const threads = await api.threads.list(pid); threadCount = threads.length; } catch {}
      let ticketCount = 0;
      try { const tickets = await api.tickets.list(pid); ticketCount = tickets.length; } catch {}

      if (pipelineSessions.length === 0 && threadCount === 0) {
        tl.innerHTML = '<div class="tiny">No intake yet for this project.</div>';
      } else {
        tl.innerHTML = '';
        if (threadCount > 0 || ticketCount > 0) {
          const stats = document.createElement('div');
          stats.className = 'tiny';
          stats.style.marginBottom = '8px';
          stats.innerHTML = `<b>Project data:</b> ${threadCount} thread(s), ${ticketCount} ticket(s)`;
          tl.appendChild(stats);
        }

        // Fetch first user message for each pipeline session in parallel
        const sessionsToShow = pipelineSessions.slice(0, 10);
        const messageResults = await Promise.all(
          sessionsToShow.map(s =>
            api.sessions.messages(s.sessionId).catch(() => [])
          )
        );

        sessionsToShow.forEach((s, i) => {
          const ev = document.createElement('div');
          ev.className = 'event';
          ev.style.cursor = 'pointer';
          const time = s.updatedAt ? new Date(s.updatedAt).toLocaleTimeString() : '';
          const statusClass = s.status === 'COMPLETED' ? 'good' : s.status === 'FAILED' ? 'bad' : '';

          // Find first user message content for a meaningful title
          const msgs = messageResults[i] || [];
          const userMsg = msgs.find(m => m.role === 'user');
          let title = userMsg?.content || '';
          if (title.length > 120) title = title.substring(0, 120) + '\u2026';
          if (!title) title = s.metadata?.agentId || 'pipeline';

          ev.innerHTML = `<div class="eventTop"><div style="min-width:0"><div class="eventTitle">${esc(title)}</div><div class="tiny">${time}</div></div><span class="pill ${statusClass}">${s.status || 'IDLE'}</span></div>`;
          ev.addEventListener('click', () => setSelected({ type: 'session', id: s.sessionId, data: s }));
          tl.appendChild(ev);
        });
      }
    } catch { /* sessions endpoint not available */ }
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
