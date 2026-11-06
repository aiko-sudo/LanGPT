package app.remotessh;

import java.text.DateFormat;
import java.util.*;
import org.json.*;

/** Account quota, independent of thread token/context usage. */
public final class UsageLimits {
  private static volatile java.util.function.Supplier<Locale> localeProvider = Locale::getDefault;
  public static void setLocaleProvider(java.util.function.Supplier<Locale> provider) {
    localeProvider = provider == null ? Locale::getDefault : provider;
  }
  private static Locale locale() {
    Locale locale = localeProvider.get();
    return locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
  }
  private static boolean chinese() {
    return !"en".equals(locale().getLanguage());
  }
  private static String text(String zh, String en) {
    return chinese() ? zh : en;
  }
  private String displayStatus() {
    if (status.equals("连接后查看剩余用量")) return text(status, "Connect to view remaining usage");
    if (status.equals("远端未提供用量数据")) return text(status, "Remote did not provide usage data");
    return status;
  }
  private String displayError() {
    String prefix = "用量暂不可用：";
    if (!error.startsWith(prefix)) return error;
    String reason = error.substring(prefix.length());
    if (reason.equals("未知错误")) reason = text(reason, "Unknown error");
    return text(prefix, "Usage unavailable: ") + reason;
  }
  private final Map<String, JSONObject> buckets = new LinkedHashMap<>();
  public String status = "连接后查看剩余用量";
  public boolean loading;
  /** 最近一次读取失败原因；非空时 summary/details 优先展示，避免永远转圈。 */
  public String error = "";
  /** 最近一次成功读取用量（或统计）的时间戳，用于 1 小时缓存判定。 */
  public long fetchedAt;
  // account/usage/read 统计快照（每日 token 消耗、连续使用天数），原版未接入的数据源
  public JSONObject stats = new JSONObject();

  public void clear() {
    buckets.clear();
    loading = false;
    error = "";
    fetchedAt = 0;
    status = "连接后查看剩余用量";
    stats = new JSONObject();
  }

  /** 读取失败：结束 loading 并记下原因，界面可据此显示与重试。 */
  public void fail(Throwable failure) {
    loading = false;
    // 额度读取失败即令缓存失效：重开弹窗会自动重读，而不是吃统计时间戳的"新鲜"缓存。
    fetchedAt = 0;
    String reason = failure == null ? "未知错误" : ConnectionFailure.reason(failure);
    error = "用量暂不可用：" + reason;
  }

  /** 数据是否仍在缓存有效期内（默认 1 小时）。 */
  public boolean fresh(long ttlMillis) {
    return fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < ttlMillis;
  }

  public void update(JSONObject payload, boolean snapshot) {
    error = "";
    fetchedAt = System.currentTimeMillis();
    if (snapshot) buckets.clear();
    JSONObject all = payload.optJSONObject("rateLimitsByLimitId");
    if (all != null) {
      Iterator<String> keys = all.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        merge(key, all.optJSONObject(key));
      }
    }
    JSONObject single = payload.optJSONObject("rateLimits");
    if (single != null) {
      String id = single.isNull("limitId") ? "codex" : single.optString("limitId", "codex");
      if (all == null || !all.has(id)) merge(id, single);
    }
    status = "远端未提供用量数据";
  }

  public void updateStats(JSONObject payload) {
    stats = payload == null ? new JSONObject() : payload;
    // 统计是辅助数据源，不能替额度写缓存时间戳：额度读取失败/缺失时
    // fresh() 必须判过期，重开弹窗自动重读额度。
  }

  private void merge(String key, JSONObject incoming) {
    if (incoming == null) return;
    JSONObject current = buckets.get(key);
    if (current == null) current = new JSONObject();
    Iterator<String> keys = incoming.keys();
    while (keys.hasNext()) {
      String field = keys.next();
      // Notifications are sparse; null does not erase an observed quota window.
      if (!incoming.isNull(field)) try { current.put(field, incoming.opt(field)); } catch (JSONException ignored) {}
    }
    buckets.put(key, current);
  }

  public String summary() {
    JSONObject bucket = buckets.get("codex");
    if (bucket == null && !buckets.isEmpty()) bucket = buckets.values().iterator().next();
    String windows = bucket == null ? "" : windows(bucket, false);
    if (windows.isEmpty()) {
      if (loading) return text("正在读取剩余用量…", "Loading remaining usage…");
      if (!error.isEmpty()) return displayError();
      return displayStatus();
    }
    return text("剩余用量 · ", "Remaining usage · ") + windows + (loading ? text(" · 刷新中", " · Refreshing") : error.isEmpty() ? "" : text(" · 更新失败", " · Update failed"));
  }

  public String details() {
    StringBuilder result = new StringBuilder();
    // 错误只由 summary() 单行展示（usageHeaderText 已含它）；这里重复拼接会出现两行同文本。
    for (Map.Entry<String, JSONObject> entry : buckets.entrySet()) {
      JSONObject b = entry.getValue();
      if (result.length() > 0) result.append("\n\n");
      result.append(b.isNull("limitName") ? entry.getKey() : b.optString("limitName", entry.getKey()));
      String value = windows(b, true);
      result.append("\n").append(value.isEmpty() ? text("远端未提供额度窗口", "Remote did not provide quota windows") : value);
      // 补充 planType 与 credits (payload 已有但 windows() 未显示)
      String plan = b.isNull("planType") ? "" : b.optString("planType", "");
      if (!plan.isEmpty()) result.append(text("\n计划：", "\nPlan: ")).append(plan);
      JSONObject credits = b.optJSONObject("credits");
      if (credits != null) {
        boolean has = credits.optBoolean("hasCredits", false);
        boolean unlimited = credits.optBoolean("unlimited", false);
        String balance = credits.optString("balance", "");
        result.append(text("\nCredits：", "\nCredits: ")).append(unlimited ? text("无限", "Unlimited") : has ? (text("余额 ", "Balance ") + balance) : text("无", "None"));
      }
    }
    JSONObject summaryStats = stats.optJSONObject("summary");
    JSONArray daily = stats.optJSONArray("dailyUsageBuckets");
    if (summaryStats != null || (daily != null && daily.length() > 0)) {
      result.append(result.length() == 0 ? "" : "\n\n").append(text("—— 用量统计 ——", "—— Usage statistics ——"));
      if (daily != null)
        for (int i = 0; i < daily.length(); i++) {
          JSONObject b = daily.optJSONObject(i);
          if (b == null) continue;
          String day = b.optString("startDate", "");
          if (day.length() >= 10) day = day.substring(5);
          result.append("\n").append(day).append(text("：", ": ")).append(fmtTokens(b.optLong("tokens", 0)));
        }
      if (summaryStats != null) {
        if (summaryStats.has("lifetimeTokens"))
          result.append(text("\n累计：", "\nLifetime: ")).append(fmtTokens(summaryStats.optLong("lifetimeTokens")));
        if (summaryStats.has("peakDailyTokens"))
          result.append(text("\n单日峰值：", "\nDaily peak: ")).append(fmtTokens(summaryStats.optLong("peakDailyTokens")));
        long longestTurn = summaryStats.optLong("longestRunningTurnSec", 0);
        if (longestTurn > 0) result.append(text("\n最长轮次：", "\nLongest turn: ")).append(longestTurn).append(text(" 秒", " s"));
        long streak = summaryStats.optLong("currentStreakDays", 0);
        if (streak > 0)
          result.append(text("\n连续使用：", "\nCurrent streak: ")).append(streak).append(text(" 天（最长 ", " days (longest "))
              .append(summaryStats.optLong("longestStreakDays", 0)).append(text(" 天）", " days)"));
      }
    }
    if (!status.equals("远端未提供用量数据") || result.length() == 0)
      result.append(result.length() == 0 ? "" : "\n\n").append(displayStatus());
    return result.toString();
  }

  private static String windows(JSONObject b, boolean detail) {
    List<String> values = new ArrayList<>();
    for (String key : new String[] {"primary", "secondary"}) {
      JSONObject w = b.optJSONObject(key);
      if (w == null || w.isNull("usedPercent")) continue;
      double used = w.optDouble("usedPercent", Double.NaN);
      if (!Double.isFinite(used)) continue;
      String label = key.equals("primary") ? text("主额度", "Primary") : text("次额度", "Secondary");
      String value = label + " " + Math.round(Math.max(0, Math.min(100, 100 - used))) + "%";
      long resets = w.optLong("resetsAt", 0);
      if (resets > 0)
        value += detail
            ? text(" · 重置 ", " · Resets ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale()).format(new Date(resets * 1000))
            : text(" (", " (resets in ") + countdown(resets) + text("后重置)", ")");
      values.add(value);
    }
    return String.join(detail ? "\n" : " · ", values);
  }

  private static String countdown(long resetsSec) {
    long mins = Math.max(0, resetsSec - System.currentTimeMillis() / 1000) / 60;
    if (mins < 60) return mins + text("分", "m");
    if (mins < 2880) return (mins / 60) + text("时", "h");
    return (mins / 1440) + text("天", "d");
  }

  private static String fmtTokens(long t) {
    if (!chinese()) {
      if (t >= 1000000000L) return String.format(Locale.US, "%.1fB", t / 1e9);
      if (t >= 1000000L) return String.format(Locale.US, "%.1fM", t / 1e6);
      if (t >= 1000L) return String.format(Locale.US, "%.1fK", t / 1e3);
      return String.valueOf(t);
    }
    if (t >= 100000000L) return String.format(java.util.Locale.US, "%.1f亿", t / 1e8);
    if (t >= 10000L) return String.format(java.util.Locale.US, "%.1f万", t / 1e4);
    return String.valueOf(t);
  }
}
