package app.remotessh;

import android.app.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Reference-inspired host chips, project list, project threads and search. */
final class RemoteBrowser {
  interface Actions {
    void connect(Profile p);

    default void version() {}

    void add();

    default void settings() {}

    default void sftp(String path) {}

    default void edit() {}

    void directories();

    void diagnostics();

    void chat();
  }

  private final RemoteApp app;
  private final RemoteScreens ui;
  private final Actions actions;
  private final Dialog dialog;
  private final LinearLayout list, hosts;
  private final TextView heading, subtitle, primary;
  private final EditText search;
  private final PullRefreshScrollView scroll;
  private final TextView refreshHint;
  private String filter = "", project = "";
  private String renderedSignature = "";

  private boolean centerHost = true;

  RemoteBrowser(Activity activity, RemoteApp app, boolean dark, Actions actions) {
    this.app = app;
    this.ui = new RemoteScreens(activity, dark);
    this.actions = actions;
    LinearLayout root = ui.column();
    root.setContentDescription("项目管理项目列表");
    dialog = ui.window(root);
    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
    heading = ui.text("项目管理", 21);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.setIncludeFontPadding(false);
    heading.setMaxLines(1);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(heading, new LinearLayout.LayoutParams(0, ui.dp(56), 1));
    IconView menu=ui.icon("more", "项目管理菜单", () ->
        CenteredMenu.show(activity,ui.fg,menuLabels(),which -> {
          String current=project.isEmpty()?app.currentProject():project;
          if(which==0)actions.sftp(current);
          else if(which==1)SshTerminalActivity.open(activity,dark);
          else GitWorkspaceDialog.show(activity,app,dark,current,path->{
            if(app.useProject(path))openProject(path);
          });
        }));
    menu.setBackground(null);
    header.addView(menu, new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
    root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(64)));
    // 连接管理统一在左侧菜单，本页只做项目管理
    subtitle = ui.text("", 13);
    subtitle.setTextColor(ui.muted);
    hosts = ui.row();
    refreshHint = ui.text("", 12);
    refreshHint.setTextColor(ui.muted);
    refreshHint.setGravity(Gravity.CENTER);
    refreshHint.setVisibility(View.GONE);
    root.addView(refreshHint);
    scroll = new PullRefreshScrollView(activity, this::refreshByPull, message -> {
      refreshHint.setText(message);
      refreshHint.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    });
    list = ui.column();
    list.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(16));
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = ui.row();
    footer.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(20));
    search = new EditText(activity);
    search.setTextSize(16);
    search.setTextColor(ui.fg);
    search.setHintTextColor(ui.muted);
    search.setHint("搜索项目和对话");
    search.setContentDescription("搜索项目和对话");
    search.setSingleLine(true);
    search.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
    search.setBackground(ui.rounded(ui.panel));
    footer.addView(search, new LinearLayout.LayoutParams(0, ui.dp(54), 1));
    primary =
        ui.button(
            "新项目",
            true,
            () -> {
              if (project.isEmpty()) actions.directories();
              else {
                if (!app.canSwitchConversation()) {
                  Toast.makeText(activity, "正在恢复会话或上传附件，请稍候", Toast.LENGTH_SHORT).show();
                  return;
                }
                if (app.useProject(project)) {
                  dialog.dismiss();
                  actions.chat();
                }
              }
            });
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, ui.dp(54));
    pp.leftMargin = ui.dp(10);
    footer.addView(primary, pp);
    root.addView(footer);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            refreshList();
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  String[] menuLabels() { return new String[] {"SFTP", "SSH 终端", "独立工作区"}; }

  private void refreshByPull() {
    if (!app.connected) {
      scroll.finishRefresh();
      Toast.makeText(ui.activity, "请先连接主机", Toast.LENGTH_SHORT).show();
      return;
    }
    app.refreshThreads(false, () -> {
      scroll.finishRefresh();
      refresh();
    });
  }

  void show() {
    ui.show(dialog);
    renderedSignature = "";
    if (!app.historyProject.isEmpty()) {
      app.historyProject = "";
      if (app.connected) app.refreshThreads(false);
    }
    filter = app.profile == null ? (app.vault.profiles.isEmpty() ? "" : app.vault.profiles.get(0).id) : app.profile.id;
    refresh();
  }

  boolean isShowing() {
    return dialog.isShowing();
  }

  void dismiss() {
    dialog.dismiss();
  }

  void openProject(String path) {
    project = path;
    filter = app.profile == null ? "" : app.profile.id;
    app.historyProject = path;
    if (app.connected) app.refreshThreads(false);
    refresh();
  }

  void refresh() {
    if (!isShowing()) return;
    subtitle.setText("");
    String signature = signature();
    if (signature.equals(renderedSignature)) return;
    renderedSignature = signature;
    heading.setText(project.isEmpty() ? "项目管理" : RemoteDirectory.name(project));
    heading.setMaxLines(1);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    primary.setText(project.isEmpty() ? "新项目" : "聊天");
    hosts.removeAllViews();
    for (Profile p : new java.util.ArrayList<Profile>()) {
      boolean active = app.connected && app.profile != null && p.id.equals(app.profile.id);
      TextView chip =
          ui.button(
              (active ? "● " : "○ ") + p.title(),
              filter.equals(p.id),
              () -> {
                centerHost = true;
                filter = p.id;
                project = "";
                if (active) {
                  app.historyProject = "";
                  app.refreshThreads(false);
                } else {
                  app.historyProject = "";
                  actions.connect(p);
                }
                refresh();
              });
      chip.setText(AppIcons.label(ui.activity,chip.getText().toString(),active?"status":"radio",null,filter.equals(p.id)?android.graphics.Color.WHITE:ui.fg,ui.dp(16)));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = ui.dp(8);
      hosts.addView(chip, lp);
      if (centerHost && filter.equals(p.id)) {
        centerHost = false;
        chip.post(() -> {
          HorizontalScrollView scroll = (HorizontalScrollView) hosts.getParent();
          scroll.scrollTo(Math.max(0, chip.getLeft() + chip.getWidth() / 2 - scroll.getWidth() / 2), 0);
        });
      }
    }
    refreshList();
  }

  private String signature() {
    StringBuilder value = new StringBuilder(filter).append('|').append(project).append('|')
        .append(app.connected).append('|').append(app.connecting).append('|')
        .append(app.profile == null ? "" : app.profile.id).append('|')
        .append(app.remoteVersion.label()).append('|').append(app.nextCursor).append('|').append(app.canSwitchConversation());
    for (Profile p : app.vault.profiles)
      value.append("|p:").append(p.id).append(':').append(p.name).append(':').append(p.cwd)
          .append(':').append(p.projects.hashCode());
    for (int i = 0; i < app.threads.length(); i++) {
      JSONObject thread = app.threads.optJSONObject(i);
      if (thread != null) value.append("|t:").append(thread.optString("id"))
          .append(':').append(thread.optString("name")).append(':').append(thread.optString("preview"))
          .append(':').append(thread.optString("cwd")).append(':').append(thread.optLong("updatedAt"))
          .append(':').append(app.threadRunning(thread));
    }
    return value.toString();
  }

  private boolean matches(String text) {
    return text.toLowerCase(Locale.ROOT)
        .contains(search.getText().toString().trim().toLowerCase(Locale.ROOT));
  }

  private void section(String title) {
    TextView label = ui.text(title, 21);
    label.setPadding(0, ui.dp(12), 0, ui.dp(16));
    list.addView(label);
  }

  private void refreshList() {
    list.removeAllViews();
    if (project.isEmpty()) {
      section("项目");
      int count = 0;
      for (Profile p : app.vault.profiles) {
        if (!filter.equals(p.id)) continue;
        LinkedHashSet<String> paths = new LinkedHashSet<>(p.projects);
        if (!p.cwd.isEmpty()) paths.add(p.cwd);
        for (String path : paths) {
          if (!matches(path)) continue;
          count++;
          LinearLayout row = ui.row();
          row.setPadding(0, ui.dp(4), 0, ui.dp(4));
          IconView folder = new IconView(ui.activity, "folder", ui.fg);
          row.addView(folder, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(48)));
          TextView name = ui.text(RemoteDirectory.name(path), 16);
          name.setPadding(ui.dp(8), 0, 0, 0);
          name.setMaxLines(1);
          name.setEllipsize(TextUtils.TruncateAt.END);
          row.addView(name, new LinearLayout.LayoutParams(0, ui.dp(46), 1));
          name.setGravity(Gravity.CENTER_VERTICAL);
          ui.clickable(
              row,
              () -> {
                if (app.connected && app.profile.id.equals(p.id)) {
                  openProject(path);
                } else {
                  actions.connect(p);
                  Toast.makeText(ui.activity, "连接此电脑后再打开项目", Toast.LENGTH_SHORT).show();
                }
              });
          // 左滑露出「移除」: 仅本地隐藏, 不删远端文件, cwd 不可移
          SwipeActionLayout swipe = new SwipeActionLayout(
              ui.activity, row, ui.dp(72), "移除", ui.bg, () -> {
                if (p.cwd.equals(path) || !p.projects.contains(path)) {
                  Toast.makeText(ui.activity, "默认项目不可移除", Toast.LENGTH_SHORT).show();
                  return;
                }
                new AlertDialog.Builder(ui.activity)
                    .setTitle("从历史中移除")
                    .setMessage("仅从 App 历史列表隐藏此项，不会删除远端项目文件。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除", (d, w) -> {
                      p.projects.remove(path);
                      try { app.vault.save(); } catch (Exception e) { /* 本地存储失败不阻塞 UI */ }
                      Toast.makeText(ui.activity, "已从历史移除", Toast.LENGTH_SHORT).show();
                      refresh();
                    })
                    .show();
              });
          list.addView(swipe, new LinearLayout.LayoutParams(-1, ui.dp(54)));
        }
        if (paths.isEmpty())
          list.addView(ui.button("连接", false, () -> actions.connect(p)));
      }
      if (count == 0) {
        TextView note = ui.text("选择远端文件夹，或新建一个项目。", 13);
        note.setTextColor(ui.muted);
        note.setPadding(0, 0, 0, ui.dp(12));
        list.addView(note);
        list.addView(ui.button("选择路径 / 新建项目", false, actions::directories));
      }
      section("最近");
    }
    int count = 0;
    if (app.connected && filter.equals(app.profile.id))
      for (int i = 0; i < app.threads.length(); i++) {
        JSONObject thread = app.threads.optJSONObject(i);
        if (thread == null) continue;
        if (!project.isEmpty() && !thread.optString("cwd").equals(project)) continue;
        String name = thread.isNull("name") ? "" : thread.optString("name");
        if (name.trim().isEmpty()) name = thread.optString("preview", "新对话");
        if (!matches(name)) continue;
        count++;
        LinearLayout row = ui.row();
        TextView text = ui.text("[" + app.profile.runtime + "] " + (name.isEmpty() ? "新对话" : name), 15);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, ui.dp(12), ui.dp(12), ui.dp(12));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        long age =
            Math.max(
                0,
                System.currentTimeMillis() / 1000
                    - thread.optLong("updatedAt", System.currentTimeMillis() / 1000));
        TextView time =
            ui.text(
                age < 3600 ? "刚刚" : age < 86400 ? (age / 3600) + "小时" : (age / 86400) + "天", 11);
        String state = app.threadRunning(thread) ? "运行中" : "未知";
        JSONObject reported = thread.optJSONObject("status");
        if (reported != null && "idle".equals(reported.optString("type"))) state = "空闲";
        if (thread.optString("id").equals(app.threadId) && !app.approvals.isEmpty()) state = "待审批";
        time.setText(state + " · " + time.getText());
        time.setTextColor(ui.muted);
        if (app.threadRunning(thread)) {
          ProgressBar running = new ProgressBar(ui.activity);
          running.setIndeterminate(true);
          running.setContentDescription("对话正在运行");
          LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
          progress.setMargins(0, 0, ui.dp(10), 0);
          row.addView(running, progress);
        }
        row.addView(time);
        String id = thread.optString("id");
        ui.clickable(
            row,
            () -> {
              if (!app.canSwitchConversation()) {
                app.openConversation(id);
                Toast.makeText(ui.activity, "正在恢复会话，完成后自动打开", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
              }
              app.viewConversation(id);
              dialog.dismiss();
            });
        SwipeActionLayout swipe = new SwipeActionLayout(
            ui.activity, row, ui.dp(72), "删除", ui.bg, () -> {
              if (app.threadRunning(thread)) {
                Toast.makeText(ui.activity, "对话正在运行，结束后才能删除", Toast.LENGTH_SHORT).show();
                return;
              }
              new AlertDialog.Builder(ui.activity)
                  .setTitle("删除历史对话？")
                  .setMessage("将永久删除该远端 Codex 对话及其消息，无法恢复。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("删除", (d, w) -> {
                    app.deleteThread(id, failure -> {
                      if (failure == null) {
                        Toast.makeText(ui.activity, "历史对话已删除", Toast.LENGTH_SHORT).show();
                        refresh();
                      } else {
                        Toast.makeText(ui.activity, "删除失败：" + failure.getMessage(), Toast.LENGTH_LONG).show();
                      }
                    });
                  })
                  .show();
            });
        list.addView(swipe, new LinearLayout.LayoutParams(-1, ui.dp(68)));
      }
    if (count == 0) {
      TextView note = ui.text(project.isEmpty() ? "暂无对话" : "没有更多线程", 16);
      note.setTextColor(ui.muted);
      note.setGravity(Gravity.CENTER);
      note.setPadding(0, ui.dp(24), 0, ui.dp(24));
      list.addView(note);
    }
    if (app.connected && !app.nextCursor.isEmpty())
      list.addView(ui.button("加载更多", false, () -> app.refreshThreads(true)));
    if (!app.connected && app.vault.profiles.isEmpty())
      list.addView(ui.button("添加 SSH 连接", true, actions::add));
  }
}
