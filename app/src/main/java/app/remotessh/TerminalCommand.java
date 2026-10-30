package app.remotessh;

/**
 * Parses the single line typed into the app's SSH terminal. Pure Java so the
 * rules stay unit-testable: the terminal never auto-connects — a connection
 * only starts when the user types an ssh command.
 *
 * Accepted forms:
 *   ssh user@host
 *   ssh user@host -p 2222        /  ssh -p2222 user@host
 *   ssh host                     (default user + port from the app profile)
 *   ssh -l user host
 *   ssh user@host uptime         (one-shot remote command instead of a shell)
 */
public final class TerminalCommand {
  public final String user;
  public final String host;
  public final int port;
  public final String remoteCommand; // empty means interactive shell
  public final String error;         // null when the command is valid

  private TerminalCommand(String user, String host, int port, String remoteCommand, String error) {
    this.user = user;
    this.host = host;
    this.port = port;
    this.remoteCommand = remoteCommand;
    this.error = error;
  }

  public static TerminalCommand fail(String error) {
    return new TerminalCommand("", "", 0, "", error);
  }

  public static TerminalCommand parse(String line, String defaultUser, int defaultPort) {
    String text = line == null ? "" : line.trim();
    if (text.isEmpty()) return fail("输入 ssh user@host 连接主机");
    String[] tokens = text.split("\\s+");
    int index = 0;
    if (tokens[index].equalsIgnoreCase("ssh")) index++;
    else if (!text.startsWith("ssh")) return fail("只支持 ssh 命令，例如 ssh user@192.168.1.10");

    String user = defaultUser == null ? "" : defaultUser.trim();
    int port = defaultPort > 0 ? defaultPort : 22;
    String host = "";
    StringBuilder remote = new StringBuilder();
    boolean targetSeen = false;
    boolean remotePhase = false; // after the first non-option token, everything is command text
    while (index < tokens.length) {
      String token = tokens[index];
      if (targetSeen && !remotePhase) {
        // ssh user@host -p 2222 is common user habit: keep accepting options
        // until the first non-option token starts the remote command.
        if (token.equals("-p") || token.equals("-P")) {
          if (index + 1 >= tokens.length) return fail("-p 后面需要端口号");
          Integer parsed = parsePort(tokens[index + 1]);
          if (parsed == null) return fail("端口必须是 1–65535");
          port = parsed;
          index += 2;
          continue;
        }
        if (token.startsWith("-p") && token.length() > 2) {
          Integer parsed = parsePort(token.substring(2));
          if (parsed == null) return fail("端口必须是 1–65535");
          port = parsed;
          index++;
          continue;
        }
        if (token.equals("-l") && index + 1 < tokens.length) {
          user = tokens[index + 1];
          index += 2;
          continue;
        }
        if (token.equals("-o")) { index += 2; continue; }
        if (token.startsWith("-")) { index++; continue; }
        remotePhase = true;
      }
      if (!targetSeen) {
        if (token.equals("-p") || token.equals("-P")) {
          if (index + 1 >= tokens.length) return fail("-p 后面需要端口号");
          Integer parsed = parsePort(tokens[index + 1]);
          if (parsed == null) return fail("端口必须是 1–65535");
          port = parsed;
          index += 2;
          continue;
        }
        if (token.startsWith("-p") && token.length() > 2) {
          Integer parsed = parsePort(token.substring(2));
          if (parsed == null) return fail("端口必须是 1–65535");
          port = parsed;
          index++;
          continue;
        }
        if (token.equals("-l")) {
          if (index + 1 >= tokens.length) return fail("-l 后面需要用户名");
          user = tokens[index + 1];
          index += 2;
          continue;
        }
        if (token.equals("-o")) { // option:value pairs are accepted but ignored
          index += 2;
          continue;
        }
        if (token.startsWith("-")) { // other single options are ignored
          index++;
          continue;
        }
        // target token: [user@]host[:port]
        String target = token;
        int at = target.lastIndexOf('@');
        if (at >= 0) {
          user = target.substring(0, at);
          target = target.substring(at + 1);
        }
        if (target.startsWith("[")) { // [ipv6]:port
          int close = target.indexOf(']');
          if (close < 0) return fail("IPv6 地址缺少 ]");
          String after = target.substring(close + 1);
          if (after.startsWith(":")) {
            Integer parsed = parsePort(after.substring(1));
            if (parsed == null) return fail("端口必须是 1–65535");
            port = parsed;
          }
          target = target.substring(1, close);
        } else {
          int colon = target.lastIndexOf(':');
          if (colon > 0 && target.indexOf(':') == colon) { // single colon = host:port
            Integer parsed = parsePort(target.substring(colon + 1));
            if (parsed != null) {
              port = parsed;
              target = target.substring(0, colon);
            }
          }
        }
        host = target;
        targetSeen = true;
        index++;
        continue;
      }
      if (remote.length() > 0) remote.append(' ');
      remote.append(token);
      index++;
    }
    if (!targetSeen) return fail("缺少主机地址，例如 ssh user@192.168.1.10");
    if (user.isEmpty()) return fail("缺少用户名，例如 ssh user@192.168.1.10");
    if (!isHost(host)) return fail("主机地址不合法：" + host);
    if (port < 1 || port > 65535) return fail("端口必须是 1–65535");
    return new TerminalCommand(user, host, port, remote.toString(), null);
  }

  private static Integer parsePort(String text) {
    try {
      int value = Integer.parseInt(text.trim());
      return value >= 1 && value <= 65535 ? value : null;
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }

  static boolean isHost(String host) {
    if (host == null || host.isEmpty() || host.length() > 253) return false;
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ':';
      if (!ok) return false;
    }
    return true;
  }

  /** Stable credential key: user@host:port, lowercase, no secrets. */
  public String credentialKey() {
    return user.toLowerCase(java.util.Locale.ROOT) + "@" + host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
  }
}
