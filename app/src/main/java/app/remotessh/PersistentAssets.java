package app.remotessh;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A single installed script, with focused local sources and one content hash. */
final class PersistentAssets {
  static byte[] host(Context context) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (String asset : new String[] {"cli_events.py", "cli_host.py", "remote_session.py"}) {
      try (InputStream input = context.getAssets().open(asset)) {
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        bytes.write('\n');
      }
    }
    return bytes.toByteArray();
  }
}
