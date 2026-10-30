package app.remotessh;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Recent successful terminal logins; host identifiers contain no passwords. */
public final class HostHistory {
  private static final int LIMIT = 20;
  private HostHistory() {}

  public static synchronized List<String> list(Context context) {
    List<String> result = new ArrayList<>();
    try {
      File file = CryptoBox.fileFor(context, "ssh-host-history");
      if (!file.isFile()) return result;
      JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        Object value = array.get(i);
        if (!(value instanceof String) || !valid((String) value)) return new ArrayList<>();
        String key = (String) value;
        if (!result.contains(key) && result.size() < LIMIT) result.add(key);
      }
    } catch (Exception corrupt) { return new ArrayList<>(); }
    return result;
  }

  public static synchronized void record(Context context, String key) {
    if (!valid(key)) return;
    List<String> keys = list(context);
    keys.remove(key);
    keys.add(0, key);
    if (keys.size() > LIMIT) keys.remove(keys.size() - 1);
    write(context, keys);
  }

  public static synchronized void remove(Context context, String key) {
    List<String> keys = list(context);
    if (keys.remove(key)) write(context, keys);
  }

  public static List<String> merge(List<String> history, List<String> credentials) {
    List<String> keys = new ArrayList<>(history);
    for (String key : credentials) if (!keys.contains(key)) keys.add(key);
    return keys;
  }

  private static boolean valid(String key) {
    if (key == null) return false;
    TerminalCommand parsed = TerminalCommand.parse("ssh " + key, "", 22);
    return parsed.error == null && parsed.remoteCommand.isEmpty() && key.equals(parsed.credentialKey());
  }

  private static void write(Context context, List<String> keys) {
    File tmp = null;
    try {
      File target = CryptoBox.fileFor(context, "ssh-host-history");
      tmp = File.createTempFile("ssh-history-", ".tmp", target.getParentFile());
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(new JSONArray(keys).toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!tmp.renameTo(target)) throw new java.io.IOException("history rename failed");
    } catch (Exception ignored) {
      // History is best-effort and must not interrupt a successful login.
    } finally { if (tmp != null) tmp.delete(); }
  }
}
