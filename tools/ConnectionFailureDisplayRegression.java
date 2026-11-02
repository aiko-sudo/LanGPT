package app.remotessh;

import java.io.EOFException;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class ConnectionFailureDisplayRegression {
  static void has(String s, String part) { if (!s.contains(part)) throw new AssertionError("Missing " + part + " in " + s); }
  static void eq(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " got " + actual); }
  static String en(Throwable e, String p, int code, String stderr) { return ConnectionFailureDisplay.describe(e, p, code, stderr, Locale.ENGLISH); }
  public static void main(String[] args) {
    Throwable e = new CompletionException(new EOFException("远端异常 连接失败 · 原样\n原因：自定义"));
    String p = "远端协议握手 / 通信";
    String remote = "远端诊断输出（警告不一定是失败原因）：\n远端自定义 · 尚未进入 SSH 身份认证";
    String normal = en(e, p, -1, remote);
    has(normal, "Connection failed · Remote protocol handshake / communication");
    has(normal, "EOFException: 远端异常 连接失败 · 原样\n原因：自定义");
    has(normal, remote);
    has(en(e, p, -1, "responses_websocket HTTP 403"), "received HTTP 403");
    has(en(e, "连接主机（TCP）", -1, ""), "SSH authentication has not yet started");
    has(en(new Exception("UnknownHostException"), p, -1, ""), "SSH authentication has not yet started");
    has(en(e, "SSH 握手与认证", -1, ""), "If using Tailscale");
    has(en(e, p, 127, "python3 missing"), "Install Python 3.8");
    has(en(e, p, 127, "codex missing"), "command -v codex");
    has(en(e, p, 3, ""), "Remote exit code: 3");
    String unknown = "远端自定义阶段";
    has(en(e, unknown, -1, ""), "Connection failed · " + unknown);
    eq(ConnectionFailure.describe(e, p, 127, remote), ConnectionFailureDisplay.describe(e, p, 127, remote, Locale.CHINESE));
    eq(ConnectionFailure.describe(e, p, 127, remote), ConnectionFailureDisplay.describe(e, p, 127, remote, null));
    eq(normal, en(e, p, -1, remote));
    System.out.println("ConnectionFailureDisplay regression passed: categories, remote preservation, locale switching");
  }
}