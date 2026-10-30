package app.remotessh;

import java.text.DateFormat;
import java.util.*;
import org.json.*;

/** Account quota, independent of thread token/context usage. */
public final class UsageLimits {
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
      if (loading) return "正在读取剩余用量…";
      if (!error.isEmpty()) return error;
      return status;
    }
    return "剩余用量 · " + windows + (loading ? " · 刷新中" : error.isEmpty() ? "" : " · 更新失败");
  }

  public String details() {
    StringBuilder result = new StringBuilder();
    if (!error.isEmpty()) result.append(error);
    for (Map.Entry<String, JSONObject> entry : buckets.entrySet()) {
      JSONObject b = entry.getValue();
      if (result.length() > 0) result.append("\n\n");
      result.append(b.isNull("limitName") ? entry.getKey() : b.optString("limitName", entry.getKey()));
      String value = windows(b, true);
      result.append("\n").append(value.isEmpty() ? "远端未提供额度窗口" : value);
      // 补充 planType 与 credits (payload 已有但 windows() 未显示)
      String plan = b.isNull("planType") ? "" : b.optString("planType", "");
      if (!plan.isEmpty()) result.append("\n计划：").append(plan);
      JSONObject credits = b.optJSONObject("credits");
      if (credits != null) {
        boolean has = credits.optBoolean("hasCredits", false);
        boolean unlimited = credits.optBoolean("unlimited", false);
        String balance = credits.optString("balance", "");
        result.append("\nCredits：").append(unlimited ? "无限" : has ? ("余额 " + balance) : "无");
      }
    }
    JSONObject summaryStats = stats.optJSONObject("summary");
    JSONArray daily = stats.optJSONArray("dailyUsageBuckets");
    if (summaryStats != null || (daily != null && daily.length() > 0)) {
      result.append(result.length() == 0 ? "" : "\n\n").append("—— 用量统计 ——");
      if (daily != null)
        for (int i = 0; i < daily.length(); i++) {
          JSONObject b = daily.optJSONObject(i);
          if (b == null) continue;
          String day = b.optString("startDate", "");
          if (day.length() >= 10) day = day.substring(5);
          result.append("\n").append(day).append("：").append(fmtTokens(b.optLong("tokens", 0)));
        }
      if (summaryStats != null) {
        if (summaryStats.has("lifetimeTokens"))
          result.append("\n累计：").append(fmtTokens(summaryStats.optLong("lifetimeTokens")));
        if (summaryStats.has("peakDailyTokens"))
          result.append("\n单日峰值：").append(fmtTokens(summaryStats.optLong("peakDailyTokens")));
        long longestTurn = summaryStats.optLong("longestRunningTurnSec", 0);
        if (longestTurn > 0) result.append("\n最长轮次：").append(longestTurn).append(" 秒");
        long streak = summaryStats.optLong("currentStreakDays", 0);
        if (streak > 0)
          result.append("\n连续使用：").append(streak).append(" 天（最长 ")
              .append(summaryStats.optLong("longestStreakDays", 0)).append(" 天）");
      }
    }
    if (!status.equals("远端未提供用量数据") || result.length() == 0)
      result.append(result.length() == 0 ? "" : "\n\n").append(status);
    return result.toString();
  }

  private static String windows(JSONObject b, boolean detail) {
    List<String> values = new ArrayList<>();
    for (String key : new String[] {"primary", "secondary"}) {
      JSONObject w = b.optJSONObject(key);
      if (w == null || w.isNull("usedPercent")) continue;
      double used = w.optDouble("usedPercent", Double.NaN);
      if (!Double.isFinite(used)) continue;
      long minutes = w.optLong("windowDurationMins", 0);
      String label = minutes <= 0 ? (key.equals("primary") ? "主额度" : "次额度")
          : minutes % 1440 == 0 ? (minutes / 1440) + "天"
          : minutes % 60 == 0 ? (minutes / 60) + "小时" : minutes + "分钟";
      String value = label + " " + Math.round(Math.max(0, Math.min(100, 100 - used))) + "%";
      long resets = w.optLong("resetsAt", 0);
      if (resets > 0)
        value += detail
            ? " · 重置 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(resets * 1000))
            : " (" + countdown(resets) + "后重置)";
      values.add(value);
    }
    return String.join(detail ? "\n" : " · ", values);
  }

  private static String countdown(long resetsSec) {
    long mins = Math.max(0, resetsSec - System.currentTimeMillis() / 1000) / 60;
    if (mins < 60) return mins + "分";
    if (mins < 2880) return (mins / 60) + "时";
    return (mins / 1440) + "天";
  }

  private static String fmtTokens(long t) {
    if (t >= 100000000L) return String.format(java.util.Locale.US, "%.1f亿", t / 1e8);
    if (t >= 10000L) return String.format(java.util.Locale.US, "%.1f万", t / 1e4);
    return String.valueOf(t);
  }
}
