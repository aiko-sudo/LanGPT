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
  // 项目根 AGENTS.md 检测缓存：path -> 是否存在；未检测的路径不显示提示行。
  private final java.util.Map<String, Boolean> agentsMd = new java.util.TreeMap<>();
  private boolean agentsMdScanning;

  private boolean centerHost = true;

  RemoteBrowser(Activity activity, RemoteApp app, boolean dark, Actions actions) {
    this.app = app;
    this.ui = new RemoteScreens(activity, dark);
    this.actions = actions;
    LinearLayout root = ui.column();
    root.setContentDescription(UiText.get(R.string.work_text_110));
    dialog = ui.window(root);
    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
    heading = ui.text(UiText.get(R.string.work_text_111), 21);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.setIncludeFontPadding(false);
    heading.setMaxLines(1);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(heading, new LinearLayout.LayoutParams(0, ui.dp(56), 1));
    IconView menu=ui.icon("more", UiText.get(R.string.work_text_112), () ->
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
    search.setHint(UiText.get(R.string.work_text_113));
    search.setContentDescription(UiText.get(R.string.work_text_113));
    search.setSingleLine(true);
    search.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
    search.setBackground(ui.rounded(ui.panel));
    footer.addView(search, new LinearLayout.LayoutParams(0, ui.dp(54), 1));
    primary =
        ui.button(
            UiText.get(R.string.work_text_114),
            true,
            () -> {
              if (project.isEmpty()) actions.directories();
              else {
                if (!app.canSwitchConversation()) {
                  Toast.makeText(activity, UiText.get(R.string.work_text_115), Toast.LENGTH_SHORT).show();
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

  String[] menuLabels() { return new String[] {"SFTP", UiText.get(R.string.work_text_116), UiText.get(R.string.work_text_117)}; }

  private void refreshByPull() {
    if (!app.connected) {
      scroll.finishRefresh();
      Toast.makeText(ui.activity, UiText.get(R.string.work_text_118), Toast.LENGTH_SHORT).show();
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
    directoryStatus.clear(); directoryScanAt = 0; directoryScanSequence++; directoryScanning = false;
    if (!app.historyProject.isEmpty()) {
      app.historyProject = "";
      if (app.connected) app.refreshThreads(false);
    }
    // Cross-channel recent: pull the other channel's session list once per
    // connection so the project page never dead-ends after a channel switch.
    app.refreshOtherChannelThreads();
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
    heading.setText(project.isEmpty() ? UiText.get(R.string.work_text_111) : RemoteDirectory.name(project));
    heading.setMaxLines(1);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    primary.setText(project.isEmpty() ? UiText.get(R.string.work_text_114) : UiText.get(R.string.work_text_119));
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
    scanAgentsMd();
    scanWorkspaceProjects();
    scanProjectDirectories();
  }

  private final Map<String, Boolean> directoryStatus = new HashMap<>();
  private long directoryScanAt;
  private int directoryScanSequence;
  private boolean directoryScanning;
  private String directoryScanKey = "";
  private String directoryStatusOwner = "";

  private void scanProjectDirectories() {
    if (!isShowing() || !app.connected || app.profile == null || directoryScanning) return;
    Profile current = app.profile;
    LinkedHashSet<String> paths = new LinkedHashSet<>(current.projects);
    for (Profile saved : app.vault.profiles) if (saved.id.equals(current.id)) {
      paths.addAll(saved.projects);
      if (!saved.cwd.isEmpty()) paths.add(saved.cwd);
    }
    if (!current.cwd.isEmpty()) paths.add(current.cwd);
    JSONArray histories = app.visibleThreads();
    for (int i = 0; i < histories.length(); i++) {
      JSONObject history = histories.optJSONObject(i);
      if (history != null && history.optString("cwd").startsWith("/")) paths.add(history.optString("cwd"));
    }
    List<String> workspace = workspaceProjects.get(current.id);
    if (workspace != null) paths.addAll(workspace);
    String key = current.id + ":" + app.connectionGeneration() + ":" + paths.toString();
    long now = android.os.SystemClock.elapsedRealtime();
    if (key.equals(directoryScanKey) && now - directoryScanAt < 3000) return;
    String owner = current.id + ":" + app.connectionGeneration();
    if (!owner.equals(directoryStatusOwner)) { directoryStatus.clear(); directoryStatusOwner = owner; }
    directoryScanKey = key; directoryScanAt = now; directoryScanning = true;
    int sequence = ++directoryScanSequence;
    app.checkProjectDirectories(paths, result -> {
      if (sequence != directoryScanSequence) return;
      directoryScanning = false;
      if (!isShowing() || app.profile != current) return;
      directoryStatus.clear();
      if (result != null) directoryStatus.putAll(result);
      refresh();
    });
  }

  // Custom-channel projects are sub-directories of ~/lanpt-workspace that the
  // thread cwd aggregation never sees; rescanned with a short throttle so a
  // folder created mid-session shows up without reconnecting.
  private final java.util.Map<String, java.util.List<String>> workspaceProjects = new java.util.HashMap<>();
  private final java.util.Map<String, Long> workspaceScanAt = new java.util.HashMap<>();
  private boolean workspaceScanning;

  private void scanWorkspaceProjects() {
    if (!isShowing() || !app.connected || workspaceScanning) return;
    if (app.profile == null || !"opencode".equals(app.profile.runtime)) return;
    final String pid = app.profile.id;
    Long at = workspaceScanAt.get(pid);
    long now = android.os.SystemClock.elapsedRealtime();
    if (at != null && now - at < 3000) return;
    workspaceScanAt.put(pid, now);
    workspaceScanning = true;
    app.listWorkspaceProjects(dirs -> {
      workspaceScanning = false;
      if (dirs == null || pid.equals(app.profile == null ? "" : app.profile.id)) {
        workspaceProjects.put(pid, dirs == null ? new java.util.ArrayList<>() : dirs);
        refresh();
      }
    });
  }

  /** 后台检测当前 profile 各项目根的 AGENTS.md；结果变化经 signature 触发重绘。 */
  private void scanAgentsMd() {
    if (!isShowing() || !app.connected || agentsMdScanning) return;
    Profile p = null;
    for (Profile candidate : app.vault.profiles)
      if (candidate.id.equals(filter)) { p = candidate; break; }
    if (p == null) return;
    LinkedHashSet<String> paths = new LinkedHashSet<>(p.projects);
    if (!p.cwd.isEmpty()) paths.add(p.cwd);
    java.util.List<String> pending = new java.util.ArrayList<>();
    for (String path : paths)
      if (!agentsMd.containsKey(path)) pending.add(path);
    if (pending.isEmpty()) return;
    agentsMdScanning = true;
    app.checkAgentsMd(pending, result -> {
      agentsMdScanning = false;
      if (result == null) return;
      boolean changed = false;
      for (java.util.Map.Entry<String, Boolean> entry : result.entrySet()) {
        Boolean old = agentsMd.get(entry.getKey());
        if (!java.util.Objects.equals(old, entry.getValue())) {
          agentsMd.put(entry.getKey(), entry.getValue());
          changed = true;
        }
      }
      if (changed) refresh();
    });
  }

  private String signature() {
    StringBuilder value = new StringBuilder(filter).append('|').append(project).append('|')
        .append(app.connected).append('|').append(app.connecting).append('|')
        .append(app.profile == null ? "" : app.profile.id).append('|')
        .append(app.remoteVersion.label()).append('|').append(app.nextCursor).append('|').append(app.canSwitchConversation());
    for (Profile p : app.vault.profiles)
      value.append("|p:").append(p.id).append(':').append(p.name).append(':').append(p.cwd)
          .append(':').append(p.projects.hashCode())
          .append(':').append(java.util.Objects.hashCode(workspaceProjects.get(p.id)));
    JSONArray visible = app.visibleThreads();
    for (int i = 0; i < visible.length(); i++) {
      JSONObject thread = visible.optJSONObject(i);
      value.append("|t:").append(thread.optString("id"))
          .append(':').append(thread.optString("name")).append(':').append(thread.optString("preview"))
          .append(':').append(thread.optString("cwd")).append(':').append(thread.optLong("updatedAt"))
          .append(':').append(thread.optString("runtime"))
          .append(':').append(app.threadRunning(thread));
    }
    value.append("|dirs:").append(directoryStatus);
    value.append("|md:").append(agentsMd);
    return value.toString();
  }

  private boolean matches(String text) {
    return text.toLowerCase(Locale.ROOT)
        .contains(search.getText().toString().trim().toLowerCase(Locale.ROOT));
  }

  private void section(String title) {
    TextView label = ui.text(title, 21);
    label.setPadding(0, ui.dp(12), 0, ui.dp(4));
    list.addView(label);
    // 左对齐渐隐分隔线（50% 屏宽）：实→透明，与菜单分隔线同风格。
    View line = new View(ui.activity);
    int base = ui.muted == 0 ? 0xff8e8e93 : ui.muted;
    int[] stops = new int[11];
    for (int i = 0; i <= 10; i++) {
      float alpha = 1f - i / 10f;
      stops[i] = (base & 0x00ffffff) | ((int) (0x55 * alpha) << 24);
    }
    line.setBackground(new android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, stops));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        Math.round(ui.activity.getResources().getDisplayMetrics().widthPixels * 0.5f),
        Math.max(1, ui.dp(1)));
    lp.bottomMargin = ui.dp(12);
    list.addView(line, lp);
  }

  private void refreshList() {
    list.removeAllViews();
    if (project.isEmpty()) {
      section(UiText.get(R.string.work_text_120));
      int count = 0;
      for (Profile p : app.vault.profiles) {
        if (!filter.equals(p.id)) continue;
        LinkedHashSet<String> paths = new LinkedHashSet<>(p.projects);
        if (!p.cwd.isEmpty()) paths.add(p.cwd);
        java.util.List<String> workspace = workspaceProjects.get(p.id);
        if (workspace != null) paths.addAll(workspace);
        for (String path : paths) {
          if (ProjectVisibility.isHidden(app, p.id, path) || (p.id + ":" + app.connectionGeneration()).equals(directoryStatusOwner) && Boolean.FALSE.equals(directoryStatus.get(path)) || !matches(path)) continue;
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
                  Toast.makeText(ui.activity, UiText.get(R.string.work_text_121), Toast.LENGTH_SHORT).show();
                }
              });
          // 左滑露出「移除」: 仅本地隐藏, 不删远端文件, cwd 不可移
          SwipeActionLayout swipe = new SwipeActionLayout(
              ui.activity, row, ui.dp(72), UiText.get(R.string.work_text_122), ui.bg, () -> {
                if (p.cwd.equals(path)) {
                  Toast.makeText(ui.activity, UiText.get(R.string.work_text_123), Toast.LENGTH_SHORT).show();
                  return;
                }
                new AlertDialog.Builder(ui.activity)
                    .setTitle(UiText.get(R.string.work_text_124))
                    .setMessage(UiText.get(R.string.work_text_125))
                    .setNegativeButton(UiText.get(R.string.work_text_004), null)
                    .setPositiveButton(UiText.get(R.string.work_text_122), (d, w) -> {
                      ProjectVisibility.setHidden(app, p.id, path, true);
                      p.projects.remove(path);
                      try { app.vault.save(); } catch (Exception e) { /* 本地存储失败不阻塞 UI */ }
                      Toast.makeText(ui.activity, UiText.get(R.string.work_text_126), Toast.LENGTH_SHORT).show();
                      refresh();
                    })
                    .show();
              });
          list.addView(swipe, new LinearLayout.LayoutParams(-1, ui.dp(54)));
          // AGENTS.md 未配置提示：仅连接中、检测结果明确为缺失时显示；点击一键写入模板。
          if (app.connected && filter.equals(p.id) && Boolean.FALSE.equals(agentsMd.get(path))) {
            TextView hint = ui.text(UiText.get(R.string.work_text_223), 11);
            hint.setTextColor(ui.muted);
            hint.setPadding(ui.dp(42), 0, 0, ui.dp(6));
            ui.clickable(hint, () -> app.writeAgentsMd(path, failure -> {
              if (failure == null) {
                agentsMd.put(path, Boolean.TRUE);
                Toast.makeText(ui.activity, UiText.get(R.string.work_text_224), Toast.LENGTH_SHORT).show();
                refresh();
              } else {
                Toast.makeText(ui.activity,
                    UiText.get(R.string.work_text_225) + (failure.getMessage() == null ? "" : failure.getMessage()),
                    Toast.LENGTH_LONG).show();
              }
            }));
            list.addView(hint);
          }
        }
        if (paths.isEmpty())
          list.addView(ui.button(UiText.get(R.string.work_text_127), false, () -> actions.connect(p)));
      }
      if (count == 0) {
        TextView note = ui.text(UiText.get(R.string.work_text_128), 13);
        note.setTextColor(ui.muted);
        note.setPadding(0, 0, 0, ui.dp(12));
        list.addView(note);
        list.addView(ui.button(UiText.get(R.string.work_text_129), false, actions::directories));
      }
      section(UiText.get(R.string.work_text_130));
    }
    int count = 0;
    if (app.connected && filter.equals(app.profile.id))
      for (int i = 0; i < app.visibleThreads().length(); i++) {
        JSONObject thread = app.visibleThreads().optJSONObject(i);
        if (thread == null) continue;
        if (!project.isEmpty() && !thread.optString("cwd").equals(project)) continue;
        String name = thread.isNull("name") ? "" : thread.optString("name");
        if (name.trim().isEmpty()) name = thread.optString("preview", UiText.get(R.string.work_text_131));
        if (!matches(name)) continue;
        count++;
        LinearLayout row = ui.row();
        LinearLayout labels = ui.column();
        String cwd = thread.optString("cwd", "");
        TextView category = ui.text("[" + thread.optString("runtime", app.profile.runtime) + "] " + cwd, 12);
        category.setTextColor(ui.muted); category.setSingleLine(true); category.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        TextView text = ui.text(name.isEmpty() ? UiText.get(R.string.work_text_131) : name, 15);
        text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END);
        labels.setPadding(0, ui.dp(7), ui.dp(12), ui.dp(7));
        labels.addView(category); labels.addView(text);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        long age =
            Math.max(
                0,
                System.currentTimeMillis() / 1000
                    - thread.optLong("updatedAt", System.currentTimeMillis() / 1000));
        TextView time =
            ui.text(
                age < 3600 ? UiText.get(R.string.work_text_132) : age < 86400 ? (age / 3600) + UiText.get(R.string.work_text_133) : (age / 86400) + UiText.get(R.string.work_text_134), 11);
        String state = app.threadRunning(thread) ? UiText.get(R.string.work_text_076) : UiText.get(R.string.work_text_135);
        JSONObject reported = thread.optJSONObject("status");
        if (reported != null && "idle".equals(reported.optString("type"))) state = UiText.get(R.string.work_text_136);
        if (thread.optString("id").equals(app.threadId) && !app.approvals.isEmpty()) state = UiText.get(R.string.work_text_094);
        time.setText(state + " · " + time.getText());
        time.setTextColor(ui.muted);
        if (app.threadRunning(thread)) {
          ProgressBar running = new ProgressBar(ui.activity);
          running.setIndeterminate(true);
          running.setContentDescription(UiText.get(R.string.work_text_137));
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
                Toast.makeText(ui.activity, UiText.get(R.string.work_text_138), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
              }
              app.viewConversation(id);
              dialog.dismiss();
            });
        // 长按分叉：基于该会话的磁盘历史 fork 出新线程（thread/fork），原会话不动。
        row.setOnLongClickListener(
            v -> {
              if (app.threadRunning(thread)) {
                Toast.makeText(ui.activity, UiText.get(R.string.work_text_222), Toast.LENGTH_SHORT).show();
                return true;
              }
              CenteredMenu.show(ui.activity, ui.fg,
                  new String[] {UiText.get(R.string.work_text_220)},
                  index -> app.forkThread(id,
                      newId -> {
                        Toast.makeText(ui.activity, UiText.get(R.string.work_text_221), Toast.LENGTH_SHORT).show();
                        app.refreshThreads(false);
                      },
                      failure -> Toast.makeText(ui.activity,
                          UiText.get(R.string.work_text_225) + (failure.getMessage() == null ? "" : failure.getMessage()),
                          Toast.LENGTH_LONG).show()));
              return true;
            });
        SwipeActionLayout swipe = new SwipeActionLayout(
            ui.activity, row, ui.dp(72), UiText.get(R.string.work_text_106), ui.bg, () -> {
              if (app.threadRunning(thread)) {
                Toast.makeText(ui.activity, UiText.get(R.string.work_text_139), Toast.LENGTH_SHORT).show();
                return;
              }
              new AlertDialog.Builder(ui.activity)
                  .setTitle(UiText.get(R.string.work_text_140))
                  .setMessage(UiText.get(R.string.work_text_141))
                  .setNegativeButton(UiText.get(R.string.work_text_004), null)
                  .setPositiveButton(UiText.get(R.string.work_text_106), (d, w) -> {
                    app.deleteThread(id, failure -> {
                      if (failure == null) {
                        Toast.makeText(ui.activity, UiText.get(R.string.work_text_142), Toast.LENGTH_SHORT).show();
                        refresh();
                      } else {
                        Toast.makeText(ui.activity, UiText.get(R.string.work_text_108) + failure.getMessage(), Toast.LENGTH_LONG).show();
                      }
                    });
                  })
                  .show();
            });
        list.addView(swipe, new LinearLayout.LayoutParams(-1, ui.dp(68)));
      }
    if (count == 0) {
      TextView note = ui.text(project.isEmpty() ? UiText.get(R.string.work_text_143) : UiText.get(R.string.work_text_144), 16);
      note.setTextColor(ui.muted);
      note.setGravity(Gravity.CENTER);
      note.setPadding(0, ui.dp(24), 0, ui.dp(24));
      list.addView(note);
    }
    if (app.connected && !app.nextCursor.isEmpty())
      list.addView(ui.button(UiText.get(R.string.work_text_145), false, () -> app.refreshThreads(true)));
    if (!app.connected && app.vault.profiles.isEmpty())
      list.addView(ui.button(UiText.get(R.string.work_text_146), true, actions::add));
  }
}
