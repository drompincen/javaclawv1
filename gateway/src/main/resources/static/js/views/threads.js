import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';
import { addLog } from '../panels/activity.js';
import { runAgent } from '../panels/agents.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'THREADS';
  document.getElementById('centerSub').textContent =
    'Threads are named + created by the Thread Agent from intake clustering (topic + continuity + dates).';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Threads</b><div class="tiny">createdBy: thread-agent • select to inspect</div></div>
        <div class="row"><button class="btn" id="runThreadAgent">Run Thread Agent</button></div>
      </div>
      <div class="cardB" id="threadTableContainer"></div>
    </div>`;

  document.getElementById('runThreadAgent')?.addEventListener('click', () => runAgent('thread_agent'));

  if (!pid) {
    document.getElementById('threadTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const threads = await api.threads.list(pid);
    const rows = threads.map(t => ({
      title: t.title || 'Untitled',
      lifecycle: t.lifecycle || 'DRAFT',
      evidence: (t.evidenceRefs || []).length,
      ideas: (t.ideaIds || []).length,
      updated: t.updatedAt ? new Date(t.updatedAt).toLocaleTimeString() : '',
      createdby: t.createdBy || '?',
      _raw: t
    }));
    renderTable(
      document.getElementById('threadTableContainer'),
      ['title', 'lifecycle', 'evidence', 'ideas', 'updated', 'createdBy'],
      rows,
      (row) => setSelected({ type: 'thread', id: row._raw.threadId, data: row._raw })
    );
  } catch {
    document.getElementById('threadTableContainer').innerHTML = '<div class="tiny">Could not load threads.</div>';
  }
}
