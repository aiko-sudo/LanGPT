package app.remotessh;
import java.util.Locale;
public class TerminalTextRegression {
  static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  public static void main(String[] args) {
    String[] keys = {"banner", "rebuilt", "switched", "queue", "help", "saved", "using", "usage", "forgot", "password", "connecting", "failed", "disconnected", "closed"};
    for (String key : keys) {
      String[] values = key.equals("switched") || key.equals("using") || key.equals("forgot") || key.equals("connecting") ? new String[]{"u@主机:22"} : new String[0];
      String en = TerminalText.text(Locale.ENGLISH, key, values);
      String zh = TerminalText.text(Locale.CHINESE, key, values);
      check(!en.equals(zh));
      if (values.length > 0) check(en.contains(values[0]) && zh.contains(values[0]));
    }
    for (String command : new String[]{"/help", "/saved", "/forget user@host", "exit"}) check(TerminalText.text(Locale.ENGLISH, "help").contains(command));
    String[] commands = {"oops", "ssh", "ssh -p", "ssh -p 0 user@host", "ssh -l", "ssh user@[broken", "ssh host"};
    for (String command : commands) {
      String error = TerminalCommand.parse(command, "", 22).error;
      check(error != null);
      check(!TerminalText.error(Locale.ENGLISH, error).equals(error));
      check(TerminalText.error(Locale.CHINESE, error).equals(error));
    }
    check(TerminalText.error(Locale.ENGLISH, "主机地址不合法：bad!主机").equals("Invalid host: bad!主机"));
    check(TerminalText.error(Locale.ENGLISH, "unknown").equals("unknown"));
    check(TerminalText.error(Locale.ENGLISH, null) == null);
    System.out.println("TerminalText bilingual behavior PASS");
  }
}
