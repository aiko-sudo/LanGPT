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
    retry=ui.button(UiText.get(R.string.work_text_158),false,this::refresh);
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
    if(error.contains("not found on host"))return UiText.get(R.string.work_text_174);
    if(error.contains("Not inside a Git working tree"))return UiText.get(R.string.work_text_175);
    if(error.contains("Select the repository root"))return UiText.get(R.string.work_text_176);
    if(error.contains("Bare repository"))return UiText.get(R.string.work_text_177);
    if(error.contains("Shallow repositories"))return UiText.get(R.string.work_text_178);
    if(error.contains("Submodules"))return UiText.get(R.string.work_text_179);
    if(error.contains("must be clean"))return UiText.get(R.string.work_text_180);
    if(error.contains("in progress"))return UiText.get(R.string.work_text_181);
    if(error.contains("Another app workspace"))return UiText.get(R.string.work_text_182);
    return UiText.get(R.string.work_text_183);
  }
  private void text(String value) {
    TextView view=ui.text(value,15); view.setTextIsSelectable(true); content.addView(view);
  }
  private void confirm(String message,Runnable action) {
    if(!valid() || busy)return;
    child=new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.work_text_184)).setMessage(message)
        .setNegativeButton(UiText.get(R.string.work_text_004),null).setPositiveButton(UiText.get(R.string.work_text_185),(d,w)->{if(valid())action.run();}).create();
    child.show();
  }
  private JSONObject request(String action) { return Json.obj("action",action,"project",project); }
  private void call(JSONObject params,Consumer<JSONObject> done) {
    if(busy || !valid()){ scroll.finishRefresh(); return; }
    boolean statusRequest=params.optString("action").equals("status");
    if(statusRequest)retry.setVisibility(View.GONE);
    busy=true; content.removeAllViews(); text(UiText.get(R.string.work_text_186));
    app.executeBackground(()->{
      JSONObject result;
      try(InputStream in=app.getAssets().open("git_workspace.py");
          ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
        MediaFiles.copy(in,bytes,65536);
        if(transport==null || app.connectionGeneration()!=generation
            || app.currentTransport()!=transport || app.profile!=profile)throw new IllegalStateException();
        result=transport.maintenance(bytes.toString("UTF-8"),params);
      }catch(Exception error){result=Json.obj("error",UiText.get(R.string.work_text_187));}
      final JSONObject reply=result;
      app.main.post(()->{
        busy=false;
        if(statusRequest)scroll.finishRefresh();
        if(!valid())return;
        if(reply.has("error")){
          String error=reply.optString("error");
          content.removeAllViews();
          text(UiText.get(R.string.work_text_188)+(params.optString("action").equals("status") ? explain(error)+"\n("+error+")" : error));
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
      text(UiText.get(R.string.work_text_189));
      if(!status.optBoolean("hasGit")) text(UiText.get(R.string.work_text_190));
      else if(!status.optBoolean("isGitRoot")) text(UiText.get(R.string.work_text_191));
      else {
        text(UiText.get(R.string.work_text_192));
        text(status.optBoolean("dirty") ? UiText.get(R.string.work_text_193)+status.optInt("dirtyCount")+UiText.get(R.string.work_text_194) : UiText.get(R.string.work_text_195));
        text(status.optBoolean("inProgress") ? UiText.get(R.string.work_text_196) : UiText.get(R.string.work_text_197));
        text(UiText.get(R.string.work_text_198)+status.optString("branch")+"\nHEAD："+status.optString("head"));
        if(!status.optString("reason").isEmpty() && !status.optBoolean("inProgress")) text("✗ "+status.optString("reason"));
      }
      if(!status.optBoolean("ready"))return;
      content.addView(ui.button(UiText.get(R.string.work_text_199),false,()->confirm(
          UiText.get(R.string.work_text_200) + "\n"+status.optString("head"),
          ()->call(Json.obj("action","create","project",project,"confirmed",true,
              "targetHead",status.optString("head")),created->{text(UiText.get(R.string.work_text_201)+created.optString("created"));refresh();}))));
      call(request("list"),reply->{
        content.removeAllViews();
        text(UiText.get(R.string.work_text_189));
        text(UiText.get(R.string.work_text_192)+"\n"+UiText.get(R.string.work_text_195)+"\n"+UiText.get(R.string.work_text_197)+"\n"+UiText.get(R.string.work_text_198)+status.optString("branch")+"\nHEAD："+status.optString("head"));
          content.addView(ui.button(UiText.get(R.string.work_text_199),false,()->confirm(UiText.get(R.string.work_text_202) + "\n"+status.optString("head"),
            ()->call(Json.obj("action","create","project",project,"confirmed",true,"targetHead",status.optString("head")),created->refresh()))));
      JSONArray rows=reply.optJSONArray("workspaces");
      if(rows==null)return;
      for(int i=0;i<rows.length();i++) {
        JSONObject row=rows.optJSONObject(i); if(row==null)continue;
        String path=row.optString("path");
        content.addView(ui.button(row.optString("branch")+"\n"+path,false,()->{
          if(!valid() || busy)return;
          child=CenteredMenu.show(activity,ui.fg,new String[]{UiText.get(R.string.work_text_203),UiText.get(R.string.work_text_204)},which->{
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
    String detail=UiText.get(R.string.work_text_205)+target.optString("branch")+"\n"+target.optString("head")
        +"\n" + UiText.get(R.string.work_text_206)+source.optString("branch")+"\n"+source.optString("head")
        +"\n" + UiText.get(R.string.work_text_207)+target.optBoolean("dirty")+" / "+source.optBoolean("dirty")
        +"\n" + UiText.get(R.string.work_text_208)+preview.optJSONArray("overlap")
        +"\n" + UiText.get(R.string.work_text_209)+preview.optJSONArray("conflicts")
        +"\n" + UiText.get(R.string.work_text_210)+preview.optBoolean("conflictFree")
        +"\n" + UiText.get(R.string.work_text_211);
    text(detail);
    if(!preview.optBoolean("mergeSupported")){text(UiText.get(R.string.work_text_212));return;}
    content.addView(ui.button(UiText.get(R.string.work_text_213),false,()->confirm(detail
        +"\n" + UiText.get(R.string.work_text_214),
        ()->call(Json.obj("action","merge","project",project,"source",source.optString("path"),
            "targetHead",target.optString("head"),"sourceHead",source.optString("head"),
            "confirmed",true),merged->{text(UiText.get(R.string.work_text_215));refresh();}))));
  }
}
