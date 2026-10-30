package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in experiment; never changes task/project/profile selection itself. */
public final class GitWorkspaceDialog {
  private final Activity activity;
  private final RemoteApp app;
  private final RemoteScreens ui;
  private final String project;
  private final Consumer<String> useWorkspace;
  private final SshTransport transport;
  private final Profile profile;
  private final int generation;
  private final LinearLayout content;
  private final Dialog window;
  private final PullRefreshScrollView scroll;
  private final View retry;
  private AlertDialog child;
  private boolean closed, busy;

  public static void show(Activity activity, RemoteApp app, boolean dark, String project,
      Consumer<String> useWorkspace) {
    new GitWorkspaceDialog(activity, app, dark, project, useWorkspace).open();
  }

  private GitWorkspaceDialog(Activity activity, RemoteApp app, boolean dark, String project,
      Consumer<String> useWorkspace) {
    this.activity=activity; this.app=app; this.project=project; this.useWorkspace=useWorkspace;
    ui=new RemoteScreens(activity,dark); transport=app.currentTransport();
    profile=app.profile; generation=app.connectionGeneration();
    LinearLayout root=ui.column();
    TextView hint=ui.text("",14);
    hint.setVisibility(View.GONE);
    root.addView(hint);
    scroll=new PullRefreshScrollView(activity, this::refresh, message->{
      hint.setText(message); hint.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    });
    content=ui.column();
    int pad=ui.dp(16); content.setPadding(pad,pad,pad,pad);
    scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    retry=ui.button("重试",false,this::refresh);
    retry.setVisibility(View.GONE);
    root.addView(retry);
    window=ui.window(root);
    window.setOnDismissListener(d->{closed=true; if(child!=null)child.dismiss();});
  }

  private void close() { window.dismiss(); }
  private boolean valid() {
    return !closed && !activity.isFinishing() && !activity.isDestroyed()
        && app.connected && app.profile==profile && app.currentTransport()==transport
        && app.connectionGeneration()==generation;
  }
  private void open() {
    ui.show(window);
    refresh();
  }
  /** Map host refusals to actionable Chinese hints; keep the original text visible. */
  private static String explain(String error) {
    if(error.contains("not found on host"))return "主机找不到 git 命令：请安装 Git 或把 git 加入 PATH 后重试";
    if(error.contains("Not inside a Git working tree"))return "当前文件夹不是 Git 仓库：此功能只适用于有 .git 的项目根目录，普通文件夹无法使用";
    if(error.contains("Select the repository root"))return "当前路径在仓库子目录里：请回到仓库根目录再打开";
    if(error.contains("Bare repository"))return "裸仓库（只有历史没有工作文件）不支持";
    if(error.contains("Shallow repositories"))return "浅克隆仓库暂不支持";
    if(error.contains("Submodules"))return "含子模块的仓库暂不支持";
    if(error.contains("must be clean"))return "工作树有未提交改动：先提交或清理后再操作";
    if(error.contains("in progress"))return "仓库正在进行 merge/rebase 等操作，完成后再试";
    if(error.contains("Another app workspace"))return "另一个工作区操作正在进行，稍后刷新";
    return "请检查此项目是否为 Git 仓库根目录后重试";
  }
  private void text(String value) {
    TextView view=ui.text(value,15); view.setTextIsSelectable(true); content.addView(view);
  }
  private void confirm(String message,Runnable action) {
    if(!valid() || busy)return;
    child=new AlertDialog.Builder(activity).setTitle("需要确认").setMessage(message)
        .setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->{if(valid())action.run();}).create();
    child.show();
  }
  private JSONObject request(String action) { return Json.obj("action",action,"project",project); }
  private void call(JSONObject params,Consumer<JSONObject> done) {
    if(busy || !valid()){ scroll.finishRefresh(); return; }
    boolean statusRequest=params.optString("action").equals("status");
    if(statusRequest)retry.setVisibility(View.GONE);
    busy=true; content.removeAllViews(); text("检查中…");
    app.executeBackground(()->{
      JSONObject result;
      try(InputStream in=app.getAssets().open("git_workspace.py");
          ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
        MediaFiles.copy(in,bytes,65536);
        if(transport==null || app.connectionGeneration()!=generation
            || app.currentTransport()!=transport || app.profile!=profile)throw new IllegalStateException();
        result=transport.maintenance(bytes.toString("UTF-8"),params);
      }catch(Exception error){result=Json.obj("error","SSH 工作区操作失败；请检查连接并刷新仓库状态后重试");}
      final JSONObject reply=result;
      app.main.post(()->{
        busy=false;
        if(statusRequest)scroll.finishRefresh();
        if(!valid())return;
        if(reply.has("error")){
          String error=reply.optString("error");
          content.removeAllViews();
          text("无法加载："+(params.optString("action").equals("status") ? explain(error)+"\n("+error+")" : error));
          if(statusRequest)retry.setVisibility(View.VISIBLE);
          return;
        }
        done.accept(reply);
      });
    });
  }
  private void refresh() {
    call(request("status"), status->{
      content.removeAllViews();
      text("在项目旁建 Git 隔离副本，任务改动不影响主目录，确认后快进合并回来");
      if(!status.optBoolean("hasGit")) text("✗ 未安装 Git → 此功能不可用");
      else if(!status.optBoolean("isGitRoot")) text("✗ 非 Git 仓库根目录 → 此功能不可用");
      else {
        text("✓ Git 仓库根目录");
        text(status.optBoolean("dirty") ? "✗ 未提交改动 "+status.optInt("dirtyCount")+" 个 → 请先提交" : "✓ 工作目录干净");
        text(status.optBoolean("inProgress") ? "✗ Git 操作进行中 → 请先完成" : "✓ 无进行中的 Git 操作");
        text("分支："+status.optString("branch")+"\nHEAD："+status.optString("head"));
        if(!status.optString("reason").isEmpty() && !status.optBoolean("inProgress")) text("✗ "+status.optString("reason"));
      }
      if(!status.optBoolean("ready"))return;
      content.addView(ui.button("创建独立工作区",false,()->confirm(
          "从此 HEAD 创建唯一分支和隔离目录？现有任务目录不会改变。\n"+status.optString("head"),
          ()->call(Json.obj("action","create","project",project,"confirmed",true,
              "targetHead",status.optString("head")),created->{text("已创建："+created.optString("created"));refresh();}))));
      call(request("list"),reply->{
        content.removeAllViews();
        text("在项目旁建 Git 隔离副本，任务改动不影响主目录，确认后快进合并回来");
        text("✓ Git 仓库根目录\n✓ 工作目录干净\n✓ 无进行中的 Git 操作\n分支："+status.optString("branch")+"\nHEAD："+status.optString("head"));
          content.addView(ui.button("创建独立工作区",false,()->confirm("从此 HEAD 创建唯一分支和隔离目录？\n"+status.optString("head"),
            ()->call(Json.obj("action","create","project",project,"confirmed",true,"targetHead",status.optString("head")),created->refresh()))));
      JSONArray rows=reply.optJSONArray("workspaces");
      if(rows==null)return;
      for(int i=0;i<rows.length();i++) {
        JSONObject row=rows.optJSONObject(i); if(row==null)continue;
        String path=row.optString("path");
        content.addView(ui.button(row.optString("branch")+"\n"+path,false,()->{
          if(!valid() || busy)return;
          child=CenteredMenu.show(activity,ui.fg,new String[]{"使用此工作区","只读预检 / 确认合并…"},which->{
            if(!valid() || busy)return;
            if(which==0){close();if(useWorkspace!=null)useWorkspace.accept(path);}
            else call(Json.obj("action","preflight","project",project,"source",path),preview->preview(preview));
          });
        }));
      }
      });
    });
  }
  private void preview(JSONObject preview) {
    JSONObject target=preview.optJSONObject("target"), source=preview.optJSONObject("source");
    if(target==null || source==null)return;
    String detail="目标："+target.optString("branch")+"\n"+target.optString("head")
        +"\n来源："+source.optString("branch")+"\n"+source.optString("head")
        +"\n目标/来源有改动："+target.optBoolean("dirty")+" / "+source.optBoolean("dirty")
        +"\n并行改动重叠："+preview.optJSONArray("overlap")
        +"\n冲突路径："+preview.optJSONArray("conflicts")
        +"\n冲突预检通过："+preview.optBoolean("conflictFree")
        +"\n仅支持干净快进；分叉历史拒绝写入。";
    text(detail);
    if(!preview.optBoolean("mergeSupported")){text("合并不可用；请检查脏文件、冲突或分叉历史。");return;}
    content.addView(ui.button("确认后快进合并…",false,()->confirm(detail
        +"\n请暂停其他 Git 写入者。确认将来源快进合并到目标？不会推送或删除工作区。",
        ()->call(Json.obj("action","merge","project",project,"source",source.optString("path"),
            "targetHead",target.optString("head"),"sourceHead",source.optString("head"),
            "confirmed",true),merged->{text("快进合并完成");refresh();}))));
  }
}
