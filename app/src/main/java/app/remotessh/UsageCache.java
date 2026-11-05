package app.remotessh;

import android.content.Context;
import org.json.JSONObject;

/** Per-connection quota snapshots and automatic request throttle. */
final class UsageCache {
  private static final long INTERVAL = 60L * 60L * 1000L;
  private final Context context;
  UsageCache(Context context) { this.context = context; }
  private String key(Profile p) { return p.id + ":" + p.runtime; }
  private android.content.SharedPreferences prefs() { return context.getSharedPreferences("usage-cache-v1", Context.MODE_PRIVATE); }
  boolean claim(Profile p, String kind, boolean force) {
    if (p == null) return false;
    String name = key(p) + ":attempt:" + kind; long now = System.currentTimeMillis(), last = prefs().getLong(name, 0);
    if (!force && last > 0 && now >= last && now - last < INTERVAL) return false;
    prefs().edit().putLong(name, now).apply(); return true;
  }
  void save(Profile p, String kind, JSONObject value) {
    if (p == null || value == null) return;
    android.content.SharedPreferences.Editor edit = prefs().edit().putString(key(p) + ":" + kind, value.toString());
    if (kind.equals("limits")) edit.putLong(key(p) + ":fetched", System.currentTimeMillis());
    edit.apply();
  }
  void restore(Profile p, UsageLimits usage) {
    if (p == null) return;
    try {
      String limits = prefs().getString(key(p) + ":limits", "");
      if (!limits.isEmpty()) { usage.update(new JSONObject(limits), true); usage.fetchedAt = prefs().getLong(key(p) + ":fetched", 0); }
      String stats = prefs().getString(key(p) + ":stats", "");
      if (!stats.isEmpty()) usage.updateStats(new JSONObject(stats));
    } catch (org.json.JSONException ignored) { }
  }
}
