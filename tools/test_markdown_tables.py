#!/usr/bin/env python3
"""Markdown table rendering tests: compile the real Markdown.java against
minimal android.text stubs and verify aligned GFM table output."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java/app/remotessh"

STUBS = {
    "android/text/Spanned.java": """package android.text;
public interface Spanned { int SPAN_EXCLUSIVE_EXCLUSIVE = 33; }
""",
    "android/text/SpannableStringBuilder.java": """package android.text;
public class SpannableStringBuilder implements CharSequence {
  private final StringBuilder body = new StringBuilder();
  public SpannableStringBuilder append(CharSequence text) { if (text != null) body.append(text); return this; }
  public SpannableStringBuilder append(char c) { body.append(c); return this; }
  public SpannableStringBuilder append(CharSequence text, int start, int end) {
    if (text != null) body.append(text, start, end); return this; }
  public void setSpan(Object what, int start, int end, int flags) {}
  public int length() { return body.length(); }
  public char charAt(int index) { return body.charAt(index); }
  public CharSequence subSequence(int start, int end) { return body.substring(start, end); }
  @Override public String toString() { return body.toString(); }
}
""",
    "android/text/style/StyleSpan.java": """package android.text.style;
public class StyleSpan { public StyleSpan(int style) {} }
""",
    "android/text/style/TypefaceSpan.java": """package android.text.style;
public class TypefaceSpan { public TypefaceSpan(String family) {} }
""",
    "android/text/style/RelativeSizeSpan.java": """package android.text.style;
public class RelativeSizeSpan { public RelativeSizeSpan(float proportion) {} }
""",
    "android/text/style/BackgroundColorSpan.java": """package android.text.style;
public class BackgroundColorSpan { public BackgroundColorSpan(int color) {} }
""",
    "android/text/style/ForegroundColorSpan.java": """package android.text.style;
public class ForegroundColorSpan { public ForegroundColorSpan(int color) {} }
""",
    "android/text/style/URLSpan.java": """package android.text.style;
public class URLSpan { public URLSpan(String url) {} }
""",
    "android/text/style/ClickableSpan.java": """package android.text.style;
public abstract class ClickableSpan {
  public abstract void onClick(android.view.View widget);
}
""",
    "android/view/View.java": """package android.view;
public class View {}
""",
    "android/graphics/Typeface.java": """package android.graphics;
public class Typeface { public static final int BOLD = 1; }
""",
    "app/remotessh/Harness.java": """package app.remotessh;
public class Harness {
  public static void main(String[] args) {
    // helpers
    if (!Markdown.isTableLine("| a | b |")) throw new AssertionError("table line missed");
    if (Markdown.isTableLine("plain text | one pipe")) throw new AssertionError("one pipe is not a table");
    if (!Markdown.isDelimiterRow("| --- | :---: | ---: |")) throw new AssertionError("delimiter row missed");
    if (Markdown.isDelimiterRow("| a | --- |")) throw new AssertionError("body row is not a delimiter");
    String[] cells = Markdown.splitRow("| 名称 | a\\\\|b | tail |");
    if (cells.length != 3 || !cells[1].equals("a|b")) throw new AssertionError("escaped pipe split: " + java.util.Arrays.toString(cells));
    if (Markdown.displayWidth("中文") != 4 || Markdown.displayWidth("ab") != 2) throw new AssertionError("cjk width");
    if (!Markdown.clip("01234567890123456789").endsWith("\\u2026") || Markdown.displayWidth(Markdown.clip("01234567890123456789")) > Markdown.COLUMN_CAP)
      throw new AssertionError("clip cap");

    String doc = "# Title\\n\\n| 名称 | 数量 | 备注 |\\n| --- | ---: | :---: |\\n| 苹果 | 12 | fresh |\\n| 香蕉牛奶 | 7 | ok |\\n\\nafter table";
    CharSequence rendered = Markdown.render(doc, 0xFF888888, 0xFFEEEEEE);
    String text = rendered.toString();
    String[] lines = text.split("\\n", -1);
    boolean header = false, ruler = false, after = false;
    for (String line : lines) {
      if (line.contains("名称") && line.contains("│")) header = true;
      if (line.contains("─") && line.contains("┼")) ruler = true;
      if (line.equals("after table")) after = true;
      if (line.contains("|") && line.contains("苹果") && line.contains("│"))
        throw new AssertionError("raw pipes leaked into table row: " + line);
    }
    if (!header) throw new AssertionError("no aligned header row:\\n" + text);
    if (!ruler) throw new AssertionError("no ruler under header:\\n" + text);
    if (!after) throw new AssertionError("content after table lost:\\n" + text);
    // right-aligned numeric column: both numbers padded to identical width
    String apple = null, banana = null;
    for (String line : lines) {
      if (line.contains("苹果")) apple = line;
      if (line.contains("香蕉")) banana = line;
    }
    if (apple == null || banana == null) throw new AssertionError("rows lost:\\n" + text);
    String appleTrim = apple.trim(), bananaTrim = banana.trim();
    int appleDash = appleTrim.indexOf('│'), bananaDash = bananaTrim.indexOf('│');
    // Untrimmed: the renderer pads every cell to the column width, so the text
    // before the separator must occupy the same display width on every row.
    int appleWidth = appleDash < 0 ? -1 : Markdown.displayWidth(appleTrim.substring(0, appleDash));
    int bananaWidth = bananaDash < 0 ? -1 : Markdown.displayWidth(bananaTrim.substring(0, bananaDash));
    if (appleWidth != bananaWidth)
      throw new AssertionError("cjk cell widths misaligned:\\n" + apple + "\\n" + banana);
    System.out.println("markdown-tables: 7/7 OK");
  }
}
""",
}


def main() -> None:
    # default TMPDIR is a local disk: NFS dirs suffer stale-listing races after javac
    work = Path(tempfile.mkdtemp(prefix="md-tables-"))
    try:
        shutil.copy(SRC / "Markdown.java", work / "Markdown.java.tmp")
        (work / "app_src").mkdir()
        shutil.move(str(work / "Markdown.java.tmp"), work / "app_src" / "Markdown.java")
        for name, body in STUBS.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", str(classes),
             str(work / "app_src" / "Markdown.java")]
            + [str(work / n) for n in STUBS if n.startswith(("android/", "app/"))],
            check=True, cwd=work)
        out = None
        for attempt in range(3):
            try:
                out = subprocess.run(
                    ["java", "-cp", str(classes), "app.remotessh.Harness"],
                    check=True, capture_output=True, text=True, cwd=work,
                    env={**__import__("os").environ, "LC_ALL": "C.UTF-8"})
                break
            except subprocess.CalledProcessError as attempt_failure:
                if attempt == 2 or "ClassNotFoundException" not in (attempt_failure.stderr or ""):
                    print(attempt_failure.stdout or "", attempt_failure.stderr or "", file=sys.stderr)
                    raise
                __import__("time").sleep(1)
        if "7/7 OK" not in out.stdout:
            print(out.stdout, out.stderr, file=sys.stderr)
            raise AssertionError("harness did not report success")
        print("markdown tables: 7/7 OK")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
