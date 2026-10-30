package app.remotessh;

import java.util.LinkedList;
import java.util.regex.Pattern;

/** Scrollback + ANSI handling for the simple PTY terminal. Pure logic for unit tests. */
public final class TerminalBuffer {
  private static final int CAPACITY = 64 * 1024;
  private static final Pattern ANSI =
      Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|\u001B[()][0-9A-B]|\r");

  private final StringBuilder raw = new StringBuilder();
  public final LinkedList<String> history = new LinkedList<>();

  public void append(byte[] data, int length) {
    int n = Math.min(Math.max(length, 0), data.length);
    if (n == 0) return;
    raw.append(new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8));
    if (raw.length() > CAPACITY * 2) raw.delete(0, raw.length() - CAPACITY);
  }

  /** Strips ANSI control sequences and applies backspace erasure for display. */
  public String text() {
    String cleaned = ANSI.matcher(raw).replaceAll("");
    StringBuilder out = new StringBuilder(cleaned.length());
    for (int i = 0; i < cleaned.length(); i++) {
      char c = cleaned.charAt(i);
      if (c == '\b') {
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) != '\n') out.deleteCharAt(len - 1);
      } else out.append(c);
    }
    int start = Math.max(0, out.length() - CAPACITY);
    return out.substring(start);
  }

  public void record(String command) {
    if (command == null) return;
    String trimmed = command.trim();
    if (trimmed.isEmpty()) return;
    history.remove(trimmed);
    history.addFirst(trimmed);
    if (history.size() > 20) history.removeLast();
  }

  public void clear() {
    raw.setLength(0);
    history.clear();
  }
}
