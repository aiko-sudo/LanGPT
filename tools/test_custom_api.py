#!/usr/bin/env python3
"""V1.6.16 wiring tests: custom-model API (ChatGPT usage-limit escape hatch).
JVM-test the pure merge logic in CustomApiConfig; source-check the wiring."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/app/remotessh"
RES = ROOT / "app/src/main/res"
sys_path = ROOT / "tools"


def org_json_jar():
    env = os.environ.get("ORG_JSON_JAR")
    if env and Path(env).is_file():
        return env
    for p in (Path.home() / ".gradle/caches/modules-2/files-2.1/org.json").rglob("*.jar"):
        return str(p)
    return None


class MergeLogic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        jar = org_json_jar()
        if jar is None:
            raise unittest.SkipTest("org.json jar not found (CI exports ORG_JSON_JAR)")
        cls.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        cls.jar = jar
        out = Path(cls.tmp.name) / "classes"
        out.mkdir()
        subprocess.run(["javac", "-cp", jar, "-d", str(out), str(JAVA / "CustomApiConfig.java")], check=True)
        cls.out = out
        cls.probe_count = 0

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_case_probe(self, mode):
        """Compile a one-off probe in the same package to exercise the
        package-private class end-to-end (list roundtrip + legacy migration)."""
        name = f"SerialProbe{int(__import__('time').time() * 1000) % 100000}"
        code = (
            "package app.remotessh;\n"
            "import org.json.JSONObject;\n"
            f"public class {name} {{ public static void main(String[] x) throws Exception {{\n"
            "  CustomApiConfig a = new CustomApiConfig();\n"
            "  CustomApiConfig.Entry e = new CustomApiConfig.Entry();\n"
            "  e.id=\"e1\"; e.name=\"DeepSeek\"; e.baseUrl=\"https://api.deepseek.com/v1\"; e.apiKey=\"sk-1\"; e.model=\"deepseek-chat\";\n"
            "  a.entries.add(e); a.activeId=\"e1\";\n"
            "  String s = a.prefsValue();\n"
            "  CustomApiConfig back = CustomApiConfig.fromPrefsValue(s);\n"
            "  if (back.entries.size()!=1 || !back.active().baseUrl.equals(\"https://api.deepseek.com/v1\") || !back.ready())\n"
            "    throw new AssertionError(\"LIST\");\n"
            "  System.out.print(\"LIST_OK \");\n"
            "  String legacy = new JSONObject().put(\"enabled\",true).put(\"baseUrl\",\"https://z.ai/api\").put(\"apiKey\",\"k\").put(\"model\",\"glm\").toString();\n"
            "  CustomApiConfig m = CustomApiConfig.fromPrefsValue(legacy);\n"
            "  if (m.entries.size()!=1 || !m.activeId.equals(m.entries.get(0).id)) throw new AssertionError(\"LEGACY\");\n"
            "  System.out.print(\"LEGACY_OK \");\n"
            "  CustomApiConfig empty = CustomApiConfig.fromPrefsValue(\"\");\n"
            "  if (empty.ready() || empty.active()!=null) throw new AssertionError(\"ACTIVE\");\n"
            "  System.out.println(\"ACTIVE_OK\");\n"
            "}}")
        d = Path(self.tmp.name)
        src = d / "app/remotessh" / f"{name}.java"
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text(code)
        subprocess.run(["javac", "-cp", f"{self.jar}:{self.out}", "-d", str(self.out), str(src)], check=True)
        r = subprocess.run(["java", "-cp", f"{self.jar}:{self.out}", "app.remotessh." + name],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr[:400])
        return r.stdout.strip()

    def run_case(self, method, existing, *rest):
        self.probe_count += 1
        name = f"Probe{self.probe_count}"
        args = ", ".join(json.dumps(a) for a in (existing, *rest))
        code = (f"package app.remotessh;\n"
                f"public class {name} {{ public static void main(String[] x) {{\n"
                f"  System.out.println(CustomApiConfig.{method}({args}));\n"
                f"}}}}")
        d = Path(self.tmp.name)
        src = d / "app/remotessh" / f"{name}.java"
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text(code)
        subprocess.run(["javac", "-cp", f"{self.jar}:{self.out}", "-d", str(self.out), str(src)], check=True)
        r = subprocess.run(["java", "-cp", f"{self.jar}:{self.out}", "app.remotessh." + name],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr[:400])
        return r.stdout.strip()

    def test_list_prefs_roundtrip(self):
        # compile + run the probe for list serialization & legacy migration
        out = self.run_case_probe("SERIAL")
        self.assertIn("LIST_OK LEGACY_OK ACTIVE_OK", out)

    def test_api_key_in_options(self):
        # opencode ignores auth.json for custom providers; the key must be in
        # provider.options.apiKey, otherwise every request 401s.
        doc = json.loads(self.run_case("mergeProviderConfig", "", "https://x/v1", "m", "sk-key123"))
        self.assertEqual(doc["provider"]["lanpt"]["options"]["apiKey"], "sk-key123")

    def test_base_url_normalized(self):
        # users paste the full endpoint; SDK appends /chat/completions again
        doc = json.loads(self.run_case("mergeProviderConfig",
                                       "", "https://token.sensenova.cn/v1/chat/completions", "m"))
        self.assertEqual(doc["provider"]["lanpt"]["options"]["baseURL"], "https://token.sensenova.cn/v1")

    def test_base_url_trailing_slash(self):
        doc = json.loads(self.run_case("mergeProviderConfig", "", "https://api.deepseek.com/v1/", "m"))
        self.assertEqual(doc["provider"]["lanpt"]["options"]["baseURL"], "https://api.deepseek.com/v1")

    def test_provider_merge_from_empty(self):
        doc = json.loads(self.run_case("mergeProviderConfig", "", "https://api.example.com/v4", "glm-5.3"))
        entry = doc["provider"]["lanpt"]
        self.assertEqual(entry["npm"], "@ai-sdk/openai-compatible")
        self.assertEqual(entry["options"]["baseURL"], "https://api.example.com/v4")
        self.assertIn("glm-5.3", entry["models"])
        self.assertEqual(doc["model"], "lanpt/glm-5.3")

    def test_provider_merge_preserves_unrelated(self):
        existing = json.dumps({"$schema": "https://opencode.ai/config.json",
                               "provider": {"other": {"npm": "x"}}, "theme": "dark"})
        doc = json.loads(self.run_case("mergeProviderConfig", existing, "https://api2.example.com", "m2"))
        self.assertEqual(doc["theme"], "dark")
        self.assertIn("other", doc["provider"])
        self.assertEqual(doc["model"], "lanpt/m2")

    def test_provider_merge_replaces_stale_entry(self):
        existing = json.dumps({"provider": {"lanpt": {"options": {"baseURL": "https://old"}}}})
        doc = json.loads(self.run_case("mergeProviderConfig", existing, "https://new.example.com", "glm-5.3"))
        self.assertEqual(doc["provider"]["lanpt"]["options"]["baseURL"], "https://new.example.com")

    def test_provider_merge_bad_existing_json(self):
        doc = json.loads(self.run_case("mergeProviderConfig", "not json{{", "https://api.example.com", "m"))
        self.assertIn("lanpt", doc["provider"])

    def test_auth_merge_preserves_other_credentials(self):
        existing = json.dumps({"github": {"type": "oauth", "token": "t"}})
        doc = json.loads(self.run_case("mergeAuthConfig", existing, "sk-test"))
        self.assertEqual(doc["github"]["token"], "t")
        self.assertEqual(doc["lanpt"], {"type": "api", "key": "sk-test"})


class Wiring(unittest.TestCase):
    def test_remoteapp_switch(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn("void switchToCustomModel(", src)
        self.assertIn("mergeProviderConfig", src)
        self.assertIn("mergeAuthConfig", src)
        self.assertIn('copy.runtime = "opencode"', src)
        self.assertIn('profile.runtime.equals("codex")', src)
        self.assertIn('if (customOverride && !"opencode".equals(p.runtime)) { customOverride = false;', src)
        # active entry becomes the session default model (not the ChatGPT one)
        self.assertIn('model = CustomApiConfig.PROVIDER_ID + "/" + entry.model', src)
        # cross-agent context carry + local handoff snapshot
        self.assertIn("buildContextPrefix(items)", src)
        self.assertIn('"history-handoff"', src)

    def test_context_carry_injection(self):
        src = (JAVA / "TurnController.java").read_text()
        self.assertIn("pendingContextPrefix", src)
        # injected once, then cleared
        self.assertIn("app.pendingContextPrefix = \"\"", src)

    def test_main_activity_list_ui(self):
        src = (JAVA / "MainActivity.java").read_text()
        for anchor in ("editCustomApiEntry(c, null)", "editCustomApiEntry(c, entry)",
                       "main_extra_75", "main_extra_76", "main_extra_77", "main_extra_78", "main_extra_79"):
            self.assertIn(anchor, src, anchor)
        # legacy single-entry dialog shape must be gone
        self.assertNotIn("main_extra_63", src)

    def test_limit_detection(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn('"usageLimitExceeded"', src)
        self.assertIn("mainModelLimited = false", src)
        self.assertIn("!historical && !customOverride", src)

    def test_backend_restart_on_switch(self):
        src = (JAVA / "RemoteApp.java").read_text()
        # config is read once by opencode serve -> switch must restart backend
        self.assertIn("fuser -k 4610/tcp", src)
        self.assertIn("daemon.pid", src)
        # never self-matching pkill patterns
        self.assertNotIn("pkill -f 'opencode serve", src)

    def test_transport_helpers(self):
        src = (JAVA / "SshTransport.java").read_text()
        self.assertIn("String readTextFile(String path)", src)
        self.assertIn("void ensureDirectory(String path)", src)
        self.assertIn("ch.mkdir(dir)", src)
        self.assertIn("String runCommand(String command)", src)

    def test_main_activity_ui(self):
        src = (JAVA / "MainActivity.java").read_text()
        self.assertIn("customApiDialog()", src)
        self.assertIn("switchToCustomModel()", src)
        self.assertIn("confirmRestorePrimary()", src)
        self.assertIn("R.string.main_extra_72", src)
        # Persistent custom-model banner removed (V1.6.30): the way back to the
        # primary model lives in the model picker's switch row, not a banner.
        self.assertNotIn("R.string.main_extra_71", src)

    def test_resources_bilingual(self):
        zh = (RES / "values/strings-main.xml").read_text()
        en = (RES / "values-en/strings-main.xml").read_text()
        for n in [62, 64, 65, 66, 67, 68, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80]:
            k = f"main_extra_{n}"
            self.assertIn(f'name="{k}"', zh, k)
            self.assertIn(f'name="{k}"', en, k)


if __name__ == "__main__":
    unittest.main()
