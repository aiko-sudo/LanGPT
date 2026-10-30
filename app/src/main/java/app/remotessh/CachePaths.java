package app.remotessh;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure java.io helpers for the external-open cache: no Android dependencies so the
 * path rules can be compiled and exercised on a plain JVM.
 */
public final class CachePaths {
  private static final AtomicLong SEQ = new AtomicLong();

  private CachePaths() {}

  /** Basename of a remote path; never empty and never containing a separator. */
  static String baseName(String remotePath) {
    if (remotePath == null) return "file";
    int slash = remotePath.lastIndexOf('/');
    String name = slash >= 0 ? remotePath.substring(slash + 1) : remotePath;
    return name.isEmpty() ? "file" : name;
  }

  /**
   * Cache-relative path unique to a single download. The original name is kept as the
   * last segment so MIME detection and viewer titles stay correct, while the unique
   * parent directory prevents same-named files (different directory, host or a
   * concurrent download) from overwriting each other.
   */
  static String uniqueRelative(String baseName) {
    return "d" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet() + "/" + baseName;
  }

  /**
   * Resolve a provider URI path inside {@code root}. Rejects empty paths and anything
   * that escapes the cache directory (traversal, absolute paths, symlinks).
   */
  static File resolve(File root, String uriPath) throws IOException {
    String relative = uriPath == null ? "" : uriPath;
    while (relative.startsWith("/")) relative = relative.substring(1);
    if (relative.isEmpty()) throw new IOException("empty cache path");
    File file = new File(root, relative);
    String canonical = file.getCanonicalPath();
    String rootCanonical = root.getCanonicalPath();
    if (!canonical.startsWith(rootCanonical + File.separator))
      throw new IOException("cache path outside root");
    return file;
  }
}
