import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class ExitNodeWiringTest(unittest.TestCase):
    def test_routes_require_confirmed_selection_and_exclude_self(self):
        service = (ROOT / 'app/src/main/java/app/remotessh/TsnetVpnService.java').read_text()
        self.assertLess(service.index('ExitNode.select(useExit ? exitId : "")'), service.index('b.addRoute("0.0.0.0", 0)'))
        self.assertIn('b.addRoute("0.0.0.0", 0).addRoute("::", 0)', service)
        self.assertIn('getPackageName().equals(pkg)', service)
        self.assertIn('if (allowed == 0)', service)
        self.assertIn('generation.incrementAndGet()', service)

    def test_selection_persisted_and_library_absence_rejected(self):
        settings = (ROOT / 'app/src/main/java/app/remotessh/SandboxSettings.java').read_text()
        bridge = (ROOT / 'app/src/main/java/app/remotessh/ExitNode.java').read_text()
        for text in ('ExitNode.online()', 'putString("exit_id", option.id)', 'putBoolean("exit_enabled", true)'):
            self.assertIn(text, settings)
        # 布局约束：禁止 setView+setMultiChoiceItems 混用（按钮行被挤掉），列表高度受限。
        self.assertNotIn('.setMultiChoiceItems(', settings)
        self.assertIn('heightPixels * 0.42', settings)
        self.assertIn('CHOICE_MODE_MULTIPLE', settings)
        self.assertIn('NoSuchMethodException', bridge)
        self.assertIn('item.optBoolean("online")', bridge)

if __name__ == '__main__':
    unittest.main()
