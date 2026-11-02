#!/usr/bin/env python3
"""Network/model UI resource parity and safety boundary checks."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/app/remotessh'
RES = ROOT / 'app/src/main/res'

class NetworkI18n(unittest.TestCase):
    def test_resource_parity_and_references(self):
        tables = []
        for directory in ('values', 'values-en'):
            root = ET.parse(RES / directory / 'strings-network.xml').getroot()
            entries = [(node.get('name'), node.text or '') for node in root.findall('string')]
            self.assertEqual(len(entries), len(dict(entries)), directory)
            tables.append(dict(entries))
        self.assertEqual(tables[0].keys(), tables[1].keys())
        self.assertTrue(all(tables[1].values()))
        used = set()
        for name in ('ModelPicker.java', 'TsnetSetup.java', 'SandboxSettings.java', 'TsnetVpnService.java'):
            used.update(re.findall(r'R\.string\.(network_text_\d+|sandbox_\w+)', (JAVA / name).read_text()))
        self.assertEqual(used, tables[0].keys())

    def test_protocol_and_security_guards_preserved(self):
        ts = (JAVA / 'TsnetSetup.java').read_text()
        sandbox = (JAVA / 'SandboxSettings.java').read_text()
        picker = (JAVA / 'ModelPicker.java').read_text()
        for token in ('"backend"', '"authURL"', '"ips"', '"paths"', '"exit"', '"https".equalsIgnoreCase(uri.getScheme())'):
            self.assertIn(token, ts)
        for token in ('"exit_id"', '"exit_name"', '"exit_enabled"', '"apps"', 'VpnService.prepare(a)', 'if (chosen.isEmpty())'):
            self.assertIn(token, sandbox)
        self.assertIn('e.getMessage()', sandbox)
        self.assertIn('app.modelsStatus', picker)
        self.assertIn('ModelOptions.displayName(app.models, id)', picker)

if __name__ == '__main__':
    unittest.main()
