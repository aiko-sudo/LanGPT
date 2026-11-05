import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

asset = Path(__file__).resolve().parents[1] / 'app/src/main/assets/custom_config_transaction.py'
spec = importlib.util.spec_from_file_location('transaction', asset)
tx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tx)

class ConfigTransaction(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        tx.ROOT = root / 'state'
        tx.JOURNAL = tx.ROOT / 'pending.json'
        tx.PATHS = [root / 'config' / 'opencode.json', root / 'auth' / 'auth.json']
        for path, value in zip(tx.PATHS, ['old-config', 'old-auth']):
            path.parent.mkdir()
            path.write_text(value)
        self.request = {'old': ['old-config', 'old-auth'], 'new': ['new-config', 'new-auth']}

    def values(self):
        return [tx.read(p) for p in tx.PATHS]

    def test_success(self):
        tx.apply(self.request)
        self.assertEqual(self.request['new'], self.values())
        self.assertFalse(tx.JOURNAL.exists())

    def test_second_write_failure_rolls_back(self):
        real = tx.replace
        failed = [False]
        def fail_once(path, value):
            if path == tx.PATHS[1] and value == 'new-auth' and not failed[0]:
                failed[0] = True
                raise OSError('synthetic failure')
            return real(path, value)
        with patch.object(tx, 'replace', side_effect=fail_once):
            with self.assertRaises(OSError): tx.apply(self.request)
        self.assertEqual(self.request['old'], self.values())
        self.assertFalse(tx.JOURNAL.exists())

    def test_interrupted_transaction_recovers(self):
        tx.ROOT.mkdir()
        tx.JOURNAL.write_text(json.dumps(self.request))
        tx.PATHS[0].write_text('new-config')
        with self.assertRaises(RuntimeError): tx.apply({'old': ['new-config', 'old-auth'], 'new': ['x', 'y']})
        self.assertEqual(self.request['old'], self.values())
        self.assertFalse(tx.JOURNAL.exists())

    def test_external_edits_are_preserved(self):
        tx.ROOT.mkdir()
        tx.JOURNAL.write_text(json.dumps(self.request))
        tx.PATHS[0].write_text('external')
        with self.assertRaises(RuntimeError): tx.apply(self.request)
        self.assertEqual(['external', 'old-auth'], self.values())
        self.assertTrue(tx.JOURNAL.exists())

    def test_stale_snapshot_does_not_write(self):
        tx.PATHS[1].write_text('external')
        with self.assertRaises(RuntimeError): tx.apply(self.request)
        self.assertEqual(['old-config', 'external'], self.values())

    def test_script_entrypoint_reads_and_removes_staged_request(self):
        home = Path(self.temp.name) / 'home'
        config = home / '.config/opencode/opencode.json'
        auth = home / '.local/share/opencode/auth.json'
        config.parent.mkdir(parents=True)
        auth.parent.mkdir(parents=True)
        config.write_text('old-config')
        auth.write_text('old-auth')
        request = home / 'request.json'
        request.write_text(json.dumps(self.request))
        result = subprocess.run([sys.executable, str(asset), str(request)],
                                env={**os.environ, 'HOME': str(home)},
                                capture_output=True, text=True, check=True)
        self.assertEqual('OK', result.stdout.strip())
        self.assertFalse(request.exists())
        self.assertEqual('new-config', config.read_text())
        self.assertEqual('new-auth', auth.read_text())

if __name__ == '__main__': unittest.main()
