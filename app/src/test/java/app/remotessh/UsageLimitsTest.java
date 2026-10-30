package app.remotessh;

import static org.junit.Assert.*;
import org.junit.Test;

public class UsageLimitsTest {
  @Test public void quotaWindowsShowRemainingAndRetainSparseUpdates() {
    UsageLimits usage = new UsageLimits();
    usage.update(Json.obj("rateLimits", Json.obj("primary", Json.obj("usedPercent", 25, "windowDurationMins", 300),
        "secondary", Json.obj("usedPercent", 90, "windowDurationMins", 10080))), true);
    assertEquals("剩余用量 · 5小时 75% · 7天 10%", usage.summary());
    usage.update(Json.obj("rateLimits", Json.obj("primary", Json.obj("usedPercent", 40, "windowDurationMins", 300))), false);
    assertEquals("剩余用量 · 5小时 60% · 7天 10%", usage.summary());
    usage.clear();
    assertFalse(usage.summary().contains("60%"));
  }

  @Test public void summaryShowsResetCountdownWhenResetsAtPresent() {
    UsageLimits usage = new UsageLimits();
    long now = System.currentTimeMillis() / 1000;
    long soon = now + 45 * 60 + 30;   // 45 分（留 30s 缓冲防 floor 抖动）
    long later = now + 3 * 86400 + 30; // 3 天
    usage.update(Json.obj("rateLimits", Json.obj(
        "primary", Json.obj("usedPercent", 25, "windowDurationMins", 300, "resetsAt", soon),
        "secondary", Json.obj("usedPercent", 10, "windowDurationMins", 10080, "resetsAt", later))), true);
    String s = usage.summary();
    assertTrue(s.contains("5小时 75% (45分后重置)"));
    assertTrue(s.contains("7天 90% (3天后重置)"));
  }

  @Test public void usageStatsSectionRendersDailyBucketsAndStreaks() {
    UsageLimits usage = new UsageLimits();
    usage.updateStats(Json.obj(
        "summary", Json.obj("lifetimeTokens", 209987757, "peakDailyTokens", 100371640,
            "longestRunningTurnSec", 401, "currentStreakDays", 7, "longestStreakDays", 7),
        "dailyUsageBuckets", new org.json.JSONArray()
            .put(Json.obj("startDate", "2026-10-19", "tokens", 100371640))
            .put(Json.obj("startDate", "2026-10-23", "tokens", 2547271))));
    String d = usage.details();
    assertTrue(d.contains("—— 用量统计 ——"));
    assertTrue(d.contains("10-19：1.0亿"));
    assertTrue(d.contains("10-23：254.7万"));
    assertTrue(d.contains("累计：2.1亿"));
    assertTrue(d.contains("连续使用：7 天（最长 7 天）"));
    usage.clear();
    assertFalse(usage.details().contains("用量统计"));
  }

  @Test public void loadingStateSummariesAreExplicit() {
    UsageLimits usage = new UsageLimits();
    usage.loading = true;
    assertEquals("正在读取剩余用量…", usage.summary());
    usage.loading = false;
    assertEquals("连接后查看剩余用量", usage.summary());
  }

  @Test public void bucketsUnknownAndOutOfRangeValuesAreExplicit() {
    UsageLimits usage = new UsageLimits();
    usage.update(Json.obj("rateLimitsByLimitId", Json.obj("other", Json.obj("primary", Json.obj("usedPercent", 120)),
        "codex", Json.obj("primary", Json.obj("usedPercent", 100)))), true);
    assertEquals("剩余用量 · 主额度 0%", usage.summary());
    assertTrue(usage.details().contains("other"));
    usage.update(Json.obj("rateLimits", Json.obj("primary", Json.obj("windowDurationMins", 300))), true);
    assertFalse(usage.summary().contains("100%"));
    assertEquals("远端未提供用量数据", usage.summary());
  }
}
