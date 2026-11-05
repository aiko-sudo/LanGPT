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
    root.setContentDescription(UiText.get(R.string.work_text_147));
    dialog = ui.window(root);
    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));
    TextView title=ui.text(UiText.get(R.string.work_text_148),21);
    title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER_VERTICAL);title.setIncludeFontPadding(false);
    header.addView(title,new LinearLayout.LayoutParams(0,ui.dp(56),1));
    IconView close=ui.icon("close",UiText.get(R.string.work_text_015),dialog::dismiss);close.setBackground(null);
    header.addView(close,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
    root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(64)));
    pathLabel = ui.text("", 14);
    pathLabel.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    pathLabel.setTextIsSelectable(true);
    root.addView(pathLabel);
    LinearLayout toolbar = ui.row();
    toolbar.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12));
    up = button(UiText.get(R.string.work_text_149), false, () -> load(RemoteDirectory.parent(displayedPath())));
    LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, -2, 1);
    upParams.setMarginEnd(ui.dp(12));
    toolbar.addView(up, upParams);
    toolbar.addView(
        button(UiText.get(R.string.work_text_150), false, this::enterPath), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(toolbar);
    status = ui.text(UiText.get(R.string.work_text_151), 14);
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
    create = button(UiText.get(R.string.work_text_152), false, this::newProject);
    LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(0, -2, 1);
    createParams.setMarginEnd(ui.dp(12));
    footer.addView(create, createParams);
    choose =
        button(
            UiText.get(R.string.work_text_153),
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
    status.setText(UiText.get(R.string.work_text_151));
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
    pathLabel.setText(requestedPath.isEmpty()?UiText.get(R.string.work_text_154):requestedPath);
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
          status.setText(UiText.get(R.string.work_text_155));
          ui.clickable(
              status,
              () ->
                  new AlertDialog.Builder(ui.activity)
                      .setTitle(UiText.get(R.string.work_text_156))
                      .setMessage(error)
                      .setPositiveButton(UiText.get(R.string.work_text_015), null)
                      .show());
          list.addView(ui.button(UiText.get(R.string.work_text_157), false, () -> load("")));
          list.addView(ui.button(UiText.get(R.string.work_text_158), false, () -> load(path)));
        });
  }

  private void display(RemoteFiles result) {
    contents=result;requestedPath=result.path;
    directory=new RemoteDirectory(result.path,new java.util.ArrayList<>(),false);
    pathLabel.setText(result.path);
    list.removeAllViews();
    clearStatusAction();
    long folders=result.entries.stream().filter(entry->entry.directory).count();
    status.setText(result.entries.isEmpty()?UiText.get(R.string.work_text_159):folders+UiText.get(R.string.work_text_160)+(result.entries.size()-folders)+UiText.get(R.string.work_text_161));
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
        else Toast.makeText(ui.activity,UiText.get(R.string.work_text_162),Toast.LENGTH_SHORT).show();
      });
      list.addView(row);
    }
    if(visible<ordered.size()) {
      TextView more=button(UiText.get(R.string.work_text_070)+(ordered.size()-visible)+UiText.get(R.string.work_text_071),false,()->{});
      more.setOnClickListener(view->{list.removeView(more);appendPage();});list.addView(more);
    }
  }

  private String sortLabel(int mode) {
    if (mode == 0) return UiText.get(R.string.work_text_163);
    if (mode == 1) return UiText.get(R.string.work_text_164);
    if (mode == 2) return UiText.get(R.string.work_text_165);
    return UiText.get(R.string.work_text_166);
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
    input.setContentDescription(UiText.get(R.string.work_text_167));
    new AlertDialog.Builder(ui.activity)
        .setTitle(UiText.get(R.string.work_text_168))
        .setView(input)
        .setNegativeButton(UiText.get(R.string.work_text_004), null)
        .setPositiveButton(UiText.get(R.string.work_text_005), (d, w) -> load(input.getText().toString().trim()))
        .show();
  }

  private void newProject() {
    if (directory == null) return;
    // House rule: projects may only be created under ~/uclaw/project. If the
    // user browsed elsewhere, jump to the root instead of refusing.
    app.resolveProjectRoot(
        root -> {
          if (root.isEmpty()) {
            Toast.makeText(ui.activity, UiText.get(R.string.work_text_155), Toast.LENGTH_SHORT).show();
            return;
          }
          if (!directory.path.equals(root) && !directory.path.startsWith(root + "/")) {
            load(root);
            Toast.makeText(ui.activity, UiText.get(R.string.work_text_230), Toast.LENGTH_SHORT).show();
            return;
          }
          showCreatePrompt();
        });
  }

  private void showCreatePrompt() {
    EditText name = new EditText(ui.activity);
    name.setSingleLine(true);
    name.setHint(UiText.get(R.string.work_text_169));
    name.setContentDescription(UiText.get(R.string.work_text_170));
    String parent = directory.path;
    AlertDialog prompt =
        new AlertDialog.Builder(ui.activity)
            .setTitle(UiText.get(R.string.work_text_171))
            .setMessage(UiText.get(R.string.work_text_172) + "\n" + parent)
            .setView(name)
            .setNegativeButton(UiText.get(R.string.work_text_004), null)
            .setPositiveButton(UiText.get(R.string.work_text_173), null)
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
