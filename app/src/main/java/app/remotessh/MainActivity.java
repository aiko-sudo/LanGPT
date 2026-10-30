package app.remotessh;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private RemoteApp app;
  private int bg, fg, muted, panel, line;
  private LinearLayout root, body, conversation, messages, empty, sidebar, composer;
  private ConversationScrollView scroll;
  private String renderedThread = "";
  private TextView status, title, error, busyLabel, modelButton, projectLabel, usageLabel;
  private EditText input;
  private DialogUi ui;
  private ProfileEditor profileEditor;
  private ModelPicker modelPicker;
  private PermissionPicker permissionPicker;
  private IconView send, permissionButton;
  private ProgressBar runningIndicator;
  private LinearLayout attachmentStrip, queuedBar;
  private HorizontalScrollView attachmentScroll;
  private final Map<String, LinearLayout> mediaRows = new HashMap<>();
  private final Map<String, List<String>> mediaSources = new HashMap<>();
  private static final java.util.concurrent.ExecutorService mediaWorker = java.util.concurrent.Executors.newFixedThreadPool(2);
  /** SFTP download pending SAF save-location result: {path, name, size}. */
  String[] pendingSftpSave;
  private static final ThumbnailLoader thumbnailWorker = new ThumbnailLoader(2);
  private String pendingDownload = "", pendingDownloadHost = "";
  private Profile previousConnection;
  private boolean switchingConnection;
  private int activeMediaDownloads;
  private final Map<String,String> connectionDrafts = new HashMap<>();
  private int pendingImageGeneration = -1;
  private String pendingImageHost = "", pendingImageThread = "", pendingImageProject = "";

  private View jump;
  private final Map<String, TextView> texts = new HashMap<>();
  private final Map<String, MessageBodyView> messageBodies = new HashMap<>();
  private final Map<String, ChatItem> rendered = new HashMap<>();
  private final Map<String, QuestionHistoryView> questionCards = new HashMap<>();
  private TextView historyButton;
  private int historyLimit=60;
  private boolean historyRender;
  private boolean mediaScheduled;
  private final List<MessageImageTile> mediaTiles=new ArrayList<>();
  private final Runnable refreshMedia=()->{mediaScheduled=false;updateMediaViewport();};
  private final Map<String, ChatItem> currentItems = new HashMap<>();
  private final List<String> order = new ArrayList<>();
  private Dialog trustDialog, approvalDialog, drawer, workPanel, pendingQueueDialog;
  private TextView connectHint;
  private Dialog versionDialog;
  private RemoteVersion shownVersion;
  private RemoteBrowser browser;
  private SftpBrowser sftpBrowser;
  private ImageDialog imageDialog;
  private DirectoryPicker directoryPicker;
  private LinearLayout readOnlyBar;
  private LinearLayout roActions;
  private TextView readOnlyLabel,resumeButton,newChatButton;
  private LoadingSpinner resumeSpinner;
  private boolean visibleActivity;
  private String shownApproval = "";
  private QuestionForm questionForm;
  private Bundle restoredQuestion;
  private String restoredQuestionToken = "";
  private boolean scheduled, firstRender = true, wide;
  private boolean latestScrollPending, messageUpdatesPaused;
  private final Runnable pinLatest = () -> {
    if (scroll == null || conversation == null || isDestroyed()) return;
    scroll.pin();
  };

  private void followLatest() {
    if (scroll == null || !scroll.isFollowing()) return;
    latestScrollPending = true;
    scroll.removeCallbacks(pinLatest);
    scroll.post(pinLatest);
  }

  private int pulse, pollTick;
  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (app.currentThreadRunning() && busyLabel != null && scroll != null && scroll.isFollowing())
            busyLabel.setText(
                (app.state.startsWith("正在停止") ? "正在停止" : "正在处理")
                    + new String[] {"·", "··", "···"}[pulse++ % 3]);
          if (++pollTick % 7 == 0) app.pollReadOnly();
          if (pollTick % 22 == 0 && app.connected && browser != null && browser.isShowing()) app.refreshThreads(false);
          app.main.postDelayed(this, 450);
        }
      };

  @Override
  protected void attachBaseContext(Context base) {
    float scale = base.getSharedPreferences("display", MODE_PRIVATE).getFloat("fontScale", 0);
    if (scale > 0) {
      Configuration config = new Configuration(base.getResources().getConfiguration());
      config.fontScale *= scale;
      base = base.createConfigurationContext(config);
    }
    super.attachBaseContext(base);
  }

  private void openNotificationConversation(Intent intent) {
    if (app.ownsIntent(intent)) {
      String id = intent.getStringExtra("threadId");
      if (id != null && !id.isEmpty() && !id.equals(app.threadId)) {
        if (input != null) app.draftText=input.getText().toString();
        app.openConversation(id);
      }
      String liveToken = intent.getStringExtra("liveToken");
      if (liveToken != null && !liveToken.isEmpty()) app.liveStatus.acknowledgeViewed(liveToken);
    }
  }
  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent); openNotificationConversation(intent);
  }

  @Override
  public void onCreate(Bundle saved) {
    setTheme(isDark() ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(saved);
    TsnetSetup.startIfEnabled(getApplicationContext());
    app = (RemoteApp) getApplication();
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
    }
    openNotificationConversation(getIntent());
    colors();
    build();
    app.prefetchSftpOnce();
    app.connectStartupOnce();
    if (saved != null) {
      returnToWorkPanel = saved.getBoolean("returnToWorkPanel", false);
      workPanelOrigin = saved.getString("workPanelOrigin", "");
      workPanelOriginHome = saved.getBoolean("workPanelOriginHome", true);
      app.draftText=saved.getString("draft", "");
      input.setText(app.draftText);
      pendingDownload = saved.getString("download", "");
      pendingDownloadHost = saved.getString("downloadHost", "");
      pendingImageGeneration = saved.getInt("imageGeneration", -1);
      pendingImageHost = saved.getString("imageHost", "");
      pendingImageThread = saved.getString("imageThread", "");
      pendingImageProject = saved.getString("imageProject", "");
      restoredQuestion=saved.getBundle("questionAnswers");
      restoredQuestionToken=saved.getString("questionToken", "");
    }
  }

  @Override
  public void onBackPressed() {
    navigateBack();
  }

  private boolean returnToWorkPanel;
  private String workPanelOrigin = "";
  private boolean workPanelOriginHome;

  private void navigateBack() {
    if (returnToWorkPanel) {
      app.draftText = input.getText().toString();
      returnToWorkPanel = false;
      if (workPanelOriginHome || workPanelOrigin.isEmpty()) app.landHome();
      else app.viewConversation(workPanelOrigin);
      chatMenu();
      return;
    }
    if (!app.homeVisible || !app.threadId.isEmpty()) {
      app.draftText = input.getText().toString();
      app.landHome();
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(input.getWindowToken(), 0);
    } else {
      moveTaskToBack(true);
    }
  }

  private boolean isDark() {
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    int mode = getPreferences(0).getInt("theme", 0);
    if (mode != 0) dark = mode == 2;
    return dark;
  }

  private void colors() {
    boolean dark = isDark();
    bg = Color.parseColor(dark ? "#000000" : "#FFFFFF");
    fg = Color.parseColor(dark ? "#F2F2F7" : "#1C1C1E");
    muted = Color.parseColor(dark ? "#8E8E93" : "#8E8E93");
    panel = Color.parseColor(dark ? "#1C1C1E" : "#F2F2F7");
    line = Color.parseColor(dark ? "#38383A" : "#E5E5EA");
    ui = new DialogUi(this, bg, fg, muted, panel, line);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  @Override
  protected void onStart() {
    super.onStart();
    visibleActivity=true;
    app.observer = this::scheduleRender;
    app.setForeground(true);
    if (app.connected) { app.refreshThreads(false); app.pollReadOnly(); }
    app.changed();
    render();
    app.main.post(ticker);
  }

  @Override
  protected void onStop() {
    super.onStop();
    visibleActivity=false;
    app.observer = null;
    app.setForeground(false, pendingImageGeneration >= 0);
    app.main.removeCallbacks(ticker);
    if(resumeSpinner!=null)resumeSpinner.stop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putBoolean("returnToWorkPanel", returnToWorkPanel);
    out.putString("workPanelOrigin", workPanelOrigin);
    out.putBoolean("workPanelOriginHome", workPanelOriginHome);
    out.putString("draft", input.getText().toString());
    out.putString("download", pendingDownload);
    out.putString("downloadHost", pendingDownloadHost);
    out.putInt("imageGeneration", pendingImageGeneration);
    out.putString("imageHost", pendingImageHost);
    out.putString("imageThread", pendingImageThread);
    out.putString("imageProject", pendingImageProject);
    if (questionForm!=null && !shownApproval.isEmpty()) {
      out.putString("questionToken",shownApproval);out.putBundle("questionAnswers",questionForm.saveAnswers());
    }
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    if (sftpBrowser != null) sftpBrowser.dismiss();
    if(resumeSpinner!=null)resumeSpinner.stop();
    app.main.removeCallbacks(refreshMedia);
    for(MessageImageTile tile:mediaTiles)tile.release();
    if (scroll != null) scroll.removeCallbacks(pinLatest);
    if (trustDialog != null) trustDialog.dismiss();
    if (approvalDialog != null) approvalDialog.dismiss();
    if (drawer != null) drawer.dismiss();
    if (workPanel != null) workPanel.dismiss();
    if (pendingQueueDialog != null) pendingQueueDialog.dismiss();
    if (profileEditor != null) profileEditor.dismiss();
    if (modelPicker != null) modelPicker.dismiss();
    if (permissionPicker != null) permissionPicker.dismiss();
    if (browser != null) browser.dismiss();
    if (versionDialog != null) versionDialog.dismiss();
    if (directoryPicker != null) directoryPicker.dismiss();
    if (imageDialog != null) imageDialog.dismiss();
    super.onDestroy();
  }

  private int dp(float n) { return ui.dp(n); }
  private LinearLayout column() { return ui.column(); }
  private LinearLayout row() { return ui.row(); }
  private TextView text(String value, int size, int color) { return ui.text(value, size, color); }
  private GradientDrawable rounded(int color, int radius) { return ui.rounded(color, radius); }
  private void clickable(View view, Runnable action) { ui.clickable(view, action); }
  private IconView icon(String name, String label, Runnable action) { return ui.icon(name, label, action); }
  private TextView action(String label, Runnable action) { return ui.action(label, action); }

  private void build() {
    root = column();
    root.setBackgroundColor(bg);
    root.setFitsSystemWindows(true);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int bottom = insets.getSystemWindowInsetBottom();
          root.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              bottom);
          return insets.consumeSystemWindowInsets();
        });
    body = row();
    body.setGravity(Gravity.TOP);
    root.addView(body, new LinearLayout.LayoutParams(-1, -1));
    wide = getResources().getConfiguration().screenWidthDp >= 840;
    permissionPicker = new PermissionPicker(this, app, ui, wide);
    int available = getResources().getConfiguration().screenWidthDp - (wide ? 260 : 0);
    if (wide) {
      sidebar = column();
      sidebar.setBackgroundColor(panel);
      sidebar.setPadding(dp(16), dp(20), dp(16), dp(16));
      ScrollView navigationScroll = new ScrollView(this);
      navigationScroll.setFillViewport(true);
      navigationScroll.setBackgroundColor(panel);
      navigationScroll.addView(sidebar);
      body.addView(navigationScroll, new LinearLayout.LayoutParams(dp(260), -1));
    }
    LinearLayout center = column();
    body.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout bar = row();
    bar.setPadding(dp(12), dp(4), dp(12), dp(4));
    center.addView(bar, new LinearLayout.LayoutParams(-1, dp(64)));
    LinearLayout heading = column();
    heading.setGravity(Gravity.CENTER);
    title = text(getString(R.string.app_name), 18, fg);
    title.setMaxLines(1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title);
    status = text("通过 SSH 连接你的电脑", 11, muted);
    status.setMaxLines(1);
    status.setEllipsize(TextUtils.TruncateAt.END);
    heading.addView(status);
    bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    clickable(heading, this::conversationInfo);
    runningIndicator = new ProgressBar(this);
    runningIndicator.setIndeterminate(true);
    runningIndicator.setContentDescription("当前对话正在运行");
    bar.addView(runningIndicator, new LinearLayout.LayoutParams(dp(20), dp(20)));
    bar.addView(
        icon(
            "more",
            "连接与对话菜单",
            this::showMainMenu),
        new LinearLayout.LayoutParams(dp(48), dp(48)));
    projectLabel = text("", 12, muted);
    projectLabel.setPadding(dp(20), 0, dp(20), dp(2));
    projectLabel.setMaxLines(1);
    projectLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    clickable(projectLabel, this::conversationInfo);
    center.addView(projectLabel);
    usageLabel = text("", 12, muted);
    usageLabel.setPadding(dp(20), dp(2), dp(20), dp(8));
    usageLabel.setMaxLines(2);
    usageLabel.setEllipsize(TextUtils.TruncateAt.END);
    clickable(usageLabel, this::usageInfo);
    center.addView(usageLabel);
    readOnlyBar = column();
    readOnlyBar.setPadding(dp(16), dp(2), dp(16), dp(6));
    readOnlyLabel = text("只读历史 · 尚未取得写入权限", 13, muted);
    readOnlyLabel.setPadding(dp(4), dp(6), dp(4), dp(6));
    readOnlyBar.addView(readOnlyLabel);
    roActions = row();
    resumeButton=action("接续",()->app.resume(app.threadId));newChatButton=action("新会话",this::startNewChat);
    resumeButton.setContentDescription("接续");resumeSpinner=new LoadingSpinner(fg,dp(16));
    TextView[] readOnlyControls = {resumeButton,newChatButton};
    for (int i=0;i<readOnlyControls.length;i++) {
      readOnlyControls[i].setSingleLine(true); readOnlyControls[i].setEllipsize(TextUtils.TruncateAt.END);
      readOnlyControls[i].setPadding(dp(8),dp(12),dp(8),dp(12));
      LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);
      if (i<readOnlyControls.length-1) cp.setMarginEnd(dp(3)); roActions.addView(readOnlyControls[i],cp);
    }
    readOnlyBar.addView(roActions);
    readOnlyBar.setVisibility(View.GONE);
    center.addView(readOnlyBar);
    error = text("", 13, isDark() ? Color.rgb(255, 151, 137) : Color.rgb(165, 47, 36));
    error.setContentDescription("查看连接错误");
    error.setPadding(dp(20), dp(12), dp(20), dp(12));
    error.setBackground(rounded(panel, 12));
    error.setMaxLines(2);
    error.setEllipsize(TextUtils.TruncateAt.END);
    clickable(error, this::showConnectionError);
    center.addView(error);
    FrameLayout viewport = new FrameLayout(this);
    center.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
    scroll = new ConversationScrollView(this);
    scroll.setContentDescription("对话消息");
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.getViewTreeObserver().addOnPreDrawListener(() -> {
      if (latestScrollPending) {
        pinLatest.run();
        latestScrollPending = false;
      }
      return true;
    });
    viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    conversation = column();
    conversation.setGravity(Gravity.CENTER_HORIZONTAL);
    scroll.addView(conversation, new ScrollView.LayoutParams(-1, -2));
    conversation.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (b - t != ob - ot) followLatest();
    });
    scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (b - t != ob - ot || r - l != or - ol) followLatest();
    });
    // Pull down at the top of a read-only history to refresh it (replaces the refresh button).
    final float[] pullStart=new float[]{-1f};
    scroll.setOnTouchListener((view,event)->{
      switch(event.getActionMasked()) {
        case android.view.MotionEvent.ACTION_DOWN:
          pullStart[0]=event.getY();break;
        case android.view.MotionEvent.ACTION_MOVE:
          if(pullStart[0]>=0 && scroll.getScrollY()==0 && event.getY()-pullStart[0]>dp(72)) {
            pullStart[0]=-1f;
            if(app.readOnly && !app.busy && app.connected) {
              android.widget.Toast.makeText(this,"正在刷新历史…",android.widget.Toast.LENGTH_SHORT).show();
              app.refreshReadOnly();
            }
          }
          break;
        default: pullStart[0]=-1f;break;
      }
      return false;
    });
    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(dp(28), dp(32), dp(28), dp(56));
    conversation.addView(empty, new LinearLayout.LayoutParams(-1, -1));
    historyButton=action("加载更早的消息",this::loadOlderMessages);
    historyButton.setContentDescription("加载更早的消息");
    LinearLayout.LayoutParams historyParams=new LinearLayout.LayoutParams(wide?dp(Math.min(740,available)-32):-1,dp(48));historyParams.setMargins(dp(16),dp(8),dp(16),0);
    conversation.addView(historyButton,historyParams);historyButton.setVisibility(View.GONE);
    messages = column();
    messages.setPadding(dp(16), dp(8), dp(16), dp(8));
    conversation.addView(
        messages, new LinearLayout.LayoutParams(wide ? dp(Math.min(740, available)) : -1, -2));
    busyLabel = text("正在处理…", 14, muted);
    busyLabel.setPadding(dp(16), 0, dp(16), dp(8));
    conversation.addView(
        busyLabel, new LinearLayout.LayoutParams(wide ? dp(Math.min(740, available)) : -1, -2));
    jump = icon("arrow", "滚动到最新消息", () -> { historyLimit=60;scroll.follow(); render(); followLatest(); });
    jump.setRotation(180);
    jump.setBackground(rounded(panel, 24));
    FrameLayout.LayoutParams jp =
        new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    jp.bottomMargin = dp(12);
    viewport.addView(jump, jp);
    jump.setVisibility(View.GONE);
    scroll.setOnScrollChangeListener(
        (v, x, y, ox, oy) -> {
          jump.setVisibility(scroll.isFollowing() ? View.GONE : View.VISIBLE);
          if (scroll.isFollowing() && messageUpdatesPaused) scheduleRender();
          scheduleMedia();
        });
    LinearLayout footer = column();
    footer.setGravity(Gravity.CENTER_HORIZONTAL);
    footer.setPadding(dp(16), dp(8), dp(16), dp(8));
    center.addView(footer);
    queuedBar = row();
    footer.addView(queuedBar, new LinearLayout.LayoutParams(wide ? dp(Math.min(720, available - 32)) : -1, -2));
    composer = column();
    composer.setBackground(rounded(panel, 28));
    composer.setPadding(dp(8), dp(4), dp(8), dp(8));
    footer.addView(
        composer, new LinearLayout.LayoutParams(wide ? dp(Math.min(720, available - 32)) : -1, -2));
    input = new EditText(this);
    input.setTextColor(fg);
    input.setHintTextColor(muted);
    input.setTextSize(16);
    input.setHint("发送消息");
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setMinLines(1);
    input.setMaxLines(6);
    input.setPadding(dp(12), dp(12), dp(8), dp(8));
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setContentDescription("消息输入框");
    attachmentStrip = row();
    attachmentStrip.setPadding(dp(4), dp(8), dp(4), dp(4));
    attachmentScroll = new HorizontalScrollView(this);
    attachmentScroll.setHorizontalScrollBarEnabled(false);
    attachmentScroll.addView(attachmentStrip, new HorizontalScrollView.LayoutParams(-2, -2));
    composer.addView(attachmentScroll, new LinearLayout.LayoutParams(-1, -2));
    composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout controls = row();
    composer.addView(controls);
    controls.addView(
        icon("plus", "添加附件或项目模板", this::pickImage),
        new LinearLayout.LayoutParams(dp(40), dp(40)));
    modelButton = text("默认模型", 12, fg);
    modelButton.setPadding(dp(4), dp(6), dp(4), dp(6));
    modelButton.setMinHeight(dp(40));
    modelButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    modelButton.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(line), null, rounded(fg, 8)));
    modelButton.setContentDescription("选择模型与推理强度");
    clickable(modelButton, this::chooseModel);
    LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(0, -2, 1);
    modelParams.setMargins(0, 0, dp(4), 0);
    controls.addView(modelButton, modelParams);
    permissionButton = icon("shield", "权限：沿用远端权限", this::choosePermission);
    controls.addView(permissionButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
    send = icon("arrow", "发送消息", this::sendMessage);
    controls.addView(send, new LinearLayout.LayoutParams(dp(40), dp(40)));
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            app.draftText=s.toString();
            updateSend();
          }

          public void afterTextChanged(Editable e) {}
        });
    input.setOnKeyListener(
        (v, key, event) -> {
          if (key == KeyEvent.KEYCODE_ENTER
              && event.isCtrlPressed()
              && event.getAction() == KeyEvent.ACTION_DOWN) {
            sendMessage();
            return true;
          }
          return false;
        });
  }

  private void scheduleRender() {
    if (scheduled) return;
    scheduled = true;
    app.main.postDelayed(
        () -> {
          scheduled = false;
          if (!isDestroyed()) render();
        },
        40);
  }

  private boolean nearBottom() {
    return scroll.nearBottom();
  }

  private void loadOlderMessages() {
    int before=conversation.getHeight(),y=scroll.getScrollY();String thread=app.threadId;
    scroll.read();historyLimit+=60;historyRender=true;render();historyRender=false;
    conversation.post(()->{if(thread.equals(app.threadId)){scroll.scrollTo(0,y+Math.max(0,conversation.getHeight()-before));scheduleMedia();}});
  }

  private static boolean sameContent(ChatItem a,ChatItem b) {
    return b!=null && a.text.equals(b.text) && a.detail.equals(b.detail) && a.status.equals(b.status)
        && a.phase.equals(b.phase) && a.type.equals(b.type) && a.images.equals(b.images)
        && Objects.equals(a.interaction==null?null:a.interaction.toString(),b.interaction==null?null:b.interaction.toString());
  }

  private void render() {
    if (usageHeader != null) usageHeader.setText(usageHeaderText());
    if(switchingConnection && !app.connecting) {
      switchingConnection=false;
      if(!app.connected && !app.error.isEmpty()) app.main.post(this::editCurrentProfile);
    }
    if (!renderedThread.equals(app.threadId)) {
      renderedThread = app.threadId;
      if (input != null) { input.setText(app.draftText); input.setSelection(input.length()); }
      scroll.follow();
      historyLimit=60;firstRender=true;
      messageUpdatesPaused = false;
    }
    title.setText(getString(R.string.app_name));
    String project = app.currentProject();
    projectLabel.setText("项目 · " + (project.isEmpty() ? "远端默认目录" : project));
    projectLabel.setVisibility(app.profile == null ? View.GONE : View.VISIBLE);
    usageLabel.setText(app.usage.summary());
    usageLabel.setVisibility(app.connected ? View.VISIBLE : View.GONE);
    boolean recovering=app.recoveryInProgress();
    // Fade only while a resume is mid-flight; plain read-only history (even while
    // loading or connected-but-idle) always keeps the actions available.
    boolean resuming = recovering && app.recoveryStatus().contains("接续");
    boolean showActions = app.readOnly && !resuming;
    // Property animations only: legacy AlphaAnimation+setAlpha compose multiplicatively
    // and left the buttons rendered transparent while still clickable.
    roActions.animate().cancel();
    if(showActions) {
      readOnlyBar.setVisibility(View.VISIBLE);
      if(roActions.getAlpha()<1f)roActions.animate().alpha(1f).setDuration(400).start();
    }else if(readOnlyBar.getVisibility()!=View.GONE || roActions.getAlpha()>0f) {
      roActions.animate().alpha(0f).setDuration(400)
          .withEndAction(()->readOnlyBar.setVisibility(View.GONE)).start();
    }
    readOnlyLabel.setText("只读历史 · 尚未取得写入权限");
    boolean available=app.connected && !app.connecting && !recovering;
    newChatButton.setEnabled(available);
    resumeButton.setEnabled(available && app.readOnly && !app.attaching);
    // Reading project history is not permission to resume a writer.
    updateRecoveryButton(resumeButton,resumeSpinner,resuming,"接续中","接续");
    input.setEnabled(true);
    input.setHint(app.readOnly ? "先准备消息，接续后发送" : "发送消息");
    status.setText(app.connected ? AppIcons.label(this,"●  " + app.profile.title() + "  ·  SSH","status",null,Color.rgb(48,148,112),dp(14)) : "");
    status.setTextColor(app.connected ? Color.parseColor(isDark()?"#309470":"#247A5B") : muted);
    error.setText(app.error.isEmpty() ? "" : app.error.split("\n", 2)[0] + "\n点按查看原因和诊断信息");
    error.setVisibility(app.error.isEmpty() ? View.GONE : View.VISIBLE);
    renderAttachments();
    renderQueuedMessage();
    updateSend();
    PermissionMode displayedPermission=app.displayedPermission();
    boolean forced = app.forcedApprovals.active();
    permissionButton.icon = forced ? "bolt" : PermissionPicker.icon(displayedPermission);
    permissionButton.color = forced ? permissionPicker.red() : permissionPicker.color(displayedPermission);
    permissionButton.setContentDescription("权限：" + (forced ? "强制允许" : displayedPermission.label));
    permissionButton.setTooltipText(forced ? "强制允许 · 新权限生效前自动允许审批" : displayedPermission.label + (app.permissionMode==PermissionMode.INHERIT && displayedPermission!=PermissionMode.INHERIT ? " · 当前远端状态" : ""));
    permissionButton.invalidate();
    modelButton.setText(ModelOptions.displayName(app.models, app.model)
        + (app.reasoningEffort.isEmpty() ? "" : " · " + ModelOptions.label(app.reasoningEffort))
        + (app.serviceTierSummary().isEmpty() ? "" : " · " + app.serviceTierSummary()));
    RuntimeControls.apply(modelButton, permissionButton, app.runtimeCapabilities);
    runningIndicator.setVisibility(app.currentThreadRunning() ? View.VISIBLE : View.GONE);
    if (scroll.isFollowing() || historyRender) {
      messageUpdatesPaused = false;
      jump.setTooltipText("滚动到最新消息");
      List<ChatItem> visibleItems = new ArrayList<>();
      List<ChatItem> presentation=app.displayItems();
      int start=Math.max(0,presentation.size()-historyLimit);
      historyButton.setVisibility(start>0?View.VISIBLE:View.GONE);
      historyButton.setText("加载更早的消息（还有 "+start+" 项）");
      for (ChatItem source : presentation.subList(start,presentation.size())) {
        ChatItem snapshot = new ChatItem(source.id, source.type, source.text);
        snapshot.detail = source.detail; snapshot.status = source.status;
        snapshot.phase=source.phase;snapshot.turnId=source.turnId;
        if(source.interaction!=null)try{snapshot.interaction=new JSONObject(source.interaction.toString());}catch(JSONException ignored){}
        snapshot.images.addAll(source.images);
        visibleItems.add(snapshot);
      }
      empty.setVisibility(visibleItems.isEmpty() && !app.busy ? View.VISIBLE : View.GONE);
      messages.setVisibility(visibleItems.isEmpty() ? View.GONE : View.VISIBLE);
      busyLabel.setVisibility(app.currentThreadRunning() ? View.VISIBLE : View.GONE);
      if (app.remoteRunning && app.readOnly) busyLabel.setText("远端运行中 · 自动刷新历史");
      if (empty.getVisibility() == View.VISIBLE) renderEmpty();
      List<String> desired = new ArrayList<>();
      for (ChatItem item : visibleItems) desired.add(item.id);
      boolean styleChanged=false;
      for(ChatItem item:visibleItems){ChatItem prior=currentItems.get(item.id);if(prior!=null && (!prior.type.equals(item.type)||!prior.phase.equals(item.phase)))styleChanged=true;}
      if (!desired.equals(order) || styleChanged) {
        boolean appendOnly = desired.size() >= order.size() && desired.subList(0, order.size()).equals(order);
        if(styleChanged)appendOnly=false;
        int from = appendOnly ? order.size() : 0;
        if (!appendOnly) {
          messages.removeAllViews();
          texts.clear();
          messageBodies.clear();
          mediaRows.clear();
          mediaSources.clear();
          for(MessageImageTile tile:mediaTiles)tile.release();
          mediaTiles.clear();questionCards.clear();
          rendered.clear();
          currentItems.clear();
        }
        order.clear();
        order.addAll(desired);
        for (int i = from; i < visibleItems.size(); i++) addItem(visibleItems.get(i), appendOnly && !firstRender);
      }
      for (ChatItem item : visibleItems) {
        currentItems.put(item.id, item);
        if (!sameContent(item,rendered.get(item.id))) {
          rendered.put(item.id, item);
          QuestionHistoryView questionCard=questionCards.get(item.id);
          if(questionCard!=null && item.interaction!=null)questionCard.bind(item.interaction);
          MessageBodyView messageBody = messageBodies.get(item.id);
          if (messageBody != null) messageBody.setSource(item.text);
          TextView view = texts.get(item.id);
          if (view != null) {
            // Empty reasoning items are valid protocol events, not missing replies.
            ((View) view.getParent()).setVisibility(item.type.equals("reasoning")
                && !item.status.equals("inProgress") && item.detail.trim().isEmpty() ? View.GONE : View.VISIBLE);
            view.setText(
                item.prose()
                    ? (item.user() ? item.text : Markdown.render(item.text, muted, panel, this::openLink))
                    : toolTitle(item));
          }
        }
        renderMedia(item);
      }
      updateMessageSpacing();
      if (!visibleItems.isEmpty() && !historyRender) followLatest();
      firstRender = false;
    } else {
      messageUpdatesPaused = true;
      jump.setVisibility(View.VISIBLE);
      jump.setTooltipText("消息区已暂停刷新，回到底部查看最新内容");
    }
    scheduleMedia();
    if (wide) fillNavigation(sidebar, false);
    if (browser != null && browser.isShowing()) browser.refresh();
    if (drawer != null && drawer.isShowing())
      fillNavigation((LinearLayout) drawer.findViewById(R.id.navigation), true);
    showPending();
  }

  private void updateRecoveryButton(TextView button,LoadingSpinner spinner,boolean active,String busyText,String idleText) {
    button.setText(active?busyText:idleText);button.setTooltipText(active?app.recoveryStatus():idleText);
    button.setCompoundDrawablePadding(dp(4));button.setCompoundDrawables(active?spinner:null,null,null,null);
    if(active && visibleActivity)spinner.start();else spinner.stop();
  }

  private CharSequence toolTitle(ChatItem m) {
    String symbol = m.status.equals("completed") ? "✓" : m.status.equals("failed") ? "!" : "›";
    String icon=m.status.equals("completed")?"check":m.status.equals("failed")?"error":"chevron";
    return AppIcons.label(this,symbol + "  " + m.text.replaceAll("\\s+", " ") + "  ›",icon,"chevron",muted,dp(15));
  }

  private void renderEmpty() {
    empty.removeAllViews();
    if (app.connected) {
      // Connected landing keeps only the subtitle and suggestions; no brand mark, no heading.
      TextView sub = text("从一个问题开始，让工作继续。", 14, muted);
      sub.setGravity(Gravity.CENTER);
      sub.setLineSpacing(dp(5), 1);
      sub.setPadding(0, dp(28), 0, dp(20));
      empty.addView(sub);
    } else {
      IconView mark = new IconView(this, "brand", fg);
      mark.setBackground(rounded(panel, 28));
      empty.addView(mark, new LinearLayout.LayoutParams(dp(88), dp(88)));
      TextView h = text("灵感无处不在", 26, fg);
      h.setTypeface(null, Typeface.BOLD);
      h.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
      hp.topMargin = dp(26);
      empty.addView(h, hp);
      TextView sub = text("连接 SSH 主机，在这里继续你的工作。", 14, muted);
      sub.setGravity(Gravity.CENTER);
      sub.setLineSpacing(dp(5), 1);
      sub.setPadding(0, dp(12), 0, dp(28));
      empty.addView(sub);
    }
    if (app.connected) {
      for (String suggestion : new String[] {"介绍一下这个项目", "检查项目中的问题", "继续之前的工作"}) {
        TextView chip =
            action(
                suggestion + "  ↗",
                () -> {
                  input.setText(suggestion);
                  input.setSelection(suggestion.length());
                  input.requestFocus();
                });
        chip.setText(AppIcons.label(this,chip.getText().toString(),null,"arrowUpRight",fg,dp(18)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(wide ? dp(340) : -1, -2);
        cp.bottomMargin = dp(10);
        empty.addView(chip, cp);
      }
    } else {
      TextView button =
          action(
              app.connecting ? "取消连接" : "连接电脑",
              () -> {
                if (app.connecting) app.disconnect();
                else if (app.vault.profiles.isEmpty()) editProfile(new Profile(), true);
                else connectProfile(app.profile != null ? app.profile : app.vault.profiles.get(0));
              });
      button.setTextColor(bg);
      button.setBackground(rounded(fg, 28));
      empty.addView(button, new LinearLayout.LayoutParams(dp(180), dp(52)));
      connectHint = text("", 13, muted);
      connectHint.setGravity(Gravity.CENTER);
      connectHint.setPadding(0, dp(12), 0, 0);
      LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
      empty.addView(connectHint, hintParams);
      if (app.connecting || app.recoveryInProgress()) {
        connectHint.setText((app.profile == null ? "" : app.profile.title() + " · ")
            + ConnectionService.summary(app));
      }
    }
  }

  private void addItem(ChatItem item, boolean animate) {
    LinearLayout wrap = column();
    wrap.setGravity(item.user() ? Gravity.END : Gravity.START);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
    messages.addView(wrap, wp);
    if(item.type.equals("question")) {
      QuestionHistoryView card=new QuestionHistoryView(this,fg,muted,
          Color.parseColor(isDark()?"#1C1C1C":"#FFFFFF"),
          fg,this::copy);
      questionCards.put(item.id,card);currentItems.put(item.id,item);
      LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);wrap.addView(card,cp);
      return;
    }
    TextView content = text("", item.prose() ? 16 : 13, item.prose() ? fg : muted);
    content.setLineSpacing(dp(5), 1);
    content.setTextIsSelectable(true);
    content.setLinksClickable(true);
    content.setMovementMethod(LinkMovementMethod.getInstance());
    if (!item.type.equals("agentMessage") && !item.type.equals("plan")) texts.put(item.id, content);
    currentItems.put(item.id, item);
    if (item.user()) {
      content.setBackground(rounded(panel, 22));
      content.setPadding(dp(18), dp(12), dp(18), dp(12));
      content.setMaxWidth(
          dp(wide ? 510 : Math.max(180, getResources().getConfiguration().screenWidthDp - 92)));
    } else if (!item.prose()) {
      content.setTextIsSelectable(false);
      content.setBackground(rounded(panel, 16));
      content.setPadding(dp(10), dp(6), dp(10), dp(6));
      content.setSingleLine(true);
      content.setEllipsize(TextUtils.TruncateAt.END);
      clickable(
          content,
          () -> {
            ChatItem latest = currentItems.get(item.id);
            if (latest != null) showDetails(latest);
          });
    }
    if (item.type.equals("agentMessage") || item.type.equals("plan")) {
      boolean plan=item.type.equals("plan"),progress=!plan && item.phase.equals("commentary");
      LinearLayout proseCard=column();
      proseCard.setBackground(rounded(progress?bg:Color.parseColor(isDark()?(plan?"#202020":"#161616"):(plan?"#EFF1F4":"#FFFFFF")),18));
      proseCard.setPadding(dp(progress?4:14),dp(progress?3:6),dp(progress?4:14),dp(progress?3:8));
      wrap.addView(proseCard,new LinearLayout.LayoutParams(-1,-2));
      if(!progress) {
        LinearLayout heading=row();TextView kind=text(plan?"计划":"回复",12,muted);heading.addView(kind,new LinearLayout.LayoutParams(0,-2,1));
        IconView copyButton=icon("copy",plan?"复制计划":"复制回复",()->{ChatItem latest=currentItems.get(item.id);if(latest!=null)copy(latest.text);});
        heading.addView(copyButton,new LinearLayout.LayoutParams(dp(48),dp(48)));proseCard.addView(heading);
      }
      MessageBodyView messageBody = new MessageBodyView(this,progress?muted:fg,muted,Color.parseColor(isDark()?"#2B2B2B":"#ECEEF1"),this::openLink,this::copy);
      messageBodies.put(item.id,messageBody); proseCard.addView(messageBody,new LinearLayout.LayoutParams(-1,-2));
    } else if(item.user()) {
      LinearLayout bubbleRow=row();bubbleRow.setGravity(Gravity.END|Gravity.BOTTOM);
      IconView copyButton=icon("copy", "复制消息", () -> {ChatItem latest=currentItems.get(item.id);if(latest!=null)copy(latest.text);});
      copyButton.alignBottomEnd=true;
      bubbleRow.addView(copyButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
      bubbleRow.addView(content,new LinearLayout.LayoutParams(-2,-2));
      wrap.addView(bubbleRow,new LinearLayout.LayoutParams(-1,-2));
    } else wrap.addView(content, new LinearLayout.LayoutParams(item.prose() ? -2 : -1, -2));
    LinearLayout media = column();
    mediaRows.put(item.id, media);
    wrap.addView(media, new LinearLayout.LayoutParams(-1, -2));
    if (animate) {
      wrap.setAlpha(0);
      wrap.setTranslationY(dp(6));
      wrap.animate().alpha(1).translationY(0).setDuration(180).start();
    }
  }

  // One gap per visible boundary, rather than stacking each message's margins.
  // Run after updates as empty reasoning items can become visible while streaming.
  private void updateMessageSpacing() {
    ChatItem previous=null;
    for(int i=0;i<messages.getChildCount();i++) {
      View wrap=messages.getChildAt(i);ChatItem item=currentItems.get(order.get(i));
      boolean visible=wrap.getVisibility()!=View.GONE;
      int gap=visible && previous!=null ? dp(messageGap(previous,item)) : 0;
      LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)wrap.getLayoutParams();
      if(params.topMargin!=gap || params.bottomMargin!=0){params.topMargin=gap;params.bottomMargin=0;wrap.setLayoutParams(params);}
      if(visible)previous=item;
    }
  }
  private static int messageGap(ChatItem before,ChatItem after) {
    boolean beforeSummary=!before.prose() && !before.type.equals("question");
    boolean afterSummary=!after.prose() && !after.type.equals("question");
    if(beforeSummary && afterSummary)return 2;
    boolean beforeProgress=before.type.equals("agentMessage") && before.phase.equals("commentary");
    boolean afterProgress=after.type.equals("agentMessage") && after.phase.equals("commentary");
    if((beforeSummary && afterProgress) || (beforeProgress && afterSummary))return 6;
    return 8;
  }

  private void updateSend() {
    if (send == null) return;
    boolean hasText = !input.getText().toString().trim().isEmpty();
    boolean hasContent=hasText || !app.draftImages.isEmpty() || !app.draftFiles.isEmpty();
    boolean stop = app.busy && !hasContent;
    send.icon = stop ? "stop" : "arrow";
    boolean enabled =
        !app.replaying && !app.readOnly
            && !app.attaching
            && (app.busy ? (stop || (app.connected && !app.recoveryInProgress() && !app.threadId.isEmpty())) : (app.connected && !app.recoveryInProgress() && hasContent));
    send.setAlpha(enabled ? 1 : .28f);
    send.color = bg;
    send.setBackground(rounded(fg, 24));
    send.setContentDescription(stop ? "停止生成" : app.busy ? "发送引导消息" : "发送消息");
    send.setEnabled(enabled);
    send.invalidate();
  }

  private void sendMessage() {
    if (app.readOnly) {
      toast("此线程正在其他客户端运行，请先接续或新建对话");
      return;
    }
    if (app.attaching) { toast("附件正在上传，请稍候"); return; }
    if (app.busy) {
      String guidance = input.getText().toString().trim();
      if (guidance.isEmpty() && app.draftImages.isEmpty() && app.draftFiles.isEmpty()) app.interrupt();
      else showRunningSendOptions(guidance);
      return;
    }
    if (!app.connected) {
      showDrawer();
      return;
    }
    String s = input.getText().toString().trim();
    if (s.isEmpty() && app.draftImages.isEmpty() && app.draftFiles.isEmpty()) return;
    app.send(s);
    input.setText("");
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(input.getWindowToken(), 0);
  }

  private void showRunningSendOptions(String text) {
    final String thread = app.threadId, turn = app.turnId;
    LinearLayout options = column();
    options.setPadding(dp(20), 0, dp(20), dp(8));
    TextView preview = text(text.isEmpty()?"发送已选择的附件":text, 14, muted);
    preview.setMaxLines(2); preview.setEllipsize(TextUtils.TruncateAt.END);
    preview.setPadding(dp(8), dp(4), dp(8), dp(16)); options.addView(preview);
    Dialog[] holder = new Dialog[1];
    if(app.canSteer()) options.addView(action("立即引导", () -> {
      if (thread.equals(app.threadId) && turn.equals(app.turnId) && app.steer(text)) {
        if (input.getText().toString().trim().equals(text)) input.setText("");
      } else toast("本轮状态已变化，请重新发送");
      holder[0].dismiss();
    }));
    TextView later = action("稍后发送", () -> {
      if (thread.equals(app.threadId) && turn.equals(app.turnId) && app.queueMessage(text,true)) {
        if (input.getText().toString().trim().equals(text)) input.setText("");
      } else toast(app.queuedMessage().isEmpty() ? "本轮状态已变化，请重新发送" : "队列已满或当前状态不能排队");
      holder[0].dismiss();
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
    options.addView(later, lp);
    options.addView(action("查看待发队列",()->{holder[0].dismiss();showPendingQueue();}));
    holder[0] = sheet("发送方式", options);
  }

  private void renderQueuedMessage() {
    queuedBar.removeAllViews();
    String queued = app.queuedMessage();
    queuedBar.setVisibility(queued.isEmpty() ? View.GONE : View.VISIBLE);
    if (queued.isEmpty()) return;
    TextView preview = text("待发送 · " + queued, 12, muted);
    preview.setMaxLines(1); preview.setEllipsize(TextUtils.TruncateAt.END);
    preview.setPadding(dp(8), dp(8), dp(8), dp(8));
    preview.setOnClickListener(v->showPendingQueue());
    queuedBar.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
    if (app.connected && !app.busy && !app.readOnly)
      queuedBar.addView(icon("arrow", "发送待发消息", app::sendQueuedMessage), new LinearLayout.LayoutParams(dp(40), dp(40)));
    queuedBar.addView(icon("menu", "管理待发队列", this::showPendingQueue), new LinearLayout.LayoutParams(dp(40), dp(40)));
  }

  private void showPendingQueue() {
    if(pendingQueueDialog!=null)pendingQueueDialog.dismiss();
    pendingQueueDialog=PendingQueueDialog.show(this,app);
  }

  private void pickImage() {
    LinearLayout choices = column(); choices.setPadding(dp(20), 0, dp(20), dp(16));
    TextView hint = text("图片可多选，也可通过文件管理器选择文档、压缩包等文件。每条消息最多 4 张图片和 8 个文件；文件每个最多 100 MiB。", 13, muted);
    hint.setPadding(dp(4), 0, dp(4), dp(12)); choices.addView(hint);
    Dialog[] holder = new Dialog[1];
    choices.addView(action("项目提示词模板",()->{holder[0].dismiss();PromptTemplateDialog.show(this,app,input,fg,muted);}));
    String[] labels = {"手机相册", "其他相册 / 图片 App", "系统选择器"};
    ImagePicker.Source[] sources = ImagePicker.Source.values();
    for (int i = 0; i < labels.length; i++) {
      ImagePicker.Source source = sources[i];
      TextView option = action(labels[i], () -> { holder[0].dismiss(); launchImagePicker(source); });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8);
      choices.addView(option, params);
    }
    choices.addView(action("选择文件",()->{holder[0].dismiss();launchFilePicker();}));
    holder[0] = sheet("添加附件", choices);
  }

  private String imageProject() {
    return app.threadCwd + "\n" + (app.profile == null ? "" : app.profile.cwd);
  }

  private void launchImagePicker(ImagePicker.Source source) {
    if (!app.runtimeCapabilities.supports("attachments")) { toast(app.runtimeCapabilities.reason()); return; }
    if (pendingImageGeneration >= 0) { toast("请先完成当前附件选择"); return; }
    if (!app.canAttach() || app.profile == null) {toast(app.attachmentUnavailable()); return;}
    int remaining = app.remainingImages();
    if (remaining <= 0) { toast("每条消息最多 " + MediaFiles.MAX_ATTACHMENTS + " 张图片"); return; }
    pendingImageGeneration = app.connectionGeneration(); pendingImageHost = app.profile.id;
    pendingImageThread = app.threadId; pendingImageProject = imageProject();
    if (!ImagePicker.launch(this, source, remaining)) {
      pendingImageGeneration = -1; toast("没有可用的图片选择器，请安装或启用相册 / 文件 App");
    }
  }

  private void launchFilePicker() {
    if (!app.runtimeCapabilities.supports("attachments")) { toast(app.runtimeCapabilities.reason()); return; }
    if (pendingImageGeneration >= 0) { toast("请先完成当前附件选择"); return; }
    if(!app.canAttach() || app.profile==null){toast(app.attachmentUnavailable());return;}
    int remaining=app.remainingFiles();
    if(remaining<=0){toast("每条消息最多 "+FileAttachment.MAX_FILES+" 个文件");return;}
    pendingImageGeneration=app.connectionGeneration();pendingImageHost=app.profile.id;
    pendingImageThread=app.threadId;pendingImageProject=imageProject();
    if(!FilePicker.launch(this,remaining)){pendingImageGeneration=-1;toast("没有可用的文件管理器，请安装或启用文件 App");}
  }

  private void addFileAttachment(FileAttachment file) {
    LinearLayout tile=row();tile.setGravity(Gravity.CENTER_VERTICAL);tile.setBackground(rounded(bg,8));
    tile.setPadding(dp(10),dp(6),0,dp(6));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(210),dp(88));params.setMarginEnd(dp(8));
    attachmentStrip.addView(tile,params);
    TextView label=text(file.name+"\n"+android.text.format.Formatter.formatShortFileSize(this,file.size),13,fg);
    label.setMaxLines(3);label.setEllipsize(TextUtils.TruncateAt.END);label.setContentDescription("待发送文件 "+file.name);
    tile.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    IconView remove=icon("close","移除文件 "+file.name,()->{
      if(app.steering || app.attaching || (app.busy && app.turnId.isEmpty()))return;
      app.draftFiles.remove(file);app.changed();
    });
    remove.setEnabled(!app.steering && !app.attaching && (!app.busy || !app.turnId.isEmpty()));
    tile.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(48)));
  }

  private void renderAttachments() {
    attachmentStrip.removeAllViews();
    attachmentScroll.setVisibility(app.attaching || !app.draftImages.isEmpty() || !app.draftFiles.isEmpty() ? View.VISIBLE : View.GONE);
    int index = 0;
    for (String path : new ArrayList<>(app.draftImages)) {
      addAttachmentPreview(app.draftImagePreviews.get(path), path, ++index);
    }
    for(FileAttachment file:new ArrayList<>(app.draftFiles))addFileAttachment(file);
    if (app.attaching) {
      if(!app.attachingFiles)addAttachmentPreview(app.pendingImagePreview, null, index + 1);
      TextView uploading = text((app.attachingFiles?"文件上传中…":"图片上传中…") + (app.imageUploadCount > 1 ? "（" + app.imageUploadIndex + " / " + app.imageUploadCount + "）" : ""), 13, muted);
      uploading.setContentDescription(app.attachingFiles?"文件上传中…":"图片上传中…"); attachmentStrip.addView(uploading);
    }
  }

  private void addAttachmentPreview(android.graphics.Bitmap bitmap, String path, int index) {
    FrameLayout tile = new FrameLayout(this);
    tile.setBackground(rounded(bg, 8));
    tile.setClipToOutline(true);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(88), dp(88));
    params.setMarginEnd(dp(8));
    attachmentStrip.addView(tile, params);
    if (bitmap != null) {
      ImageView preview = new ImageView(this);
      preview.setImageBitmap(bitmap);
      preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
      preview.setContentDescription("待发送图片 " + index);
      preview.setOnClickListener(v->{if(path!=null)openImage(path);else openImagePreview(bitmap);});PressFeedback.apply(preview,isDark());
      tile.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    } else {
      TextView label = text("图片 " + index, 12, muted);
      label.setGravity(Gravity.CENTER);
      label.setContentDescription("待发送图片 " + index);
      if(path!=null){label.setOnClickListener(v->openImage(path));PressFeedback.apply(label,isDark());}
      tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
    }
    if (path != null) {
      IconView remove = icon("close", "移除图片 " + index, () -> {
        if (app.steering || app.attaching || (app.busy && app.turnId.isEmpty())) return;
        app.draftImages.remove(path);
        app.draftImagePreviews.remove(path);
        app.changed();
      });
      remove.setBackground(rounded(panel, 8));
      remove.setEnabled(!app.steering && !app.attaching && (!app.busy || !app.turnId.isEmpty()));
      tile.addView(remove, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END));
    }
  }

  private void scheduleMedia() {
    if(mediaScheduled)return;mediaScheduled=true;app.main.postDelayed(refreshMedia,16);
  }
  private void updateMediaViewport() {
    if(isDestroyed() || scroll==null)return;
    android.graphics.Rect rect=new android.graphics.Rect();
    for(MessageImageTile tile:mediaTiles){tile.refreshConnection();tile.visible=tile.valid() && tile.tile.isShown() && tile.tile.getGlobalVisibleRect(rect) && rect.height()>0;if(!tile.visible)tile.release();}
    for(MessageImageTile tile:mediaTiles)if(tile.visible)tile.load();
  }

  private void openImagePreview(android.graphics.Bitmap preview) {
    showImage(null,preview);
  }

  private void openImage(String source) {
    android.graphics.Bitmap preview=app.draftImagePreviews.get(source);
    if(preview==null)for(MessageImageTile tile:mediaTiles)if(tile.source.equals(source) && tile.loaded && tile.preview.getDrawable() instanceof BitmapDrawable){preview=((BitmapDrawable)tile.preview.getDrawable()).getBitmap();break;}
    showImage(source,preview);
  }

  private void showImage(String source,android.graphics.Bitmap preview) {
    if(imageDialog!=null)imageDialog.dismiss();
    int generation=app.connectionGeneration();String host=app.profile==null?"":app.profile.id;
    imageDialog=new ImageDialog(this,isDark(),source,preview,
        (path,out,active)->app.readMedia(path,out,MediaFiles.IMAGE_LIMIT,active),
        ()->!isDestroyed() && generation==app.connectionGeneration() && host.equals(app.profile==null?"":app.profile.id),
        this::saveMedia);
  }

  private void renderMedia(ChatItem item) {
    LinearLayout row = mediaRows.get(item.id);
    List<String> sources = MediaFiles.images(item);
    if (row == null) return;
    if (sources.equals(mediaSources.get(item.id)) && mediaTiles.stream().noneMatch(tile->tile.itemId.equals(item.id) && !tile.sameContext())) return;
    mediaSources.put(item.id, sources);
    mediaTiles.removeIf(tile->{if(!tile.itemId.equals(item.id))return false;tile.release();return true;});
    row.removeAllViews();
    LinearLayout grid=null;int index=0;
    for(String original:sources) {
      String source=original;
      try{if(!source.startsWith("data:") && !source.startsWith("https://"))source=MediaFiles.remotePath(source,app.currentProject());}catch(Exception ignored){}
      if(index%2==0 || sources.size()==1){grid=row();row.addView(grid,new LinearLayout.LayoutParams(-1,-2));}
      MessageImageTile tile=new MessageImageTile(this,app,ui,thumbnailWorker,item.id,source,row,sources.size()>1,()->mediaRows.get(item.id)==row,this::openImage);mediaTiles.add(tile);grid.addView(tile.tile);index++;
    }
    scheduleMedia();
  }

  private void openLink(String source) {
    if (source.startsWith("https://") || source.startsWith("http://")) {
      try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(source))); }
      catch (ActivityNotFoundException e) { toast("没有可打开链接的浏览器"); }
    } else saveMedia(source);
  }

  private void saveMedia(String source) {
    try {
      String resolved = source.startsWith("data:") || source.startsWith("https://") ? source
          : MediaFiles.remotePath(source, app.profile == null ? "" : app.profile.cwd);
      if (pendingDownload.length() > 0) { toast("请先完成当前保存操作"); return; }
      pendingDownload = resolved;
      pendingDownloadHost = app.profile == null ? "" : app.profile.id;
      startActivityForResult(MediaFiles.saveIntent(resolved), 44);
    } catch (Exception e) { pendingDownload = ""; toast(ConnectionFailure.reason(e)); }
  }

  private void copy(String s) {
    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), s));
    toast("已复制");
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  private void showConnectionError() {
    String diagnostic = app.error.isEmpty() ? app.lastError : app.error;
    if (diagnostic.isEmpty()) {
      toast("暂无错误记录");
      return;
    }
    TextView detail = text(diagnostic, 14, fg);
    detail.setTextIsSelectable(true);
    detail.setPadding(dp(20), dp(12), dp(20), dp(12));
    ScrollView sc = new ScrollView(this);
    sc.addView(detail);
    new AlertDialog.Builder(this)
        .setTitle("连接信息")
        .setView(sc)
        .setNegativeButton("复制诊断", (d, w) -> copy(diagnostic))
        .setNeutralButton(
            ConnectionSetupUi.canContinue(app, diagnostic) ? "关闭持久模式" : app.unsentText.isEmpty() ? null : "恢复消息",
            (d, w) -> {
              if (ConnectionSetupUi.canContinue(app, diagnostic)) {
                ConnectionSetupUi.continueWithoutPersistence(this, app); return;
              }
              input.setText(app.unsentText);
              input.setSelection(input.length());
              app.clearError();
            })
        .setPositiveButton("知道了", (d, w) -> app.clearError())
        .show();
  }

  private void choosePermission() {
    if (!app.runtimeCapabilities.supports("approval")) { toast(app.runtimeCapabilities.reason()); return; }
    permissionPicker.show();
  }

  private String detailText(ChatItem item) {
    if (item.type.equals("reasoning")) return item.detail.trim().isEmpty()
        ? (item.status.equals("inProgress") ? "正在等待思考摘要…" : "远端未提供此思考项的摘要。部分思考项可能没有可显示的摘要。") : item.detail;
    return item.text + (item.detail.isEmpty() ? "" : "\n\n" + item.detail);
  }

  private void showDetails(ChatItem m) {
    LinearLayout layout = column();
    layout.setPadding(dp(20), dp(12), dp(20), dp(20));
    TextView detail = text(detailText(m), 13, fg);
    detail.setTypeface(Typeface.MONOSPACE);
    detail.setTextIsSelectable(true);
    ScrollView s = new ScrollView(this);
    s.addView(detail);
    layout.addView(s, new LinearLayout.LayoutParams(-1, dp(380)));
    layout.addView(action("复制", () -> {
      ChatItem latest = currentItems.get(m.id);
      copy(detailText(latest == null ? m : latest));
    }));
    Dialog dialog = sheet(m.type.equals("reasoning") ? "思考过程" : "执行详情", layout);
    Runnable update = new Runnable() {
      public void run() {
        if (!dialog.isShowing()) return;
        ChatItem latest = currentItems.get(m.id);
        if (latest != null) {
          String value = detailText(latest);
          if (!value.contentEquals(detail.getText())) detail.setText(value);
        }
        app.main.postDelayed(this, 150);
      }
    };
    dialog.setOnDismissListener(d -> app.main.removeCallbacks(update));
    update.run();
  }

  private Dialog sheet(String heading, View content) { return ui.sheet(heading, content, wide); }

  // Edge-swipe menus (HIG-style): swipe inward from the left bezel to open the
  // drawer, from the right bezel to open the chat menu. Only gestures that
  // START inside a narrow bezel band count, so list scrolling and taps are
  // untouched; the event stream is never consumed.
  private float edgeStartX, edgeStartY;
  private boolean edgeTracking, edgeFired;

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (!wide) {
      final int screenWidth = getWindow().getDecorView().getWidth();
      final int third = screenWidth / 3;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          edgeStartX = event.getX();
          edgeStartY = event.getY();
          // 起手在左/右三分之一屏幕即可触发, 位移 40dp 且横向为主
          edgeTracking = edgeStartX <= third || edgeStartX >= screenWidth - third;
          edgeFired = false;
          break;
        case MotionEvent.ACTION_MOVE:
          if (edgeTracking && !edgeFired) {
            float dx = event.getX() - edgeStartX;
            float dy = event.getY() - edgeStartY;
            if (Math.abs(dx) > dp(40) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
              if (edgeStartX <= third && dx > 0) { edgeFired = true; showDrawer(); }
              else if (edgeStartX >= screenWidth - third && dx < 0) { edgeFired = true; if (returnToWorkPanel) navigateBack(); else chatMenu(); }
            }
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          edgeTracking = false;
          break;
        default:
          break;
      }
    }
    return super.dispatchTouchEvent(event);
  }

  private void showDrawer() {
    returnToWorkPanel = false;
    if (browser != null && browser.isShowing()) return;
    browser =
        new RemoteBrowser(
            this,
            app,
            isDark(),
            new RemoteBrowser.Actions() {
              public void connect(Profile p) {
                connectProfile(p);
              }

              public void version() { showRemoteVersion(); }

              public void add() {
                browser.dismiss();
                editProfile(new Profile(), true);
              }

              public void directories() {
                openDirectoryPicker();
              }

              public void sftp(String path) {
                if (sftpBrowser != null) sftpBrowser.dismiss();
                sftpBrowser = new SftpBrowser(MainActivity.this, app, isDark(), path);
                sftpBrowser.show();
              }

              public void diagnostics() {
                showConnectionError();
              }

              public void chat() {
                startNewChat();
              }

              public void edit() {
                if(app.profile!=null)editProfile(app.profile,false);else editProfile(new Profile(),true);
              }


            });
    app.leaveIdleConversation();
    browser.show();
  }

  private void openDirectoryPicker() {
    if (!app.connected) {
      toast("请先连接 SSH 主机");
      return;
    }
    if (!app.canSwitchConversation()) {
      toast("正在恢复会话或上传附件，请稍候");
      return;
    }
    directoryPicker =
        new DirectoryPicker(
            this,
            app,
            isDark(),
            path -> {
              if (app.useProject(path) && browser != null && browser.isShowing())
                browser.openProject(path);
            });
    directoryPicker.show(app.profile.cwd);
  }

  private void startNewChat() {
    returnToWorkPanel = false;
    if (app.connected && !app.canSwitchConversation()) {
      toast("正在连接会话或上传附件，请稍候"); return;
    }
    if (!app.connected) {
      showDrawer();
      return;
    }
    if (app.profile.cwd.isEmpty()) {
      openDirectoryPicker();
      return;
    }
    app.draftText=input.getText().toString();
    app.newChat();
  }

  private void fillNavigation(LinearLayout nav, boolean modal) {
    if (nav == null) return;
    nav.removeAllViews();
    TextView label = text("连接", 12, muted);
    label.setPadding(dp(4), dp(8), 0, dp(12));
    nav.addView(label);
    for (Profile p : app.vault.profiles) {
      LinearLayout r = row();
      TextView t =
          text(
              (app.connected && app.profile.id.equals(p.id) ? "●  " : "▱  ")
                  + p.title()
                  + "\n"
                  + p.user
                  + "@"
                  + p.host,
              14,
              fg);
      t.setPadding(dp(8), dp(12), dp(8), dp(12));
      t.setText(AppIcons.label(this,t.getText().toString(),app.connected && app.profile.id.equals(p.id)?"status":"computer",null,fg,dp(18)));
      t.setMaxLines(2);
      clickable(
          t,
          () -> {
            if (modal && drawer != null) drawer.dismiss();
            if (app.profile == p && app.connected) connectionMenu();
            else connectProfile(p);
          });
      r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(
          icon("chevron", "编辑 " + p.title(), () -> editProfile(p, false)),
          new LinearLayout.LayoutParams(dp(44), dp(48)));
      nav.addView(r);
    }
    TextView addConnection=action("＋ 添加 SSH 连接", () -> editProfile(new Profile(), true));
    addConnection.setText(AppIcons.label(this,addConnection.getText().toString(),"plus",null,fg,dp(18)));nav.addView(addConnection);
    if (app.connected && app.profile != null) {
      nav.addView(action("⌨ 终端 · " + app.profile.user + "@" + app.profile.host, () -> {
        if (drawer != null) drawer.dismiss();
        openTerminalDialog();
      }));
    }
    if (app.connected && (app.tuiThreads.length() > 0 || app.tuiIdeIpc)) {
      int tuiActive = 0;
      for (int i = 0; i < app.tuiThreads.length(); i++) {
        org.json.JSONObject watched = app.tuiThreads.optJSONObject(i);
        if (watched != null && watched.optBoolean("active")) tuiActive++;
      }
      TextView tuiHeader = text("PC 终端会话（Codex TUI · 旁观" + (tuiActive > 0 ? " · 运行中 " + tuiActive : "") + "）", 12, muted);
      tuiHeader.setPadding(dp(4), dp(28), 0, dp(12));
      nav.addView(tuiHeader);
      for (int i = 0; i < app.tuiThreads.length() && i < 20; i++) {
        org.json.JSONObject watched = app.tuiThreads.optJSONObject(i);
        if (watched == null) continue;
        String tid = watched.optString("id");
        if (tid.isEmpty()) continue;
        String name = watched.optString("title", "").trim();
        String tuiLabel = (watched.optBoolean("active") ? "▶ 运行中 · " : "○ 空闲 · ")
            + (name.isEmpty() ? "会话 " + tid.substring(0, Math.min(8, tid.length())) : name);
        TextView item = text(tuiLabel, 13, watched.optBoolean("active") ? fg : muted);
        item.setPadding(dp(8), dp(10), dp(8), dp(10));
        item.setMaxLines(1);
        item.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        clickable(
            item,
            () -> {
              if (!app.canSwitchConversation()) {
                toast("正在恢复会话或上传附件，请稍候");
                return;
              }
              if (drawer != null) drawer.dismiss();
              app.viewConversation(tid);
            });
        nav.addView(item);
      }
      if (app.tuiIdeIpc) {
        TextView ide = text("检测到 IDE / 桌面 Codex IPC 通道", 12, muted);
        ide.setPadding(dp(8), dp(4), 0, dp(4));
        nav.addView(ide);
      }
    }
    TextView history = text("最近的对话", 12, muted);
    history.setPadding(dp(4), dp(28), 0, dp(12));
    nav.addView(history);
    if (!app.connected) {
      TextView note = text("连接电脑后查看历史对话", 13, muted);
      note.setPadding(dp(4), dp(4), 0, dp(16));
      nav.addView(note);
    } else if (app.threads.length() == 0) {
      nav.addView(text("还没有对话", 13, muted));
    }
    for (int i = 0; i < app.threads.length(); i++) {
      JSONObject t = app.threads.optJSONObject(i);
      if (t == null) continue;
      String id = t.optString("id");
      String name = t.isNull("name") ? "" : t.optString("name");
      if (name.trim().isEmpty()) name = t.optString("preview", "新对话");
      TextView item = text(name.trim().isEmpty() ? "新对话" : name, 14, fg);
      item.setPadding(dp(8), dp(14), dp(8), dp(14));
      item.setMaxLines(2);
      item.setEllipsize(TextUtils.TruncateAt.END);
      if (id.equals(app.threadId)) item.setBackground(rounded(line, 12));
      clickable(
          item,
          () -> {
            if (!app.canSwitchConversation()) {
              toast("正在恢复会话或上传附件，请稍候"); return;
            }
            app.draftText = input.getText().toString();
            if (modal && drawer != null) drawer.dismiss();
            app.viewConversation(id);
          });
      // 左滑露出「删除」: 与远程控制列表一致的交互
      SwipeActionLayout swipe = new SwipeActionLayout(
          this, item, dp(72), "删除", bg, () -> {
            if (app.threadRunning(t)) {
              toast("会话运行中，稍后再删"); return;
            }
            new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("永久删除远端对话及消息，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                  if (drawer != null) drawer.dismiss();
                  app.deleteThread(id, err -> { if (err != null) toast("删除失败: " + err.getMessage()); });
                })
                .show();
          });
      nav.addView(swipe, new LinearLayout.LayoutParams(-1, -2));
    }
    if (app.connected) {
      nav.addView(
          action(
              app.nextCursor.isEmpty() ? "刷新对话" : "加载更多",
              () -> app.refreshThreads(!app.nextCursor.isEmpty())));
    }
    TextView theme = action("外观", () -> chooseTheme());
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
    tp.topMargin = dp(20);
    nav.addView(theme, tp);
  }

  /** Secondary menu keeps the primary standard: title-free, centered, 60% width. */
  private void chooseTheme() {
    String[] modes = {"跟随系统", "浅色", "深色"};
    int current = getPreferences(0).getInt("theme", 0);
    String[] labels = new String[modes.length];
    for (int i = 0; i < modes.length; i++) labels[i] = (i == current ? "✓ " : "") + modes[i];
    CenteredMenu.show(this, fg, muted, "外观设置", labels, which -> {
      getPreferences(0).edit().putInt("theme", which).apply();
      recreate();
    });
  }

  /** Explicit top-right menu. Swiping left remains the work-panel shortcut. */
  private void showSettings() {
    String[] options = {"外观设置", "文字大小", "后台设置", "添加连接", "当前连接", "应用沙箱"};
    String version;
    try { version=getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
    catch (Exception e) { version=getString(R.string.app_name); }
    CenteredMenu.show(this,fg,muted,version,options,which -> {
      if (which == 0) chooseTheme();
      else if (which == 1) chooseFontSize();
      else if (which == 2) backgroundSettings();
      else if (which == 3) editProfile(new Profile(), true);
      else if (which == 4) editCurrentProfile();
      else if (which == 5) SandboxSettings.show(this);
    });
  }

  private void editCurrentProfile() {
    ConnectionPicker.show(this,app,fg,muted,this::connectProfile,
        p -> editProfile(p,!app.vault.profiles.stream().anyMatch(saved -> saved.id.equals(p.id))), previousConnection);
  }

  private void showMainMenu() { showSettings(); }

  private void chatMenu() {
    if (workPanel != null) workPanel.dismiss();
    if (pendingQueueDialog != null) pendingQueueDialog.dismiss();
    workPanel = WorkPanel.show(this, app, isDark(), this::startNewChat, this::showConnectionError,
        this::conversationInfo, id -> {
          if (!returnToWorkPanel) {
            workPanelOrigin = app.threadId;
            workPanelOriginHome = app.homeVisible;
          }
          returnToWorkPanel = true;
          app.draftText = input.getText().toString();
          app.viewConversation(id);
        });
  }

  private void chooseFontSize() {
    float[] scales = {0, .9f, 1f, 1.15f, 1.3f};
    String[] labels = {"跟随系统", "小", "标准", "大", "更大"};
    float current = getSharedPreferences("display", MODE_PRIVATE).getFloat("fontScale", 0);
    int selected = 0;
    for (int i = 0; i < scales.length; i++) if (Math.abs(scales[i] - current) < .001f) selected = i;
    String[] rows = new String[labels.length];
    for (int i = 0; i < labels.length; i++) rows[i] = (i == selected ? "✓ " : "") + labels[i];
    CenteredMenu.show(this, fg, muted, "文字大小", rows, which -> {
      getSharedPreferences("display", MODE_PRIVATE).edit().putFloat("fontScale", scales[which]).apply();
      recreate();
    });
  }

  private void backgroundSettings() {
    // Compact action menu: no long explanation wall; header hints the purpose.
    CenteredMenu.show(this,fg,muted,null,new String[]{"电池设为不受限","通知与流体云","Tailscale"},which->{
      if(which==0) {
        try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException e) { toast("请在系统应用设置中打开 "+getString(R.string.app_name)); }
      } else if (which == 1) liveStatusSettings();
      else if (which == 2) TsnetSetup.show(this);
    });
  }

  private void liveStatusSettings() {
    // Compact toggle menu: no dialog title, no explanation wall; only a one-line
    // system hint when the current device needs attention.
    android.content.SharedPreferences prefs = getSharedPreferences("background", MODE_PRIVATE);
    boolean on = prefs.getBoolean("liveStatus", true);
    String hint;
    if (Build.VERSION.SDK_INT >= 36) {
      boolean allowed = getSystemService(NotificationManager.class).canPostPromotedNotifications();
      hint = allowed ? "" : "系统尚未允许实时通知";
    } else hint = "本机将显示普通任务通知";
    CenteredMenu.show(this,fg,muted,hint,
        new String[]{(on ? "✓ " : "") + "显示任务状态与审批流体云","打开系统通知设置"},
        which -> {
          if (which == 0) {
            prefs.edit().putBoolean("liveStatus", !on).apply();
            app.liveStatus.update();
          } else {
            try { startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())); }
            catch (ActivityNotFoundException e) { toast("请在系统设置中打开 "+getString(R.string.app_name)+" 的通知设置"); }
          }
        });
  }

  private void conversationInfo() {
    String project = app.currentProject();
    String details = "项目：" + (project.isEmpty() ? "远端默认目录（未返回路径）" : project)
        + "\n\n对话：" + (app.threadTitle.isEmpty() ? "新对话" : app.threadTitle)
        + "\n\n对话 ID：" + (app.threadId.isEmpty() ? "发送首条消息后创建" : app.threadId)
        + "\n\n状态：" + (!app.connected ? "未连接" : app.readOnly ? "只读，可尝试接续" : app.busy ? "正在处理" : "可继续对话");
    new AlertDialog.Builder(this).setTitle("当前项目与对话").setMessage(details)
        .setPositiveButton("关闭", null)
        .setNeutralButton("复制", (d, w) -> {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          clipboard.setPrimaryClip(android.content.ClipData.newPlainText("当前项目与对话", details));
          toast("已复制");
        }).show();
  }

  private void openTerminalDialog() {
    if (!app.connected) {
      toast("请先连接 SSH 主机");
      return;
    }
    TextView output = new TextView(this);
    output.setTypeface(android.graphics.Typeface.MONOSPACE);
    output.setTextSize(12);
    output.setTextColor(0xffe0e0e0);
    output.setBackgroundColor(0xff101014);
    output.setTextIsSelectable(true);
    output.setPadding(dp(10), dp(10), dp(10), dp(10));
    output.setText(app.terminal.text());
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(output);
    scroll.setBackgroundColor(0xff101014);
    android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("$ 输入命令，回车或点发送");
    input.setSingleLine(true);
    input.setTypeface(android.graphics.Typeface.MONOSPACE);
    input.setTextColor(fg);
    input.setHintTextColor(muted);
    android.widget.Button send = new android.widget.Button(this);
    send.setText("发送");
    android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
    bar.addView(input, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(send, new android.widget.LinearLayout.LayoutParams(-2, -2));
    android.widget.LinearLayout root = new android.widget.LinearLayout(this);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("终端 · " + app.profile.user + "@" + app.profile.host)
            .setView(root)
            .setPositiveButton("关闭", null)
            .create();
    java.util.function.Consumer<String> update =
        text -> {
          if (!dialog.isShowing()) return;
          output.setText(text);
          scroll.post(() -> scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN));
        };
    send.setOnClickListener(
        v -> {
          String command = input.getText().toString();
          if (!command.trim().isEmpty()) {
            app.sendTerminalLine(command);
            input.setText("");
          }
        });
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
            send.performClick();
            return true;
          }
          return false;
        });
    input.setOnLongClickListener(
        v -> {
          pickTerminalHistory(input);
          return true;
        });
    dialog.setOnDismissListener(d -> app.closeTerminal());
    dialog.show();
    dialog.getWindow().setLayout(-1, dp(560));
    app.openTerminal(update, e -> toast("终端不可用：" + e.getMessage()));
  }

  private void pickTerminalHistory(android.widget.EditText input) {
    if (app.terminal.history.isEmpty()) {
      toast("暂无历史命令");
      return;
    }
    String[] items = app.terminal.history.toArray(new String[0]);
    CenteredMenu.show(this, fg, muted, "历史命令（长按输入框呼出）", items,
        which -> input.setText(items[which]));
  }

  private TextView usageHeader;
  private AlertDialog usageDialog;

  /** Cache window for account usage: reopening within an hour shows data instantly. */
  private static final long USAGE_CACHE_MS = 60L * 60L * 1000L;

  private void usageInfo() {
    usageInfo(false);
  }

  private void usageInfo(boolean forceRefresh) {
    if (forceRefresh || !app.usage.fresh(USAGE_CACHE_MS)) {
      app.refreshUsage();
      app.refreshUsageStats();
    }
    // Full statistics live directly in the header (the menu scrolls); the only
    // action is 刷新 at the top, closing happens by tapping outside or back.
    // The header view is bound so arriving data repaints it in place.
    if (usageDialog != null) usageDialog.dismiss();
    usageDialog =
        CenteredMenu.show(
            this, fg, muted, usageHeaderText(), new String[] {"刷新"}, which -> usageInfo(true),
            view -> usageHeader = view);
    AlertDialog ownedUsageDialog = usageDialog;
    usageDialog.setOnDismissListener(
        dialog -> {
          if (usageDialog == ownedUsageDialog) {
            usageHeader = null;
            usageDialog = null;
          }
        });
  }

  /** Header text for the usage menu; recomputed whenever usage state changes. */
  private String usageHeaderText() {
    String summary = app.usage.summary();
    if (summary.startsWith("剩余用量 · ")) summary = summary.substring("剩余用量 · ".length());
    StringBuilder header = new StringBuilder("剩余用量\n").append(summary);
    header.append("\n\n").append(app.usage.loading ? "正在读取剩余用量…" : app.usage.details());
    if (!app.usage.error.isEmpty()) header.append("\n\n点“刷新”重试");
    return header.toString();
  }

  private void connectionMenu() {
    if (!app.connected && !app.connecting) {
      showDrawer();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(app.profile.title())
        .setMessage(
            app.profile.user + "@" + app.profile.host + ":" + app.profile.port + "\n" + app.state)
        .setPositiveButton("返回", null)
        .setNeutralButton("编辑连接", (d, w) -> editProfile(app.profile, false))
        .setNegativeButton("断开连接", (d, w) -> app.disconnectAll(this))
        .show();
  }

  private void connectProfile(Profile p) {
    if ((app.connected || app.connecting) && app.profile != null && app.profile.id.equals(p.id)) return;
    if (app.attaching || app.attachingFiles || activeMediaDownloads > 0 || SftpContent.hasActiveOperations()) {
      toast("文件正在传输，请完成后再切换连接"); return;
    }
    if (app.connecting) { toast("正在连接，请稍后再切换"); return; }
    boolean changing = app.profile != null && !app.profile.id.equals(p.id);
    boolean attention = app.remoteRunning || (app.busy && !app.readOnly) || !app.approvals.isEmpty()
        || TurnController.of(app).hasPendingForCurrentProfile();
    for (ConversationState task : app.workConversations())
      attention |= task.remoteRunning || (task.busy && !task.readOnly) || !task.approvals.isEmpty();
    boolean attachments = !app.draftImages.isEmpty() || !app.draftFiles.isEmpty();
    if (changing && (attention || attachments)) {
      new AlertDialog.Builder(this).setTitle("切换连接？")
          .setMessage("切换后暂时无法查看或处理原连接的任务；远端是否继续取决于持久会话。待发队列会暂停，不会发送到新连接。" +
              (attachments ? "\n当前未发送附件将清除，请切回后重新选择。" : ""))
          .setNegativeButton("取消",null)
          .setPositiveButton("切换",(d,w)->switchConnection(p)).show();
    } else switchConnection(p);
  }

  private void switchConnection(Profile p) {
    // Recheck after the confirmation: a file operation may have started meanwhile.
    if(app.attaching || app.attachingFiles || activeMediaDownloads > 0 || SftpContent.hasActiveOperations() || app.connecting) {
      toast("当前操作尚未完成，请稍后切换"); return;
    }
    if (!p.tailscaleSsh && p.password.isEmpty() && p.privateKey.isEmpty()) {
      editProfile(p,false); return;
    }
    if(app.profile!=null && !app.profile.id.equals(p.id)) {
      if(input!=null)connectionDrafts.put(app.profile.id,input.getText().toString());
      previousConnection=app.profile;
    }
    if(drawer!=null)drawer.dismiss();
    if(workPanel!=null)workPanel.dismiss();
    if(pendingQueueDialog!=null)pendingQueueDialog.dismiss();
    returnToWorkPanel=false;
    switchingConnection=false;
    if(input!=null)input.setText("");
    beginConnect(p);
    switchingConnection=true;
    app.draftText=connectionDrafts.getOrDefault(p.id,"");
    if(input!=null)input.setText(app.draftText);
    app.changed();
  }

  private void beginConnect(Profile p) {
    if (!p.tailscaleSsh && p.password.isEmpty() && p.privateKey.isEmpty()) {
      editProfile(p, false);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    app.connect(p);
  }

  private void editProfile(Profile original, boolean fresh) {
    if (profileEditor != null && profileEditor.isShowing()) return;
    if (drawer != null) drawer.dismiss();
    profileEditor = new ProfileEditor(this, app, ui, wide, new ProfileEditor.Actions() {
      public void connect(Profile profile) { connectProfile(profile); }
      public void removed() { render(); }
    });
    profileEditor.show(original, fresh);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == ImagePicker.REQUEST || request == FilePicker.REQUEST) {
      boolean sameContext = pendingImageGeneration >= 0 && pendingImageGeneration == app.connectionGeneration()
          && app.profile != null && pendingImageHost.equals(app.profile.id) && pendingImageThread.equals(app.threadId)
          && pendingImageProject.equals(imageProject());
      pendingImageGeneration = -1;
      if (result == RESULT_OK && data != null) {
        List<android.net.Uri> selected = ImagePicker.selected(data);
        if (!selected.isEmpty()) {
          if (!sameContext) toast("连接或对话已改变，请在当前对话重新选择附件");
          else if(request==FilePicker.REQUEST)app.attachFiles(selected);
          else app.attachImages(selected);
        }
      }
      return;
    }
    if (request == TsnetVpnService.VPN_REQUEST) {
      if (result == RESULT_OK) SandboxSettings.start(this);
      else toast("未授予 VPN 权限，沙箱未启用");
      return;
    }
    if (request == 46) {
      String[] p = pendingSftpSave; pendingSftpSave = null;
      if (p == null || result != RESULT_OK || data == null || data.getData() == null) return;
      if (app == null) { toast("连接已断开，下载取消"); return; }
      SftpDownloadDialog.start(this, app, p[0], p[1], Long.parseLong(p[2]), data.getData());
      return;
    }
    if (request == 44) {
      String source = pendingDownload, host = pendingDownloadHost;
      pendingDownload = "";
      if (result == RESULT_OK && data != null && data.getData() != null && !source.isEmpty()) {
        android.net.Uri destination = data.getData();
        toast("正在保存文件…");
        activeMediaDownloads++;
        mediaWorker.execute(() -> {
          try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException("无法写入所选位置");
            if (app.profile == null || !host.equals(app.profile.id)) throw new IOException("连接已切换，请重新下载");
            app.readMedia(source, out, MediaFiles.DOWNLOAD_LIMIT);
            app.main.post(() -> toast("文件已保存"));
          } catch (Exception e) {
            try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), destination); } catch (Exception ignored) { }
            app.main.post(() -> toast("保存失败：" + ConnectionFailure.reason(e)));
          } finally { app.main.post(() -> activeMediaDownloads--); }
        });
      }
    }
    if (request == ProfileEditor.REQUEST_KEY && result == RESULT_OK && data != null && profileEditor != null)
      profileEditor.importKey(data.getData());
  }


  private void chooseModel() {
    if (!app.runtimeCapabilities.supports("models")) { toast(app.runtimeCapabilities.reason()); return; }
    if (modelPicker != null) modelPicker.dismiss();
    modelPicker = new ModelPicker(this, app, ui, wide);
    modelPicker.show();
  }

  private void showRemoteVersion() {
    if (!app.connected || versionDialog != null) return;
    RemoteVersion version = app.remoteVersion;
    shownVersion = version;
    String target = version.latest;
    // Compact menu standard: version facts live in the small header, one short
    // hint line replaces the old explanation wall, actions are centered rows.
    String header = "Codex 版本"
        + "\n会话 " + (version.running.isEmpty() ? "未知" : version.running)
        + " · 已安装 " + (version.installed.isEmpty() ? "未知" : version.installed)
        + " · 最新 " + (target.isEmpty() ? "尚未获取" : target);
    if (!version.reason.isEmpty()) header += "\n" + version.reason;
    if (!version.error.isEmpty()) header += "\n" + version.error;
    if (version.checking) header += "\n正在检查更新";
    else if (version.updating) header += "\n正在更新，请保持连接";
    else if (version.needsRestart()) header += "\n新版已安装，重启后启用（会停止进行中的对话）";
    else if (version.canUpdate()) header += "\n空闲时自动启用新版；运行中的任务完成后生效";
    java.util.List<String> rows = new java.util.ArrayList<>();
    java.util.List<Runnable> actions = new java.util.ArrayList<>();
    if (version.canUpdate()) {
      rows.add("更新至 " + target);
      actions.add(() -> app.updateRemoteVersion(version, target));
    } else if (version.needsRestart()) {
      rows.add("重启并启用新版");
      actions.add(() -> confirmRemoteRestart(version));
    }
    if (!version.checking && !version.updating) {
      rows.add("重新检查");
      actions.add(() -> app.checkRemoteVersion(true));
    }
    rows.add("关闭");
    actions.add(() -> {});
    versionDialog = CenteredMenu.show(this, fg, muted, header, rows.toArray(new String[0]),
        which -> actions.get(which).run());
    versionDialog.setOnDismissListener(d -> { versionDialog = null; shownVersion = null; });
  }

  private void confirmRemoteRestart(RemoteVersion version) {
    if (!app.connected || version != app.remoteVersion || !version.needsRestart()) return;
    new AlertDialog.Builder(this)
        .setTitle("停止任务并重启？")
        .setMessage("重启将立即停止这台远端电脑上由 RemoteGPT 托管的所有进行中对话和待处理审批。未完成的回复会中断。\n\n重启后 App 会自动重新连接，并使用已安装的 Codex "
            + version.installed + "。")
        .setNegativeButton("取消", null)
        .setPositiveButton("停止任务并重启", (d, w) -> app.restartRemoteVersion(version))
        .show();
  }

  private void showPending() {
    if (versionDialog != null && (!app.connected || shownVersion != app.remoteVersion)) versionDialog.dismiss();
    if (app.connected && app.remoteVersion.prompt && versionDialog == null
        && trustDialog == null && approvalDialog == null && app.visibleApproval() == null) {
      app.remoteVersion.prompt = false;
      showRemoteVersion();
    }
    if (!app.trustHost.isEmpty() && (trustDialog == null || !trustDialog.isShowing())) {
      String fingerprint = app.trustFingerprint;
      trustDialog =
          new AlertDialog.Builder(this)
              .setTitle("首次连接此主机")
              .setMessage(
                  app.trustHost
                      + "\n\n主机指纹\n"
                      + fingerprint
                      + "\n\n请与服务器上的 SSH 主机指纹核对。信任后会记住此主机，密钥变化时拒绝连接。")
              .setNegativeButton("取消", (d, w) -> app.trust(false))
              .setPositiveButton("信任并连接", (d, w) -> app.trust(true))
              .setOnCancelListener(d -> app.trust(false))
              .show();
    }
    if (app.trustHost.isEmpty() && trustDialog != null) {
      trustDialog.dismiss();
      trustDialog = null;
    }
    JSONObject req = app.visibleApproval();
    String id = req == null ? "" : app.notificationApprovalToken(req);
    if (!id.equals(shownApproval) && approvalDialog != null) {
      approvalDialog.dismiss();
      approvalDialog = null;
      shownApproval = "";
      questionForm = null;
    }
    if (req == null || approvalDialog != null || app.isAnswering(req)) return;
    shownApproval = id;
    JSONObject p = Json.object(req, "params");
    if (ConversationJournal.question(req)) {
      showQuestions(req, p);
      return;
    }
    String description =
        p.optString("reason", "远端请求执行以下操作")
            + "\n\n"
            + p.optString("command", p.optString("grantRoot", "文件修改"))
            + "\n"
            + p.optString("cwd")
            + "\n会话："
            + p.optString("threadId");
    if (p.has("networkApprovalContext")) description += "\n网络访问：" + p.opt("networkApprovalContext");
    for (ChatItem item : app.items)
      if (item.id.equals(p.optString("itemId")) && !item.detail.isEmpty())
        description += "\n\n" + item.detail;
    TextView details = text(description, 14, fg);
    details.setPadding(dp(20), dp(12), dp(20), dp(12));
    details.setTextIsSelectable(true);
    ScrollView sc = new ScrollView(this);
    sc.addView(details);
    approvalDialog =
        new AlertDialog.Builder(this)
            .setTitle("需要你的确认")
            .setView(sc)
            .setCancelable(false)
            .setNeutralButton(app.forcedApprovals.canOffer(req) ? "全部允许(强制)" : null, null)
            .setNegativeButton(
                "拒绝",
                (d, w) -> {
                  app.answer(req, Json.obj("decision", "decline"));
                  approvalDialog = null;
                  shownApproval = "";
                })
            .setPositiveButton(
                "允许一次",
                (d, w) -> {
                  app.answer(req, Json.obj("decision", "accept"));
                  approvalDialog = null;
                  shownApproval = "";
                })
            .show();
    AlertDialog approvals = (AlertDialog) approvalDialog;
    if (app.forcedApprovals.canOffer(req)) {
      approvals.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(permissionPicker.red());
      approvals.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> permissionPicker.confirm(req));
    }
  }

  private void showQuestions(JSONObject req, JSONObject p) {
    QuestionForm form = new QuestionForm(this,p,fg,muted,panel);
    questionForm=form;
    if (shownApproval.equals(restoredQuestionToken)) form.restoreAnswers(restoredQuestion);
    restoredQuestion=null;restoredQuestionToken="";
    ScrollView sc = new ScrollView(this);
    sc.addView(form);
    approvalDialog =
        new AlertDialog.Builder(this)
            .setTitle("补充信息")
            .setView(sc)
            .setCancelable(false)
            .setNegativeButton(
                ConversationJournal.savedQuestion(req) ? "不回答" : "停止任务",
                (d, w) -> {
                  app.cancelQuestion(req);
                  approvalDialog = null;
                  shownApproval = "";
                })
            .setPositiveButton("发送", null)
            .show();
    AlertDialog dialog=(AlertDialog) approvalDialog;
    android.widget.Button sendAnswer=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    form.onChanged(()->sendAnswer.setEnabled(form.ready()));
    sendAnswer.setOnClickListener(v->{
      if(!form.ready() || app.isAnswering(req))return;
      app.answer(req,form.result());
      if(app.isAnswering(req)) {
        restoredQuestion=form.saveAnswers();restoredQuestionToken=shownApproval;
        dialog.dismiss();approvalDialog=null;shownApproval="";questionForm=null;
      }
    });
  }
}
