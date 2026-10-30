package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tailscale.Tailscale;

/** Local, explicitly initiated tsnet node setup; never shares access with other apps. */
final class TsnetSetup {
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static final Handler main = new Handler(Looper.getMainLooper());

  /** 控制服务器可自定义（默认官方；自建 headscale 等传完整地址）。 */
  private static String controlUrl(Context context) {
    String url = context.getApplicationContext().getSharedPreferences("tsnet_setup", 0).getString("control_url", "");
    return url == null ? "" : url.trim();
  }

  static void startIfEnabled(android.content.Context context) {
    android.content.Context app = context.getApplicationContext();
    if (!app.getSharedPreferences("tsnet_setup", 0).getBoolean("enabled", false)) return;
    worker.execute(() -> { try {
      Tailscale.setInterfaceSource(new app.remotessh.tsnet.AndroidInterfaceSource());
      JSONObject s = new JSONObject(Tailscale.statusJSON());
      if (!s.optBoolean("running")) Tailscale.start(new File(app.getFilesDir(), "tsnet").getAbsolutePath(), "langpt-android", "", controlUrl(app));
    } catch (Exception | LinkageError ignored) { /* Status UI exposes failures; direct SSH remains available. */ } });
  }
  static void show(Activity a) {
    Toast.makeText(a, "正在检查内置 Tailscale…", Toast.LENGTH_SHORT).show();
    worker.execute(() -> {
      try {
        Tailscale.setInterfaceSource(new app.remotessh.tsnet.AndroidInterfaceSource());
        JSONObject state = new JSONObject(Tailscale.statusJSON());
        if (!state.optBoolean("running")) {
          File dir = new File(a.getApplicationContext().getFilesDir(), "tsnet");
          Tailscale.start(dir.getAbsolutePath(), "langpt-android", "", controlUrl(a.getApplicationContext()));
          a.getApplicationContext().getSharedPreferences("tsnet_setup", 0).edit().putBoolean("enabled", true).apply();
        }
        state = new JSONObject(Tailscale.statusJSON());
        final JSONObject s = state;
        main.post(() -> display(a, s));
      } catch (Exception | LinkageError e) {
        main.post(() -> { if (!a.isFinishing()) new AlertDialog.Builder(a).setMessage("内置 Tailscale 启动失败：" + e.getClass().getSimpleName()).setPositiveButton("确定", null).show(); });
      }
    });
  }
  private static void display(Activity a, JSONObject state) {
    if (a.isFinishing() || a.isDestroyed()) return;
    Context app = a.getApplicationContext();
    String backend = state.optString("backend", "启动中");
    String url = state.optString("authURL", "");
    String ips = state.optJSONArray("ips") == null ? "" : state.optJSONArray("ips").toString();
    boolean online = state.optBoolean("running") && !ips.equals("[]") && !ips.isEmpty();
    String sandboxLine = TsnetVpnService.running()
        ? "\n沙箱：已启用，白名单应用共用此节点"
        : "\n已授权应用：仅 LanGPT 自身";
    String server = controlUrl(app);
    String serverLine = "\n服务器：" + (server.isEmpty() ? "官方 login.tailscale.com" : server);
    // 连接路径：直连（快）还是中继（慢，几十到几百 KB/s）。
    StringBuilder pathLine = new StringBuilder();
    org.json.JSONArray paths = state.optJSONArray("paths");
    if (paths != null) for (int i = 0; i < paths.length(); i++) {
      if (i == 0) pathLine.append("\n连接：");
      pathLine.append(i == 0 ? "" : "；").append(paths.optString(i));
    }
    // 网络节点生效状态：沙箱内公网访问依赖它。
    String exit = state.optString("exit", "");
    String exitLine = exit.isEmpty() ? "\n出口：未使用" : "\n出口：" + exit;

    AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("内置 Tailscale")
      .setMessage("状态：" + backend + (ips.equals("[]") || ips.isEmpty() ? "" : "\n地址：" + ips) + serverLine + pathLine + exitLine + sandboxLine)
      .setNegativeButton("关闭", null)
      .setNeutralButton("刷新状态", (d,w) -> show(a));

    if (!online) {
      // 未授权：控制服务器地址可编辑（他人部署/自建 headscale 时自定义）。
      int density = (int) a.getResources().getDisplayMetrics().density;
      LinearLayout box = new LinearLayout(a);
      box.setOrientation(LinearLayout.VERTICAL);
      int pad = density * 20;
      box.setPadding(pad, pad / 2, pad, 0);
      TextView label = new TextView(a);
      label.setText("控制服务器（默认官方，自建可改）：");
      final EditText input = new EditText(a);
      input.setText(server.isEmpty() ? "https://controlplane.tailscale.com" : server);
      input.setSelection(input.getText().length());
      box.addView(label); box.addView(input);
      b.setView(box);
      b.setPositiveButton("保存并重启节点", (d, w) -> {
        String value = input.getText().toString().trim();
        if (!value.isEmpty() && !value.startsWith("https://") && !value.startsWith("http://"))
          value = "https://" + value;
        String finalValue = value.equals("https://controlplane.tailscale.com") ? "" : value;
        app.getSharedPreferences("tsnet_setup", 0).edit().putString("control_url", finalValue).apply();
        worker.execute(() -> { try { Tailscale.stop(); } catch (Exception | LinkageError ignored) { } });
        Toast.makeText(a, finalValue.isEmpty() ? "已恢复官方服务器，节点重启中" : "已设置：" + finalValue, Toast.LENGTH_LONG).show();
        main.postDelayed(() -> show(a), 800);
      });
    } else if (!url.isEmpty()) {
      Uri uri = Uri.parse(url);
      if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null)
        b.setPositiveButton("浏览器授权", (d,w) -> {
          try { a.startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
          catch (Exception ex) { Toast.makeText(a, "无法打开授权页面", Toast.LENGTH_LONG).show(); }
        });
    } else {
      // 已登录运行的节点提供显式停用：关闭节点并取消开机自启。
      b.setPositiveButton("停用", (d,w) -> {
        app.getSharedPreferences("tsnet_setup", 0).edit().putBoolean("enabled", false).apply();
        worker.execute(() -> { try { Tailscale.stop(); } catch (Exception | LinkageError ignored) { } });
        Toast.makeText(a, "内置 Tailscale 已停用，下次启动不再自动运行", Toast.LENGTH_SHORT).show();
      });
    }
    b.show();
  }
}
