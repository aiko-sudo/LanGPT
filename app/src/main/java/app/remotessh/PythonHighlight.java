package app.remotessh;

import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

/**
 * Pure-Java Python syntax highlighting for the SFTP preview page.
 *
 * tokenize() is a plain single-pass scanner with no Android dependencies so it
 * can be unit-tested on the JVM; render() only maps token spans onto colors.
 * The scanner deliberately stays small: keywords, builtins, decorators,
 * numbers (incl. 0x/0o/0b/underscores), comments, and strings with r/b/f/rb/br
 * prefixes and triple quotes. It is a preview aid, not a parser: unknown or
 * malformed text simply stays unstyled instead of failing.
 */
final class PythonHighlight {

  static final int KEYWORD = 0, BUILTIN = 1, STRING = 2, COMMENT = 3, NUMBER = 4,
      DECORATOR = 5, DEFNAME = 6, SELF = 7;

  /** Palette supplied by the caller so dark/light themes stay consistent. */
  static final class Palette {
    final int keyword, builtin, string, comment, number, decorator, defname, self;
    Palette(int keyword,int builtin,int string,int comment,int number,int decorator,int defname,int self){
      this.keyword=keyword;this.builtin=builtin;this.string=string;this.comment=comment;
      this.number=number;this.decorator=decorator;this.defname=defname;this.self=self;
    }
  }

  static final class Token {
    final int start, end, kind;
    Token(int start,int end,int kind){this.start=start;this.end=end;this.kind=kind;}
  }

  private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
      "False","None","True","and","as","assert","async","await","break","class","continue",
      "def","del","elif","else","except","finally","for","from","global","if","import","in",
      "is","lambda","nonlocal","not","or","pass","raise","return","try","while","with","yield",
      "match","case"));
  private static final java.util.Set<String> BUILTINS = new java.util.HashSet<>(java.util.Arrays.asList(
      "abs","all","any","bool","bytes","callable","chr","dict","dir","enumerate","eval","filter",
      "float","format","frozenset","getattr","hasattr","hash","hex","id","input","int","isinstance",
      "issubclass","iter","len","list","map","max","min","next","object","oct","open","ord","pow",
      "print","range","repr","reversed","round","set","setattr","slice","sorted","str","sum",
      "super","tuple","type","vars","zip","Exception","ValueError","TypeError","KeyError",
      "IndexError","RuntimeError","StopIteration","OSError","IOError","FileNotFoundError"));

  static boolean isPython(String path) {
    if (path == null) return false;
    return path.toLowerCase(java.util.Locale.ROOT).endsWith(".py");
  }

  /** Single pass over the source; tokens are ordered and non-overlapping. */
  static java.util.List<Token> tokenize(String source) {
    java.util.List<Token> tokens = new java.util.ArrayList<>();
    if (source == null || source.isEmpty()) return tokens;
    int i = 0, n = source.length();
    // Previous significant word (def/class/async def name highlighting).
    String pendingDef = null;
    int atLineStart = 0; // index just after the most recent newline/sep
    while (i < n) {
      char c = source.charAt(i);
      if (c == '#') {
        int end = source.indexOf('\n', i);
        if (end < 0) end = n;
        tokens.add(new Token(i, end, COMMENT));
        i = end;
        continue;
      }
      if (c == '"' || c == '\'') {
        i = scanString(source, i, tokens);
        pendingDef = null;
        continue;
      }
      if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1)))) {
        int end = scanNumber(source, i);
        tokens.add(new Token(i, end, NUMBER));
        i = end;
        pendingDef = null;
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int end = i + 1;
        while (end < n && Character.isJavaIdentifierPart(source.charAt(end))) end++;
        String word = source.substring(i, end);
        if (pendingDef != null) {
          tokens.add(new Token(i, end, DEFNAME));
          pendingDef = null;
        } else if (KEYWORDS.contains(word)) {
          tokens.add(new Token(i, end, KEYWORD));
          if (word.equals("def") || word.equals("class")) pendingDef = word;
        } else if (word.equals("self") || word.equals("cls")) {
          tokens.add(new Token(i, end, SELF));
        } else if (BUILTINS.contains(word)) {
          tokens.add(new Token(i, end, BUILTIN));
        }
        i = end;
        continue;
      }
      if (c == '@' && (i == 0 || source.charAt(i - 1) == '\n' || Character.isWhitespace(source.charAt(i - 1)))) {
        int end = i + 1;
        while (end < n && (Character.isJavaIdentifierPart(source.charAt(end)) || source.charAt(end) == '.')) end++;
        if (end > i + 1) {
          tokens.add(new Token(i, end, DECORATOR));
          i = end;
          continue;
        }
      }
      if (c == '\n' || c == ';' || c == ':') {
        atLineStart = i + 1;
        pendingDef = null;
      } else if (!Character.isWhitespace(c)) {
        // Other operators clear a pending def name only after one word.
      }
      i++;
    }
    return tokens;
  }

  /** Handles prefixes (r, b, f, rb, br…), triple quotes and escapes. */
  private static int scanString(String s, int quoteStart, java.util.List<Token> out) {
    int i = quoteStart;
    char q = s.charAt(i);
    // Triple quote?
    if (i + 2 < s.length() && s.charAt(i + 1) == q && s.charAt(i + 2) == q) {
      int close = s.indexOf(new String(new char[]{q, q, q}), i + 3);
      int end = close < 0 ? s.length() : Math.min(s.length(), close + 3);
      out.add(new Token(quoteStart, end, STRING));
      return end;
    }
    i++;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') { i += 2; continue; }
      if (c == q) { i++; break; }
      if (c == '\n') break; // unterminated single-line string ends at newline
      i++;
    }
    out.add(new Token(quoteStart, Math.min(i, s.length()), STRING));
    return Math.min(i, s.length());
  }

  private static int scanNumber(String s, int start) {
    int i = start;
    if (s.charAt(i) == '0' && i + 1 < s.length()) {
      char base = Character.toLowerCase(s.charAt(i + 1));
      if (base == 'x' || base == 'o' || base == 'b') {
        i += 2;
        while (i < s.length() && (Character.digit(s.charAt(i), 16) >= 0 || s.charAt(i) == '_')) i++;
        return i;
      }
    }
    boolean seenDot = false, seenExp = false;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (Character.isDigit(c) || c == '_') i++;
      else if (c == '.' && !seenDot && !seenExp) { seenDot = true; i++; }
      else if ((c == 'e' || c == 'E') && !seenExp && i + 1 < s.length()
          && (Character.isDigit(s.charAt(i + 1))
              || ((s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-') && i + 2 < s.length()
                  && Character.isDigit(s.charAt(i + 2))))) { seenExp = true; i += 2; }
      else if (c == 'j' || c == 'J') { i++; break; }
      else break;
    }
    return i;
  }

  /** Applies token colors onto a fresh spannable for the preview TextView. */
  static CharSequence render(String source, Palette palette) {
    if (source == null || source.isEmpty()) return source == null ? "" : source;
    java.util.List<Token> tokens = tokenize(source);
    if (tokens.isEmpty()) return source;
    SpannableString spanned = new SpannableString(source);
    for (Token t : tokens) {
      int color;
      switch (t.kind) {
        case KEYWORD: color = palette.keyword; break;
        case BUILTIN: color = palette.builtin; break;
        case STRING: color = palette.string; break;
        case COMMENT: color = palette.comment; break;
        case NUMBER: color = palette.number; break;
        case DECORATOR: color = palette.decorator; break;
        case DEFNAME: color = palette.defname; break;
        case SELF: color = palette.self; break;
        default: continue;
      }
      spanned.setSpan(new ForegroundColorSpan(color), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (t.kind == COMMENT) spanned.setSpan(new StyleSpan(Typeface.ITALIC), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (t.kind == DEFNAME || t.kind == DECORATOR) spanned.setSpan(new StyleSpan(Typeface.BOLD), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return spanned;
  }

  private PythonHighlight() {}
}
