"""Exercise both bridge dispatchers without a real server or credentials."""
import pathlib
import json
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/remote_session.py'
ns = {'__name__': 'cross_channel_fixture'}
exec(compile(SOURCE.read_text(), str(SOURCE), 'exec'), ns)

class Peer:
    def __init__(self): self.values = []
    def send(self, value): self.values.append(value)

class CrossChannelDispatchTest(unittest.TestCase):
    def test_dispatches_to_opposite_store(self):
        for cls, prefix in [('Host', 'opencode'), ('OpenCodeHost', 'codex')]:
            with self.subTest(runtime=cls):
                host = ns[cls].__new__(ns[cls])
                host.lock = threading.RLock()
                host.peer = peer = Peer()
                host.shutting_down = False
                with patch.dict(ns, {prefix + '_other_sessions': lambda: [{'id': 'other'}],
                                     prefix + '_other_turns': lambda tid: [{'id': tid}]}):
                    host.receive(peer, {'id': 1, 'method': 'thread/listOther', 'params': {}})
                    host.receive(peer, {'id': 2, 'method': 'thread/readOther', 'params': {'threadId': 'other'}})
                self.assertEqual([{'id': 'other'}], peer.values[0]['result']['data'])
                self.assertEqual([{'id': 'other'}], peer.values[1]['result']['thread']['turns'])

class CodexIndexTest(unittest.TestCase):
    def test_index_excludes_archived_deleted_and_missing_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            codex = root / '.codex'
            codex.mkdir()
            db = sqlite3.connect(codex / 'state_5.sqlite')
            db.execute('CREATE TABLE threads (id TEXT, title TEXT, cwd TEXT, updated_at INTEGER, rollout_path TEXT, archived INTEGER, has_user_event INTEGER)')
            for name, archived, exists in [('live', 0, True), ('old', 1, True), ('deleted', 0, True), ('missing', 0, False)]:
                path = codex / (name + '.jsonl')
                if exists:
                    path.write_text(json.dumps({'payload': {'type': 'message', 'role': 'user', 'id': name, 'content': [{'text': name}]}}) + '\n' + json.dumps({'timestamp': '2026-11-05T09:03:00Z', 'payload': {'type': 'message', 'role': 'assistant', 'id': 'answer', 'content': [{'text': 'reply'}]}}) + '\n')
                db.execute('INSERT INTO threads VALUES (?, ?, ?, ?, ?, ?, 1)', (name, name, '/work', 1, str(path), archived))
            db.commit(); db.close()
            with patch.object(ns['Path'], 'home', return_value=root), patch.dict(ns, {'_latest_tombstones': lambda: {'deleted'}}):
                self.assertEqual(['live'], [x['id'] for x in ns['codex_other_sessions']()])
                turns = ns['codex_other_turns']('live')
                self.assertEqual('live-u', turns[0]['items'][0]['id'])
                self.assertEqual('2026-11-05T09:03:00Z', turns[1]['items'][0]['timestamp'])
                for name in ['old', 'deleted', 'missing']:
                    with self.assertRaises(FileNotFoundError): ns['codex_other_turns'](name)

class TombstoneTest(unittest.TestCase):
    def test_concurrent_writers_preserve_all_deletions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'deleted.json'
            with patch.dict(ns, {'SHARED_TOMBSTONES': path}):
                workers = [threading.Thread(target=ns['remember_tombstone'], args=(str(i),)) for i in range(20)]
                for worker in workers: worker.start()
                for worker in workers: worker.join()
            self.assertEqual({str(i) for i in range(20)}, set(json.loads(path.read_text())))

if __name__ == '__main__': unittest.main()
