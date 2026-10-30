"""Execute the shipped xterm and trusted page logic without network or a device."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TerminalWebBehaviorTest(unittest.TestCase):
    def test_real_xterm_and_page(self):
        subprocess.run(["node", "tools/terminal_behavior_test.js"], cwd=ROOT, check=True)

    def test_sealed_assets(self):
        page = (ROOT / "app/src/main/assets/terminal/index.html").read_text()
        for policy in ("connect-src 'none'", "frame-src 'none'", "object-src 'none'", "form-action 'none'"):
            self.assertIn(policy, page)
        self.assertNotIn('copy-selection', page)
        self.assertNotIn('close-selection', page)
        self.assertIn('transform:scaleX(.9)', page)
        self.assertNotIn('letter-spacing:-', page)
        self.assertTrue((ROOT / "app/src/main/assets/terminal/vendor/LICENSE").is_file())
        self.assertTrue((ROOT / "app/src/main/assets/terminal/vendor/NOTICE.md").is_file())


if __name__ == '__main__':
    unittest.main()
