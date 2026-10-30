package app.remotessh;

import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.regex.*;

/** Local text rendering only: never executes HTML, scripts, or remote URLs. */
public final class Markdown {
  /** Table columns are clipped beyond this display width so rows still fit a phone. */
  static final int COLUMN_CAP = 16;

  public static CharSequence render(String source, int muted, int panel) {
    return render(source, muted, panel, null);
  }

  public static CharSequence render(String source, int muted, int panel, java.util.function.Consumer<String> open) {
    return render(source, muted, panel, open, null);
  }

  public static CharSequence render(String source, int muted, int panel, java.util.function.Consumer<String> open, java.util.function.Consumer<String> copy) {
    SpannableStringBuilder out = new SpannableStringBuilder();
    boolean code = false;
    String[] lines = source.split("\n", -1);
    for (int n = 0; n < lines.length; n++) {
      String line = lines[n];
      if (line.startsWith("```")) {
        code = !code;
        if (n > 0 && out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        continue;
      }
      int start = out.length();
      if (code) {
        out.append(line);
        out.setSpan(
            new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(
            new BackgroundColorSpan(panel), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else if (line.matches("^#{1,6} .*")) {
        int level = line.indexOf(' ');
        out.append(line.substring(level + 1));
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), 33);
        out.setSpan(new RelativeSizeSpan(level == 1 ? 1.35f : 1.14f), start, out.length(), 33);
      } else if (isTableLine(line) && n + 1 < lines.length && isDelimiterRow(lines[n + 1])) {
        // GFM table block: aligned monospace grid instead of raw pipe soup.
        int k = n + 1;
        while (k < lines.length && isTableLine(lines[k])) k++;
        renderTable(out, lines, n, k, muted, panel);
        n = k - 1;
      } else {
        if (line.startsWith("- ") || line.startsWith("* ")) line = "•  " + line.substring(2);
        if (line.startsWith("> ")) {
          line = "│ " + line.substring(2);
          out.append(line);
          out.setSpan(new ForegroundColorSpan(muted), start, out.length(), 33);
        } else inline(out, line, open, copy);
      }
      if (n < lines.length - 1) out.append('\n');
    }
    return out;
  }

  /** A markdown line that looks like a table row: at least two pipes and fenced by one. */
  static boolean isTableLine(String line) {
    String trimmed = line.trim();
    int pipes = 0;
    for (int i = 0; i < trimmed.length(); i++) if (trimmed.charAt(i) == '|') pipes++;
    return pipes >= 2 && (trimmed.startsWith("|") || trimmed.endsWith("|"));
  }

  /** The row under a header made only of ---, :--:, --: cells. */
  static boolean isDelimiterRow(String line) {
    String[] cells = splitRow(line);
    if (cells.length == 0) return false;
    for (String cell : cells)
      if (!cell.matches(":?-{1,}:?")) return false;
    return true;
  }

  /** Split on unescaped pipes, dropping the outer fence cells. */
  static String[] splitRow(String line) {
    String trimmed = line.trim();
    String guarded = trimmed.replace("\\|", "\u0000");
    if (guarded.startsWith("|")) guarded = guarded.substring(1);
    if (guarded.endsWith("|")) guarded = guarded.substring(0, guarded.length() - 1);
    String[] raw = guarded.split("\\|", -1);
    String[] cells = new String[raw.length];
    for (int i = 0; i < raw.length; i++) cells[i] = raw[i].replace("\u0000", "|").trim();
    return cells;
  }

  /** Monospace display width: CJK characters occupy two cells. */
  static int displayWidth(String text) {
    int width = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean wide = (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)
          || (c >= 0xFF00 && c <= 0xFF60) || (c >= 0xFFE0 && c <= 0xFFE6);
      width += wide ? 2 : 1;
    }
    return width;
  }

  /** Cell text for the grid: inline markers stripped, clipped to the column cap. */
  static String clip(String cell) {
    String plain = cell.replace("**", "").replace("`", "");
    if (displayWidth(plain) <= COLUMN_CAP) return plain;
    StringBuilder kept = new StringBuilder();
    int width = 0;
    for (int i = 0; i < plain.length(); i++) {
      String one = plain.substring(i, i + 1);
      int w = displayWidth(one);
      if (width + w > COLUMN_CAP - 1) break;
      kept.append(one);
      width += w;
    }
    return kept.append('…').toString();
  }

  private static String pad(String cell, int width, char align) {
    int free = width - displayWidth(cell);
    if (free <= 0) return cell;
    StringBuilder spaces = new StringBuilder();
    for (int i = 0; i < free; i++) spaces.append(' ');
    if (align == 'r') return spaces + cell;
    if (align == 'c') {
      int left = free / 2;
      return spaces.substring(0, left) + cell + spaces.substring(0, free - left);
    }
    return cell + spaces;
  }

  private static void renderTable(SpannableStringBuilder out, String[] lines, int from, int to, int muted, int panel) {
    String[][] rows = new String[to - from][];
    char[] aligns = null;
    int filled = 0;
    for (int i = from; i < to; i++) {
      if (i == from + 1) {
        String[] delimiters = splitRow(lines[i]);
        aligns = new char[delimiters.length];
        for (int c = 0; c < delimiters.length; c++) {
          String d = delimiters[c];
          aligns[c] = d.length() > 1 && d.startsWith(":") && d.endsWith(":") ? 'c'
              : d.endsWith(":") ? 'r' : 'l';
        }
        continue;
      }
      rows[filled++] = splitRow(lines[i]);
    }
    int bodyRows = filled;
    int cols = 0;
    for (int i = 0; i < bodyRows; i++) cols = Math.max(cols, rows[i].length);
    int[] widths = new int[cols];
    for (int i = 0; i < bodyRows; i++)
      for (int c = 0; c < rows[i].length; c++)
        widths[c] = Math.max(widths[c], displayWidth(clip(rows[i][c])));

    int blockStart = out.length();
    for (int i = 0; i < bodyRows; i++) {
      if (i > 0) out.append('\n');
      int rowStart = out.length();
      for (int c = 0; c < cols; c++) {
        if (c > 0) out.append(" │ ");
        char align = aligns == null || c >= aligns.length ? 'l' : aligns[c];
        String cell = c < rows[i].length ? clip(rows[i][c]) : "";
        out.append(pad(cell, widths[c], align));
      }
      if (i == 0) out.setSpan(new StyleSpan(Typeface.BOLD), rowStart, out.length(), 33);
    }
    // Ruler under the header, muted, matching the computed column widths.
    StringBuilder ruler = new StringBuilder();
    for (int c = 0; c < cols; c++) {
      if (c > 0) ruler.append('┼');
      for (int w = 0; w < widths[c] + 2; w++) ruler.append('─');
    }
    int rulerStart = out.length();
    out.append('\n').append(ruler);
    out.setSpan(new ForegroundColorSpan(muted), rulerStart, out.length(), 33);
    out.setSpan(new TypefaceSpan("monospace"), blockStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    out.setSpan(new BackgroundColorSpan(panel), blockStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static void inline(SpannableStringBuilder out, String line, java.util.function.Consumer<String> open, java.util.function.Consumer<String> copy) {
    Matcher m =
        Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)\\]\\(([^\\s)]+)\\)")
            .matcher(line);
    int last = 0;
    while (m.find()) {
      out.append(line, last, m.start());
      int s = out.length();
      if (m.group(1) != null) {
        out.append(m.group(1));
        out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), 33);
      } else if (m.group(2) != null) {
        String value = m.group(2);
        out.append(value);
        out.setSpan(new TypefaceSpan("monospace"), s, out.length(), 33);
        if (copy != null) out.setSpan(new ClickableSpan() {
          @Override public void onClick(android.view.View widget) { copy.accept(value); }
        }, s, out.length(), 33);
      } else {
        out.append(m.group(3));
        String url = m.group(4);
        out.setSpan(open == null ? new URLSpan(url) : new ClickableSpan() {
          @Override public void onClick(android.view.View widget) { open.accept(url); }
        }, s, out.length(), 33);
      }
      last = m.end();
    }
    out.append(line, last, line.length());
  }
}
