#!/usr/bin/env python3
"""Compile and exercise the real CachePaths class on a plain JVM.

Covers the three cache defects found in review: same-name downloads overwriting
each other, special characters in provider URIs, and path escapes out of the
cache directory. No Android SDK or emulator needed.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'app/src/main/java/app/remotessh/CachePaths.java'
provider = ROOT / 'app/src/main/java/app/remotessh/CacheFileProvider.java'
activity = ROOT / 'app/src/main/java/app/remotessh/SftpFileActivity.java'
manifest = ROOT / 'app/src/main/AndroidManifest.xml'

text = source.read_text()
assert 'import android.' not in text, 'CachePaths must stay free of Android imports'
assert 'encodedPath' not in provider.read_text(), 'provider URIs must be built with appendPath, not encodedPath'
assert 'APP_ID_PLACEHOLDER' not in manifest.read_text()
assert '${applicationId}.files' in manifest.read_text(), 'provider authority must follow the application id'
assert 'CachePaths.uniqueRelative' in activity.read_text(), 'external-open downloads need a unique cache path'
assert 'uriFor(owner,dir,target)' in activity.read_text(), 'open flow must pass the cache root when building the URI'
print('PASS source guards (encoding, authority, unique path)')

with tempfile.TemporaryDirectory(prefix='cache-paths-test-', ignore_cleanup_errors=True) as temporary:
    work = Path(temporary)
    classes = work / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-d', str(classes), str(source)], check=True)
    harness = work / 'Harness.java'
    harness.write_text('''package app.remotessh;

import app.remotessh.CachePaths;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class Harness {
  static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
  public static void main(String[] args) throws Exception {
    check(CachePaths.baseName("/home/user/report.pdf").equals("report.pdf"), "basename from absolute path");
    check(CachePaths.baseName("report.pdf").equals("report.pdf"), "basename without directory");
    check(CachePaths.baseName("/home/user/").equals("file"), "trailing slash falls back to a safe name");
    check(CachePaths.baseName(null).equals("file"), "null path falls back to a safe name");
    check(CachePaths.baseName("a/b/we%20ird name.txt").equals("we%20ird name.txt"), "special characters survive");

    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      String relative = CachePaths.uniqueRelative("report.pdf");
      if (!relative.endsWith("/report.pdf")) throw new AssertionError("unique path keeps the original name");
      seen.add(relative.substring(0, relative.indexOf('/')));
    }
    check(seen.size() == 500, "500 same-name downloads keep the original name and never share a directory");

    File root = new File(args[0], "open");
    check(root.mkdirs(), "cache root created");
    File nested = CachePaths.resolve(root, "/d1-1/report.pdf");
    check(nested.getAbsolutePath().equals(new File(root, "d1-1/report.pdf").getAbsolutePath()), "nested path resolves inside root");
    check(CachePaths.resolve(root, "report.pdf").getName().equals("report.pdf"), "legacy flat path still resolves");

    String[] escapes = {"/../escape.txt", "../escape.txt", "/d1/../../escape.txt", "/", "", null};
    for (String escape : escapes) {
      boolean rejected = false;
      try { CachePaths.resolve(root, escape); } catch (java.io.IOException expected) { rejected = true; }
      check(rejected, "rejects " + (escape == null ? "null" : "'" + escape + "'"));
    }
  }
}
''')
    subprocess.run(['javac', '-cp', str(classes), '-d', str(classes), str(harness)], check=True)
    result = subprocess.run(['java', '-cp', str(classes), 'app.remotessh.Harness', str(work)], capture_output=True, text=True)
    print(result.stdout.strip())
    if result.returncode != 0:
        raise SystemExit(result.stderr.strip() or 'harness failed')
print('cache path regressions passed; on-device external open still needs a phone check.')
