package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** SFTP page. Reuses saved SSH identity; never resumes a coding task. */
final class SftpBrowser {
  private final RemoteApp app;
  private final RemoteScreens ui;
  private final Profile profile;
  private final Dialog dialog;
  private final TextView pathLabel, status;
  private final LinearLayout list;
  private final Runnable observer;
  private String path;
  private RemoteFiles files;
  private int token, visible;
  private int sortMode=1;
  private String summary = "";
  private final PullRefreshScrollView scroll;
  private boolean loading, waiting;

  SftpBrowser(Activity activity, RemoteApp app, boolean dark, String project) {
    this.app=app; ui=new RemoteScreens(activity,dark);
    profile=app.profile!=null ? app.profile : app.vault.profiles.isEmpty() ? null : app.vault.profiles.get(0);
    path=project==null ? "" : project;
    LinearLayout root=ui.column();
    LinearLayout header=ui.row();
    header.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));
    TextView title=ui.text("SFTP",21);title.setTypeface(null,Typeface.BOLD);
    title.setGravity(android.view.Gravity.CENTER_VERTICAL);title.setIncludeFontPadding(false);
    header.addView(title,new LinearLayout.LayoutParams(0,ui.dp(56),1));
    header.addView(ui.button("上一级",false,()->load(RemoteDirectory.parent(path))));
    header.addView(ui.button("排序",false,this::showSort));
    header.addView(ui.button("上传",false,()->openFile(path,true)));
    root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(64)));
    pathLabel=ui.text(path.isEmpty()?"当前连接的工作目录":path,13);
    pathLabel.setTextIsSelectable(true);pathLabel.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
    root.addView(pathLabel);
    status=ui.text("",13);status.setTextColor(ui.muted);
    status.setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(8));root.addView(status);
    scroll=new PullRefreshScrollView(activity, () -> ensureConnection(true), message ->
        status.setText(message.isEmpty() ? summary : message));
    list=ui.column();
    list.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(20));scroll.addView(list);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    dialog=ui.window(root);
    observer=()->{
      if (!waiting || !dialog.isShowing()) return;
      if (app.connected) {
        waiting=false;
        if (sameProfile()) load(path); else status.setText("连接已切换，请关闭后重新打开 SFTP");
      } else if (app.connecting) status.setText(app.state);
      else { waiting=false;scroll.finishRefresh();status.setText(app.error.isEmpty()?"尚未连接，下拉刷新重试":app.error); }
    };
    dialog.setOnDismissListener(d->{token++;waiting=false;app.stateListeners.remove(observer);});
  }

  void show() {
    ui.show(dialog);
    app.stateListeners.add(observer);
    ensureConnection(false);
  }

  void dismiss() { dialog.dismiss(); }

  private void openFile(String target,boolean upload) {
    if(!sameProfile()){status.setText("连接已切换，请重新打开 SFTP");return;}
    SftpFileActivity.open(ui.activity,app,target,upload,ui.bg==android.graphics.Color.BLACK,false);
  }

  /** Hold 1s copies the full path; keep holding to 3s for save-to-device. */
  private void attachHoldActions(View row,String full,String name,long size) {
    android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable[] copy=new Runnable[1];Runnable[] save=new Runnable[1];float[] origin=new float[2];
    int slop=android.view.ViewConfiguration.get(ui.activity).getScaledTouchSlop();
    row.setOnTouchListener((view,event)->{
      switch(event.getActionMasked()) {
        case android.view.MotionEvent.ACTION_DOWN:
          origin[0]=event.getX();origin[1]=event.getY();
          copy[0]=()->copyPath(full);save[0]=()->offerSave(full,name,size);
          timer.postDelayed(copy[0],1000);timer.postDelayed(save[0],3000);
          break;
        case android.view.MotionEvent.ACTION_MOVE:
          float dx=event.getX()-origin[0],dy=event.getY()-origin[1];
          if(dx*dx+dy*dy>slop*slop){timer.removeCallbacks(copy[0]);timer.removeCallbacks(save[0]);}
          break;
        default:
          timer.removeCallbacks(copy[0]);timer.removeCallbacks(save[0]);break;
      }
      return false;
    });
  }

  private void copyPath(String full) {
    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
        ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    if(clipboard!=null) {
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("文件路径",full));
      android.widget.Toast notice=android.widget.Toast.makeText(ui.activity,"路径已复制",android.widget.Toast.LENGTH_SHORT);
      notice.setGravity(android.view.Gravity.BOTTOM|android.view.Gravity.CENTER_HORIZONTAL,0,ui.dp(64));
      notice.show();
    }
  }

  private void offerSave(String full,String name,long size) {
    if(ui.activity instanceof MainActivity) {
      MainActivity m=(MainActivity)ui.activity;
      m.pendingSftpSave=new String[]{full,name==null||name.isEmpty()?"download":name,Long.toString(size)};
    }
    SftpDownloadDialog.pickLocation(ui.activity,name);
  }

  private boolean sameProfile() { return profile!=null && app.profile!=null && profile.id.equals(app.profile.id); }

  private void ensureConnection(boolean force) {
    if (loading) { scroll.finishRefresh(); return; }
    if (profile==null) { scroll.finishRefresh();status.setText("请先添加连接");return; }
    if (path.isEmpty()) path=profile.cwd;
    if (!force) {
      RemoteFiles cached=app.cachedSftp(profile,path);
      if (cached!=null) { display(cached,true);return; }
    }
    if (app.connected) {
      if (sameProfile()) load(path); else { scroll.finishRefresh();status.setText("连接已切换，请重新打开 SFTP"); }
    } else {
      waiting=false;status.setText("正在连接当前主机…");
      if (!app.connecting) app.connect(profile);
      waiting=true;
      observer.run();
    }
  }

  private void load(String target) {
    if (loading) { scroll.finishRefresh(); return; }
    if (!app.connected) { path=target;ensureConnection(true);return; }
    if (!sameProfile()) { scroll.finishRefresh();status.setText("连接已切换，请重新打开 SFTP");return; }
    SshTransport transport=app.currentTransport();
    if (transport==null) { scroll.finishRefresh();status.setText("SSH 尚未就绪，请下拉刷新");return; }
    int request=++token, generation=app.connectionGeneration();
    loading=true; status.setText("正在读取目录…");
    app.executeBackground(()->{
      try {
        RemoteFiles result=transport.listFiles(target);
        app.main.post(()->{
          if (request!=token || !dialog.isShowing()) return;
          loading=false;
          if (generation!=app.connectionGeneration() || !sameProfile()) { scroll.finishRefresh();status.setText("连接已改变，请重新打开目录");return; }
          app.cacheSftp(profile,target,result);
          display(result,false);
        });
      } catch (Exception e) {
        app.main.post(()->{
          if (request!=token || !dialog.isShowing()) return;
          loading=false;scroll.finishRefresh();
          String message=e.getMessage()==null?"":e.getMessage();
          // sshd rejects channel opens once its per-connection session slots are
          // exhausted ("no more sessions"); point the user at reconnect instead
          // of a raw protocol string.
          if(message.contains("channel is not opened")||message.contains("no more sessions"))
            message="暂时无法打开 SSH 文件通道，请稍后下拉重试；持续失败请断开重连";
          else if(message.isEmpty())message="请检查连接和目录权限";
          status.setText("目录读取失败："+message);
        });
      }
    });
  }

  private void display(RemoteFiles result, boolean cached) {
    files=result.sorted(sortMode);path=result.path;visible=0;
    pathLabel.setText(path);list.removeAllViews();
    summary=result.entries.size()+" 项 · "+(cached?"缓存，下拉更新":"点击预览 · 按住1秒复制路径 · 3秒保存");
    scroll.finishRefresh();status.setText(summary);
    appendPage();
  }

  private void showSort() {
    String[] modes={"名称","日期","大小"};
    String[] rows=new String[modes.length];
    for(int i=0;i<modes.length;i++)rows[i]=(i==sortMode?"✓ ":"")+modes[i];
    CenteredMenu.show(ui.activity,ui.fg,ui.muted,"排序",rows,which->{
      sortMode=which;
      if(files!=null) {
        files=files.sorted(sortMode);visible=0;list.removeAllViews();appendPage();
        scroll.scrollTo(0,0);
      }
    });
  }

  private void appendPage() {
    if (files==null) return;
    int end=Math.min(visible+100,files.entries.size());
    for (;visible<end;visible++) {
      RemoteFiles.Entry entry=files.entries.get(visible);
      LinearLayout row=ui.row();row.setPadding(0,ui.dp(8),0,ui.dp(8));
      row.addView(new IconView(ui.activity,entry.directory?"folder":"file",ui.fg),new LinearLayout.LayoutParams(ui.dp(32),ui.dp(44)));
      LinearLayout content=ui.column();
      TextView name=ui.text(entry.name,15);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      content.addView(name);
      row.addView(content,new LinearLayout.LayoutParams(0,-2,1));
      LinearLayout facts=ui.column();facts.setGravity(android.view.Gravity.CENTER_VERTICAL);
      TextView size=ui.text(entry.directory||entry.link?"":RemoteFiles.humanSize(entry.size),11);
      size.setTextColor(ui.muted);size.setGravity(android.view.Gravity.RIGHT);facts.addView(size);
      TextView when=ui.text(entry.modified>0?new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT)
          .format(new java.util.Date(entry.modified*1000)):"",11);
      when.setTextColor(ui.muted);when.setGravity(android.view.Gravity.RIGHT);facts.addView(when);
      row.addView(facts,new LinearLayout.LayoutParams(-2,-2));
      ui.clickable(row,()->{
        String full=RemoteDirectory.child(path,entry.name);
        if (entry.directory || entry.link) load(full);
        // Skip the text viewer entirely for known binaries or oversize files: straight to phone apps.
        else if(!RemoteFiles.isLikelyText(entry.name) || entry.size>SftpContent.TEXT_LIMIT)
          SftpFileActivity.openExternal(ui.activity,app,full);
        else openFile(full,false);
      });
      attachHoldActions(row,RemoteDirectory.child(path,entry.name),entry.name,entry.size);
      list.addView(row);
    }
    if (visible<files.entries.size()) {
      TextView more=ui.button("显示更多（剩余 "+(files.entries.size()-visible)+" 项）",false,()->{});
      more.setOnClickListener(v->{list.removeView(more);appendPage();});list.addView(more);
    } else if (files.entries.isEmpty()) {
      TextView empty=ui.text("此文件夹为空",14);empty.setTextColor(ui.muted);list.addView(empty);
    }
  }
}
