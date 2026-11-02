package app.remotessh;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** Document viewer: double-tap edits, pinch zooms, exit auto-saves; never touches conversations. */
public final class SftpFileActivity extends Activity {
  private RemoteApp app;
  private SshTransport transport;
  private SftpContent content;
  private int generation;
  private String profile,path;
  private TextView status;
  private EditText editor;
  private ScrollView scroller;
  private SftpContent.Text loaded;
  private volatile boolean closed;
  private boolean busy,editing,dirty,downloadOnly;
  private Uri pendingUpload;
  private String uploadPath;
  private SftpContent.Snapshot uploadExpected;
  private RemoteScreens ui;
  private float textSize=13f;
  private boolean markdown, python;
  private DraftStore drafts;
  private SftpContent.Snapshot editSnapshot;
  private android.text.method.KeyListener plainKeys;

  /** Direct third-party open from any page: temp cache copy + ACTION_VIEW, no preview page. */
  static void openExternal(Activity owner,RemoteApp app,String path){
    if(!app.connected || app.currentTransport()==null || app.profile==null){Toast.makeText(owner,UiText.get(R.string.work_text_001),Toast.LENGTH_SHORT).show();return;}
    new AlertDialog.Builder(owner).setTitle(UiText.get(R.string.work_text_002))
      .setMessage(path+"\n" + UiText.get(R.string.work_text_003))
      .setNegativeButton(UiText.get(R.string.work_text_004),null)
      .setPositiveButton(UiText.get(R.string.work_text_005),(d,w)->{
        final SshTransport transport=app.currentTransport();
        final int generation=app.connectionGeneration();
        final Profile profile=app.profile;
        final SftpContent content=transport==null?null:transport.fileContent();
        if(content==null){Toast.makeText(owner,UiText.get(R.string.work_text_006),Toast.LENGTH_SHORT).show();return;}
        Toast.makeText(owner,UiText.get(R.string.work_text_007),Toast.LENGTH_SHORT).show();
        app.executeBackground(()->{
          try{
            File dir=CacheFileProvider.openDir(owner);dir.mkdirs();
            final File target=new File(dir,CachePaths.uniqueRelative(CachePaths.baseName(path)));
            File parent=target.getParentFile();if(parent!=null)parent.mkdirs();
            try(OutputStream out=new FileOutputStream(target)){
              content.download(path,out,()->{
                CompletableFuture<Boolean> ok=new CompletableFuture<>();
                app.main.post(()->ok.complete(app.connected && app.currentTransport()==transport
                    && app.connectionGeneration()==generation && app.profile==profile));
                if(!ok.get(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException(UiText.get(R.string.work_text_008));
              });
            }
            app.main.post(()->{
              if(owner.isFinishing()||owner.isDestroyed())return;
              Uri uri=CacheFileProvider.uriFor(owner,dir,target);
              Intent view=new Intent(Intent.ACTION_VIEW);
              view.setDataAndType(uri,owner.getContentResolver().getType(uri));
              view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              view.setClipData(ClipData.newUri(owner.getContentResolver(),UiText.get(R.string.work_text_009),uri));
              try{owner.startActivity(view);}
              catch(ActivityNotFoundException e){Toast.makeText(owner,UiText.get(R.string.work_text_010),Toast.LENGTH_LONG).show();}
            });
          }catch(Exception e){
            String reason=ConnectionFailure.reason(e);
            app.main.post(()->Toast.makeText(owner,UiText.get(R.string.work_text_011)+reason,Toast.LENGTH_LONG).show());
          }
        });
      }).show();
  }
  static void open(Activity owner,RemoteApp app,String path,boolean upload,boolean dark,boolean save){
    if(!app.connected || app.currentTransport()==null || app.profile==null){Toast.makeText(owner,UiText.get(R.string.work_text_001),Toast.LENGTH_SHORT).show();return;}
    Intent i=new Intent(owner,SftpFileActivity.class).putExtra("path",path).putExtra("upload",upload)
        .putExtra("save",save).putExtra("generation",app.connectionGeneration()).putExtra("profile",app.profile.id).putExtra("dark",dark);
    owner.startActivity(i);
  }
  @Override protected void attachBaseContext(android.content.Context base) {
    super.attachBaseContext(AppLanguage.wrap(base));
  }

  @Override public void onCreate(Bundle state){
    setTheme(getIntent().getBooleanExtra("dark",false)?R.style.AppThemeDark:R.style.AppTheme);
    super.onCreate(state);app=(RemoteApp)getApplication();transport=app.currentTransport();
    content=transport==null?null:transport.fileContent();
    if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
    generation=getIntent().getIntExtra("generation",-1);profile=getIntent().getStringExtra("profile");path=getIntent().getStringExtra("path");drafts=new DraftStore(new File(getCacheDir(),"drafts"));
    downloadOnly=getIntent().getBooleanExtra("save",false);
    ui=new RemoteScreens(this,getIntent().getBooleanExtra("dark",false));
    LinearLayout root=ui.column();root.setBackgroundColor(ui.bg);root.setFitsSystemWindows(true);
    LinearLayout header=ui.row();header.setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));
    String name=path==null?"":path.substring(path.lastIndexOf('/')+1);
    TextView heading=ui.text(downloadOnly?UiText.get(R.string.work_text_012):getIntent().getBooleanExtra("upload",false)?UiText.get(R.string.work_text_013):name.isEmpty()?UiText.get(R.string.work_text_014):name,20);
    heading.setTypeface(null,android.graphics.Typeface.BOLD);
    heading.setGravity(android.view.Gravity.CENTER_VERTICAL);heading.setIncludeFontPadding(false);
    header.addView(heading,new LinearLayout.LayoutParams(0,ui.dp(56),1));
    IconView close=ui.icon("close",UiText.get(R.string.work_text_015),this::onBackPressed);close.setBackground(null);
    header.addView(close,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
    root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(64)));
    TextView title=ui.text(path==null?"":path,12);title.setTextColor(ui.muted);title.setTextIsSelectable(true);
    title.setPadding(ui.dp(16),0,ui.dp(16),0);root.addView(title);
    status=ui.text("",12);status.setTextColor(ui.muted);
    status.setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(8));root.addView(status);
    scroller=new ScrollView(this);scroller.setFillViewport(true);scroller.setVerticalScrollBarEnabled(true);
    scroller.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(20));
    editor=new EditText(this);editor.setTextColor(ui.fg);editor.setBackgroundColor(0);
    editor.setGravity(android.view.Gravity.TOP);editor.setTypeface(android.graphics.Typeface.MONOSPACE);
    editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    editor.setTextSize(TypedValue.COMPLEX_UNIT_SP,textSize);editor.setTextIsSelectable(true);
    plainKeys=editor.getKeyListener();editor.setKeyListener(null);
    editor.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
      @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
      @Override public void afterTextChanged(Editable s){if(editing)dirty=true;}
    });
    scroller.addView(editor,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
    attachGestures();
    if(!active()){status.setText(UiText.get(R.string.work_text_016));return;}
    if(state!=null&&!downloadOnly){restoreDraft(drafts.load(DraftStore.key(profile,path)));return;}
    if(downloadOnly){heading.setTextIsSelectable(false);scroller.setVisibility(View.GONE);status.setText(UiText.get(R.string.work_text_017));picker(false);}
    else if(getIntent().getBooleanExtra("upload",false)){scroller.setVisibility(View.GONE);picker(true);}
    else load();
  }
  /** Double-tap starts editing, two fingers zoom text, fast left fling saves and exits. */
  private void attachGestures(){
    final android.view.GestureDetector taps=new android.view.GestureDetector(this,new android.view.GestureDetector.SimpleOnGestureListener(){
      @Override public boolean onDoubleTap(MotionEvent e){
        if(!editing&&loaded!=null&&!busy&&!downloadOnly&&!getIntent().getBooleanExtra("upload",false))startEdit();
        return true;
      }
      @Override public boolean onFling(MotionEvent from, MotionEvent to,float vx,float vy){
        if(from!=null&&to!=null&&vx<-1800&&Math.abs(vx)>Math.abs(vy)*1.5f&&(from.getX()-to.getX())>ui.dp(48))leave();
        return true;
      }
    });
    final android.view.ScaleGestureDetector zoom=new android.view.ScaleGestureDetector(this,new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScaleBegin(android.view.ScaleGestureDetector detector){
        // Stop the ScrollView from stealing the two-finger stream mid-pinch.
        ViewParent parent=editor.getParent();
        if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);
        return true;
      }
      @Override public boolean onScale(android.view.ScaleGestureDetector detector){
        textSize=Math.max(7f,Math.min(30f,textSize*detector.getScaleFactor()));
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP,textSize);
        return true;
      }
      @Override public void onScaleEnd(android.view.ScaleGestureDetector detector){
        ViewParent end=editor.getParent();
        if(end!=null)end.requestDisallowInterceptTouchEvent(false);
      }
    });
    editor.setOnTouchListener((view,event)->{
      if(event.getPointerCount()>1){
        ViewParent parent=editor.getParent();
        if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);
      }
      taps.onTouchEvent(event);zoom.onTouchEvent(event);
      if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
        ViewParent parent=editor.getParent();
        if(parent!=null)parent.requestDisallowInterceptTouchEvent(false);
      }
      return false;
    });
  }
  private void startEdit(){
    if(markdown||python){
      // Editing always edits the raw source, not the rendered preview.
      editor.setMovementMethod(android.text.method.ArrowKeyMovementMethod.getInstance());
      editor.setTypeface(android.graphics.Typeface.MONOSPACE);
      editor.setText(loaded.value);
    }
    editing=true;dirty=false;editSnapshot=loaded.snapshot;editor.setKeyListener(plainKeys);editor.requestFocus();
    editor.setSelection(editor.getText().length());
    status.setText(UiText.get(R.string.work_text_018));
    Object service=getSystemService(INPUT_METHOD_SERVICE);
    if(service instanceof android.view.inputmethod.InputMethodManager)
      ((android.view.inputmethod.InputMethodManager)service).showSoftInput(editor,0);
  }
  /** Recreate the editing session from a persisted draft (page rebuild or process death). */
  private void restoreDraft(DraftStore.Draft draft){
    if(draft==null){status.setText(UiText.get(R.string.work_text_019));return;}
    editing=true;dirty=true;editSnapshot=draft.snapshot;
    editor.setKeyListener(plainKeys);editor.setTypeface(android.graphics.Typeface.MONOSPACE);
    editor.setText(draft.text);
    editor.setMovementMethod(android.text.method.ArrowKeyMovementMethod.getInstance());
    editor.setSelection(editor.getText().length());
    loaded=new SftpContent.Text(draft.text,draft.snapshot);
    status.setText(active()?UiText.get(R.string.work_text_020):UiText.get(R.string.work_text_021));
  }
  /** Explicit recovery offer when the same file is opened fresh with a leftover draft. */
  private void offerDraftRecovery(){
    final String key=DraftStore.key(profile,path);
    DraftStore.Draft pending=drafts.load(key);
    if(pending==null||loaded!=null&&pending.text.contentEquals(loaded.value))return;
    String when=new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(pending.savedAt));
    new AlertDialog.Builder(this).setTitle(UiText.get(R.string.work_text_022))
      .setMessage(path+"\n" + UiText.get(R.string.work_text_023)+when+UiText.get(R.string.work_text_024))
      .setNegativeButton(UiText.get(R.string.work_text_025),(d,w)->{drafts.clear(key);status.setText(UiText.get(R.string.work_text_026));})
      .setNeutralButton(UiText.get(R.string.work_text_027),null)
      .setPositiveButton(UiText.get(R.string.work_text_028),(d,w)->restoreDraft(pending)).show();
  }
  private boolean active(){return !closed && app.connected && transport!=null && transport==app.currentTransport()
      && generation==app.connectionGeneration() && app.profile!=null && profile!=null && profile.equals(app.profile.id);}
  private void guard()throws Exception {
    CompletableFuture<Boolean> result=new CompletableFuture<>();app.main.post(()->result.complete(active()));
    if(!result.get(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException(UiText.get(R.string.work_text_029));
  }
  interface Work { void run()throws Exception; }
  private void run(Work work,Runnable success){run(work,success,null);}
  private void run(Work work,Runnable success,java.util.function.Consumer<String> failure){
    if(busy||!active()){status.setText(UiText.get(R.string.work_text_030));return;}
    busy=true;status.setText(UiText.get(R.string.work_text_031));
    app.executeBackground(()->{try{guard();work.run();app.main.post(()->{if(closed)return;busy=false;
      if(!active()){status.setText(UiText.get(R.string.work_text_032));return;}success.run();});
    }catch(Exception e){app.main.post(()->{if(closed)return;busy=false;
      String reason=ConnectionFailure.reason(e);
      if(failure!=null&&active())failure.accept(reason);
      else status.setText(active()?UiText.get(R.string.work_text_033)+reason:UiText.get(R.string.work_text_034));});}});
  }
  private void load(){run(()->{loaded=content.read(path,this::guard);},()->{
    markdown=isMarkdown(path);
    python=!markdown&&PythonHighlight.isPython(path);
    if(markdown){
      editor.setTypeface(android.graphics.Typeface.DEFAULT);
      editor.setText(Markdown.render(loaded.value,ui.muted,ui.panel));
      editor.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
      status.setText(UiText.get(R.string.work_text_035));
    }else if(python){
      editor.setTypeface(android.graphics.Typeface.MONOSPACE);
      CharSequence source=loaded.value;
      // Very large sources render plain: thousands of spans would stall layout.
      if(source.length()<=256*1024)
        source=PythonHighlight.render(loaded.value,pythonPalette(getIntent().getBooleanExtra("dark",false)));
      editor.setText(source);
      editor.setMovementMethod(android.text.method.ArrowKeyMovementMethod.getInstance());
      status.setText(UiText.get(R.string.work_text_036));
    }else{
      editor.setTypeface(android.graphics.Typeface.MONOSPACE);
      editor.setText(loaded.value);
      status.setText(UiText.get(R.string.work_text_037));
    }
    offerDraftRecovery();
    },reason->{if(reason.contains("仅支持下载"))offerExternal(reason);else status.setText(UiText.get(R.string.work_text_033)+reason);});}
  /** iOS-palette syntax colors for the Python preview, dark and light variants. */
  static PythonHighlight.Palette pythonPalette(boolean dark){
    return dark
      ? new PythonHighlight.Palette(0xff5e8ce6,0xff64d2ff,0xff4cd584,0xff8e8e93,0xffff9f0a,0xffbf5af2,0xff7ee0ff,0xffc7c7cc)
      : new PythonHighlight.Palette(0xff1f6feb,0xff0969da,0xff1a7f37,0xff6e7781,0xffbc4c00,0xff8250df,0xff0550ae,0xff57606a);
  }
  static boolean isMarkdown(String path){
    if(path==null)return false;
    String lower=path.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".md")||lower.endsWith(".markdown")||lower.endsWith(".mdx");
  }
  /** Binary/oversize/invalid-UTF-8 files: download a cache copy and let phone apps open it. */
  private void offerExternal(String reason){
    status.setText(reason);
    new AlertDialog.Builder(this).setTitle(UiText.get(R.string.work_text_038))
      .setMessage(reason+"\n" + UiText.get(R.string.work_text_039))
      .setNegativeButton(UiText.get(R.string.work_text_004),null)
      .setPositiveButton(UiText.get(R.string.work_text_002),(d,w)->openExternally()).show();
  }
  private void openExternally(){openExternal(this,app,path);}
  /** Auto-save then close; on failure the page stays so nothing is silently lost. */
  private void leave(){
    if(busy){new AlertDialog.Builder(this).setMessage(UiText.get(R.string.work_text_040))
        .setNegativeButton(UiText.get(R.string.work_text_041),null).setPositiveButton(UiText.get(R.string.work_text_042),(d,w)->finish()).show();return;}
    if(!editing||!dirty||loaded==null){finish();return;}
    byte[] bytes=editor.getText().toString().getBytes(StandardCharsets.UTF_8);
    // Android EditText preserves CR/LF characters; unchanged saves retain exact UTF-8 bytes including BOM.
    if(bytes.length>SftpContent.TEXT_LIMIT){status.setText(UiText.get(R.string.work_text_043));return;}
    run(()->{content.write(path,new ByteArrayInputStream(bytes),SftpContent.TEXT_LIMIT,loaded.snapshot,this::guard);},
        ()->{
          drafts.clear(DraftStore.key(profile,path));
          if(downloadOnly||getIntent().getBooleanExtra("upload",false))finish();
          else{Toast.makeText(this,UiText.get(R.string.work_text_044),Toast.LENGTH_SHORT).show();finish();}
        });
  }
  private void picker(boolean upload){
    if(!active()){if(downloadOnly||getIntent().getBooleanExtra("upload",false))finish();else status.setText(UiText.get(R.string.work_text_008));return;}
    Intent intent=new Intent(upload?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
    if(!upload)intent.putExtra(Intent.EXTRA_TITLE,path.substring(path.lastIndexOf('/')+1));
    try{startActivityForResult(intent,upload?1:2);}catch(ActivityNotFoundException e){status.setText(UiText.get(R.string.work_text_045));}
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    boolean transfer=getIntent().getBooleanExtra("upload",false)||downloadOnly;
    if(result!=RESULT_OK||data==null||data.getData()==null){
      if(transfer){Toast.makeText(this,UiText.get(R.string.work_text_046),Toast.LENGTH_SHORT).show();finish();return;}
      status.setText(UiText.get(R.string.work_text_046));return;
    }
    if(!active()){status.setText(UiText.get(R.string.work_text_047));return;}
    Uri uri=data.getData();
    if(request==2){run(()->{
      File dir=CacheFileProvider.openDir(this);
      File staged=File.createTempFile("download-",".part",dir);
      File backup=null;
      try{
        try(OutputStream out=new FileOutputStream(staged)){content.download(path,out,this::guard);}
        try(InputStream original=getContentResolver().openInputStream(uri)){
          if(original!=null){backup=File.createTempFile("backup-",".part",dir);
            try(OutputStream sink=new FileOutputStream(backup)){byte[] buffer=new byte[16384];int n;
              while((n=original.read(buffer))>=0)sink.write(buffer,0,n);}}
        }catch(IOException unreadable){backup=null;}
        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
          if(out==null)throw new IOException(UiText.get(R.string.work_text_048));
          try(InputStream in=new FileInputStream(staged)){SftpContent.copy(in,out,SftpContent.TRANSFER_LIMIT,this::guard);}
          out.flush();
        }
      }catch(Exception failure){
        boolean restored=false;
        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
          if(out!=null&&backup!=null&&backup.isFile()){
            try(InputStream in=new FileInputStream(backup)){SftpContent.copy(in,out,SftpContent.TRANSFER_LIMIT,()->{});}
            out.flush();restored=true;
          }
        }catch(Exception ignored){}
        throw new IOException(restored?UiText.get(R.string.work_text_049)
          :backup!=null?UiText.get(R.string.work_text_050)
          :UiText.get(R.string.work_text_051),failure);
      }finally{staged.delete();if(backup!=null)backup.delete();}
    },()->{
      if(downloadOnly){Toast.makeText(this,UiText.get(R.string.work_text_052),Toast.LENGTH_SHORT).show();finish();}
      else status.setText(UiText.get(R.string.work_text_053));
    });}
    else if(request==1){pendingUpload=uri;String name=FileAttachment.displayName(getContentResolver(),uri);
      uploadPath=RemoteDirectory.child(path,name);
      run(()->{uploadExpected=content.inspect(uploadPath,this::guard);},()->{
        new AlertDialog.Builder(this).setTitle(uploadExpected.exists?UiText.get(R.string.work_text_054):UiText.get(R.string.work_text_055))
          .setMessage(uploadPath+"\n" + UiText.get(R.string.work_text_056))
          .setNegativeButton(UiText.get(R.string.work_text_004),(d,w)->{status.setText(UiText.get(R.string.work_text_046));finish();})
          .setPositiveButton(UiText.get(R.string.work_text_057),(d,w)->upload()).show();
      });
    }
  }
  private void upload(){run(()->{
    try(InputStream in=getContentResolver().openInputStream(pendingUpload)){
      if(in==null)throw new IOException(UiText.get(R.string.work_text_058));content.write(uploadPath,in,SftpContent.TRANSFER_LIMIT,uploadExpected,this::guard);
    }
  },()->{Toast.makeText(this,UiText.get(R.string.work_text_059),Toast.LENGTH_SHORT).show();finish();});}
  @Override public void onBackPressed(){leave();}
  /** Unsaved edits survive recreation and process death: persist draft+snapshot on stop. */
  @Override protected void onStop(){
    super.onStop();
    if(editing&&dirty&&editSnapshot!=null&&drafts!=null)
      drafts.save(DraftStore.key(profile,path),editor.getText().toString(),editSnapshot);
  }
  @Override protected void onDestroy(){closed=true;if(content!=null)content.cancel();super.onDestroy();}
}
