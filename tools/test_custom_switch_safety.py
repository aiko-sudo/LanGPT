import importlib.util
from pathlib import Path
import threading
import unittest
from unittest.mock import Mock, patch

path = Path(__file__).resolve().parents[1] / 'app/src/main/assets/remote_session.py'
spec = importlib.util.spec_from_file_location('remote_session_safety', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class RestartSafety(unittest.TestCase):
    def test_refused_upgrade_preserves_existing_daemon(self):
        sock = Mock()
        with patch.object(module, 'rpc_once', side_effect=[
                {'result': {'scriptVersion': 1, 'threads': []}},
                {'error': {'message': 'busy'}}]), \
             patch.object(module.os, 'killpg') as kill, \
             patch.object(module, 'spawn_daemon') as spawn:
            self.assertIs(sock, module.upgrade_if_stale(sock, Path('/unused'), 'cmd', 'opencode'))
            kill.assert_not_called()
            spawn.assert_not_called()

    def host(self):
        host = module.OpenCodeHost.__new__(module.OpenCodeHost)
        host.lock = threading.RLock()
        host.active = set()
        host.permissions = {}
        host.process = Mock()
        host.process.pid = 1234
        return host

    def test_active_or_approval_never_stops_server(self):
        for field in ('active', 'permissions'):
            host = self.host()
            setattr(host, field, {'thread'} if field == 'active' else {'request': 'thread'})
            with patch.object(module.os, 'killpg') as kill:
                with self.assertRaises(RuntimeError):
                    host.restart_server()
                kill.assert_not_called()

    def test_unrelated_listener_is_never_killed(self):
        host = self.host()
        host.process.poll.return_value = 0
        host.port = 4610
        sock = Mock()
        with patch.object(module.socket, 'socket', return_value=sock), \
             patch.object(module.os, 'killpg') as kill, \
             patch.object(module.subprocess, 'run') as run, \
             patch.object(module.Path, 'read_bytes',
                          return_value=b'python3 unrelated-listener.py\x00'), \
             patch.object(module.time, 'time', side_effect=[0, 11]):
            run.return_value = Mock(stdout='9999\n')
            with self.assertRaises(RuntimeError):
                host.restart_server()
            kill.assert_not_called()
            self.assertFalse(any('-KILL' in str(c) for c in run.call_args_list),
                             'a foreign listener must never be killed')

    def test_stale_opencode_serve_is_reclaimed(self):
        # All holders look like our own `opencode serve` -> kill by port and
        # proceed (switching back must not be blocked by our own leftovers).
        host = self.host()
        host.port = 4610
        with patch.object(module.subprocess, 'run') as run, \
             patch.object(module.time, 'sleep'), \
             patch.object(module.Path, 'read_bytes',
                          return_value=b'opencode serve --port 4610\x00'):
            run.side_effect = [Mock(stdout='4242\n'), Mock(stdout=''), Mock(stdout='')]
            host._reclaim_stale_serve()  # must not raise
            kills = [c for c in run.call_args_list
                     if c.args[0][:1] == ['fuser'] and '-KILL' in c.args[0]]
            self.assertTrue(kills, 'expected a guarded fuser -KILL reclaim')

    def test_foreign_holder_is_never_killed(self):
        host = self.host()
        host.port = 4610
        with patch.object(module.subprocess, 'run') as run, \
             patch.object(module.Path, 'read_bytes',
                          return_value=b'python3 unrelated-server.py\x00'):
            run.return_value = Mock(stdout='4242\n')
            with self.assertRaises(RuntimeError):
                host._reclaim_stale_serve()
            self.assertFalse(any('-KILL' in str(c) for c in run.call_args_list),
                             'a foreign listener must never be killed')

    def test_failed_model_selection_never_sends_prompt(self):
        host = self.host()
        peer = Mock()
        host.peer = peer
        host.shutting_down = False
        host.http = Mock(side_effect=RuntimeError('selection failed'))
        host.receive(peer, {'id': 3, 'method': 'turn/start', 'params': {
            'threadId': 'thread', 'model': 'lanpt/model',
            'input': [{'type': 'text', 'text': 'hello'}]}})
        self.assertEqual(1, host.http.call_count)
        self.assertIn('/model', host.http.call_args.args[1])
        self.assertIn('error', peer.send.call_args.args[0])

if __name__ == '__main__':
    unittest.main()
