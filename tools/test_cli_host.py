"""CLI fixtures run only synthetic subprocesses. No model, real CLI or credentials."""
import json
import pathlib
import shlex
import sys
import tempfile
import threading
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
ns = {'__name__': 'cli_fixture'}
exec(compile((ASSETS / 'cli_events.py').read_text() + '\n' + (ASSETS / 'cli_host.py').read_text() + '\n' +
             (ASSETS / 'remote_session.py').read_text(), 'bundled-host', 'exec'), ns)


class Peer:
    def __init__(self):
        self.values = []
    def send(self, value):
        self.values.append(json.loads(json.dumps(value)))


class CliFixtureTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / 'build/implementation/fixtures'
        parent.mkdir(parents=True, exist_ok=True)
        self.exceptions = []
        self.old_hook = threading.excepthook
        threading.excepthook = lambda args: self.exceptions.append(args.exc_value)
        self.tmp = tempfile.TemporaryDirectory(dir=parent, ignore_cleanup_errors=True)
        self.folder = pathlib.Path(self.tmp.name)
        self.host = ns['CliHost'](self.folder, sys.executable, 'claude')
        self.peer = Peer()
        self.host.peer = self.peer

    def tearDown(self):
        for process in self.host.active.values():
            process.terminate()
            process.wait()
        self.tmp.cleanup()
        threading.excepthook = self.old_hook
        self.assertEqual([], self.exceptions)

    def request(self, method, **params):
        self.host.receive(self.peer, dict(id=123, method=method, params=params))
        return [v for v in self.peer.values if v.get('id') == 123][-1]

    def start(self):
        return self.request('thread/start', cwd=str(self.folder))['result']['thread']['id']

    def command(self, mode):
        fixture = self.folder / 'fake.py'
        fixture.write_text('''import sys,json,time
from pathlib import Path
Path(__file__).with_suffix('.capture.json').write_text(json.dumps({'argv':sys.argv[1:],'prompt':sys.stdin.read()}))
print(json.dumps({'type':'system','session_id':'native-session'}),flush=True)
if MODE == 'slow': time.sleep(10)
if MODE == 'error': sys.exit(2)
if MODE == 'dsh':
 print(json.dumps({'type':'session','sessionId':'session-fixture','cwd':'.'}))
 print(json.dumps({'type':'text','text':'safe answer'}))
 print(json.dumps({'type':'status','phase':'turn_end','reason':{'kind':'completed'}}))
 print(json.dumps({'type':'final','text':'safe answer'}))
 print(json.dumps({'type':'final','text':'safe answer'}))
 sys.exit(0)
if MODE == 'malformed': print('invalid');sys.exit(0)
if MODE == 'shape': print('[]');sys.exit(0)
if MODE == 'unknown': print(json.dumps({'type':'future_event'}));sys.exit(0)
if MODE == 'stderr': sys.stderr.write('private-fixture' * 20000)
e={'type':'assistant','message':{'id':'m1','content':[{'type':'text','text':'safe answer'}]}}
print(json.dumps(e)); print(json.dumps(e))
print(json.dumps({'type':'future_event'}))
print(json.dumps({'type':'result','is_error':False,'permission_denials':[{'tool':'Bash'}] if MODE == 'denied' else []}))
print(json.dumps({'type':'result','is_error':False,'permission_denials':[{'tool':'Bash'}] if MODE == 'denied' else []}))
'''.replace('MODE', repr(mode)))
        self.host.command = [sys.executable, str(fixture)]

    def wait(self, tid):
        deadline = time.monotonic() + 5
        while tid in self.host.active and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertNotIn(tid, self.host.active)
        return self.host.threads[tid]['turns'][-1]

    def test_completion_deduplicates_and_isolates_snapshot_replay(self):
        self.command('ok')
        a, b = self.start(), self.start()
        self.request('turn/start', threadId=a, input=[{'type':'text','text':'$(never shell)'}])
        turn = self.wait(a)
        self.assertEqual('completed', turn['status'])
        self.assertEqual(2, len(turn['items']))
        captured = json.loads((self.folder / 'fake.capture.json').read_text())
        self.assertEqual('$(never shell)', captured['prompt'])
        self.assertIn('manual', captured['argv'])
        self.assertIn('none', captured['argv'])
        self.assertEqual([], self.host.threads[b]['turns'])
        before = len([v for v in self.peer.values if v.get('method') == 'item/completed'])
        for _ in range(2):
            self.request('thread/resume', threadId=a)
            self.request('remote/replay', threadId=a)
        self.assertEqual(before, len([v for v in self.peer.values if v.get('method') == 'item/completed']))
        self.assertEqual(1, len([v for v in self.peer.values if v.get('method') == 'turn/completed']))
        restored = ns['CliHost'](self.folder, sys.executable, 'claude')
        self.assertEqual('native-session', restored.threads[a]['native'])

    def test_dsh_shapes_final_snapshot_native_resume(self):
        self.host.runtime = 'dsh'
        self.command('dsh')
        tid = self.start()
        self.request('turn/start', threadId=tid, input=[{'type':'text','text':'hello'}])
        turn = self.wait(tid)
        self.assertEqual('completed', turn['status'])
        self.assertEqual(2, len(turn['items']))
        self.assertEqual('session-fixture', self.host.threads[tid]['native'])
        self.assertEqual('safe answer', turn['items'][1]['text'])
        self.request('turn/start', threadId=tid, input=[{'type':'text','text':'continue'}])
        self.assertEqual('completed', self.wait(tid)['status'])
        self.assertIn('session-fixture', json.loads((self.folder / 'fake.capture.json').read_text())['argv'])

    def test_runtime_capabilities_use_common_contract(self):
        from types import SimpleNamespace
        for runtime in ('codex', 'opencode', 'dsh', 'claude'):
            if runtime in ('dsh', 'claude'):
                host = self.host
                host.runtime = runtime
            else:
                cls = ns['Host'] if runtime == 'codex' else ns['OpenCodeHost']
                host = cls.__new__(cls)
                host.lock = threading.RLock()
                host.peer = self.peer
                host.shutting_down = False
                host.last_thread = ''
                host.baselines = {}
                host.folder = self.folder
            host.receive(self.peer, dict(id=456, method='remote/session', params={}))
            response = [v for v in self.peer.values if v.get('id') == 456][-1]['result']
            self.assertEqual(runtime, response['capabilities']['runtime'])
            self.assertTrue(response['capabilities']['resume'])
            self.assertTrue(response['capabilities']['interrupt'])

    def test_cli_runtime_error_interrupt_and_attachment_contract(self):
        for runtime in ('dsh', 'claude'):
            self.host.runtime = runtime
            self.command('slow')
            tid = self.start()
            self.assertIn('error', self.request('turn/start', threadId=tid,
                input=[{'type':'localImage','path':'/not/read'}]))
            self.request('turn/start', threadId=tid, input=[])
            self.assertIn('error', self.request('thread/delete', threadId=tid))
            self.request('turn/interrupt', threadId=tid)
            self.assertEqual('failed', self.wait(tid)['status'])
            self.command('malformed')
            tid = self.start()
            self.request('turn/start', threadId=tid, input=[])
            self.assertEqual('failed', self.wait(tid)['status'])

    def test_errors_and_unknown_completion(self):
        for mode in ('error', 'malformed', 'shape', 'unknown', 'denied'):
            self.command(mode)
            tid = self.start()
            self.request('turn/start', threadId=tid, input=[])
            self.assertEqual('failed', self.wait(tid)['status'])

    def test_interrupt_running_delete_duplicate_start(self):
        self.command('slow')
        tid = self.start()
        self.request('turn/start', threadId=tid, input=[])
        self.assertIn('error', self.request('thread/delete', threadId=tid))
        self.assertIn('error', self.request('turn/start', threadId=tid, input=[]))
        self.request('turn/interrupt', threadId=tid)
        self.assertEqual('failed', self.wait(tid)['status'])
        self.assertIn('result', self.request('thread/delete', threadId=tid))

    def test_missing_cli_and_dsh_fail_closed(self):
        self.host.command = ['missing-langpt-fixture-cli']
        self.assertIn('error', self.request('account/read'))
        tid = self.start()
        self.assertIn('error', self.request('turn/start', threadId=tid, input=[]))
        self.host.runtime = 'dsh'
        self.assertIn('error', self.request('turn/start', threadId=tid, input=[]))
        self.assertFalse(self.host.active)

    def test_stderr_is_drained_without_exposing_it(self):
        self.command('stderr')
        tid = self.start()
        self.request('turn/start', threadId=tid, input=[])
        self.assertEqual('completed', self.wait(tid)['status'])
        self.assertNotIn('private-fixture', json.dumps(self.peer.values))

    def test_public_tool_events_project_existing_items(self):
        for runtime in ('dsh', 'claude'):
            self.host.runtime = runtime
            tid = self.start()
            turn = {'id':'turn-' + runtime, 'items':[]}
            if runtime == 'claude':
                call = {'type':'assistant','message':{'content':[{'type':'tool_use','id':'call-1',
                    'name':'Bash','input':{'command':'echo fixture'}}]}}
                done = {'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'call-1',
                    'content':'fixture','is_error':False}]}}
            else:
                call = {'type':'tool_call','callId':'call-1','tool':'bash','input':{'command':'echo fixture'}}
                done = {'type':'tool_result','callId':'call-1','status':'completed','result':'fixture'}
            with self.host.lock:
                ns['apply_cli_event'](self.host,tid,turn,call)
                ns['apply_cli_event'](self.host,tid,turn,done)
            self.assertEqual(1, len(turn['items']))
            self.assertEqual('completed', turn['items'][0]['status'])

    def test_corrupt_history_is_preserved_and_operations_fail_closed(self):
        path = self.folder / 'cli-history.json'
        for damaged in ('not-json', '[]', '{"t":{"turns":[null]}}'):
            path.write_text(damaged)
            restored = ns['CliHost'](self.folder, sys.executable, 'claude')
            self.assertEqual(damaged, path.read_text())
            self.assertTrue(restored.history_error)
            restored.peer = self.peer
            restored.receive(self.peer, dict(id=999, method='thread/list', params={}))
            self.assertIn('error', self.peer.values[-1])

    def test_path_injection_and_bypass_rejected(self):
        self.assertIn('error', self.request('thread/read', threadId='../../outside'))
        with self.assertRaises(ValueError):
            ns['CliHost'](self.folder, 'claude --dangerously-skip-permissions', 'claude')

    def test_parallel_tasks_interrupt_isolated_and_background_completion(self):
        for runtime in ('dsh', 'claude'):
            self.host.runtime = runtime
            fixture = self.folder / ('parallel-' + runtime + '.py')
            fixture.write_text('''import sys,json,time
from pathlib import Path
prompt=sys.stdin.read()
gate=Path(__file__).parent / ('release-' + prompt)
(gate.with_suffix('.started')).write_text('ready')
while not gate.exists(): time.sleep(.01)
if '--profile' in sys.argv:
 print(json.dumps({'type':'text','text':prompt}),flush=True)
 print(json.dumps({'type':'status','phase':'turn_end','reason':{'kind':'completed'}}),flush=True)
 print(json.dumps({'type':'final','text':prompt}),flush=True)
else:
 print(json.dumps({'type':'assistant','message':{'id':'msg-'+prompt,'content':[{'type':'text','text':prompt}]}}),flush=True)
 print(json.dumps({'type':'result','is_error':False}),flush=True)
''')
            self.host.command = [sys.executable, str(fixture)]
            a, b = self.start(), self.start()
            prompt_a, prompt_b = runtime + '-A', runtime + '-B'
            for tid, prompt in ((a, prompt_a), (b, prompt_b)):
                self.assertIn('result', self.request('turn/start', threadId=tid,
                    input=[{'type':'text','text':prompt}]))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and not all(
                (self.folder / ('release-' + p + '.started')).exists() for p in (prompt_a, prompt_b)):
                time.sleep(.01)
            self.assertTrue(all((self.folder / ('release-' + p + '.started')).exists()
                for p in (prompt_a, prompt_b)))
            self.assertIn(a, self.host.active)
            self.assertIn(b, self.host.active)
            self.assertIn('error', self.request('turn/start', threadId=a, input=[]))
            self.request('turn/interrupt', threadId=a)
            self.wait(a)
            self.assertIn(b, self.host.active, 'interrupting A must not kill B')
            (self.folder / ('release-' + prompt_b)).write_text('go')
            turn = self.wait(b)
            self.assertEqual('completed', turn['status'])
            self.assertIn(prompt_b, json.dumps(turn))
            self.assertNotIn(prompt_a, json.dumps(turn))
            completions = [v for v in self.peer.values if v.get('method') == 'turn/completed'
                and v.get('params', {}).get('threadId') in (a, b)]
            self.assertEqual({a, b}, {v['params']['threadId'] for v in completions})

    def test_crash_marks_unknown_without_retry(self):
        tid = self.start()
        self.host.threads[tid]['turns'].append(dict(id='lost', status='inProgress', items=[]))
        self.host.save()
        restored = ns['CliHost'](self.folder, sys.executable, 'claude')
        self.assertEqual('failed', restored.threads[tid]['turns'][0]['status'])
        self.assertFalse(restored.active)
        self.assertTrue(restored.threads[tid]['deliveryUnknown'])
        restored.peer = self.peer
        restored.receive(self.peer, dict(id=999, method='turn/start', params={'threadId':tid,'input':[]}))
        self.assertIn('error', self.peer.values[-1])


if __name__ == '__main__':
    unittest.main()
