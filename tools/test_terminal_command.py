#!/usr/bin/env python3
"""SSH terminal command parsing + credential storage tests.

Compiles the real TerminalCommand/SshCredentials against minimal Android and
CryptoBox stubs, so the rules (no auto-connect, saved-credential matching)
are verified without a device."""
import os
import shutil
import sys
import subprocess
import tempfile
from pathlib import Path

sys_path_root = Path(__file__).resolve().parent
SRC = sys_path_root.parent / "app/src/main/java/app/remotessh"
sys.path.insert(0, str(sys_path_root))
import testdeps  # noqa: E402

JAR = testdeps.require(testdeps.org_json_jar(), "org.json")

STUBS = {
    "android/content/Context.java": """package android.content;
public class Context {}
""",
    "app/remotessh/CryptoBox.java": """package app.remotessh;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
public final class CryptoBox {
  // Test stub: plaintext temp files so delete/forget semantics are real.
  static final File root = new File(System.getProperty("java.io.tmpdir"), "credbox-" + System.nanoTime());
  static File fileFor(Context c, String name) { root.mkdirs(); return new File(root, name); }
  static void writeSealed(Context c, String name, String plaintext) {
    try { Files.write(fileFor(c, name).toPath(), plaintext.getBytes(StandardCharsets.UTF_8)); }
    catch (Exception ignored) {}
  }
  static String readSealed(Context c, String name) {
    try {
      File f = fileFor(c, name);
      if (!f.isFile()) return null;
      return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    } catch (Exception e) { return null; }
  }
}
""",
    "app/remotessh/Harness.java": """package app.remotessh;
import android.content.Context;
public class Harness {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) {
    TerminalCommand a = TerminalCommand.parse("ssh user@192.168.1.10", "fallback", 22);
    check(a.error == null && a.user.equals("user") && a.host.equals("192.168.1.10")
        && a.port == 22 && a.remoteCommand.isEmpty(), "basic target");
    TerminalCommand b = TerminalCommand.parse("  ssh   root@nas.local  ", "", 22);
    check(b.error == null && b.user.equals("root") && b.host.equals("nas.local"), "whitespace");
    TerminalCommand c = TerminalCommand.parse("ssh user@host -p 2222", "", 22);
    check(c.error == null && c.port == 2222 && c.host.equals("host"), "-p port");
    TerminalCommand d = TerminalCommand.parse("ssh -p2200 user@host", "", 22);
    check(d.error == null && d.port == 2200 && d.host.equals("host"), "-p2200");
    TerminalCommand e = TerminalCommand.parse("ssh host", "user", 2222);
    check(e.error == null && e.user.equals("user") && e.port == 2222, "defaults from profile");
    TerminalCommand f = TerminalCommand.parse("ssh user@host:2200", "", 22);
    check(f.error == null && f.port == 2200 && f.host.equals("host"), "host:port");
    TerminalCommand g = TerminalCommand.parse("ssh -l bob host", "", 22);
    check(g.error == null && g.user.equals("bob") && g.host.equals("host"), "-l user");
    TerminalCommand h = TerminalCommand.parse("ssh user@host uptime -p", "", 22);
    check(h.error == null && h.remoteCommand.equals("uptime -p"), "remote command");
    TerminalCommand i = TerminalCommand.parse("ssh -p 99999 user@host", "", 22);
    check(i.error != null, "bad port rejected");
    TerminalCommand j = TerminalCommand.parse("ssh", "", 22);
    check(j.error != null, "missing host rejected");
    TerminalCommand k = TerminalCommand.parse("ping host", "", 22);
    check(k.error != null, "non-ssh rejected");
    TerminalCommand l = TerminalCommand.parse("ssh user@ho$t", "", 22);
    check(l.error != null, "invalid host characters rejected");
    check(TerminalCommand.parse("ssh user@New.Host", "", 22).credentialKey().equals("user@new.host:22"),
        "credential key lowercase");

    Context ctx = new Context();
    TerminalCommand save = TerminalCommand.parse("ssh user@nas.local -p 2222", "", 22);
    SshCredentials.save(ctx, save, "s3cret");
    check(SshCredentials.list(ctx).equals(java.util.Arrays.asList("user@nas.local:2222")), "index save");
    SshCredentials.save(ctx, save, "s3cret");
    check(SshCredentials.list(ctx).size() == 1, "index deduplicated");
    SshCredentials.Entry loaded = SshCredentials.load(ctx, save);
    check(loaded != null && loaded.password.equals("s3cret") && loaded.port == 2222
        && loaded.user.equals("user") && loaded.host.equals("nas.local"), "credential roundtrip");
    SshCredentials.Entry byKey = SshCredentials.load(ctx, "user", "NAS.local", 2222);
    check(byKey != null && byKey.password.equals("s3cret"), "case-insensitive host match");
    check(SshCredentials.load(ctx, "user", "nas.local", 22) == null, "port is part of the key");
    check(SshCredentials.fileName(save.credentialKey()).equals("ssh-cred-"
        + SshCredentials.fileName(save.credentialKey()).substring(9)), "stable file name");
    check(!SshCredentials.fileName(save.credentialKey()).contains("@"), "file name hides identity");
    SshCredentials.forget(ctx, "user", "nas.local", 2222);
    check(SshCredentials.load(ctx, save) == null, "forget removes the entry");
    check(SshCredentials.list(ctx).isEmpty(), "forget removes index entry");
    try { java.nio.file.Files.write(CryptoBox.fileFor(ctx, "ssh-cred-index").toPath(), "broken".getBytes("UTF-8")); }
    catch (Exception ex) { throw new RuntimeException(ex); }
    check(SshCredentials.list(ctx).isEmpty(), "corrupt index is empty");
    check(HostHistory.list(ctx).isEmpty(), "missing history is empty");
    HostHistory.record(ctx, "user@unsaved:22");
    check(HostHistory.merge(HostHistory.list(new Context()), SshCredentials.list(ctx))
        .contains("user@unsaved:22"), "unsaved login survives and appears in menu");
    HostHistory.record(ctx, "user@second:22");
    HostHistory.record(ctx, "user@unsaved:22");
    check(HostHistory.list(ctx).equals(java.util.Arrays.asList("user@unsaved:22", "user@second:22")),
        "history deduplicated and most recent first");
    for (int n = 0; n < 21; n++) HostHistory.record(ctx, "u@host" + n + ":22");
    check(HostHistory.list(ctx).size() == 20 && HostHistory.list(ctx).get(0).equals("u@host20:22")
        && !HostHistory.list(ctx).contains("u@host0:22"), "history cap and order");
    HostHistory.remove(ctx, "u@host20:22");
    check(!HostHistory.list(ctx).contains("u@host20:22"), "forget removes history");
    check(HostHistory.merge(java.util.Arrays.asList("a@a:22", "b@b:22"),
        java.util.Arrays.asList("b@b:22", "c@c:22"))
        .equals(java.util.Arrays.asList("a@a:22", "b@b:22", "c@c:22")), "menu union order");
    try { java.nio.file.Files.write(CryptoBox.fileFor(ctx, "ssh-host-history").toPath(), "broken".getBytes("UTF-8")); }
    catch (Exception ex) { throw new RuntimeException(ex); }
    check(HostHistory.list(ctx).isEmpty(), "corrupt history is empty");
    HostHistory.record(ctx, "u@recovered:22");
    check(HostHistory.list(ctx).equals(java.util.Arrays.asList("u@recovered:22")), "history recovers");
    System.out.println("terminal-command: 18/18 OK; host-history: 8/8 OK");
  }
}
""",
}


def main() -> None:
    work = Path(tempfile.mkdtemp(prefix="ssh-term-"))
    try:
        for name, body in STUBS.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body)
        for name in ("TerminalCommand.java", "SshCredentials.java", "HostHistory.java"):
            shutil.copy(SRC / name, work / "app/remotessh" / name)
        classes = work / "classes"
        classes.mkdir()
        sources = [str(work / "app/remotessh" / name)
                   for name in ("TerminalCommand.java", "SshCredentials.java", "HostHistory.java", "CryptoBox.java", "Harness.java")]
        sources.append(str(work / "android/content/Context.java"))
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", JAR, "-d", str(classes)] + sources,
                       check=True, cwd=work)
        out = subprocess.run(["java", "-cp", str(classes) + os.pathsep + JAR, "app.remotessh.Harness"],
                             capture_output=True, text=True, cwd=work,
                             env={**os.environ, "LC_ALL": "C.UTF-8"})
        if out.returncode != 0 or "18/18 OK" not in out.stdout:
            print(out.stdout, out.stderr, file=__import__("sys").stderr)
            raise AssertionError("terminal command harness failed")
        print("terminal command: 18/18 OK; host history: 8/8 OK")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
