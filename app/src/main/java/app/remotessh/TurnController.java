package app.remotessh;

import java.util.*;
import org.json.*;

/** Sends and steers turns, and owns bounded pending messages per profile/thread.
 * Transport callbacks are routed through RemoteApp.request to their originating
 * conversation; replay never grants a queued message permission to auto-send.
 */
final class TurnController {
  private final RemoteApp app;

  private static final Map<RemoteApp,java.lang.ref.WeakReference<TurnController>> controllers = new WeakHashMap<>();
  static TurnController of(RemoteApp app) { return controllers.get(app).get(); }
  TurnController(RemoteApp app) {
    this.app = app; controllers.put(app,new java.lang.ref.WeakReference<>(this));
    app.stateListeners.add(this::observeSafety);
  }
  private void observeSafety() {
    if (!app.connected) { for(TaskQueue<PendingMessage> q:pendingMessages.values())q.disconnect(); }
    else {
      queue().observeSafety(true,app.readOnly,app.replaying,!app.approvals.isEmpty(),!app.error.isEmpty(),app.stopping||app.isRestartingRemote());
      for(ConversationState state:app.conversationStore.values()) {
        TaskQueue<PendingMessage> q=pendingMessages.find(queueOwner(),state.threadId);
        if(q!=null)q.observeSafety(true,state.readOnly,state.replaying,!state.approvals.isEmpty(),!state.error.isEmpty(),state.stopping);
      }
    }
  }

  public void send(String text) {
    sendPayload(text,new ArrayList<>(app.draftImages),new ArrayList<>(app.draftFiles));
  }

  private void sendPayload(String text,List<String> attachments,List<FileAttachment> files) {
    if (app.isRestartingRemote() || app.recoveryInProgress() || app.replaying || !app.connected || app.busy || app.readOnly || app.attaching || (text.trim().isEmpty() && attachments.isEmpty() && files.isEmpty())) return;
    if (!app.validateServiceTier()) return;
    app.rememberLastSessionChoices();
    app.busy = true;
    app.stopping = false;
    app.unsentText = text;
    app.error = "";
    app.state = "正在思考…";
    app.changed();
    if (app.threadId.isEmpty()) {
      JSONObject p = app.permissionMode.startParams();
      if(app.modelSettings.cwd.equals(app.currentProject())) app.modelSettings.applyQuestionSupport(p);
      // ThreadStartParams uses kebab-case configuration values; turn overrides use tagged
      // camel-case objects.
      try {
        if (!app.profile.cwd.isEmpty()) p.put("cwd", app.profile.cwd);
        if (!app.model.isEmpty()) p.put("model", app.model);
        if (app.shouldSendServiceTier()) {
          if (app.modelSettings.configKnown && !app.inheritedTierKnown) {
            app.inheritedServiceTier = app.defaultServiceTier(app.selectedModel(app.model)); app.inheritedTierKnown = true;
          }
          p.put("serviceTier", app.requestedServiceTier());
        }
      } catch (JSONException ignored) {
      }
      app.request(
          "thread/start",
          p,
          r -> {
            app.threadId = Json.object(r, "thread").optString("id");
            if (app.threadId.isEmpty()) {
              app.busy = false;
              app.error = "远端没有返回会话 ID";
              app.changed();
              return;
            }
            app.threadCwd = Json.object(r, "thread").optString("cwd", r.optString("cwd", app.profile.cwd));
            app.journalPath=Json.object(r,"thread").optString("path");app.journal=new ConversationJournal();app.savePermission();
            PermissionMode remotePermission=PermissionMode.fromRemote(r.opt("approvalPolicy"),r.optJSONObject("sandbox"));
            if(remotePermission!=null)app.effectivePermissionMode=remotePermission;
            app.effectiveModel = r.optString("model", app.model);
            if (r.has("serviceTier")) {
              app.noteServiceTierProtocol();
              app.effectiveServiceTier = r.isNull("serviceTier") ? ServiceTierOptions.STANDARD : r.optString("serviceTier");
              if (!app.inheritedTierKnown && app.serviceTier.isEmpty()) { app.inheritedServiceTier = app.effectiveServiceTier; app.inheritedTierKnown = true; }
            }
            if (p.has("serviceTier")) app.tierOverrideApplied = !app.serviceTier.isEmpty();
            app.saveServiceTier();
            app.profile.lastThread = app.threadId;
            app.rememberLoadedThread();
            app.threadTitle = text;
            app.save();
            startTurn(FileAttachment.message(text,files), attachments,()->app.draftFiles.removeAll(files),null);
          });
    } else startTurn(FileAttachment.message(text,files), attachments,()->app.draftFiles.removeAll(files),null);
  }

  static final class PendingMessage {
    final String text;
    final List<String> images;
    final List<FileAttachment> files;
    final Map<String,android.graphics.Bitmap> previews;
    String acknowledgedTurn="";
    PendingMessage(String text,List<String> images,List<FileAttachment> files,Map<String,android.graphics.Bitmap> previews) {
      this.text=text;this.images=images;this.files=files;this.previews=previews;
    }
    String preview(String text){return text.isEmpty()?"附件 · "+images.size()+" 张图片、"+files.size()+" 个文件":text;}
  }
  private final TaskQueueRegistry<PendingMessage> pendingMessages = new TaskQueueRegistry<>();
  private final Set<String> readyMessages = new HashSet<>();
  private String queueOwner() { return app.profile==null?"":app.profile.id+"\n"+app.profile.runtime; }
  private String queueKey() {
    return queueKey(app.threadId);
  }
  private String queueKey(String thread) {
    String owner=queueOwner();
    return owner.length()+":"+owner+thread;
  }
  TaskQueue<PendingMessage> queue() { return pendingMessages.get(queueOwner(),app.threadId); }
  boolean hasPendingForCurrentProfile() {
    if (!queue().snapshot().isEmpty()) return true;
    for (ConversationState state : app.conversationStore.values()) {
      TaskQueue<PendingMessage> q = pendingMessages.find(queueOwner(), state.threadId);
      if (q != null && !q.snapshot().isEmpty()) return true;
    }
    return false;
  }
  void forgetThread(String id) {
    String owner=queueOwner();
    String key=owner.length()+":"+owner+id;
    pendingMessages.remove(owner,id);readyMessages.remove(key);
  }
  public String queuedMessage() {
    TaskQueue.Item<PendingMessage> item=queue().first();
    return item==null?"":queue().snapshot().size()+" 条 · "+TaskQueue.label(item.status)+" · "+item.attachments.preview(item.text);
  }
  public boolean queueMessage(String text) {return queueMessage(text,false);}
  public boolean queueMessage(String text,boolean includeAttachments) {
    if(!app.connected || app.readOnly || app.replaying || app.recoveryInProgress() || app.attaching || app.steering || app.stopping || app.threadId.isEmpty())return false;
    List<String> images=includeAttachments?new ArrayList<>(app.draftImages):new ArrayList<>();
    List<FileAttachment> files=includeAttachments?new ArrayList<>(app.draftFiles):new ArrayList<>();
    if(text.trim().isEmpty() && images.isEmpty() && files.isEmpty())return false;
    Map<String,android.graphics.Bitmap> previews=new HashMap<>();
    for(String path:images)if(app.draftImagePreviews.containsKey(path))previews.put(path,app.draftImagePreviews.get(path));
    if(!queue().add(text,new PendingMessage(text,images,files,previews)))return false;
    app.draftImages.removeAll(images);app.draftFiles.removeAll(files);for(String path:images)app.draftImagePreviews.remove(path);
    app.changed();return true;
  }
  private void restoreQueuedAttachments(PendingMessage message) {
    if(message==null)return;
    for(String path:message.images)if(!app.draftImages.contains(path))app.draftImages.add(path);
    for(FileAttachment file:message.files)if(!app.draftFiles.contains(file))app.draftFiles.add(file);
    app.draftImagePreviews.putAll(message.previews);
  }
  void remove(TaskQueue.Item<PendingMessage> item) {
    Set<String> images=new HashSet<>(app.draftImages);images.addAll(item.attachments.images);
    Set<FileAttachment> files=new HashSet<>(app.draftFiles);files.addAll(item.attachments.files);
    if(images.size()>MediaFiles.MAX_ATTACHMENTS || files.size()>FileAttachment.MAX_FILES) {
      app.error="草稿附件空间不足，请先处理草稿后移除队列条目";app.changed();return;
    }
    TaskQueue.Item<PendingMessage> removed=queue().remove(item);
    if(removed!=null)restoreQueuedAttachments(removed.attachments);
    queue().pause();app.changed();
  }
  public void cancelQueuedMessage() {
    TaskQueue.Item<PendingMessage> first=queue().first();if(first!=null)remove(first);
  }
  public void sendQueuedMessage() {
    observeSafety();
    if (!app.connected || app.busy || app.replaying || app.readOnly || app.attaching || app.steering || app.stopping || !app.approvals.isEmpty() || app.isRestartingRemote() || app.recoveryInProgress() || app.threadId.isEmpty() || !app.validateServiceTier()) return;
    TaskQueue<PendingMessage> q=queue();
    TaskQueue.Item<PendingMessage> item=q.begin();if(item==null)return;
    PendingMessage message=item.attachments;
    readyMessages.remove(queueKey());
    try {
      app.rememberLastSessionChoices();app.busy=true;app.stopping=false;
      app.error="";app.state="正在发送队列消息…";
      startTurn(FileAttachment.message(item.text,message.files),message.images,()->{
        q.accepted(item,message.acknowledgedTurn);
        drainQueuedMessage();
      },failure->q.unknown(item));
    } catch(RuntimeException failure) {
      q.unknown(item);app.busy=false;app.error="队列提交中断，送达未知，请核对远端";app.changed();
    }
  }
  int remainingImages() { return Math.max(0,MediaFiles.MAX_ATTACHMENTS-app.draftImages.size()); }
  int remainingFiles() { return Math.max(0,FileAttachment.MAX_FILES-app.draftFiles.size()); }
  private void drainQueuedMessage() {
    observeSafety();
    TaskQueue.Item<PendingMessage> first=queue().first();
    if (queue().sequential() && first!=null && first.status==TaskQueue.Status.PENDING && readyMessages.remove(queueKey())) sendQueuedMessage();
  }

  public boolean canSteer() {
    return app.runtimeCapabilities.supports("steer") && !app.replaying && !app.recoveryInProgress() && !app.attaching && app.connected && app.busy && !app.readOnly && !app.steering && !app.stopping && !app.turnId.isEmpty();
  }

  public boolean steer(String text) {
    if (!canSteer() || (text.trim().isEmpty() && app.draftImages.isEmpty() && app.draftFiles.isEmpty())) return false;
    List<String> attachments=new ArrayList<>(app.draftImages);
    List<FileAttachment> files=new ArrayList<>(app.draftFiles);
    String body=FileAttachment.message(text,files);
    JSONArray content=new JSONArray();
    if(!body.isEmpty())content.put(Json.obj("type","text","text",body));
    for(String path:attachments)content.put(Json.obj("type","localImage","path",path));
    app.steering = true;
    app.unsentText = text;
    final String target = app.threadId;
    Set<String> previous = new HashSet<>();
    for (ChatItem item : app.items) previous.add(item.id);
    app.request("turn/steer", Json.obj("threadId", target, "expectedTurnId", app.turnId,
        "input", content), r -> {
      app.steering = false;
      if (app.threadId.equals(target)) {
        boolean echoed = false;
        for (ChatItem item : app.items) if (!previous.contains(item.id) && item.user() && item.text.equals(body) && item.images.equals(attachments)) echoed = true;
        if (!echoed) {
          ChatItem item=new ChatItem("local-"+UUID.randomUUID(),"userMessage",body);item.images.addAll(attachments);app.items.add(item);
        }
        app.draftImages.removeAll(attachments);for(String path:attachments)app.draftImagePreviews.remove(path);app.draftFiles.removeAll(files);
        if (app.unsentText.equals(text)) app.unsentText = "";
      }
      app.changed();
      drainQueuedMessage();
    });
    app.changed();
    return true;
  }

  void startTurn(String text,List<String> attachments,Runnable accepted,java.util.function.Consumer<Throwable> failure) {
    final PermissionMode sentPermission = app.runtimeCapabilities.supports("approval") ? app.permissionMode : PermissionMode.INHERIT;
    final ForcedApprovalController.State forceGrant = app.forcedApprovalState;
    final long forceRevision = forceGrant.revision;
    ChatItem optimistic = new ChatItem("local-" + UUID.randomUUID(), "userMessage", text);
    optimistic.images.addAll(attachments);
    app.items.add(optimistic);
    app.changed();
    JSONArray content = new JSONArray();
    if (!text.isEmpty()) content.put(Json.obj("type", "text", "text", text));
    for (String path : attachments) content.put(Json.obj("type", "localImage", "path", path));
    JSONObject params =
        Json.obj(
            "threadId",
            app.threadId,
            "input",
            content);
    try {
      if (app.runtimeCapabilities.supports("models") && !app.model.isEmpty()) params.put("model", app.model);
      if (app.runtimeCapabilities.supports("models") && !app.reasoningEffort.isEmpty()) params.put("effort", app.reasoningEffort);
      if (app.runtimeCapabilities.supports("models") && app.shouldSendServiceTier()) params.put("serviceTier", app.requestedServiceTier());
      params.put("summary", "auto");
      sentPermission.applyTurn(params);
    } catch (JSONException ignored) {
    }
    final String sentDefaultTier = app.inheritedTierKnown ? (app.inheritedServiceTier.isEmpty() ? ServiceTierOptions.STANDARD : app.inheritedServiceTier)
        : app.defaultServiceTier(app.selectedModel(app.model));
    app.request(
        "turn/start",
        params,
        r -> {
          if (params.has("serviceTier")) {
            app.effectiveServiceTier = params.optString("serviceTier");
            // A selection changed while the request was pending belongs to the next turn.
            app.tierOverrideApplied = !ServiceTierOptions.same(params.optString("serviceTier"), sentDefaultTier);
          }
          if (params.has("model")) app.effectiveModel = params.optString("model");
          if(sentPermission!=PermissionMode.INHERIT)app.effectivePermissionMode=sentPermission;
          app.forcedApprovals.permissionApplied(sentPermission, forceGrant, forceRevision);
          app.saveServiceTier();
          app.unsentText = "";
          app.draftImages.removeAll(attachments);
          for (String path : attachments) app.draftImagePreviews.remove(path);
          JSONObject turn = Json.object(r, "turn");
          TaskQueue.Item<PendingMessage> queued=queue().first();
          if(queued!=null && queued.status==TaskQueue.Status.SENDING)
            queued.attachments.acknowledgedTurn=turn.optString("id");
          if (app.busy) {
            app.turnId = turn.optString("id");
            if (app.stopping) app.interrupt();
          }
          if(accepted!=null)accepted.run();
          app.changed();
        },failure);
  }

  void clearReady() { readyMessages.clear(); for(TaskQueue<PendingMessage> q:pendingMessages.values())q.disconnect(); }
  // Parent adapter supplies authoritative completion identity before the legacy ready hook.
  void completed(String id,boolean success,boolean historical) {
    queue().outcome(id,success,historical);
  }
  void setSequential(boolean enabled) {
    readyMessages.remove(queueKey());
    if(enabled)queue().enable();else queue().pause();app.changed();
  }
  void markReady() { readyMessages.add(queueKey()); }
  void clearCurrentReady() { readyMessages.remove(queueKey()); queue().pause(); }
  void drain() { drainQueuedMessage(); }
}
