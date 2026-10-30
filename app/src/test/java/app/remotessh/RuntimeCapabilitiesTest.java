package app.remotessh;
import static org.junit.Assert.*;
import org.junit.Test;
public class RuntimeCapabilitiesTest {
  @Test public void oldServerPreservesCodexAndReconnectClearsCapabilities() {
    RuntimeCapabilities c = new RuntimeCapabilities();
    assertTrue(c.supports("approval"));
    c.apply(Json.obj("capabilities", Json.obj("approval", false, "models", false, "reason", "unsupported")));
    assertFalse(c.supports("approval"));
    assertFalse(c.supports("models"));
    assertEquals("unsupported", c.reason());
    c.reset();
    assertTrue(c.supports("approval"));
    c.apply(Json.obj("capabilities", Json.obj("runtime", "codex", "approval", true)));
    assertTrue(c.supports("approval"));
  }
}
