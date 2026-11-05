package app.remotessh;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Custom-model API registry (hardened fork): multiple user-defined
 * OpenAI-compatible endpoints (DeepSeek / OpenRouter / Gemini-compatible ...),
 * each with name, base URL, key and model, one of them active. Stored locally
 * as a JSON list in app-private preferences; the single-entry V1.6.16 format
 * is migrated on first read.
 *
 * Pure Java (JVM-testable): serialization, CRUD and the PC-side opencode
 * config merges.
 */
final class CustomApiConfig {
  static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/coding/paas/v4";
  static final String DEFAULT_MODEL = "glm-5.3";
  /** opencode provider id used on the PC side. */
  static final String PROVIDER_ID = "lanpt";

  static final class Entry {
    String id;
    String name = "";
    String baseUrl = "", apiKey = "", model = "";
    boolean enabled = true;
  }

  final List<Entry> entries = new ArrayList<>();
  /** id of the entry used by the escape-hatch switch; may be empty. */
  String activeId = "";

  Entry active() {
    for (Entry e : entries) if (e.id.equals(activeId)) return e;
    return null;
  }

  /** The switch needs a ready active entry. */
  boolean ready() {
    Entry e = active();
    return e != null && e.enabled && ready(e);
  }

  static boolean ready(Entry e) {
    return e != null && !e.baseUrl.trim().isEmpty() && !e.apiKey.trim().isEmpty() && !e.model.trim().isEmpty();
  }

  String prefsValue() {
    JSONObject o = new JSONObject();
    try {
      JSONArray list = new JSONArray();
      for (Entry e : entries) {
        JSONObject item = new JSONObject();
        item.put("id", e.id);
        item.put("name", e.name);
        item.put("baseUrl", e.baseUrl.trim());
        item.put("apiKey", e.apiKey.trim());
        item.put("model", e.model.trim());
        item.put("enabled", e.enabled);
        list.put(item);
      }
      o.put("apis", list);
      o.put("activeId", activeId);
    } catch (Exception ignored) {
    }
    return o.toString();
  }

  /** Reads the list format; falls back to migrating the V1.6.16 single-entry format. */
  static CustomApiConfig fromPrefsValue(String raw) {
    CustomApiConfig c = new CustomApiConfig();
    if (raw == null || raw.trim().isEmpty()) return c;
    try {
      JSONObject o = new JSONObject(raw);
      if (o.has("apis")) {
        JSONArray list = o.optJSONArray("apis");
        for (int i = 0; i < list.length(); i++) {
          JSONObject item = list.optJSONObject(i);
          if (item == null) continue;
          Entry e = new Entry();
          e.id = item.optString("id", UUID.randomUUID().toString());
          e.name = item.optString("name", "");
          e.baseUrl = item.optString("baseUrl", "");
          e.apiKey = item.optString("apiKey", "");
          e.model = item.optString("model", "");
          e.enabled = item.optBoolean("enabled", true);
          c.entries.add(e);
        }
        c.activeId = o.optString("activeId", "");
        return c;
      }
      // Legacy single-entry migration (V1.6.16).
      Entry e = new Entry();
      e.id = "legacy";
      e.name = "API";
      e.enabled = o.optBoolean("enabled", false);
      e.baseUrl = o.optString("baseUrl", "");
      e.apiKey = o.optString("apiKey", "");
      e.model = o.optString("model", "");
      if (!e.baseUrl.isEmpty() && !e.apiKey.isEmpty()) {
        c.entries.add(e);
        c.activeId = e.id;
      }
    } catch (Exception ignored) {
    }
    return c;
  }

  /**
   * OpenAI-compatible SDKs append "/chat/completions" to the configured base
   * URL. Users routinely paste the full endpoint instead, which produces
   * ".../chat/completions/chat/completions" and a provider-side 401/404
   * (verified: SenseNova returns 401 "Authorization Not Found" for the doubled
   * path while the correct root returns 200). Normalize on write.
   */
  static String normalizeBaseUrl(String url) {
    if (url == null) return "";
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
    for (String suffix : new String[]{"/chat/completions", "/responses", "/completions"}) {
      if (lower.endsWith(suffix)) {
        trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        break;
      }
    }
    return trimmed;
  }

  /**
   * Merge the custom provider into an existing opencode.json document.
   * Preserves unrelated keys; rejects malformed existing JSON without replacing it.
   */
  /** Legacy 3-arg form: no provider key (only auth.json). Prefer the 4-arg form. */
  static String mergeProviderConfig(String existingJson, String baseUrl, String model) {
    return mergeProviderConfig(existingJson, baseUrl, model, "");
  }

  static String mergeProviderConfig(String existingJson, String baseUrl, String model, String apiKey) {
    JSONObject root = parseOrEmpty(existingJson);
    try {
      JSONObject options = new JSONObject();
      options.put("baseURL", normalizeBaseUrl(baseUrl));
      // Verified: @ai-sdk/openai-compatible does NOT pick the key up from
      // auth.json for a custom provider (every request then fails with 401
      // "Authorization Not Found"); the key must be in options.apiKey.
      // A {file:}/{env:} reference is NOT viable on opencode 1.19.4: the
      // reference string is sent VERBATIM to the provider (HTTP 401 "api key
      // ...key} is invalid"), and the config is re-serialized with "/" escaped
      // so slashed {file:} paths stop resolving and serve exits at boot.
      // Plaintext here + the scoped workspace AGENTS.md (which forbids the
      // model from reading PC configs) is the proven-working arrangement.
      options.put("apiKey", apiKey == null ? "" : apiKey.trim());
      JSONObject entry = new JSONObject();
      entry.put("npm", "@ai-sdk/openai-compatible");
      entry.put("name", "LanGPT Custom API");
      entry.put("options", options);
      JSONObject models = new JSONObject();
      JSONObject modelEntry = new JSONObject();
      modelEntry.put("name", model + " (custom)");
      models.put(model, modelEntry);
      entry.put("models", models);
      JSONObject providers = root.optJSONObject("provider");
      if (providers == null) {
        providers = new JSONObject();
        root.put("provider", providers);
      }
      providers.put(PROVIDER_ID, entry);
      root.put("model", PROVIDER_ID + "/" + model);
    } catch (Exception ignored) {
    }
    return root.toString();
  }

  /**
   * Store the raw LanGPT key in auth.json. Preserves credentials for other
   * providers. (No {file:}/{env:} indirection: opencode 1.19.4 sends the
   * reference string verbatim to the provider, so the key must be literal.)
   */
  static String mergeAuthConfig(String existingJson, String apiKey) {
    JSONObject root = parseOrEmpty(existingJson);
    try {
      JSONObject entry = new JSONObject();
      entry.put("type", "api");
      entry.put("key", apiKey == null ? "" : apiKey.trim());
      root.put(PROVIDER_ID, entry);
    } catch (Exception ignored) {
    }
    return root.toString();
  }

  private static JSONObject parseOrEmpty(String json) {
    if (json == null || json.trim().isEmpty()) return new JSONObject();
    try {
      return new JSONObject(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("现有 OpenCode 配置不是有效 JSON，未修改远端文件", e);
    }
  }
}
