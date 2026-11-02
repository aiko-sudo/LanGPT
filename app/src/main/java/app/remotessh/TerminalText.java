package app.remotessh;

import java.util.Locale;

/** Text owned by the local terminal, never remote output or commands. */
final class TerminalText {
  static String text(Locale locale, String key, String... args) {
    boolean en = locale != null && "en".equals(locale.getLanguage());
    String s;
    switch (key) {
      case "banner": s = en ? "Supports -p port\n/help command help\n/saved saved credentials\n/forget user@host delete credentials\nEnter ssh user@host to connect\n-------------------------\n" : "支持 -p 端口\n/help 命令帮助\n/saved 已存凭证\n/forget user@host 删除凭证\n输入 ssh user@host 连接\n-------------------------\n"; break;
      case "rebuilt": s = en ? "[Page recreated; session not restored. Enter the ssh command again]\n\n" : "[页面已重建，会话未恢复，请重新输入 ssh 命令]\n\n"; break;
      case "switched": s = en ? "\n[Switched to %s]\n" : "\n[已切换到 %s]\n"; break;
      case "queue": s = en ? "\n[Input queue is full; input not sent. Try again later]\n" : "\n[输入队列已满，本次输入未发送，请稍后重试]\n"; break;
      case "help": s = en ? "/help help  /saved saved credentials (matched automatically by ssh)\n/forget user@host delete credentials  exit disconnect and exit\n" : "/help 帮助　/saved 已存凭证列表（用 ssh 命令自动匹配）\n/forget user@host 删除该主机凭证　exit 断开并退出\n"; break;
      case "saved": s = en ? "Saved credentials are matched by host; enter ssh user@host to use them.\n" : "已存凭证按主机自动匹配；输入 ssh user@host 即可使用。\n"; break;
      case "using": s = en ? "\nConnecting with saved credentials to %s\n" : "\n使用已保存的凭证连接 %s\n"; break;
      case "usage": s = en ? "Usage: /forget user@host[:port]\n" : "用法：/forget user@host[:port]\n"; break;
      case "forgot": s = en ? "Deleted credentials and host history for %s\n" : "已删除 %s 的凭证和主机历史\n"; break;
      case "password": s = en ? "✗ Password cannot be empty\n" : "✗ 密码不能为空\n"; break;
      case "connecting": s = en ? "Connecting to %s …\n" : "连接 %s …\n"; break;
      case "failed": s = en ? "\n[SSH connection ended or failed]\n" : "\n[SSH 连接结束或失败]\n"; break;
      case "disconnected": s = en ? "\n[Connection closed; enter ssh user@host]\n" : "\n[连接已断开，请输入 ssh user@host]\n"; break;
      case "closed": s = en ? "\n[Disconnected; enter ssh user@host]\n" : "\n[已断开，请输入 ssh user@host]\n"; break;
      default: throw new IllegalArgumentException(key);
    }
    return args.length == 0 ? s : String.format(Locale.ROOT, s, (Object[]) args);
  }

  static String error(Locale locale, String error) {
    if (error == null || locale == null || !"en".equals(locale.getLanguage())) return error;
    switch (error) {
      case "输入 ssh user@host 连接主机": return "Enter ssh user@host to connect";
      case "只支持 ssh 命令，例如 ssh user@192.168.1.10": return "Only ssh commands are supported, e.g. ssh user@192.168.1.10";
      case "-p 后面需要端口号": return "-p requires a port";
      case "端口必须是 1–65535": return "Port must be 1–65535";
      case "-l 后面需要用户名": return "-l requires a username";
      case "IPv6 地址缺少 ]": return "IPv6 address is missing ]";
      case "缺少主机地址，例如 ssh user@192.168.1.10": return "Missing host, e.g. ssh user@192.168.1.10";
      case "缺少用户名，例如 ssh user@192.168.1.10": return "Missing username, e.g. ssh user@192.168.1.10";
      default:
        String prefix = "主机地址不合法：";
        return error.startsWith(prefix) ? "Invalid host: " + error.substring(prefix.length()) : error;
    }
  }
}
