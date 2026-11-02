package app.remotessh;

import java.util.Locale;

/** Presentation of app-owned connection states; never translate remote data. */
final class ConnectionDisplay {
  private ConnectionDisplay() {}

  static String state(String value, Locale locale) {
    if (value == null || locale == null || !"en".equals(locale.getLanguage())) return value;
    boolean ellipsis = value.endsWith("…");
    String key = ellipsis ? value.substring(0, value.length() - 1) : value;
    String translated;
    switch (key) {
      case "未连接": translated = "Disconnected"; break;
      case "正在连接": translated = "Connecting"; break;
      case "已连接": translated = "Connected"; break;
      case "SSH 连接与认证": translated = "SSH connection and authentication"; break;
      case "连接主机（TCP）": translated = "Connecting to host (TCP)"; break;
      case "直连不通，尝试备用地址": translated = "Direct connection failed, trying alternate address"; break;
      case "尝试内置 Tailscale 连接": translated = "Trying built-in Tailscale connection"; break;
      case "SSH 握手与认证": translated = "SSH handshake and authentication"; break;
      case "检查远端环境（SSH 已认证）": translated = "Checking remote environment (SSH authenticated)"; break;
      case "检查 SFTP 与家目录": translated = "Checking SFTP and home directory"; break;
      case "准备远端持久会话（需要 Python 3）": translated = "Preparing persistent remote session (Python 3 required)"; break;
      case "启动远端程序": translated = "Starting remote program"; break;
      case "远端协议握手 / 通信": translated = "Remote protocol handshake / communication"; break;
      case "初始化远端程序": translated = "Initializing remote program"; break;
      case "正在同步离线消息": translated = "Synchronizing offline messages"; break;
      case "正在获取对话历史": translated = "Loading conversation history"; break;
      case "正在检查对话状态": translated = "Checking conversation status"; break;
      case "正在接续对话": translated = "Resuming conversation"; break;
      case "正在刷新对话历史": translated = "Refreshing conversation history"; break;
      case "正在恢复对话": translated = "Restoring conversation"; break;
      case "正在重连": translated = "Reconnecting"; break;
      default: return value;
    }
    return translated + (ellipsis ? "…" : "");
  }
}
