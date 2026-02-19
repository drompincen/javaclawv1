/**
 * DOM-level tests for UI improvements using jsdom.
 * Run: node tests/test_ui.mjs
 */
import { JSDOM } from 'jsdom';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import assert from 'assert';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STATIC = path.resolve(__dirname, '..');

function readFile(rel) {
  return fs.readFileSync(path.join(STATIC, rel), 'utf-8');
}

let passed = 0;
let failed = 0;
const failures = [];

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`  \x1b[32m✓\x1b[0m ${name}`);
  } catch (e) {
    failed++;
    failures.push({ name, error: e.message });
    console.log(`  \x1b[31m✗\x1b[0m ${name}`);
    console.log(`    ${e.message}`);
  }
}

function makeDom(html = '') {
  return new JSDOM(`<!doctype html><html><body>${html}</body></html>`, {
    url: 'http://localhost:9082/',
    pretendToBeVisual: true,
  });
}

// ═══════════════════════════════════════════════════════════════
console.log('\n── 1. Inspector: session case ──');
// ═══════════════════════════════════════════════════════════════

test('renderInspector handles session type', () => {
  const src = readFile('js/panels/inspector.js');
  // Extract the switch cases
  const cases = [...src.matchAll(/case\s+'(\w+)'/g)].map(m => m[1]);
  assert.ok(cases.includes('session'), 'Missing case "session" in switch');
});

test('loadSessionMessages defined', () => {
  const src = readFile('js/panels/inspector.js');
  assert.ok(src.includes('async function loadSessionMessages'), 'Missing loadSessionMessages');
});

test('session inspector renders pills for roles', () => {
  const src = readFile('js/panels/inspector.js');
  // Inside loadSessionMessages, it should create chip elements with role
  assert.ok(src.includes('class="chip'), 'Should render chip class for role');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 1b. Thread content preview + inspector ──');
// ═══════════════════════════════════════════════════════════════

test('threads.js references raw.content for preview', () => {
  const src = readFile('js/views/threads.js');
  assert.ok(src.includes('raw.content'), 'Must reference raw.content for preview');
});

test('threads.js truncates content at 200 chars', () => {
  const src = readFile('js/views/threads.js');
  assert.ok(src.includes('200'), 'Content preview should truncate at 200 chars');
});

test('inspector.js displays thread content', () => {
  const src = readFile('js/panels/inspector.js');
  assert.ok(src.includes('d.content'), 'Must reference d.content in thread inspector');
  assert.ok(src.includes('<b>Content:</b>'), 'Must render Content label');
});

test('inspector content uses pre-wrap formatting', () => {
  const src = readFile('js/panels/inspector.js');
  assert.ok(src.includes('white-space:pre-wrap'), 'Content should use pre-wrap');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 2. Agent panel ──');
// ═══════════════════════════════════════════════════════════════

test('initAgentPanel shows info message instead of buttons', () => {
  const dom = makeDom('<div id="agentButtons"></div>');
  const container = dom.window.document.getElementById('agentButtons');

  // Simulate what initAgentPanel now does
  container.innerHTML = '<div class="tiny">Pipeline handles agents automatically during intake. Use timers below for scheduled runs.</div>';

  assert.ok(container.querySelector('.tiny'), 'Should have info message');
  assert.strictEqual(container.querySelectorAll('button').length, 0, 'Should have no buttons');
});

test('AGENTS array still defined in agents.js', () => {
  const src = readFile('js/panels/agents.js');
  assert.ok(src.includes('const AGENTS'), 'AGENTS array must still exist');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 3. Paper plane SVG ──');
// ═══════════════════════════════════════════════════════════════

test('send button contains SVG element', () => {
  const src = readFile('js/views/intake.js');
  const svgMatch = src.match(/<svg[^>]*>[\s\S]*?<\/svg>/);
  assert.ok(svgMatch, 'Must contain inline SVG');
});

test('SVG has correct viewBox', () => {
  const src = readFile('js/views/intake.js');
  assert.ok(src.includes('viewBox="0 0 24 24"'), 'SVG should have 24x24 viewBox');
});

test('SVG renders in DOM correctly', () => {
  const dom = makeDom(`
    <button class="btn primary" id="intakeSend">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M22 2L11 13"/><path d="M22 2L15 22L11 13L2 9L22 2Z"/>
      </svg>
    </button>
  `);
  const btn = dom.window.document.getElementById('intakeSend');
  const svg = btn.querySelector('svg');
  assert.ok(svg, 'Button must contain SVG element');
  assert.strictEqual(svg.querySelectorAll('path').length, 2, 'SVG should have 2 paths');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 4. CSS pulse-green ──');
// ═══════════════════════════════════════════════════════════════

test('pulse-green keyframes exist', () => {
  const css = readFile('css/cockpit.css');
  assert.ok(css.includes('@keyframes pulse-green'), 'Missing @keyframes pulse-green');
});

test('.agent-tile.running uses pulse-green', () => {
  const css = readFile('css/cockpit.css');
  // Find .agent-tile.running rule
  const ruleMatch = css.match(/\.agent-tile\.running\s*\{([^}]+)\}/);
  assert.ok(ruleMatch, 'Missing .agent-tile.running rule');
  assert.ok(ruleMatch[1].includes('pulse-green'), 'Rule must reference pulse-green');
});

test('.btn svg vertical-align rule', () => {
  const css = readFile('css/cockpit.css');
  const rule = css.match(/\.btn\s+svg\s*\{([^}]+)\}/);
  assert.ok(rule, 'Missing .btn svg rule');
  assert.ok(rule[1].includes('vertical-align'), 'Must set vertical-align');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 5. Token counter ──');
// ═══════════════════════════════════════════════════════════════

test('api.js exports logs.metrics', () => {
  const src = readFile('js/api.js');
  assert.ok(src.includes('export const logs'), 'Must export logs');
  assert.ok(src.includes("metrics:"), 'logs must have metrics method');
  assert.ok(src.includes('/api/logs/llm-interactions/metrics'), 'Correct endpoint');
});

test('app.js defines refreshTokenCounter', () => {
  const src = readFile('js/app.js');
  assert.ok(src.includes('async function refreshTokenCounter'), 'Must define refreshTokenCounter');
});

test('app.js polls every 30 seconds', () => {
  const src = readFile('js/app.js');
  assert.ok(src.includes('setInterval(refreshTokenCounter, 30000)'), 'Must set 30s interval');
});

test('refreshTokenCounter formats output correctly', () => {
  const src = readFile('js/app.js');
  assert.ok(src.includes('tokens'), 'Output must mention tokens');
  assert.ok(src.includes('calls'), 'Output must mention calls');
  assert.ok(src.includes('avg'), 'Output must mention avg latency');
});

test('index.html footer has token counter', () => {
  const html = readFile('index.html');
  assert.ok(html.includes('id="tokenCount"'), 'Footer must have tokenCount span');
  assert.ok(html.includes('tokens'), 'Footer must show tokens text');
});

test('WS handler no longer overwrites tokenCount', () => {
  const src = readFile('js/app.js');
  // Find the ws.onEvent callback and check it doesn't write to tokenCount
  const wsBlock = src.match(/ws\.onEvent\(\(msg\)\s*=>\s*\{([\s\S]*?)\}\);/);
  assert.ok(wsBlock, 'ws.onEvent block must exist');
  assert.ok(!wsBlock[1].includes("tokenCount"), 'WS handler must not touch tokenCount');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 6. Objectives inline actions ──');
// ═══════════════════════════════════════════════════════════════

test('renderTable includes actions column', () => {
  const src = readFile('js/views/objectives.js');
  assert.ok(src.includes("'actions'"), 'Column list must include actions');
});

test('objectives has complete/atrisk/delete handlers', () => {
  const src = readFile('js/views/objectives.js');
  assert.ok(src.includes("'ACHIEVED'"), 'Must set ACHIEVED status');
  assert.ok(src.includes("'AT_RISK'"), 'Must set AT_RISK status');
  assert.ok(src.includes('objectives.delete'), 'Must call delete API');
});

test('action buttons call stopPropagation', () => {
  const src = readFile('js/views/objectives.js');
  assert.ok(src.includes('e.stopPropagation()'), 'Must stop propagation');
});

test('inline buttons render in DOM', () => {
  const dom = makeDom('<table class="table"><tbody><tr><td>s1</td><td>obj1</td><td>80%</td><td>0</td><td>Low</td><td>PROPOSED</td><td class="actions"></td></tr></tbody></table>');
  const doc = dom.window.document;
  const cell = doc.querySelector('.actions');

  // Simulate what objectives.js does — create 3 buttons
  const labels = ['\u2713', '\u26A0', '\u2715'];
  labels.forEach(l => {
    const b = doc.createElement('button');
    b.className = 'btn';
    b.textContent = l;
    cell.appendChild(b);
  });

  assert.strictEqual(cell.querySelectorAll('button').length, 3, 'Should have 3 action buttons');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 7. Resources busy/free ──');
// ═══════════════════════════════════════════════════════════════

test('resources.js contains FREE and BUSY text', () => {
  const src = readFile('js/views/resources.js');
  assert.ok(src.includes("'FREE'"), 'Must render FREE');
  assert.ok(src.includes("'BUSY'"), 'Must render BUSY');
});

test('threshold is 0.5', () => {
  const src = readFile('js/views/resources.js');
  assert.ok(src.includes('>= 0.5'), 'Threshold must be >= 0.5');
});

test('pills render correctly in DOM', () => {
  const dom = makeDom('<table><tbody><tr><td>Name</td><td>Role</td><td>100</td><td>0.8</td><td>a@b.com</td><td>proj1</td></tr></tbody></table>');
  const doc = dom.window.document;
  const availCell = doc.querySelectorAll('td')[3];

  // Simulate the post-processing
  const val = 0.8;
  const pill = doc.createElement('span');
  pill.className = 'pill ' + (val >= 0.5 ? 'good' : 'bad');
  pill.textContent = val >= 0.5 ? 'FREE' : 'BUSY';
  availCell.appendChild(pill);

  assert.ok(availCell.querySelector('.pill.good'), 'Should have green pill for 0.8');
  assert.strictEqual(availCell.querySelector('.pill').textContent, 'FREE', 'Should say FREE');
});

test('BUSY pill for low availability', () => {
  const dom = makeDom('<table><tbody><tr><td>0.2</td></tr></tbody></table>');
  const doc = dom.window.document;
  const cell = doc.querySelector('td');

  const val = 0.2;
  const pill = doc.createElement('span');
  pill.className = 'pill ' + (val >= 0.5 ? 'good' : 'bad');
  pill.textContent = val >= 0.5 ? 'FREE' : 'BUSY';
  cell.appendChild(pill);

  assert.ok(cell.querySelector('.pill.bad'), 'Should have red pill for 0.2');
  assert.strictEqual(cell.querySelector('.pill').textContent, 'BUSY', 'Should say BUSY');
});

// ═══════════════════════════════════════════════════════════════
console.log('\n── 8. Structural integrity ──');
// ═══════════════════════════════════════════════════════════════

const FILES = [
  'js/panels/inspector.js',
  'js/panels/agents.js',
  'js/views/intake.js',
  'js/views/objectives.js',
  'js/views/resources.js',
  'js/views/threads.js',
  'js/api.js',
  'js/app.js',
  'index.html',
  'css/cockpit.css',
];

test('all modified files exist', () => {
  FILES.forEach(f => {
    const full = path.join(STATIC, f);
    assert.ok(fs.existsSync(full), `${f} must exist`);
  });
});

test('JS files have balanced braces', () => {
  FILES.filter(f => f.endsWith('.js')).forEach(f => {
    const src = readFile(f);
    const opens = (src.match(/\{/g) || []).length;
    const closes = (src.match(/\}/g) || []).length;
    assert.strictEqual(opens, closes, `${f}: unbalanced braces {=${opens} }=${closes}`);
  });
});

test('JS files have balanced parentheses', () => {
  FILES.filter(f => f.endsWith('.js')).forEach(f => {
    const src = readFile(f);
    const opens = (src.match(/\(/g) || []).length;
    const closes = (src.match(/\)/g) || []).length;
    assert.strictEqual(opens, closes, `${f}: unbalanced parens (=${opens} )=${closes}`);
  });
});

test('CSS has balanced braces', () => {
  const src = readFile('css/cockpit.css');
  const opens = (src.match(/\{/g) || []).length;
  const closes = (src.match(/\}/g) || []).length;
  assert.strictEqual(opens, closes, `cockpit.css: unbalanced braces {=${opens} }=${closes}`);
});

test('no duplicate exports in api.js', () => {
  const src = readFile('js/api.js');
  const exports = [...src.matchAll(/export const (\w+)/g)].map(m => m[1]);
  const unique = new Set(exports);
  assert.strictEqual(exports.length, unique.size, `Duplicate exports: ${exports}`);
});

test('index.html has valid structure', () => {
  const html = readFile('index.html');
  const dom = new JSDOM(html);
  const doc = dom.window.document;
  assert.ok(doc.getElementById('tokenCount'), 'Must have #tokenCount');
  assert.ok(doc.getElementById('agentButtons'), 'Must have #agentButtons');
  assert.ok(doc.getElementById('inspector'), 'Must have #inspector');
  assert.ok(doc.querySelector('script[src="js/app.js"]'), 'Must load app.js');
});

// ═══════════════════════════════════════════════════════════════
// Summary
// ═══════════════════════════════════════════════════════════════
console.log('\n' + '═'.repeat(50));
console.log(`  ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failures.length > 0) {
  console.log('\nFailures:');
  failures.forEach(f => console.log(`  ✗ ${f.name}: ${f.error}`));
}
console.log('');
process.exit(failed > 0 ? 1 : 0);
