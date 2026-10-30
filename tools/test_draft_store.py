#!/usr/bin/env python3
"""DraftStore JVM round-trip tests: compile the real class against a minimal
SftpContent.Snapshot stub, then exercise save/load/clear/corruption paths."""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java/app/remotessh"
STUBS = {
    "SftpContent.java": """package app.remotessh;
public interface SftpContent {
  static final class Snapshot {
    final boolean exists; final long size; final int modified; final int permissions; final byte[] digest;
    Snapshot(boolean exists,long size,int modified,int permissions,byte[] digest){
      this.exists=exists;this.size=size;this.modified=modified;this.permissions=permissions;this.digest=digest;
    }
  }
}
""",
    "Harness.java": """package app.remotessh;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public class Harness {
  public static void main(String[] args) throws Exception {
    File root = new File(args[0]);
    DraftStore store = new DraftStore(root);
    String check = args[1];
    if (check.equals("roundtrip")) {
      byte[] digest = new byte[]{1,2,3,(byte)200};
      SftpContent.Snapshot snap = new SftpContent.Snapshot(true, 123456L, 77, 420, digest);
      store.save("k1", "第一行\\nline2 \\u00e9\\u4e2d\\ud83d\\ude00 trailing ", snap);
      DraftStore.Draft d = store.load("k1");
      if (d == null) { System.out.println(json("loaded", "null")); return; }
      if (!d.text.equals("第一行\\nline2 \\u00e9\\u4e2d\\ud83d\\ude00 trailing ")) throw new AssertionError("text mismatch");
      if (d.snapshot == null || !d.snapshot.exists || d.snapshot.size != 123456L || d.snapshot.modified != 77
          || d.snapshot.permissions != 420 || !java.util.Arrays.equals(d.snapshot.digest, digest))
        throw new AssertionError("snapshot mismatch");
      if (d.savedAt <= 0) throw new AssertionError("savedAt missing");
      // overwrite wins
      store.save("k1", "second", snap);
      DraftStore.Draft d2 = store.load("k1");
      if (!d2.text.equals("second")) throw new AssertionError("overwrite lost");
      System.out.println(json("roundtrip", "ok"));
    } else if (check.equals("nullsnapshot")) {
      store.save("k2", "no base", null);
      DraftStore.Draft d = store.load("k2");
      if (d == null || !d.text.equals("no base") || d.snapshot != null) throw new AssertionError("null snapshot lost");
      System.out.println(json("nullsnapshot", "ok"));
    } else if (check.equals("clear")) {
      store.save("k3", "x", null);
      if (store.load("k3") == null) throw new AssertionError("save missing");
      store.clear("k3");
      if (store.load("k3") != null) throw new AssertionError("clear failed");
      System.out.println(json("clear", "ok"));
    } else if (check.equals("corrupt")) {
      store.save("k4", "good", null);
      File f = new File(root, "draft-k4.bin");
      try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[]{9,9,9,9,9,9,9,9,9}); }
      if (store.load("k4") != null) throw new AssertionError("corrupt record accepted");
      if (f.isFile()) throw new AssertionError("corrupt record kept");
      System.out.println(json("corrupt", "dropped"));
    } else if (check.equals("key")) {
      String a = DraftStore.key("prof1", "/srv/a.txt");
      String b = DraftStore.key("prof1", "/srv/a.txt");
      String c = DraftStore.key("prof2", "/srv/a.txt");
      String e = DraftStore.key("prof1", "/srv/b.txt");
      if (!a.equals(b) || a.equals(c) || a.equals(e)) throw new AssertionError("key collision/unstable");
      System.out.println(json("key", "stable"));
    } else if (check.equals("nosuchdir")) {
      DraftStore fresh = new DraftStore(new File(root, "nested/made"));
      fresh.save("k5", "deep", null);
      if (fresh.load("k5") == null) throw new AssertionError("nested dir save lost");
      System.out.println(json("nosuchdir", "ok"));
    }
  }
  static String json(String k, String v) { return "{\\\"" + k + "\\\":\\\"" + v + "\\"}"; }
}
""",
}


def main() -> None:
    work = Path(tempfile.mkdtemp(prefix="draft-store-", dir=Path(__file__).resolve().parent))
    try:
        shutil.copy(SRC / "DraftStore.java", work / "DraftStore.java")
        for name, body in STUBS.items():
            (work / name).write_text(body)
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                        str(work / "DraftStore.java"),
                        str(work / "SftpContent.java"), str(work / "Harness.java")],
                       check=True, cwd=work)
        results = {}
        shown = set()
        for case in ["roundtrip", "nullsnapshot", "clear", "corrupt", "key", "nosuchdir"]:
            data = work / ("root-" + case)
            try:
                out = subprocess.run(["java", "-cp", str(work), "app.remotessh.Harness", str(data), case],
                                     check=True, capture_output=True, text=True, cwd=work,
                                     env={**__import__("os").environ, "LC_ALL": "C.UTF-8"})
            except subprocess.CalledProcessError as failure:
                print("java failed for", case, failure.stderr or "", file=sys.stderr)
                raise
            body = out.stdout.strip()
            if not body.startswith("{"):
                if case not in shown:
                    shown.add(case)
                    print("JAVA-ERR", case, out.stderr.strip()[-400:], file=sys.stderr)
                raise AssertionError(case + " produced no json")
            results.update(json.loads(body))
        assert results["roundtrip"] == "ok"
        assert results["nullsnapshot"] == "ok"
        assert results["clear"] == "ok"
        assert results["corrupt"] == "dropped", "corrupt record must be dropped"
        assert results["key"] == "stable"
        assert results["nosuchdir"] == "ok"
        print("draft-store: 6/6 OK")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
