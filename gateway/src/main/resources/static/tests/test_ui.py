"""
Static analysis + content verification tests for UI improvements.
Run: python3 tests/test_ui.py
"""
import os
import re
import json
import unittest
import http.server
import threading
import urllib.request

STATIC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(relpath):
    with open(os.path.join(STATIC_DIR, relpath), encoding='utf-8') as f:
        return f.read()


# ── 1. Inspector: case 'session' ──────────────────────────────────────────

class TestInspectorSession(unittest.TestCase):
    def setUp(self):
        self.src = read('js/panels/inspector.js')

    def test_case_session_exists(self):
        self.assertIn("case 'session'", self.src,
                       "inspector.js must handle case 'session'")

    def test_loads_session_messages(self):
        self.assertIn('loadSessionMessages', self.src,
                       "inspector.js must define loadSessionMessages()")

    def test_session_messages_container(self):
        self.assertIn("getElementById('sessionMessages')", self.src,
                       "must target #sessionMessages container")

    def test_renders_role_chip(self):
        # The session message renderer should output role chips
        self.assertRegex(self.src, r'class="chip.*?".*?m\.role',
                         "session messages should render role as chip")

    def test_agent_label_chip(self):
        self.assertIn('m.agentId', self.src,
                       "session messages should show agent ID when present")


# ── 2. Agent buttons removed ─────────────────────────────────────────────

class TestAgentPanel(unittest.TestCase):
    def setUp(self):
        self.src = read('js/panels/agents.js')

    def test_no_button_loop(self):
        # initAgentPanel should NOT iterate AGENTS to create buttons
        self.assertNotIn('AGENTS.forEach', self.src,
                         "initAgentPanel must not loop AGENTS to create buttons")

    def test_info_message(self):
        self.assertIn('Pipeline handles agents automatically', self.src,
                       "should show pipeline info message")

    def test_run_agent_export_preserved(self):
        self.assertIn('export async function runAgent', self.src,
                       "runAgent export must remain for programmatic use")


# ── 3. Paper plane icon ──────────────────────────────────────────────────

class TestPaperPlane(unittest.TestCase):
    def setUp(self):
        self.intake = read('js/views/intake.js')
        self.css = read('css/cockpit.css')

    def test_svg_in_send_button(self):
        self.assertIn('<svg', self.intake,
                       "send button must use inline SVG")

    def test_old_arrow_removed(self):
        self.assertNotIn('&#10148;', self.intake,
                         "old arrow entity must be removed")

    def test_svg_vertical_align_css(self):
        self.assertIn('.btn svg', self.css,
                       "CSS must have .btn svg rule")
        self.assertIn('vertical-align: middle', self.css,
                       "SVG in buttons should be vertically centered")


# ── 4. Agent tile pulse-green animation ──────────────────────────────────

class TestPulseGreen(unittest.TestCase):
    def setUp(self):
        self.css = read('css/cockpit.css')

    def test_keyframes_defined(self):
        self.assertIn('@keyframes pulse-green', self.css,
                       "pulse-green keyframes must be defined")

    def test_agent_tile_running_uses_animation(self):
        # Find the .agent-tile.running rule that references pulse-green
        self.assertRegex(self.css, r'\.agent-tile\.running\s*\{[^}]*pulse-green',
                         ".agent-tile.running must use pulse-green animation")

    def test_box_shadow_in_animation(self):
        self.assertIn('box-shadow', self.css,
                       "pulse-green should animate box-shadow")


# ── 5. Token counter ─────────────────────────────────────────────────────

class TestTokenCounter(unittest.TestCase):
    def test_api_logs_metrics(self):
        src = read('js/api.js')
        self.assertIn('logs', src, "api.js must export logs namespace")
        self.assertIn('/api/logs/llm-interactions/metrics', src,
                       "must call correct metrics endpoint")

    def test_app_refresh_function(self):
        src = read('js/app.js')
        self.assertIn('refreshTokenCounter', src,
                       "app.js must define refreshTokenCounter")

    def test_app_polling_interval(self):
        src = read('js/app.js')
        self.assertIn('setInterval(refreshTokenCounter', src,
                       "must poll refreshTokenCounter on interval")
        self.assertIn('30000', src, "polling interval should be 30s")

    def test_footer_token_placeholder(self):
        html = read('index.html')
        self.assertIn('id="tokenCount"', html,
                       "footer must have tokenCount element")
        self.assertIn('tokens', html.lower(),
                       "footer should mention tokens")

    def test_event_count_no_longer_overwrites(self):
        src = read('js/app.js')
        # The WS event handler should NOT write to tokenCount anymore
        lines = src.split('\n')
        in_ws_handler = False
        for line in lines:
            if 'ws.onEvent' in line:
                in_ws_handler = True
            if in_ws_handler and "el.textContent = 'events:" in line:
                self.fail("WS handler must not overwrite tokenCount with event count")
            if in_ws_handler and line.strip() == '});':
                in_ws_handler = False


# ── 6. Objectives inline actions ─────────────────────────────────────────

class TestObjectivesInline(unittest.TestCase):
    def setUp(self):
        self.src = read('js/views/objectives.js')

    def test_actions_column_added(self):
        self.assertIn("'actions'", self.src,
                       "renderTable columns must include 'actions'")

    def test_complete_button(self):
        self.assertIn('ACHIEVED', self.src,
                       "must set status to ACHIEVED on complete")

    def test_atrisk_button(self):
        self.assertIn('AT_RISK', self.src,
                       "must set status to AT_RISK")

    def test_delete_button_with_confirm(self):
        self.assertIn('confirm(', self.src,
                       "delete action must confirm before deleting")

    def test_stop_propagation(self):
        self.assertIn('stopPropagation', self.src,
                       "action buttons must stop click propagation to avoid row select")


# ── 7. Resources busy/free ───────────────────────────────────────────────

class TestResourcesBusyFree(unittest.TestCase):
    def setUp(self):
        self.src = read('js/views/resources.js')

    def test_free_pill(self):
        self.assertIn("'FREE'", self.src,
                       "must render FREE pill")

    def test_busy_pill(self):
        self.assertIn("'BUSY'", self.src,
                       "must render BUSY pill")

    def test_threshold(self):
        self.assertIn('>= 0.5', self.src,
                       "threshold for FREE should be >= 0.5")

    def test_pill_good_class(self):
        self.assertIn("'pill ' + (val >= 0.5 ? 'good' : 'bad')", self.src,
                       "should use pill good/bad classes")


# ── 8. Structural integrity ──────────────────────────────────────────────

class TestStructuralIntegrity(unittest.TestCase):
    """Verify no syntax-breaking issues in modified files."""

    FILES = [
        'js/panels/inspector.js',
        'js/panels/agents.js',
        'js/views/intake.js',
        'js/views/objectives.js',
        'js/views/resources.js',
        'js/api.js',
        'js/app.js',
        'index.html',
        'css/cockpit.css',
    ]

    def test_all_files_exist(self):
        for f in self.FILES:
            path = os.path.join(STATIC_DIR, f)
            self.assertTrue(os.path.isfile(path), f"{f} must exist")

    def test_js_balanced_braces(self):
        for f in self.FILES:
            if not f.endswith('.js'):
                continue
            src = read(f)
            opens = src.count('{')
            closes = src.count('}')
            self.assertEqual(opens, closes,
                             f"{f}: unbalanced braces (open={opens}, close={closes})")

    def test_js_balanced_parens(self):
        for f in self.FILES:
            if not f.endswith('.js'):
                continue
            src = read(f)
            opens = src.count('(')
            closes = src.count(')')
            self.assertEqual(opens, closes,
                             f"{f}: unbalanced parens (open={opens}, close={closes})")

    def test_html_balanced_tags(self):
        html = read('index.html')
        for tag in ['div', 'span', 'button', 'select']:
            opens = len(re.findall(rf'<{tag}[\s>]', html))
            closes = html.count(f'</{tag}>')
            self.assertEqual(opens, closes,
                             f"index.html: unbalanced <{tag}> (open={opens}, close={closes})")

    def test_css_balanced_braces(self):
        src = read('css/cockpit.css')
        opens = src.count('{')
        closes = src.count('}')
        self.assertEqual(opens, closes,
                         f"cockpit.css: unbalanced braces (open={opens}, close={closes})")

    def test_no_duplicate_exports(self):
        """Ensure we didn't accidentally create duplicate export names in api.js."""
        src = read('js/api.js')
        exports = re.findall(r'export const (\w+)', src)
        self.assertEqual(len(exports), len(set(exports)),
                         f"api.js has duplicate exports: {exports}")


# ── 9. Served HTML smoke test ────────────────────────────────────────────

class TestServedHTML(unittest.TestCase):
    """Spin up a quick HTTP server and verify index.html is servable."""

    @classmethod
    def setUpClass(cls):
        cls.handler = lambda *a: http.server.SimpleHTTPRequestHandler(*a, directory=STATIC_DIR)
        cls.server = http.server.HTTPServer(('127.0.0.1', 0), cls.handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def _get(self, path):
        url = f'http://127.0.0.1:{self.port}{path}'
        with urllib.request.urlopen(url) as r:
            return r.status, r.read().decode()

    def test_index_serves(self):
        status, body = self._get('/index.html')
        self.assertEqual(status, 200)
        self.assertIn('JAVACLAW', body)

    def test_css_serves(self):
        status, body = self._get('/css/cockpit.css')
        self.assertEqual(status, 200)
        self.assertIn('pulse-green', body)

    def test_api_js_serves(self):
        status, body = self._get('/js/api.js')
        self.assertEqual(status, 200)
        self.assertIn('logs', body)

    def test_app_js_serves(self):
        status, body = self._get('/js/app.js')
        self.assertEqual(status, 200)
        self.assertIn('refreshTokenCounter', body)


if __name__ == '__main__':
    unittest.main(verbosity=2)
