package app.remotessh;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stages data over SFTP; the remote helper serializes and journals both replacements. */
final class CustomConfigTransaction {
  private CustomConfigTransaction() { }

  static void apply(Context context, SshTransport transport, String oldConfig, String oldAuth,
                    String newConfig, String newAuth) throws Exception {
    String script;
    try (InputStream in = context.getAssets().open("custom_config_transaction.py");
         ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) != -1;) bytes.write(buffer, 0, n);
      script = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }
    String name = "request-" + UUID.randomUUID().toString() + ".json";
    transport.ensureDirectory(".remote-gpt/custom-config");
    JSONArray oldValues = new JSONArray().put(oldConfig == null ? JSONObject.NULL : oldConfig)
        .put(oldAuth == null ? JSONObject.NULL : oldAuth);
    JSONArray newValues = new JSONArray().put(newConfig).put(newAuth);
    String payload = new JSONObject().put("old", oldValues).put("new", newValues).toString();
    transport.writeTextFile(".remote-gpt/custom-config/" + name, payload);
    String result = transport.runCommand("python3 -c 'import base64;exec(base64.b64decode(\""
        + script + "\"))' \"$HOME/.remote-gpt/custom-config/" + name + "\"").trim();
    if (!result.equals("OK")) throw new IOException(result.startsWith("ERROR: ")
        ? result.substring(7) : "远端配置事务结果未知，请重试并检查恢复记录");
  }
}
