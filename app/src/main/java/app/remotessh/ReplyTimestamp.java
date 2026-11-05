package app.remotessh;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/** Reply completion times; unknown historical times remain unknown. */
final class ReplyTimestamp {
  static long parse(JSONObject item) {
    Object value = item.opt("completedAt");
    if (value == null || value == JSONObject.NULL) value = item.opt("timestamp");
    if (value == null || value == JSONObject.NULL) {
      JSONObject time = item.optJSONObject("time");
      if (time != null) value = time.opt("completed");
    }
    if (value instanceof Number) {
      long n = ((Number)value).longValue();
      return n > 0 && n < 100000000000L ? n * 1000 : Math.max(0, n);
    }
    if (value instanceof String) try { return java.time.Instant.parse((String)value).toEpochMilli(); }
    catch (java.time.format.DateTimeParseException ignored) {}
    return 0;
  }
  private static String key(String profile, String thread, String item) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest((profile + "\0" + thread + "\0" + item).getBytes(StandardCharsets.UTF_8));
      return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  static long load(Context context, String profile, String thread, String item) {
    if (profile.isEmpty() || thread.isEmpty() || item.isEmpty()) return 0;
    return context.getSharedPreferences("reply-times", Context.MODE_PRIVATE).getLong(key(profile, thread, item), 0);
  }
  static void save(Context context, String profile, String thread, String item, long time) {
    if (profile.isEmpty() || thread.isEmpty() || item.isEmpty() || time <= 0) return;
    context.getSharedPreferences("reply-times", Context.MODE_PRIVATE).edit().putLong(key(profile, thread, item), time).apply();
  }
  static String format(long time, long now, Locale locale) {
    if (time <= 0) return "";
    Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
    a.setTimeInMillis(time); b.setTimeInMillis(now);
    boolean today = a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    return new SimpleDateFormat(today ? "HH:mm" : "yyyy-MM-dd HH:mm", locale).format(new Date(time));
  }
}
