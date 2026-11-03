package app.remotessh;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Disk cache for conversation images (hardened fork).
 *
 * Remote images are re-fetched over SFTP after every reconnect and are lost
 * forever once the remote file disappears. This cache stores the raw bytes
 * under cacheDir/images-disk/ keyed by (host, source) hash, bounded by a
 * simple LRU sweep on total bytes. Memory ThumbnailCache stays the hot tier;
 * this is the warm tier used on memory miss before any network fetch.
 *
 * All methods are blocking file I/O — call from worker threads only.
 */
final class ImageDiskCache {
  private static final long MAX_BYTES = 64L * 1024 * 1024;

  private final File dir;

  ImageDiskCache(File cacheDir) {
    this.dir = new File(cacheDir, "images-disk");
    //noinspection ResultOfMethodCallIgnored
    dir.mkdirs();
  }

  private static String fileName(String key) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : d) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      return hex.substring(0, 32) + ".img";
    } catch (Exception e) {
      // Deterministic fallback: hex of hashcodes (still collision-safe enough for a cache tier)
      return Integer.toHexString(key.hashCode()) + "-" + Integer.toHexString(key.length() * 31) + ".img";
    }
  }

  /** Returns cached bytes or null. */
  byte[] get(String key) {
    File f = new File(dir, fileName(key));
    if (!f.isFile()) return null;
    byte[] data = new byte[(int) f.length()];
    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
      int off = 0;
      while (off < data.length) {
        int n = in.read(data, off, data.length - off);
        if (n < 0) return null;
        off += n;
      }
    } catch (IOException e) {
      return null;
    }
    //noinspection ResultOfMethodCallIgnored
    f.setLastModified(System.currentTimeMillis());
    return data;
  }

  /** Best-effort write; cache failures never surface to the UI. */
  void put(String key, byte[] data) {
    if (data == null || data.length == 0) return;
    File f = new File(dir, fileName(key));
    File tmp = new File(dir, f.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(data);
      out.getFD().sync();
    } catch (IOException e) {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      return;
    }
    if (!tmp.renameTo(f)) {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      return;
    }
    trim();
  }

  /** LRU sweep: drop oldest files until the directory fits the budget. */
  private void trim() {
    File[] files = dir.listFiles();
    if (files == null) return;
    long total = 0;
    for (File f : files) total += f.length();
    if (total <= MAX_BYTES) return;
    Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
    for (File f : files) {
      if (total <= MAX_BYTES) break;
      long len = f.length();
      if (f.delete()) total -= len;
    }
  }
}
