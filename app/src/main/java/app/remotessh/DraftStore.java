package app.remotessh;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Crash-safe draft store for the file editor: persists the unsaved text plus the
 * remote snapshot it was based on, so a page recreation or process death never
 * silently discards user edits. Pure java.io — JVM-testable without Android.
 *
 * File layout (single binary record under the app cache directory):
 *   magic long | savedAtMillis | snapshot fields | digest bytes | draft UTF-8
 */
public final class DraftStore {
  private static final long MAGIC = 0x4452414654_3032L; // "DRAFT02"

  public static final class Draft {
    public final String text;
    public final SftpContent.Snapshot snapshot; // null when the base was unknown
    public final long savedAt;
    Draft(String text, SftpContent.Snapshot snapshot, long savedAt) {
      this.text = text; this.snapshot = snapshot; this.savedAt = savedAt;
    }
  }

  private final File root;
  public DraftStore(File root) { this.root = root; root.mkdirs(); }

  /** Stable per profile+path so reopening the same file finds its own draft. */
  public static String key(String profile, String path) {
    return Integer.toHexString((String.valueOf(profile) + "|" + String.valueOf(path)).hashCode());
  }

  private File file(String key) { return new File(root, "draft-" + key + ".bin"); }

  public void save(String key, String text, SftpContent.Snapshot snapshot) {
    byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    File target = file(key);
    File staging = new File(root, "draft-" + key + ".tmp");
    try (DataOutputStream out = new DataOutputStream(new FileOutputStream(staging))) {
      out.writeLong(MAGIC);
      out.writeLong(System.currentTimeMillis());
      if (snapshot == null) { out.writeBoolean(false); }
      else {
        out.writeBoolean(true);
        out.writeBoolean(snapshot.exists);
        out.writeLong(snapshot.size);
        out.writeInt(snapshot.modified);
        out.writeInt(snapshot.permissions);
        out.writeInt(snapshot.digest == null ? 0 : snapshot.digest.length);
        if (snapshot.digest != null) out.write(snapshot.digest);
      }
      out.writeInt(body.length);
      out.write(body);
      if (!staging.renameTo(target)) {
        try (InputStream in = new FileInputStream(staging); OutputStream copy = new FileOutputStream(target)) {
          byte[] buffer = new byte[16384]; int n;
          while ((n = in.read(buffer)) >= 0) copy.write(buffer, 0, n);
        }
        staging.delete();
      }
    } catch (IOException failure) {
      staging.delete(); // A partial draft record must never replace a good one.
    }
  }

  /** Returns the stored draft, or null when absent/corrupt (corrupt records are dropped). */
  public Draft load(String key) {
    File source = file(key);
    if (!source.isFile()) return null;
    try (DataInputStream in = new DataInputStream(new FileInputStream(source))) {
      if (in.readLong() != MAGIC) { source.delete(); return null; }
      long savedAt = in.readLong();
      SftpContent.Snapshot snapshot = null;
      if (in.readBoolean()) {
        boolean exists = in.readBoolean();
        long size = in.readLong();
        int modified = in.readInt();
        int permissions = in.readInt();
        int digestLength = in.readInt();
        byte[] digest = digestLength > 0 ? new byte[digestLength] : new byte[0];
        in.readFully(digest);
        snapshot = new SftpContent.Snapshot(exists, size, modified, permissions, digest);
      }
      int bodyLength = in.readInt();
      if (bodyLength < 0 || bodyLength > 4 * 1024 * 1024) { source.delete(); return null; }
      byte[] body = new byte[bodyLength];
      in.readFully(body);
      return new Draft(new String(body, StandardCharsets.UTF_8), snapshot, savedAt);
    } catch (IOException corrupt) {
      source.delete();
      return null;
    }
  }

  /** Called after a successful save: the draft is now the file on the remote. */
  public void clear(String key) { file(key).delete(); }
}
