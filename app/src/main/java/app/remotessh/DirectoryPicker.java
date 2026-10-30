package app.remotessh;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;

/** SFTP directory picker. Only an explicit create action can mutate the remote filesystem. */
final class DirectoryPicker {
  private final RemoteApp app;
  private final RemoteScreens ui;
  private final Dialog dialog;
  private final LinearLayout list;
  private final TextView pathLabel, status, choose, up, create;
  private final Consumer<String> selected;
  private RemoteDirectory directory;
  private RemoteFiles contents;
  private final TextView nameCol,dateCol;
  private String requestedPath="";
  private int visible;
  private java.util.List<RemoteFiles.Entry> ordered=new java.util.ArrayList<>();
  private int request;
  // 排序状态: 0=名称升, 1=名称降, 2=日期升, 3=日期降
  private int sortMode = 3;

  DirectoryPicker(Activity activity, RemoteApp app, boolean dark, Consumer<String> selected) {
    this.app = app;
    this.ui = new RemoteScreens(activity, dark);
    this.selected = selected;
    LinearLayout root = ui.column();
    root.setContentDescription("远端目录选择器");
    dialog = ui.window(root);
    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));
    TextView title=ui.text("选择项目路径",21);
    title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER_VERTICAL);title.setIncludeFontPadding(false);
    header.addView(title,new LinearLayout.LayoutParams(0,ui.dp(56),1));
    IconView close=ui.icon("close","关闭",dialog::dismiss);close.setBackground(null);
    header.addView(close,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
    root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(64)));
    pathLabel = ui.text("", 14);
    pathLabel.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    pathLabel.setTextIsSelectable(true);
    root.addView(pathLabel);
    LinearLayout toolbar = ui.row();
    toolbar.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12));
    up = button("上一级", false, () -> load(RemoteDirectory.parent(displayedPath())));
    LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, -2, 1);
    upParams.setMarginEnd(ui.dp(12));
    toolbar.addView(up, upParams);
    toolbar.addView(
        button("输入路径", false, this::enterPath), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(toolbar);
    status = ui.text("正在读取目录…", 14);
    status.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    root.addView(status);
    // 排序头: 名称 | 日期 两栏, 点击切换键 + 升降序
    LinearLayout sortBar = ui.row();
    sortBar.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4));
    nameCol = ui.text(sortLabel(0), 13);
    nameCol.setTextColor(ui.muted);
    nameCol.setMaxLines(1);
    dateCol = ui.text(sortLabel(2), 13);
    dateCol.setTextColor(ui.muted);
    dateCol.setMaxLines(1);
    dateCol.setGravity(android.view.Gravity.RIGHT);
    ui.clickable(nameCol, () -> { sortMode = (sortMode == 0) ? 1 : 0; sortBarRefresh(nameCol, dateCol); resort(); });
    ui.clickable(dateCol, () -> { sortMode = (sortMode == 2) ? 3 : 2; sortBarRefresh(nameCol, dateCol); resort(); });
    sortBar.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1));
    sortBar.addView(dateCol, new LinearLayout.LayoutParams(-2, -2));
    root.addView(sortBar);
    ScrollView scroll = new ScrollView(activity);
    list = ui.column();
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = ui.row();
    footer.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20));
    create = button("新建文件夹", false, this::newProject);
    LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(0, -2, 1);
    createParams.setMarginEnd(ui.dp(12));
    footer.addView(create, createParams);
    choose =
        button(
            "使用此文件夹",
            true,
            () -> {
              if (directory != null) {
                selected.accept(directory.path);
                dialog.dismiss();
              }
            });
    footer.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(footer);
    dialog.setOnDismissListener(d -> request++);
  }

  private TextView button(String label, boolean primary, Runnable click) {
    TextView view = ui.button(label, primary, click);
    view.setMaxLines(1);
    view.setEllipsize(android.text.TextUtils.TruncateAt.END);
    view.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12));
    return view;
  }

  void show(String initial) {
    ui.show(dialog);
    load(initial);
  }

  void dismiss() {
    dialog.dismiss();
  }

  private void loading() {
    list.removeAllViews();
    clearStatusAction();
    status.setText("正在读取目录…");
    status.setVisibility(View.VISIBLE);
    choose.setEnabled(false);
    choose.setAlpha(.4f);
    up.setEnabled(!displayedPath().equals("/"));
    create.setEnabled(false);
  }

  private String displayedPath() {
    return requestedPath.isEmpty() ? directory==null?"/":directory.path : requestedPath;
  }

  private void load(String path) {
    requestedPath=path==null?"":path;
    pathLabel.setText(requestedPath.isEmpty()?"远端主目录":requestedPath);
    int token = ++request;
    loading();
    app.browseProjectDirectory(
        path,
        d -> {
          if (token != request || !dialog.isShowing()) return;
          display(d);
        },
        error -> {
          if (token != request || !dialog.isShowing()) return;
          status.setText("无法读取此路径，点按查看原因");
          ui.clickable(
              status,
              () ->
                  new AlertDialog.Builder(ui.activity)
                      .setTitle("浏览目录失败")
                      .setMessage(error)
                      .setPositiveButton("关闭", null)
                      .show());
          list.addView(ui.button("返回主目录", false, () -> load("")));
          list.addView(ui.button("重试", false, () -> load(path)));
        });
  }

  private void display(RemoteFiles result) {
    contents=result;requestedPath=result.path;
    directory=new RemoteDirectory(result.path,new java.util.ArrayList<>(),false);
    pathLabel.setText(result.path);
    list.removeAllViews();
    clearStatusAction();
    long folders=result.entries.stream().filter(entry->entry.directory).count();
    status.setText(result.entries.isEmpty()?"此文件夹为空":folders+" 个文件夹 · "+(result.entries.size()-folders)+" 个文件／链接（选择文件夹作为项目）");
    status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
    choose.setEnabled(true);
    choose.setAlpha(1);
    up.setEnabled(!result.path.equals("/"));
    create.setEnabled(true);
    // 刷新排序头状态
    resort();
  }

  /** 按当前 sortMode 重新渲染文件列表 (不重新拉远端) */
  private void resort() {
    if(contents==null)return;
    ordered=new java.util.ArrayList<>(contents.entries);
    ordered.sort(RemoteFiles.projectComparator(sortMode));
    visible=0;list.removeAllViews();appendPage();
    // Keep explicit references: never search arbitrary two-button rows for sort labels.
    sortBarRefresh(nameCol,dateCol);
  }

  private void appendPage() {
    int end=Math.min(visible+100,ordered.size());
    for(;visible<end;visible++) {
      RemoteFiles.Entry entry=ordered.get(visible);
      LinearLayout row=ui.row();row.setPadding(ui.dp(18),ui.dp(6),ui.dp(18),ui.dp(6));
      row.addView(new IconView(ui.activity,entry.directory?"folder":"file",entry.directory?ui.fg:ui.muted),
          new LinearLayout.LayoutParams(ui.dp(38),ui.dp(48)));
      TextView name=ui.text(entry.name,16);name.setPadding(ui.dp(8),0,ui.dp(8),0);
      name.setTextColor(entry.directory?ui.fg:ui.muted);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
      String stamp=entry.modified>0?new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT)
          .format(new java.util.Date(entry.modified*1000)):"";
      TextView date=ui.text(stamp,12);date.setTextColor(ui.muted);row.addView(date);
      ui.clickable(row,()->{
        if(entry.directory || entry.link)load(RemoteDirectory.child(directory.path,entry.name));
        else Toast.makeText(ui.activity,"这是文件，请选择文件夹作为项目",Toast.LENGTH_SHORT).show();
      });
      list.addView(row);
    }
    if(visible<ordered.size()) {
      TextView more=button("显示更多（剩余 "+(ordered.size()-visible)+" 项）",false,()->{});
      more.setOnClickListener(view->{list.removeView(more);appendPage();});list.addView(more);
    }
  }

  private String sortLabel(int mode) {
    if (mode == 0) return "名称 ▲";
    if (mode == 1) return "名称 ▼";
    if (mode == 2) return "日期 ▲";
    return "日期 ▼";
  }

  private void sortBarRefresh(TextView nameCol, TextView dateCol) {
    nameCol.setText(sortLabel(sortMode < 2 ? sortMode : 0));
    dateCol.setText(sortLabel(sortMode >= 2 ? sortMode : 2));
  }

  private void clearStatusAction() {
    status.setOnClickListener(null);
    status.setClickable(false);
    status.setFocusable(false);
    status.setForeground(null);
  }

  private void enterPath() {
    EditText input = new EditText(ui.activity);
    input.setSingleLine(true);
    input.setText(displayedPath());
    input.setContentDescription("远端绝对路径");
    new AlertDialog.Builder(ui.activity)
        .setTitle("输入远端路径")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("打开", (d, w) -> load(input.getText().toString().trim()))
        .show();
  }

  private void newProject() {
    if (directory == null) return;
    EditText name = new EditText(ui.activity);
    name.setSingleLine(true);
    name.setHint("项目名称");
    name.setContentDescription("新项目名称");
    String parent = directory.path;
    AlertDialog prompt =
        new AlertDialog.Builder(ui.activity)
            .setTitle("新建项目")
            .setMessage("在以下目录新建文件夹：\n" + parent)
            .setView(name)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create();
    prompt.setOnShowListener(
        d ->
            prompt
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String value = name.getText().toString().trim();
                      try {
                        RemoteDirectory.child(parent, value);
                      } catch (Exception e) {
                        name.setError(e.getMessage());
                        return;
                      }
                      prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                      int token = ++request;
                      app.createProject(
                          parent,
                          value,
                          result -> {
                            if (token != request || !dialog.isShowing()) return;
                            prompt.dismiss();
                            load(result.path);
                          },
                          error -> {
                            if (token != request || !dialog.isShowing()) return;
                            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            name.setError(error);
                          });
                    }));
    prompt.show();
  }
}
