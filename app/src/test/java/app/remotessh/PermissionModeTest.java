package app.remotessh;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
public class PermissionModeTest {
  @Test public void labelsFollowLocaleWithoutChangingProtocol() {
    String[] chinese = {"沿用远端权限", "按需审批", "只读", "完全访问"};
    String[] english = {"Use remote permissions", "Approve as needed", "Read only", "Full access"};
    PermissionMode[] modes = PermissionMode.values();
    for (int i = 0; i < modes.length; i++) {
      assertEquals(chinese[i], modes[i].label(java.util.Locale.CHINESE));
      assertEquals(english[i], modes[i].label(java.util.Locale.US));
      assertEquals(chinese[i], modes[i].label(null));
    }
  }
  @Test public void mapsStartAndTurnPoliciesWithoutChangingInheritedPolicy() throws Exception {
    JSONObject inherited = new JSONObject(); PermissionMode.INHERIT.applyTurn(inherited);
    assertEquals(0, inherited.length());
    assertEquals("workspace-write", PermissionMode.DEFAULT.startParams().getString("sandbox"));
    JSONObject read = new JSONObject(); PermissionMode.READ_ONLY.applyTurn(read);
    assertEquals("never", read.getString("approvalPolicy"));
    assertEquals("readOnly", read.getJSONObject("sandboxPolicy").getString("type"));
    JSONObject full = new JSONObject(); PermissionMode.FULL.applyTurn(full);
    assertEquals("dangerFullAccess", full.getJSONObject("sandboxPolicy").getString("type"));
  }
}
