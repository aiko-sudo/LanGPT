#!/usr/bin/env python3
"""Usage popup must never spin forever.

Regression for: "剩余用量弹窗一直显示正在读取剩余用量" — refreshUsage used the
request overload without a failure callback, so a failed read left
usage.loading=true forever and every later refresh was skipped by the guard.
JVM part exercises the real UsageLimits; structural part pins the wiring
(failure callback, watchdog, live header repaint)."""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SRC = ROOT / "app/src/main/java/app/remotessh"
sys.path.insert(0, str(HERE))
import testdeps  # noqa: E402

JAR = testdeps.require(testdeps.org_json_jar(), "org.json")

STUBS = {
    "app/remotessh/ConnectionFailure.java": """package app.remotessh;
public final class ConnectionFailure {
  static String reason(Throwable error) {
    return error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
  }
}
""",
    "app/remotessh/Harness.java": """package app.remotessh;
import org.json.JSONObject;
public class Harness {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    UsageLimits usage = new UsageLimits();
    usage.loading = true;
    check(usage.summary().contains("正在读取"), "loading state is explicit");
    usage.fail(new java.io.IOException("failed to fetch codex rate limits"));
    check(!usage.loading, "failure must clear loading");
    check(usage.summary().contains("用量暂不可用"), "summary surfaces the failure: " + usage.summary());
    check(usage.summary().contains("failed to fetch"), "summary keeps the reason: " + usage.summary());
    check(usage.details().contains("用量暂不可用"), "details surface the failure");
    check(usage.error.contains("用量暂不可用"), "error field readable by the UI");

    JSONObject payload = new JSONObject();
    JSONObject limits = new JSONObject();
    JSONObject codex = new JSONObject();
    JSONObject primary = new JSONObject();
    primary.put("usedPercent", 25);
    primary.put("windowDurationMins", 300);
    primary.put("resetsAt", 1893456000L);
    codex.put("primary", primary);
    limits.put("codex", codex);
    payload.put("rateLimitsByLimitId", limits);
    usage.update(payload, true);
    check(usage.error.isEmpty(), "successful read clears the error");
    check(usage.summary().contains("5小时") || usage.summary().contains("75"),
        "window renders after recovery: " + usage.summary());
    usage.loading = true;
    usage.fail(new java.io.IOException("读取超时，请重试"));
    check(usage.summary().contains("更新失败"),
        "later failure on stale data is flagged: " + usage.summary());
    check(usage.details().contains("超时"), "stale details keep the watchdog reason");
    usage.update(payload, true);
    check(usage.fresh(60L * 60L * 1000L), "fresh data is served from cache");
    usage.fetchedAt = System.currentTimeMillis() - 2L * 60L * 60L * 1000L;
    check(!usage.fresh(60L * 60L * 1000L), "data older than an hour is refetched");
    usage.clear();
    check(usage.error.isEmpty() && !usage.loading && !usage.fresh(1L), "clear resets error + loading + cache");
    System.out.println("usage-limits: 10/10 OK");
  }
}
""",
}


def structural() -> None:
    remote = (SRC / "RemoteApp.java").read_text()
    block = remote.split("public void refreshUsage()")[1].split("public boolean terminalAlive")[0]
    assert "failure ->" in block, "refreshUsage needs a failure callback"
    assert "usage.fail(failure)" in block, "failed read must clear loading via usage.fail"
    assert "postDelayed" in block and "读取超时" in block, "watchdog prevents an endless spinner"
    assert "attempt != usageAttempt" in block, "stale attempts must not overwrite newer state"

    main = (SRC / "MainActivity.java").read_text()
    usage_info = main.split("private void usageInfo()")[1].split("private String usageHeaderText")[0]
    assert "view -> usageHeader = view" in usage_info, "menu header view is bound for live repaint"
    assert "dismiss()" in usage_info, "refresh closes the previous dialog"
    render = main.split("private void render() {")[1].split("\n")[1]
    assert "usageHeader.setText(usageHeaderText())" in render, "render() repaints the open usage menu"
    assert '点“刷新”重试' in main, "error state offers a retry"

    menu = (SRC / "CenteredMenu.java").read_text()
    assert "headerSink.accept(header)" in menu, "CenteredMenu exposes the header view"
    header_block = menu.split("content.addView(header);")[1].split("if(headerSink")[0]
    assert "divider(activity,muted,density)" in header_block, \
        "a fading divider separates the header from the first action row"

    # One-hour cache: reopen shows data instantly, 刷新 forces a new read.
    assert "USAGE_CACHE_MS = 60L * 60L * 1000L" in main, "usage cache window is one hour"
    usage_info_full = main.split("private void usageInfo(boolean forceRefresh)")[1].split("private String usageHeaderText")[0]
    assert "app.usage.fresh(USAGE_CACHE_MS)" in usage_info_full, "cached data skips the network read"
    assert "which -> usageInfo(true)" in main, "刷新 forces a fresh read"
    assert "public boolean fresh(long ttlMillis)" in (SRC / "UsageLimits.java").read_text()

    print("usage-popup wiring: 10/10 OK")


def main() -> None:
    work = Path(tempfile.mkdtemp(prefix="usage-limits-"))
    try:
        for name, body in STUBS.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body)
        shutil.copy(SRC / "UsageLimits.java", work / "app/remotessh/UsageLimits.java")
        classes = work / "classes"
        classes.mkdir()
        sources = [str(work / "app/remotessh" / n)
                   for n in ("UsageLimits.java", "ConnectionFailure.java", "Harness.java")]
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", JAR, "-d", str(classes)] + sources,
                       check=True, cwd=work)
        out = subprocess.run(["java", "-cp", str(classes) + os.pathsep + JAR, "app.remotessh.Harness"],
                             capture_output=True, text=True, cwd=work,
                             env={**os.environ, "LC_ALL": "C.UTF-8"})
        if out.returncode != 0 or "10/10 OK" not in out.stdout:
            print(out.stdout, out.stderr, file=sys.stderr)
            raise AssertionError("usage limits harness failed")
        print("usage-limits: 10/10 OK")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    structural()


if __name__ == "__main__":
    main()
