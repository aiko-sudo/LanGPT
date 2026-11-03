#!/usr/bin/env python3
"""V1.6.14 wiring tests: context usage display + conversation compaction and
the conversation-image disk cache tier. Source-level checks."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/app/remotessh"
RES = ROOT / "app/src/main/res"


class ContextUsage(unittest.TestCase):
    def test_remoteapp_events(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn('"thread/tokenUsage/updated"', src)
        self.assertIn('"thread/compacted"', src)
        self.assertIn("firstNonNegative", src)
        # lenient parse: usage object accepted at params root or under params.usage
        self.assertIn('p.optJSONObject("usage")', src)
        # reset on conversation switch
        self.assertRegex(src, r"ctxUsedTokens = -1;")

    def test_compact_rpc(self):
        src = (JAVA / "RemoteApp.java").read_text()
        self.assertIn('"thread/compact/start"', src)
        self.assertIn("void compactThread(String id", src)

    def test_compacted_reloads_thread(self):
        src = (JAVA / "RemoteApp.java").read_text()
        # the compacted branch must re-read the thread, not just flip state
        idx = src.index('"thread/compacted"')
        branch = src[idx : idx + 700]
        self.assertIn("readThread(t, true)", branch)

    def test_main_activity_wiring(self):
        src = (JAVA / "MainActivity.java").read_text()
        self.assertIn("R.string.main_extra_57", src)  # usage line
        self.assertIn("R.string.main_extra_58", src)  # compact button
        self.assertIn("compactConversation()", src)
        self.assertIn("formatTokens", src)
        # compact gated to codex runtime + idle + connected
        self.assertIn('"codex".equals(app.profile.runtime)', src)


class ImageDiskCacheTier(unittest.TestCase):
    def test_class_exists(self):
        src = (JAVA / "ImageDiskCache.java").read_text()
        self.assertIn("images-disk", src)
        # bounded LRU trim
        self.assertIn("MAX_BYTES", src)
        self.assertIn("trim()", src)
        # atomic write via tmp+rename
        self.assertIn('".tmp"', src)

    def test_tile_warm_tier(self):
        src = (JAVA / "MessageImageTile.java").read_text()
        # disk read happens on memory miss before network fetch
        self.assertIn("app.imageDisk.get(key)", src)
        self.assertIn("app.imageDisk.put(key,fetched)", src)
        self.assertIn("MediaFiles.decode(cached,640)", src)

    def test_remoteapp_field(self):
        src = (JAVA / "RemoteApp.java").read_text()
        # Must NOT be a field initializer: getCacheDir() before attachBaseContext
        # crashes the app on startup (V1.6.14 lesson).
        self.assertNotIn("new ImageDiskCache(getCacheDir());\n  // Context", src)
        self.assertRegex(src, r"ImageDiskCache imageDisk;")
        self.assertIn("imageDisk = new ImageDiskCache(getCacheDir());", src)
        # ...and that assignment must live inside onCreate()
        onCreateIdx = src.index("public void onCreate()")
        assignIdx = src.index("imageDisk = new ImageDiskCache(getCacheDir());")
        self.assertGreater(assignIdx, onCreateIdx)
        fieldDeclIdx = src.index("ImageDiskCache imageDisk;")
        self.assertLess(fieldDeclIdx, onCreateIdx)

    def test_disk_dir_not_purged_as_top_level_file(self):
        # the onCreate purge only deletes top-level *files* older than 6h;
        # a subdirectory must be checked with isFile() before delete
        src = (JAVA / "RemoteApp.java").read_text()
        idx = src.index("purge stale plaintext temp attachments")
        window = src[idx : idx + 900]
        self.assertIn("f.isFile()", window)

    def test_resources_bilingual(self):
        zh = (RES / "values/strings-main.xml").read_text()
        en = (RES / "values-en/strings-main.xml").read_text()
        for key in ("main_extra_57", "main_extra_58", "main_extra_59", "main_extra_60", "main_extra_61"):
            self.assertIn(f'name="{key}"', zh, key)
            self.assertIn(f'name="{key}"', en, key)


if __name__ == "__main__":
    unittest.main()
