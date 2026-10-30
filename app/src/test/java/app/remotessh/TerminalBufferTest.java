package app.remotessh;

import static org.junit.Assert.*;
import org.junit.Test;

public class TerminalBufferTest {
  @Test public void ansiSequencesAndCarriageReturnsAreStripped() {
    TerminalBuffer b = new TerminalBuffer();
    b.append("\u001B[1;32mok\u001B[0m\r\ndone\r".getBytes(java.nio.charset.StandardCharsets.UTF_8), 999);
    assertEquals("ok\ndone", b.text());
  }

  @Test public void backspaceErasesPreviousChar() {
    TerminalBuffer b = new TerminalBuffer();
    b.append("ab\b\bc".getBytes(java.nio.charset.StandardCharsets.UTF_8), 999);
    assertEquals("c", b.text());
  }

  @Test public void longOutputIsCapped() {
    TerminalBuffer b = new TerminalBuffer();
    StringBuilder chunk = new StringBuilder();
    for (int i = 0; i < 1000; i++) chunk.append("0123456789");
    for (int i = 0; i < 40; i++) b.append(chunk.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 999999);
    assertTrue(b.text().length() <= 64 * 1024 + 10);
    assertTrue(b.text().endsWith(chunk.toString()));
  }

  @Test public void commandHistoryIsMostRecentFirstAndDeduped() {
    TerminalBuffer b = new TerminalBuffer();
    b.record("ps aux");
    b.record("tail -f x");
    b.record("ps aux");
    assertEquals(2, b.history.size());
    assertEquals("ps aux", b.history.getFirst());
    assertEquals("tail -f x", b.history.getLast());
    b.record("   ");
    assertEquals(2, b.history.size());
  }
}
