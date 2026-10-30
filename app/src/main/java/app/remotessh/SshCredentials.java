package app.remotessh;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Saved SSH terminal credentials. Secrets are sealed with the app's Android
 * Keystore key via CryptoBox, never stored in plaintext, and are only used
 * for explicitly selected SSH connections.
 */
public final class SshCredentials {
  private SshCredentials() {}

  public static final class Entry {
    public final String user, host, password;
    public final int port;

    Entry(String user, String host, int port, String password) {
      this.user = user;
      this.host = host;
      this.port = port;
      this.password = password;
    }

    public String label() { return user + "@" + host + ":" + port; }
  }

  /** File name per credential key: opaque, stable, no path-hostile characters. */
  static String fileName(String credentialKey) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(credentialKey.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder("ssh-cred-");
      for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i]));
      return hex.toString();
    } catch (Exception impossible) {
      return "ssh-cred-" + Integer.toHexString(credentialKey.hashCode());
    }
  }

  public static synchronized void save(Context context, TerminalCommand command, String password) {
    if (password == null || password.isEmpty()) return;
    try {
      JSONObject json = new JSONObject();
      json.put("user", command.user);
      json.put("host", command.host);
      json.put("port", command.port);
      json.put("password", password);
      CryptoBox.writeSealed(context, fileName(command.credentialKey()), json.toString());
      Entry saved = load(context, command);
      if (saved != null && password.equals(saved.password)) {
        List<String> keys = list(context);
        if (!keys.contains(command.credentialKey())) keys.add(command.credentialKey());
        writeIndex(context, keys);
      }
    } catch (Exception ignored) {
      // Saving is best-effort: a failure must not block the login itself.
    }
  }

  public static Entry load(Context context, TerminalCommand command) {
    return load(context, command.user, command.host, command.port);
  }

  public static Entry load(Context context, String user, String host, int port) {
    try {
      String raw = CryptoBox.readSealed(context, fileName(credentialKey(user, host, port)));
      if (raw == null || raw.isEmpty()) return null;
      JSONObject json = new JSONObject(raw);
      String password = json.optString("password", "");
      if (password.isEmpty()) return null;
      return new Entry(json.optString("user", user), json.optString("host", host),
          json.optInt("port", port), password);
    } catch (Exception unreadable) {
      return null;
    }
  }

  public static synchronized void forget(Context context, String user, String host, int port) {
    try {
      CryptoBox.fileFor(context, fileName(credentialKey(user, host, port))).delete();
      List<String> keys = list(context);
      keys.remove(credentialKey(user, host, port));
      writeIndex(context, keys);
    } catch (Exception ignored) {
      // Nothing to do: the entry simply stays until the next successful forget.
    }
  }

  /** Legacy hashed credential files cannot be enumerated and appear after their next save. */
  public static synchronized List<String> list(Context context) {
    List<String> result = new ArrayList<>();
    try {
      java.io.File file = CryptoBox.fileFor(context, "ssh-cred-index");
      if (!file.isFile()) return result;
      JSONArray array = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        Object value = array.get(i);
        if (!(value instanceof String)) return new ArrayList<>();
        String key = (String) value;
        TerminalCommand parsed = TerminalCommand.parse("ssh " + key, "", 22);
        if (parsed.error != null || !parsed.remoteCommand.isEmpty() || !key.equals(parsed.credentialKey()))
          return new ArrayList<>();
        if (!result.contains(key)) result.add(key);
      }
    } catch (Exception corrupt) { return new ArrayList<>(); }
    return result;
  }

  private static void writeIndex(Context context, List<String> keys) throws Exception {
    java.io.File target = CryptoBox.fileFor(context, "ssh-cred-index");
    java.io.File tmp = java.io.File.createTempFile("ssh-index-", ".tmp", target.getParentFile());
    try {
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(new JSONArray(keys).toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!tmp.renameTo(target)) throw new java.io.IOException("index rename failed");
    } finally { tmp.delete(); }
  }

  static String credentialKey(String user, String host, int port) {
    return user.toLowerCase(java.util.Locale.ROOT) + "@"
        + host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
  }
}
