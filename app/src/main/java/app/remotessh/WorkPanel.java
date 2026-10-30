package app.remotessh;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * Read-only work overview grouped by project. The whole process list is folded
 * under one project card (collapsed by default, counts only); expanding reveals
 * per-item detail. If the conversation's project has been removed from the
 * connection's project list, its section is not rendered.
 */
final class WorkPanel {
  private static final String KEY_PROJECT = "project";

  private static LinearLayout card(RemoteScreens ui, LinearLayout body) {
    LinearLayout card = ui.column();
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(ui.panel);
    bg.setCornerRadius(ui.dp(16));
    card.setBackground(bg);
    card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = ui.dp(8);
    body.addView(card, lp);
    return card;
  }

  private static void note(RemoteScreens ui, LinearLayout body, String text) {
    TextView label = ui.text(text, 14);
    label.setTextColor(ui.muted);
    label.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(8));
    body.addView(label);
  }

  private static String status(String value) {
    switch (value) {
      case "inProgress": return "进行中";
      case "completed": return "已完成";
      case "failed": return "失败";
      case "interrupted": return "已中断";
      default: return value;
    }
  }

  private static void itemRow(RemoteScreens ui, LinearLayout group, String title,
      String state, String detail, String key, Set<String> expanded) {
    LinearLayout row = ui.column();
    TextView heading = ui.text(title.isEmpty() ? "操作详情" : title, 14);
    heading.setMaxLines(2);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(heading);
    if (!state.isEmpty()) {
      TextView subtitle = ui.text(state, 12);
      subtitle.setTextColor(ui.muted);
      subtitle.setPadding(0, ui.dp(3), 0, 0);
      row.addView(subtitle);
    }
    if (detail != null && !detail.trim().isEmpty()) {
      TextView toggle = ui.text(expanded.contains(key) ? "收起详情" : "展开详情", 12);
      toggle.setTextColor(0xff007aff);
      toggle.setPadding(0, ui.dp(8), 0, ui.dp(2));
      row.addView(toggle);
      TextView content = ui.text(detail, 12);
      content.setTextIsSelectable(true);
      content.setTypeface(Typeface.MONOSPACE);
      content.setPadding(0, ui.dp(10), 0, 0);
      content.setVisibility(expanded.contains(key) ? View.VISIBLE : View.GONE);
      row.addView(content);
      toggle.setOnClickListener(v -> {
        boolean open = !expanded.contains(key);
        if (open) expanded.add(key); else expanded.remove(key);
        content.setVisibility(open ? View.VISIBLE : View.GONE);
        toggle.setText(open ? "收起详情" : "展开详情");
      });
    }
    group.addView(row);
  }

  /** Bottom sheet with the task process; batches rows so huge histories never freeze the panel. */
  private static void showProcess(Activity activity, RemoteScreens ui, ConversationState task) {
    Dialog process = new Dialog(activity);
    process.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    LinearLayout sheet = ui.column();
    GradientDrawable background = new GradientDrawable();
    background.setColor(ui.panel);
    background.setCornerRadius(ui.dp(16));
    sheet.setBackground(background);
    sheet.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8));
    LinearLayout head = ui.row();
    java.util.List<ChatItem> steps = new java.util.ArrayList<>();
    for (ChatItem item : task.items) if (!item.prose()) steps.add(item);
    TextView name = ui.text((task.title.isEmpty() ? "操作详情" : task.title) + " · 共 " + steps.size() + " 步", 15);
    name.setTypeface(null, Typeface.BOLD);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    head.addView(name, new LinearLayout.LayoutParams(0, ui.dp(40), 1));
    head.addView(ui.button("收起", false, process::dismiss));
    sheet.addView(head);
    ScrollView body = new ScrollView(activity);
    body.setVerticalScrollBarEnabled(true);
    LinearLayout rows = ui.column();
    body.addView(rows);
    sheet.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    Set<String> detailOpen = new HashSet<>();
    int[] shown = {0};
    Runnable[] fill = new Runnable[1];
    fill[0] = () -> {
      int end = Math.min(shown[0] + 30, steps.size());
      for (; shown[0] < end; shown[0]++) {
        ChatItem step = steps.get(shown[0]);
        itemRow(ui, rows, step.text, status(step.status), step.detail,
            task.threadId + ":" + step.id, detailOpen);
      }
      if (shown[0] < steps.size()) {
        TextView more = ui.button("显示更多（剩余 " + (steps.size() - shown[0]) + " 项）", false, () -> {});
        more.setOnClickListener(button -> { rows.removeView(more); fill[0].run(); });
        rows.addView(more);
      } else if (steps.isEmpty()) {
        TextView none = ui.text("无过程记录", 13);
        none.setTextColor(ui.muted);
        rows.addView(none);
      }
    };
    fill[0].run();
    process.setContentView(sheet);
    process.show();
    if (process.getWindow() != null) {
      process.getWindow().setGravity(Gravity.BOTTOM);
      process.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
      process.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels, ui.dp(420)),
          Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.60f));
    }
  }

  static Dialog show(Activity activity, RemoteApp app, boolean dark, Runnable start, Runnable error, Runnable info, java.util.function.Consumer<String> open) {
    RemoteScreens ui = new RemoteScreens(activity, dark);
    LinearLayout root = ui.column();
    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
    TextView title = ui.text("工作面板", 21);
    title.setTypeface(null, Typeface.BOLD);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setIncludeFontPadding(false);
    header.addView(title, new LinearLayout.LayoutParams(0, ui.dp(56), 1));
    root.addView(header, new LinearLayout.LayoutParams(-1, ui.dp(64)));
    int[] selectedFilter = {0};
    Runnable[] renderRef = new Runnable[1];
    LinearLayout filters = ui.row();
    String[] filterNames = {"全部", "待处理", "运行中"};
    TextView[] filterLabels = new TextView[filterNames.length];
    for(int n=0;n<filterNames.length;n++) {
      final int choice=n;
      TextView filter=ui.text(filterNames[n],14);filter.setGravity(Gravity.CENTER);
      filter.setPadding(0,ui.dp(10),0,ui.dp(10));
      filter.setOnClickListener(v->{selectedFilter[0]=choice;if(renderRef[0]!=null)renderRef[0].run();});
      filters.addView(filter,new LinearLayout.LayoutParams(0,ui.dp(44),1));filterLabels[n]=filter;
    }
    root.addView(filters);
    boolean[] panelOpen={true}, refreshing={false};
    int[] refreshGeneration={-1}, refreshSequence={0};
    TextView refreshHint=ui.text("",12);refreshHint.setGravity(Gravity.CENTER);
    refreshHint.setTextColor(ui.muted);refreshHint.setVisibility(View.GONE);root.addView(refreshHint);
    PullRefreshScrollView[] scrollRef=new PullRefreshScrollView[1];
    Runnable timeout=()->{
      if(panelOpen[0] && refreshing[0]) {
        refreshing[0]=false;scrollRef[0].finishRefresh();
        refreshHint.setText("刷新超时，可再次下拉重试");refreshHint.setVisibility(View.VISIBLE);
      }
    };
    PullRefreshScrollView scroll=new PullRefreshScrollView(activity,()->{
      if(!app.connected) {
        scrollRef[0].finishRefresh();refreshHint.setText("请先连接电脑");refreshHint.setVisibility(View.VISIBLE);return;
      }
      int request=++refreshSequence[0];refreshing[0]=true;refreshGeneration[0]=app.connectionGeneration();
      refreshHint.removeCallbacks(timeout);refreshHint.postDelayed(timeout,15000);
      app.refreshWorkPanel(failure->{
        if(!panelOpen[0] || request!=refreshSequence[0])return;
        refreshing[0]=false;refreshHint.removeCallbacks(timeout);scrollRef[0].finishRefresh();
        if(failure!=null){refreshHint.setText("刷新失败，请下拉重试");refreshHint.setVisibility(View.VISIBLE);}
      });
    },message->{refreshHint.setText(message);refreshHint.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);});
    scrollRef[0]=scroll;
    // Horizontal fling anywhere on the panel list switches filter tabs.
    final android.view.GestureDetector tabSwipe=new android.view.GestureDetector(activity,
        new android.view.GestureDetector.SimpleOnGestureListener(){
          @Override public boolean onFling(android.view.MotionEvent from,android.view.MotionEvent to,float vx,float vy){
            if(from==null||to==null||Math.abs(vx)<1500||Math.abs(vx)<=Math.abs(vy)*1.5f)return false;
            int next=selectedFilter[0]+(vx<0?1:-1);
            if(next<0||next>=filterLabels.length)return true;
            selectedFilter[0]=next;
            if(renderRef[0]!=null)renderRef[0].run();
            return true;
          }
        });
    scroll.setOnTouchListener((view,event)->{tabSwipe.onTouchEvent(event);return false;});
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Dialog dialog = ui.window(root);
    View more = ui.icon("more", "更多对话操作", () -> {});
    more.setContentDescription("更多对话操作");
    more.setBackground(null);
    header.addView(more, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    more.setOnClickListener(v -> CenteredMenu.show(activity, ui.fg,
        new String[]{"新建会话", "最近报错"}, which -> {
          dialog.dismiss();if(which==0)start.run();else error.run();
        }));
    java.util.List<Runnable> clockUpdates = new java.util.ArrayList<>();
    Runnable render = () -> {
      if(refreshing[0] && (!app.connected || refreshGeneration[0]!=app.connectionGeneration())) {
        refreshing[0]=false;refreshSequence[0]++;refreshHint.removeCallbacks(timeout);scroll.finishRefresh();
      }
      int position = scroll.getScrollY();
      body.removeAllViews();
      clockUpdates.clear();
      java.util.Map<String, java.util.List<ConversationState>> projects = new java.util.LinkedHashMap<>();
      int active = 0, approvals = 0, attention = 0;
      String profileId=app.profile==null?"":app.profile.id;
      java.util.List<ConversationState> tasks=new java.util.ArrayList<>(app.workConversations());
      tasks.sort(java.util.Comparator.comparingInt(task -> app.taskInbox.rank(profileId,task.threadId,
          task.remoteRunning || task.busy,!task.approvals.isEmpty(),task.error)));
      for(int n=0;n<filterLabels.length;n++) {
        filterLabels[n].setTextColor(selectedFilter[0]==n?0xff007aff:ui.muted);
        filterLabels[n].setTypeface(null,selectedFilter[0]==n?Typeface.BOLD:Typeface.NORMAL);
      }
      for (ConversationState task : tasks) {
        String path = task.cwd;
        if (app.profile != null && !path.isEmpty() && !path.equals(app.profile.cwd)
            && !app.profile.projects.contains(path)) continue;
        boolean running=task.remoteRunning || task.busy;
        boolean needs=app.taskInbox.attention(profileId,task.threadId,running,!task.approvals.isEmpty(),task.error);
        if(running || !task.approvals.isEmpty())active++;
        approvals += task.approvals.size();
        if(needs)attention++;
        if(selectedFilter[0]==1 && !needs)continue;
        if(selectedFilter[0]==2 && (!app.connected || !running))continue;
        projects.computeIfAbsent(path, ignored -> new java.util.ArrayList<>()).add(task);
      }
      LinearLayout overview = card(ui, body);
      TextView state = ui.text(!app.connected ? "连接已断开 · 状态待同步"
          : "运行 " + active + " · 待处理 " + attention + " · 待审批 " + approvals, 17);
      state.setTypeface(null, Typeface.BOLD);
      overview.addView(state);
      note(ui, overview, "点击任务进入；返回手势回到此面板，不中断执行");
      if (projects.isEmpty()) note(ui, body, !app.connected && selectedFilter[0]>=2
          ? "连接后同步任务状态" : "当前筛选下暂无任务");
      for (java.util.Map.Entry<String, java.util.List<ConversationState>> entry : projects.entrySet()) {
        String path = entry.getKey();
        LinearLayout section = card(ui, body);
        TextView projectTitle = ui.text((path.isEmpty() ? "远端默认目录" : RemoteDirectory.name(path))
            + " · " + entry.getValue().size() + " 个任务", 16);
        projectTitle.setTypeface(null, Typeface.BOLD);
        section.addView(projectTitle);
        for (ConversationState task : entry.getValue()) {
          LinearLayout taskRow = ui.column();
          taskRow.setPadding(0, ui.dp(12), 0, ui.dp(10));
          TextView taskTitle = ui.text(task.title.isEmpty() ? "未命名任务" : task.title, 15);
          taskTitle.setMaxLines(1);
          taskTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
          taskRow.addView(taskTitle);
          boolean running = task.remoteRunning || task.busy;
          String taskState = !app.connected ? "状态待同步" : !task.approvals.isEmpty() ? "待审批"
              : running ? "运行中" : task.error.isEmpty() ? "空闲 / 已结束" : "出现错误";
          TextView elapsed = ui.text(taskState + " · " + app.taskProgress(task.threadId).elapsed(System.currentTimeMillis()), 12);
          elapsed.setTextColor(ui.muted);
          taskRow.addView(elapsed);
          clockUpdates.add(() -> elapsed.setText(taskState + " · "
              + app.taskProgress(task.threadId).elapsed(System.currentTimeMillis())));
          int tools = 0, done = 0, files = 0;
          String latest = "";
          for (ChatItem item : task.items) {
            if (item.type.equals("fileChange")) files++;
            if (!item.prose()) { tools++; if (item.status.equals("completed")) done++; }
            if (!item.user() && !item.text.trim().isEmpty()) latest = item.text;
          }
          if (!latest.isEmpty()) {
            TextView progress = ui.text(latest.replace('\n', ' '), 13);
            progress.setMaxLines(2);
            progress.setEllipsize(android.text.TextUtils.TruncateAt.END);
            progress.setPadding(0, ui.dp(6), 0, 0);
            taskRow.addView(progress);
          }
          TextView counts = ui.text("活动完成 " + done + "/" + tools + " · 文件变更 " + files + " · 点击进入", 12);
          counts.setTextColor(0xff007aff);
          counts.setPadding(0, ui.dp(6), 0, 0);
          // Only this explicit label opens the task, so the card body stays free for tab swiping.
          counts.setOnClickListener(v -> {
            if (!app.canSwitchConversation()) {
              android.widget.Toast.makeText(activity, "正在同步会话或上传附件，请稍候", android.widget.Toast.LENGTH_SHORT).show();
              return;
            }
            dialog.dismiss();
            open.accept(task.threadId);
          });
          taskRow.addView(counts);
          TaskResult result=app.taskInbox.result(profileId,task.threadId);
          if(!running && result!=null) {
            TextView resultTitle=ui.text(result.label()+(app.taskInbox.unread(profileId,task.threadId)?" · 未读结果":""),14);
            resultTitle.setTypeface(null,Typeface.BOLD);resultTitle.setPadding(0,ui.dp(10),0,0);
            resultTitle.setTextColor(result.failed()?0xffdf4b43:ui.fg);taskRow.addView(resultTitle);
            TextView summary=ui.text(result.preview(),13);summary.setMaxLines(3);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);taskRow.addView(summary);
            TextView resultAction=ui.text("查看结果 · 改动 "+result.paths.size()+" · 执行 "+result.commands.size(),13);
            resultAction.setTextColor(0xff007aff);resultAction.setPadding(0,ui.dp(8),0,ui.dp(8));
            resultAction.setOnClickListener(v->{
              android.app.AlertDialog details=new android.app.AlertDialog.Builder(activity)
                  .setTitle(result.label()).setMessage(result.details()).setPositiveButton("关闭",null).show();
              TextView message=details.findViewById(android.R.id.message);
              if(message!=null)message.setTextIsSelectable(true);
              app.taskInbox.read(profileId,task.threadId,result);app.changed();
            });
            taskRow.addView(resultAction);
          }
          section.addView(taskRow);
          // Long-press a card to delete residue entries (e.g. unnamed stubs the remote forgot).
          taskRow.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(activity)
                .setTitle("删除该任务记录？")
                .setMessage((task.title.isEmpty() ? "未命名任务" : task.title) + "\n将从远端与本地一并移除；远端已丢失时仅清本地。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> app.deleteThread(task.threadId, failure -> {
                  if (failure == null) android.widget.Toast.makeText(activity, "已删除", android.widget.Toast.LENGTH_SHORT).show();
                  else android.widget.Toast.makeText(activity, "删除失败：" + failure.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                })).show();
            return true;
          });
          TextView toggle = ui.text("展开过程", 12);
          toggle.setTextColor(ui.muted);
          toggle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
          toggle.setPadding(0, ui.dp(4), 0, ui.dp(8));
          section.addView(toggle);
          toggle.setOnClickListener(v -> showProcess(activity, ui, task));
        }
      }
      scroll.post(() -> scroll.scrollTo(0, position));
    };
    renderRef[0]=render;
    Runnable ticker = new Runnable() {
      public void run() { if (dialog.isShowing()) {
        for (Runnable update : clockUpdates) update.run();
        body.postDelayed(this, 1000);
      } }
    };
    dialog.setOnDismissListener(d -> {
      panelOpen[0]=false;refreshSequence[0]++;refreshHint.removeCallbacks(timeout);scroll.finishRefresh();
      app.stateListeners.remove(render);
      body.removeCallbacks(ticker);
    });
    app.stateListeners.add(render);
    render.run();
    ui.show(dialog);
    body.postDelayed(ticker, 1000);
    dialog.getWindow().setGravity(Gravity.RIGHT);
    dialog.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels, ui.dp(420)), -1);
    return dialog;
  }
}
