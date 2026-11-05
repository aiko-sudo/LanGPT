package app.remotessh;

import android.content.Context;
import java.util.HashSet;
import java.util.Set;

/** Local project removals survive discovery; choosing a project restores it. */
final class ProjectVisibility {
  private static Set<String> hidden(Context context, String profile) {
    return new HashSet<>(context.getSharedPreferences("hidden-projects", Context.MODE_PRIVATE)
        .getStringSet(profile, java.util.Collections.emptySet()));
  }
  private static String fingerprint(String path) {
    try {
      byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  static boolean isHidden(Context context, String profile, String path) {
    return hidden(context, profile).contains(fingerprint(path));
  }
  static void setHidden(Context context, String profile, String path, boolean hide) {
    Set<String> paths = hidden(context, profile);
    if (hide) paths.add(fingerprint(path)); else paths.remove(fingerprint(path));
    context.getSharedPreferences("hidden-projects", Context.MODE_PRIVATE)
        .edit().putStringSet(profile, paths).apply();
  }
}
