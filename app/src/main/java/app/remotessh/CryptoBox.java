package app.remotessh;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM file encryption for conversation interaction history (hardened edition).
 * Same trust model as Vault: non-exportable Android Keystore key, per-file random nonce.
 * Replaces plaintext SharedPreferences persistence for interaction-history.
 */
public final class CryptoBox {
  private static final String ALIAS = "remote.ssh.interactions.v1";

  private CryptoBox() {}

  static File fileFor(Context c, String name) {
    File dir = new File(c.getFilesDir(), "interactions");
    if (!dir.exists()) dir.mkdirs();
    // defensive: never allow path separators in derived file names
    String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
    return new File(dir, safe + ".enc");
  }

  private static java.security.Key key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return ks.getKey(ALIAS, null);
  }

  /** Returns encrypted payload: 12-byte nonce || ciphertext+tag. */
  static byte[] seal(String plaintext) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    byte[] nonce = c.getIV(); // 12 bytes for GCM
    byte[] out = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] packed = new byte[nonce.length + out.length];
    System.arraycopy(nonce, 0, packed, 0, nonce.length);
    System.arraycopy(out, 0, packed, nonce.length, out.length);
    return packed;
  }

  static String unseal(byte[] packed) throws Exception {
    if (packed == null || packed.length < 29) throw new java.io.IOException("interaction store corrupt");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, 12)));
    return new String(c.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
  }

  /** Atomically write sealed payload (tmp file + rename, no partial plaintext risk). */
  static void writeSealed(Context c, String name, String plaintext) {
    try {
      File target = fileFor(c, name);
      File tmp = File.createTempFile("enc-", ".tmp", target.getParentFile());
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(seal(plaintext));
        out.getFD().sync();
      }
      if (!tmp.renameTo(target)) { // fall back for exotic filesystems
        java.nio.file.Files.copy(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tmp.delete();
      }
    } catch (Exception ignored) {
      // Encryption/keystore failure must never crash the app; history persistence is best-effort.
    }
  }

  /** Read and unseal; returns null when absent (fresh install). */
  static String readSealed(Context c, String name) {
    try {
      File f = fileFor(c, name);
      if (!f.exists()) return null;
      return unseal(java.nio.file.Files.readAllBytes(f.toPath()));
    } catch (Exception e) {
      return null; // unreadable/corrupt history is treated as absent
    }
  }
}
