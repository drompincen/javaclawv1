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

// Common words to filter out of keyword search
const STOP_WORDS = new Set([
  'the', 'a', 'an', 'is', 'are', 'was', 'were', 'be', 'been', 'being',
  'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'could',
  'should', 'may', 'might', 'can', 'shall', 'to', 'of', 'in', 'for',
  'on', 'with', 'at', 'by', 'from', 'as', 'into', 'about', 'between',
  'through', 'during', 'before', 'after', 'and', 'but', 'or', 'nor',
  'not', 'so', 'if', 'than', 'that', 'this', 'it', 'what', 'which',
  'who', 'whom', 'how', 'when', 'where', 'why', 'all', 'each', 'every',
  'both', 'few', 'more', 'most', 'other', 'some', 'such', 'no', 'only',
  'own', 'same', 'my', 'our', 'your', 'his', 'her', 'its', 'their', 'me'
]);

function extractKeywords(question) {
  return question.toLowerCase()
    .replace(/[^a-z0-9\s]/g, '')
    .split(/\s+/)
    .filter(w => w.length > 2 && !STOP_WORDS.has(w));
}

function scoreItem(keywords, ...fields) {
  let score = 0;
  const text = fields.join(' ').toLowerCase();
  for (const kw of keywords) {
    const idx = text.indexOf(kw);
    if (idx !== -1) score++;
  }
  return score;
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
      <div class="cardH"><div><b>\uD83E\uDD80 ASK CLAW</b><div class="tiny">Ask questions about your project data</div></div></div>
      <div class="cardB">
        <div class="ask-claw-input">
          <input type="text" id="askClawInput" placeholder="What are the biggest risks this sprint?">
          <button class="btn primary" id="askClawBtn">Ask \u2197</button>
        </div>
        <div id="askClawResponse" class="ask-results"></div>
      </div>
    </div>
    <div style="height:10px"></div>
    <div class="card">
      <div class="cardH"><div><b>Activity Log</b><div class="tiny">project data summary</div></div></div>
      <div class="cardB"><div class="activity-log" id="activityLog"><div class="tiny">Loading...</div></div></div>
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

  // Ask Claw
  const askInput = document.getElementById('askClawInput');
  const askBtn = document.getElementById('askClawBtn');

  function askTs() {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  function askLogLine(tag, text, clickable) {
    const div = document.createElement('div');
    div.className = 'ask-log-line';
    div.innerHTML = `<span class="ask-ts">${askTs()}</span><span class="ask-tag">[${esc(tag)}]</span>` +
      `<span class="${clickable ? 'ask-link' : ''}">${esc(text)}</span>`;
    return div;
  }

  async function handleAsk() {
    const question = (askInput?.value || '').trim();
    if (!question) { toast('type a question first'); return; }
    if (!pid) { toast('select a project first'); return; }

    const responseDiv = document.getElementById('askClawResponse');
    responseDiv.innerHTML = '';
    responseDiv.appendChild(askLogLine('ask', question));
    responseDiv.appendChild(askLogLine('sys', 'querying...'));

    // Try backend first
    const backendResult = await api.ask.query(pid, question);
    if (backendResult && backendResult.answer) {
      responseDiv.innerHTML = '';
      responseDiv.appendChild(askLogLine('ask', question));
      responseDiv.appendChild(askLogLine('sys', 'response from generalist agent'));

      const answerDiv = document.createElement('div');
      answerDiv.className = 'ask-answer';
      answerDiv.textContent = backendResult.answer;
      responseDiv.appendChild(answerDiv);

      if (backendResult.sources && backendResult.sources.length > 0) {
        const srcCount = backendResult.sources.length;
        const srcSummary = backendResult.sources.slice(0, 4).map(s => s.title || s.id).join(', ');
        const line = askLogLine('src', `${srcCount} sources: ${srcSummary}${srcCount > 4 ? '...' : ''}`);
        responseDiv.appendChild(line);
      }
      return;
    }

    // Client-side keyword search fallback
    try {
      const [threads, objectives, tickets, blindspots] = await Promise.all([
        api.threads.list(pid).catch(() => []),
        api.objectives.list(pid).catch(() => []),
        api.tickets.list(pid).catch(() => []),
        api.blindspots.list(pid).catch(() => [])
      ]);

      const keywords = extractKeywords(question);
      if (keywords.length === 0) {
        responseDiv.innerHTML = '';
        responseDiv.appendChild(askLogLine('ask', question));
        responseDiv.appendChild(askLogLine('warn', 'no keywords extracted — try a more specific question'));
        return;
      }

      responseDiv.innerHTML = '';
      responseDiv.appendChild(askLogLine('ask', question));
      responseDiv.appendChild(askLogLine('sys', `local search — keywords: ${keywords.join(', ')}`));

      const scored = [
        ...threads.map(t => ({ score: scoreItem(keywords, t.title||'', t.content||'', t.summary||''), label: t.title||'Untitled', type: 'thread', id: t.threadId, data: t })),
        ...objectives.map(o => ({ score: scoreItem(keywords, o.outcome||'', o.sprintName||''), label: `${o.sprintName||''} — ${o.outcome||''}`, type: 'objective', id: o.objectiveId, data: o })),
        ...tickets.map(t => ({ score: scoreItem(keywords, t.title||'', t.description||'', t.summary||''), label: t.title||t.key||'Untitled', type: 'ticket', id: t.ticketId, data: t })),
        ...blindspots.map(b => ({ score: scoreItem(keywords, b.title||'', b.description||''), label: b.title||'Untitled', type: 'blindspot', id: b.blindspotId, data: b })),
      ].filter(r => r.score > 0).sort((a, b) => b.score - a.score);

      if (scored.length === 0) {
        responseDiv.appendChild(askLogLine('sys', 'no matches found'));
        return;
      }

      responseDiv.appendChild(askLogLine('hit', `${scored.length} results across ${new Set(scored.map(s=>s.type)).size} collections`));

      scored.slice(0, 8).forEach(r => {
        const line = askLogLine(r.type.substring(0, 4), r.label, true);
        line.querySelector('.ask-link').addEventListener('click', () => setSelected({ type: r.type, id: r.id, data: r.data }));
        responseDiv.appendChild(line);
      });

      if (scored.length > 8) {
        responseDiv.appendChild(askLogLine('sys', `+${scored.length - 8} more results`));
      }
    } catch {
      responseDiv.innerHTML = '';
      responseDiv.appendChild(askLogLine('err', 'search failed'));
    }
  }

  if (askBtn) askBtn.addEventListener('click', handleAsk);
  if (askInput) {
    askInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); handleAsk(); }
    });
  }

  // Populate activity log
  if (pid) {
    try {
      const logEl = document.getElementById('activityLog');
      if (!logEl) return;
      logEl.innerHTML = '';

      function logLine(label, cls, text) {
        const div = document.createElement('div');
        div.className = 'log-line';
        const ts = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        div.innerHTML = `<span class="log-ts">${ts}</span><span class="log-label ${cls}">${esc(label)}</span>${esc(text)}`;
        logEl.appendChild(div);
      }

      const safe = async (fn) => { try { return await fn(); } catch { return []; } };
      const [threads, tickets, objs, blindspots, resources, agents] = await Promise.all([
        safe(() => api.threads.list(pid)),
        safe(() => api.tickets.list(pid)),
        safe(() => api.objectives.list(pid)),
        safe(() => api.blindspots.list(pid)),
        safe(() => api.resources.list()),
        safe(() => api.agents.list()),
      ]);

      logLine('data', 'accent', `${threads.length} threads, ${tickets.length} tickets, ${objs.length} objectives`);
      logLine('data', 'accent', `${blindspots.length} blindspots, ${resources.length} resources`);

      const openBs = blindspots.filter(b => b.status === 'OPEN').length;
      if (openBs > 0) logLine('warn', 'warn', `${openBs} open blindspot(s)`);

      const lowAvail = resources.filter(r => r.availability < 0.4);
      if (lowAvail.length > 0) logLine('warn', 'warn', `${lowAvail.length} resource(s) below 40% availability`);

      if (Array.isArray(agents) && agents.length > 0) {
        logLine('sys', 'good', `${agents.length} agents registered`);
      }

      if (threads.length === 0 && tickets.length === 0) {
        logLine('hint', '', 'paste content in intake to start generating data');
      }
    } catch { /* activity log is non-critical */ }
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
