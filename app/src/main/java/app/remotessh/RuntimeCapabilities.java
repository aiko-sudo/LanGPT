package app.remotessh;

import org.json.JSONObject;

/** Connection-scoped additive handshake; older servers preserve existing behavior. */
final class RuntimeCapabilities {
  private JSONObject values;
  void reset() { values = null; }
  void apply(JSONObject session) { values = session.optJSONObject("capabilities"); }
  boolean supports(String key) { return values == null || values.optBoolean(key, true); }
  String reason() {
    return values == null ? "远端未提供能力说明" : values.optString("reason", "此运行时不支持该功能");
  }
}
