package app.remotessh;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import tailscale.Tailscale;

/** Runtime bridge for AARs that expose exit-node methods. */
final class ExitNode {
  static final class Option {
    final String id, name;
    Option(String id, String name) { this.id = id; this.name = name; }
  }
  private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
    try {
      Method method = Tailscale.class.getMethod(name, types);
      return method.invoke(null, args);
    } catch (NoSuchMethodException e) {
      throw new Exception("内置 Tailscale 库不支持网络节点，需更新库", e);
    } catch (InvocationTargetException e) {
      throw new Exception("网络节点操作失败", e.getCause());
    }
  }
  static List<Option> online() throws Exception {
    JSONArray array = new JSONArray((String) call("exitNodesJSON", new Class<?>[0]));
    List<Option> result = new ArrayList<>();
    List<Option> offline = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.getJSONObject(i);
      String id = item.optString("id", "");
      if (id.isEmpty()) continue;
      String name = item.optString("name", id);
      // 冷启动后 peer 在线状态可能滞后：离线的也列出（标注），避免列表空。
      if (item.optBoolean("online")) result.add(new Option(id, name));
      else offline.add(new Option(id, name + "（暂离）"));
    }
    result.addAll(offline);
    return result;
  }
  static boolean supported() {
    try { Tailscale.class.getMethod("setExitNode", String.class); return true; }
    catch (NoSuchMethodException e) { return false; }
  }
  static void select(String id) throws Exception {
    call("setExitNode", new Class<?>[]{String.class}, id);
  }
}
