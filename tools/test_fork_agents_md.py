#!/usr/bin/env python3
"""Wiring tests for V1.6.13: conversation fork (thread/fork long-press) and
AGENTS.md presence hint on the project management page. Source-level checks
plus a protocol shape assertion against the verified codex 0.166.0 response."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/app/remotessh"
RES = ROOT / "app/src/main/res"


class ForkWiring(unittest.TestCase):
    def test_remoteapp_fork_thread(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn('"thread/fork"', src)
        self.assertIn("void forkThread(String id", src)
        # params verified against codex 0.166.0 app-server
        self.assertIn('Json.obj("threadId", id)', src)
        # new thread id is read from result.thread.id (verified shape)
        self.assertIn('r.optJSONObject("thread")', src)

    def test_browser_long_press_menu(self):
        src = (JAVA / "RemoteBrowser.java").read_text()
        self.assertIn("setOnLongClickListener", src)
        self.assertIn("forkThread(id", src)
        self.assertIn("R.string.work_text_220", src)
        # running conversations are refused (same guard family as delete)
        self.assertIn("R.string.work_text_222", src)

    def test_resources_bilingual(self):
        zh = (RES / "values/strings-work.xml").read_text()
        en = (RES / "values-en/strings-work.xml").read_text()
        for key in range(220, 226):
            k = f"work_text_22{key % 100 if key >= 220 else key}"
            k = f"work_text_{key}"
            self.assertIn(f'name="{k}"', zh, k)
            self.assertIn(f'name="{k}"', en, k)


class AgentsMdWiring(unittest.TestCase):
    def test_transport_helpers(self):
        src = (JAVA / "SshTransport.java").read_text()
        self.assertIn("boolean fileExists(String path)", src)
        self.assertIn("void writeTextFile(String path, String content)", src)
        self.assertIn("ChannelSftp.SSH_FX_NO_SUCH_FILE", src)

    def test_remoteapp_agents_md(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn("void checkAgentsMd(", src)
        self.assertIn("void writeAgentsMd(", src)
        self.assertIn('"/AGENTS.md"', src)
        self.assertIn("AGENTS_MD_TEMPLATE", src)
        # template skeleton must keep the four documented sections
        for section in ("项目说明", "代码风格", "测试要求", "禁止操作"):
            self.assertIn(section, src)

    def test_browser_hint_row(self):
        src = (JAVA / "RemoteBrowser.java").read_text()
        self.assertIn("Boolean.FALSE.equals(agentsMd.get(path))", src)
        self.assertIn("R.string.work_text_223", src)
        self.assertIn("writeAgentsMd(path", src)
        # hint only when connected for the active profile
        self.assertIn("app.connected && filter.equals(p.id)", src)
        # detection state participates in the render signature
        self.assertIn('value.append("|md:")', src)


if __name__ == "__main__":
    unittest.main()
