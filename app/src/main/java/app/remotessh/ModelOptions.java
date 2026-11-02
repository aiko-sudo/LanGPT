package app.remotessh;

import java.util.*;
import org.json.*;

final class ModelOptions {
  private static volatile java.util.function.Supplier<Locale> localeProvider = Locale::getDefault;
  static void setLocaleProvider(java.util.function.Supplier<Locale> provider) {
    localeProvider = provider == null ? Locale::getDefault : provider;
  }
  private static boolean chinese() {
    Locale locale = localeProvider.get();
    return locale == null || !"en".equals(locale.getLanguage());
  }
  static String id(JSONObject model) { return model.isNull("model") || model.optString("model").trim().isEmpty()
      ? model.optString("id", "").trim() : model.optString("model").trim(); }
  static List<String> efforts(JSONObject model) {
    List<String> values = new ArrayList<>();
    if (model == null) return values;
    JSONArray supported = model.optJSONArray("supportedReasoningEfforts");
    if (supported != null) for (int i = 0; i < supported.length(); i++) {
      JSONObject entry = supported.optJSONObject(i);
      String effort = entry == null ? supported.optString(i) : entry.optString("reasoningEffort");
      if (!effort.isEmpty() && !values.contains(effort)) values.add(effort);
    }
    return values;
  }
  static String displayName(JSONArray models, String id) {
    if (id.isEmpty()) return chinese() ? "默认模型" : "Default model";
    for (int i = 0; i < models.length(); i++) {
      JSONObject model = models.optJSONObject(i);
      if (model != null && id(model).equals(id) && !model.isNull("displayName")
          && !model.optString("displayName").trim().isEmpty()) return model.optString("displayName");
    }
    return id;
  }
  static String label(String effort) {
    boolean zh = chinese();
    switch (effort) {
      case "": return zh ? "默认" : "Default";
      case "none": return zh ? "无" : "None";
      case "minimal": return zh ? "最低" : "Minimal";
      case "low": return zh ? "低" : "Low";
      case "medium": return zh ? "中" : "Medium";
      case "high": return zh ? "高" : "High";
      case "xhigh": return zh ? "极高" : "Extra High";
      case "max": return zh ? "最大" : "Max";
      case "ultra": return zh ? "超高" : "Ultra";
      default: return effort;
    }
  }
}
