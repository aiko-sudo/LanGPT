package app.remotessh;

import java.util.Locale;

public final class ConnectionDisplayRegression {
  public static void main(String[] args) {
    String[][] states = {
      {"正在连接…", "Connecting…"},
      {"SSH 连接与认证", "SSH connection and authentication"},
      {"连接主机（TCP）", "Connecting to host (TCP)"},
      {"直连不通，尝试备用地址", "Direct connection failed, trying alternate address"},
      {"尝试内置 Tailscale 连接", "Trying built-in Tailscale connection"},
      {"SSH 握手与认证", "SSH handshake and authentication"},
      {"检查远端环境（SSH 已认证）", "Checking remote environment (SSH authenticated)"},
      {"检查 SFTP 与家目录", "Checking SFTP and home directory"},
      {"准备远端持久会话（需要 Python 3）", "Preparing persistent remote session (Python 3 required)"},
      {"启动远端程序", "Starting remote program"},
      {"远端协议握手 / 通信", "Remote protocol handshake / communication"},
      {"正在重连…", "Reconnecting…"},
      {"正在同步离线消息…", "Synchronizing offline messages…"},
      {"正在获取对话历史…", "Loading conversation history…"},
      {"正在检查对话状态…", "Checking conversation status…"},
      {"正在接续对话…", "Resuming conversation…"},
      {"正在刷新对话历史…", "Refreshing conversation history…"}
    };
    for (String[] row : states) {
      check(row[1], ConnectionDisplay.state(row[0], Locale.ENGLISH));
      check(row[0], ConnectionDisplay.state(row[0], Locale.CHINESE));
      if (!row[0].endsWith("…")) check(row[1] + "…", ConnectionDisplay.state(row[0] + "…", Locale.ENGLISH));
    }
    String remote = "远端协议握手 / 通信 · 用户内容…";
    check(remote, ConnectionDisplay.state(remote, Locale.ENGLISH));
    check("SSH 握手与认证", ConnectionDisplay.state("SSH 握手与认证", null));
    if (ConnectionDisplay.state(null, Locale.ENGLISH) != null) throw new AssertionError("null");
    System.out.println("ConnectionDisplay regression passed: " + states.length + " bilingual states, unknown and null preservation");
  }
  private static void check(String expected, String actual) {
    if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
  }
}
