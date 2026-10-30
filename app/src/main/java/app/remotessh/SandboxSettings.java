package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用沙箱白名单设置：勾选允许借用内置 Tailscale 的应用。
 * 转发节点是弹窗顶部的独立区块（带指示勾选框，状态读自偏好，与应用
 * 列表完全解耦——V1.4.15 前出口行混在列表首行，勾选状态与应用列表的
 * checked 机制互相干扰，出现「勾上了但框没勾」）。应用列表用固定高度
 * （约屏高 42%）的自定义 ListView：系统多选列表会随数百个应用占满整
 * 窗，且 setView+setMultiChoiceItems 混用在部分 ROM 上把按钮行挤出屏
 * 幕（V1.4.4 实测按钮消失）——按钮必须是弹窗原生的。
 */
final class SandboxSettings {
  private static final String PREFS = "sandbox";

  static Set<String> whitelist(Context context) {
    return context.getSharedPreferences(PREFS, 0).getStringSet("apps", new HashSet<>());
  }

  static boolean enabled(Context context) {
    return context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false);
  }

  static void show(Activity a) { show(a, null); }

  /** restore 非空时用于重开弹窗恢复用户已勾选的应用集合。 */
  private static void show(final Activity a, final Set<String> restore) {
    PackageManager pm = a.getPackageManager();
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
    // 只列有桌面入口的应用；跳过 LanGPT 自身（进程内通道，且必须避开环路）。
    List<ApplicationInfo> apps = new ArrayList<>();
    for (ResolveInfo ri : resolved) {
      if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
      ApplicationInfo info = ri.activityInfo.applicationInfo;
      if (a.getPackageName().equals(info.packageName)) continue;
      if (!apps.contains(info)) apps.add(info);
    }
    Collections.sort(apps, (x, y) -> String.CASE_INSENSITIVE_ORDER.compare(
        String.valueOf(x.loadLabel(pm)), String.valueOf(y.loadLabel(pm))));
    if (apps.isEmpty()) { toast(a, "未找到可授权的应用"); return; }

    // 独立副本：getStringSet 返回存储内部引用，直接增删行为不可靠。
    final Set<String> chosen = restore != null ? restore : new HashSet<>(whitelist(a));

    int density = (int) a.getResources().getDisplayMetrics().density;
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(density * 20, density * 8, density * 20, 0);

    // ── 转发节点独立区块（与应用列表完全分离）──────────────────────
    LinearLayout exitRow = new LinearLayout(a);
    exitRow.setOrientation(LinearLayout.HORIZONTAL);
    exitRow.setGravity(Gravity.CENTER_VERTICAL);
    exitRow.setPadding(0, density * 6, 0, density * 6);
    final CheckBox exitBox = new CheckBox(a);
    exitBox.setClickable(false); // 整行点击统一处理，避免半行响应
    exitBox.setFocusable(false);
    final TextView exitText = new TextView(a);
    exitText.setPadding(density * 8, 0, 0, 0);
    exitText.setTextSize(15);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    exitText.setLayoutParams(tp);
    exitRow.addView(exitBox);
    exitRow.addView(exitText);
    final Runnable refreshExit = () -> {
      android.content.SharedPreferences p = a.getSharedPreferences(PREFS, 0);
      String id = p.getString("exit_id", "");
      String name = p.getString("exit_name", "");
      boolean on = p.getBoolean("exit_enabled", false) && !id.isEmpty();
      exitBox.setChecked(on);
      exitText.setText(on ? name : "未使用");
    };
    refreshExit.run();
    View divider = CenteredMenu.divider(a, 0xff8e8e93, a.getResources().getDisplayMetrics().density);
    exitRow.setOnClickListener(v -> pickExitNode(a, chosen, refreshExit));
    root.addView(exitRow);
    root.addView(divider);

    // ── 应用列表（纯应用，无出口行）────────────────────────────────
    String[] names = new String[apps.size()];
    final String[] packages = new String[apps.size()];
    for (int i = 0; i < apps.size(); i++) {
      names[i] = String.valueOf(apps.get(i).loadLabel(pm));
      packages[i] = apps.get(i).packageName;
    }
    final ListView list = new ListView(a);
    list.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_list_item_multiple_choice, names) {
      @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = super.getView(position, convertView, parent);
        android.widget.CheckedTextView ctv = (android.widget.CheckedTextView) row;
        ctv.setGravity(Gravity.CENTER);
        ctv.setTextSize(13);
        return row;
      }
    });
    list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    list.setDividerHeight(0);
    for (int i = 0; i < apps.size(); i++) {
      if (chosen.contains(packages[i])) list.setItemChecked(i, true);
    }
    // 高度约屏高 42%：上下可滚动，不再占满整窗。
    int listHeight = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.42);
    list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
    root.addView(list);

    final AlertDialog dialog = new AlertDialog.Builder(a)
        .setView(root)
        .setPositiveButton("启用", null) // 空名单时保持弹窗打开，见下方覆写
        .setNeutralButton("停用沙箱", (d, w) -> {
          a.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false).apply();
          stop(a);
          toast(a, "沙箱已停用，白名单保留");
        })
        .show();
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(
        Math.round(a.getResources().getDisplayMetrics().widthPixels * .60f), ViewGroup.LayoutParams.WRAP_CONTENT);

    list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      @Override public void onItemClick(AdapterView<?> parent, View view, int which, long id) {
        if (list.isItemChecked(which)) chosen.add(packages[which]);
        else chosen.remove(packages[which]);
      }
    });

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((v) -> {
      if (chosen.isEmpty()) { toast(a, "请先在列表勾选应用（点每一行即可勾选）"); return; }
      a.getSharedPreferences(PREFS, 0).edit()
          .putStringSet("apps", new HashSet<>(chosen))
          .putBoolean("enabled", true)
          .apply();
      dialog.dismiss();
      ensureStarted(a);
    });
  }

  /** 查询可用转发节点并让用户单选；选择结果通过 onUpdate 就地刷新区块显示。 */
  private static void pickExitNode(final Activity a, final Set<String> chosen, final Runnable onUpdate) {
    toast(a, "正在查询转发节点…");
    new Thread(() -> {
      // 节点可能尚未启动（冷启动 10s+）：先拉起并等就绪，否则列表必空。
      final android.content.Context app = a.getApplicationContext();
      TsnetSetup.startIfEnabled(app);
      boolean ready = false;
      for (int i = 0; i < 30; i++) {
        try {
          org.json.JSONObject s = new org.json.JSONObject(tailscale.Tailscale.statusJSON());
          if (s.optBoolean("running") && "Running".equals(s.optString("backend"))) { ready = true; break; }
        } catch (Throwable ignored) { }
        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
      }
      if (!ready) {
        post(a, "内置 Tailscale 未就绪，请先在菜单「内置 Tailscale」完成授权后再试");
        return;
      }
      String error = null;
      List<ExitNode.Option> options = null;
      try { options = ExitNode.online(); }
      catch (Exception e) {
        error = e.getMessage() == null ? "查询转发节点失败" : e.getMessage();
        if (error.contains("tsnet not running")) error = "内置 Tailscale 未运行";
      }
      final List<ExitNode.Option> result = options;
      final String failure = error;
      post(a, () -> showNodePicker(a, result, failure, onUpdate));
    }, "exit-node-pick").start();
  }

  private static void post(Activity a, String message) { toast(a, message); }

  private static void post(Activity a, Runnable r) {
    new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
  }

  private static void showNodePicker(final Activity a, final List<ExitNode.Option> result, final String failure, final Runnable onUpdate) {
    if (a.isFinishing() || a.isDestroyed()) return;
    if (failure != null || result == null) { toast(a, failure != null ? failure : "查询转发节点失败"); return; }
    android.content.SharedPreferences p = a.getSharedPreferences(PREFS, 0);
    if (result.isEmpty()) {
      // 转发节点不能在 App 内手动添加：来自 tailnet 中其他设备的通告。
      ArrayAdapter<String> hint = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, new String[]{
          "转发节点由 Tailscale 网络中的其他设备提供",
          "在目标设备开启 exit node 通告，",
          "并在 Tailscale 管理台批准后自动出现",
          "知道了"}) {
        @Override public View getView(int position, View convertView, ViewGroup parent) {
          android.widget.TextView tv = (android.widget.TextView) super.getView(position, convertView, parent);
          tv.setGravity(Gravity.CENTER);
          tv.setTextSize(13);
          return tv;
        }
      };
      AlertDialog h = new AlertDialog.Builder(a).setAdapter(hint, (d, w) -> d.dismiss()).show();
      if (h.getWindow() != null) h.getWindow().setLayout(
          Math.round(a.getResources().getDisplayMetrics().widthPixels * .60f), ViewGroup.LayoutParams.WRAP_CONTENT);
      return;
    }
    final String currentId = p.getString("exit_id", "");
    final String NONE_LABEL = "不使用出口";
    // 与主菜单同风格：列表尾部放“不使用出口”，替代底部按钮。
    String[] labels = new String[result.size() + 1];
    int checked = -1;
    for (int i = 0; i < result.size(); i++) {
      labels[i] = result.get(i).name;
      if (result.get(i).id.equals(currentId)) checked = i;
    }
    labels[result.size()] = NONE_LABEL;
    if (checked < 0 && p.getBoolean("exit_enabled", false) == false) checked = result.size();
    ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_single_choice, labels) {
      @Override public View getView(int position, View convertView, ViewGroup parent) {
        android.widget.CheckedTextView ctv = (android.widget.CheckedTextView) super.getView(position, convertView, parent);
        ctv.setGravity(Gravity.CENTER);
        ctv.setTextSize(13);
        return ctv;
      }
    };
    AlertDialog dialog = new AlertDialog.Builder(a)
        .setSingleChoiceItems(adapter, checked, (d, which) -> {
          if (which == result.size()) {
            a.getSharedPreferences(PREFS, 0).edit().putBoolean("exit_enabled", false).apply();
            toast(a, "已停用转发节点");
          } else {
            ExitNode.Option option = result.get(which);
            a.getSharedPreferences(PREFS, 0).edit()
                .putString("exit_id", option.id)
                .putString("exit_name", option.name)
                .putBoolean("exit_enabled", true)
                .apply();
            toast(a, option.name);
          }
          d.dismiss();
          if (onUpdate != null) onUpdate.run();
        })
        .show();
    // 主菜单同款：无标题、60% 屏宽居中。
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(
        Math.round(a.getResources().getDisplayMetrics().widthPixels * .60f), ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  /** 准备网络授权（首次会弹系统确认框）并启动沙箱服务。 */
  static void ensureStarted(Activity a) {
    Intent prepare = VpnService.prepare(a);
    if (prepare != null) {
      try { a.startActivityForResult(prepare, TsnetVpnService.VPN_REQUEST); }
      catch (Exception e) { toast(a, "无法请求网络权限"); }
      return;
    }
    start(a);
  }

  static void start(Context context) {
    try {
      context.startService(new Intent(context, TsnetVpnService.class).setAction(TsnetVpnService.ACTION_START));
    } catch (Exception e) { toast(context, "沙箱服务启动失败"); }
  }

  static void stop(Context context) {
    // 双通道：ACTION_STOP 即时处理；stopService 兜底触发 onDestroy→shutdown。
    // 实测部分机型 startService 到不了 onStartCommand（无打点日志），仅靠单通道 fd 不关、钥匙图标残留。
    try {
      context.startService(new Intent(context, TsnetVpnService.class).setAction(TsnetVpnService.ACTION_STOP));
    } catch (Throwable ignored) { }
    try {
      context.stopService(new Intent(context, TsnetVpnService.class));
    } catch (Throwable ignored) { }
  }

  private static void toast(Context context, String message) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
  }
}
