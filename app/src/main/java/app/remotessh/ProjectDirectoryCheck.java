package app.remotessh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import java.util.*;

/** One SFTP channel; only confirmed missing paths are marked absent. */
final class ProjectDirectoryCheck {
  static Map<String, Boolean> check(SshTransport transport, Collection<String> paths) throws Exception {
    Map<String, Boolean> result = new LinkedHashMap<>();
    ChannelSftp channel = transport.sftp();
    try {
      for (String path : paths) {
        try { result.put(path, channel.stat(path).isDir()); }
        catch (SftpException e) {
          if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) result.put(path, false);
          // Unknown and permission errors do not establish absence.
        }
      }
    } finally { channel.disconnect(); }
    return result;
  }
}
