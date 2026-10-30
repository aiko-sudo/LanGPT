"""Run the real Java bridge policy against deterministic WebView API doubles.

This verifies bridge data/lifecycle behavior; it is not a Chromium/device test.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import testdeps

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
    'android/webkit/JavascriptInterface.java': 'package android.webkit; public @interface JavascriptInterface {}',
    'android/graphics/Bitmap.java': 'package android.graphics; public class Bitmap {}',
    'android/webkit/RenderProcessGoneDetail.java': 'package android.webkit; public class RenderProcessGoneDetail {}',
    'android/net/Uri.java': 'package android.net; public class Uri { final String s; public Uri(String s){this.s=s;} public String toString(){return s;} }',
    'android/content/res/AssetManager.java': '''package android.content.res; public class AssetManager {
      public java.io.InputStream open(String name) throws Exception {
        return new java.io.FileInputStream("app/src/main/assets/" + name);
      } }''',
    'android/app/Activity.java': '''package android.app; public class Activity {
      public void runOnUiThread(Runnable r){r.run();}
      public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
    }''',
    'android/webkit/WebSettings.java': '''package android.webkit; public class WebSettings {
      public static final int MIXED_CONTENT_NEVER_ALLOW=1;
      public boolean js,files,content,network,windows,multiple; public int mixed;
      public void setJavaScriptEnabled(boolean b){js=b;} public void setDomStorageEnabled(boolean b){} public void setAllowFileAccess(boolean b){files=b;}
      public void setAllowContentAccess(boolean b){content=b;} public void setBlockNetworkLoads(boolean b){network=b;}
      public void setMixedContentMode(int n){mixed=n;} public void setJavaScriptCanOpenWindowsAutomatically(boolean b){windows=b;}
      public void setSupportMultipleWindows(boolean b){multiple=b;} public void setSaveFormData(boolean b){}
    }''',
    'android/webkit/WebResourceRequest.java': '''package android.webkit;
      public interface WebResourceRequest {android.net.Uri getUrl();}''',
    'android/webkit/WebResourceResponse.java': '''package android.webkit; public class WebResourceResponse {
      public int status=200; public java.io.InputStream body;
      public WebResourceResponse(String m,String e,java.io.InputStream s){body=s;}
      public WebResourceResponse(String m,String e,int code,String reason,java.util.Map<String,String> headers,java.io.InputStream s){status=code;body=s;}
    }''',
    'android/webkit/WebViewClient.java': '''package android.webkit; public class WebViewClient {
      public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}
      public boolean shouldOverrideUrlLoading(WebView v,String u){return false;}
      public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return null;}
      public void onPageStarted(WebView v,String u,android.graphics.Bitmap b){}
      public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail d){return false;}
    }''',
    'android/webkit/WebView.java': '''package android.webkit; public class WebView {
      public final WebSettings settings=new WebSettings(); public WebViewClient client; public Object bridge;
      public String url; public volatile String script=""; public boolean removed,destroyed;
      public WebView(android.app.Activity a){} public void setBackgroundColor(int c){}
      public WebSettings getSettings(){return settings;} public void setWebViewClient(WebViewClient c){client=c;}
      public void addJavascriptInterface(Object o,String n){bridge=o;}
      public void removeJavascriptInterface(String n){removed=true;}
      public void loadUrl(String s){url=s;client.onPageStarted(this,s,null);} public String getUrl(){return url;}
      public void evaluateJavascript(String s,Object callback){script=s;}
      public void stopLoading(){} public void destroy(){destroyed=true;}
    }''',
    'app/remotessh/BridgeHarness.java': r'''package app.remotessh;
import android.webkit.*;
public class BridgeHarness {
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    java.util.List<String> input=new java.util.ArrayList<>();
    int[] resize={0}, hides={0}, shows={0}; java.util.List<String> copies=new java.util.ArrayList<>();
    TerminalWebView w=new TerminalWebView(new android.app.Activity(),new TerminalWebView.Listener(){
      public void input(String s){input.add(s);} public void closed(){}
      public void showKeyboard(){shows[0]++;}
      public void hideKeyboard(){hides[0]++;} public void copy(String s){copies.add(s);}
      public void resize(int c,int r,int x,int y){resize[0]++;}
    });
    WebView v=w.view;
    check(v.settings.js&&!v.settings.files&&!v.settings.content&&v.settings.network&&!v.settings.windows&&!v.settings.multiple,"locked settings");
    TerminalWebView.Bridge bridge=(TerminalWebView.Bridge)v.bridge;
    bridge.showKeyboard();bridge.hideKeyboard();bridge.copy("before");check(shows[0]==0&&hides[0]==0&&copies.isEmpty(),"UI bridge gated before ready");
    bridge.input("before");check(input.isEmpty(),"bridge gated until local ready");
    for(String path:new String[]{"index.html","terminal.js","vendor/xterm.js","vendor/xterm.css"}){
      WebResourceResponse response=v.client.shouldInterceptRequest(v,()->new android.net.Uri(TerminalWebView.ORIGIN+path));
      check(response.status==200&&response.body.read()!=-1,"local allowlist "+path); response.body.close();
    }
    for(String url:new String[]{"https://evil.test/","file:///etc/passwd","content://foo","https://terminal.invalid.evil/index.html",
        TerminalWebView.ORIGIN+"../index.html",TerminalWebView.ORIGIN+"index.html?x",TerminalWebView.ORIGIN+"vendor/LICENSE"}){
      check(v.client.shouldInterceptRequest(v,()->new android.net.Uri(url)).status==403,"reject "+url);
      check(v.client.shouldOverrideUrlLoading(v,url),"deny navigation");
    }
    w.status("local <script>\"\\\n"); bridge.ready();
    check(v.script.startsWith("terminalStatus(") && v.script.endsWith(")") && !v.script.contains("\n"),"quoted local text");
    String literal=v.script.substring("terminalStatus(".length(),v.script.length()-1);
    check(new org.json.JSONTokener(literal).nextValue().equals("local <script>\"\\\r\n"),"local text roundtrip");
    bridge.showKeyboard();check(shows[0]==1,"show IME callback");bridge.hideKeyboard();bridge.copy("selected 中");bridge.copy("x".repeat(1048577));
    check(hides[0]==1&&copies.size()==1&&copies.get(0).equals("selected 中"),"clipboard and IME bounded UI callbacks");
    bridge.input("中\u001b[A"); check(input.get(0).equals("中\u001b[A"),"raw input preserved");
    bridge.input("x".repeat(65537)); check(input.size()==1,"oversized bridge input denied");
    bridge.resize(0,2,1,1); bridge.resize(80,24,800,400);check(resize[0]==1,"resize validation");
    byte[] payload="中\u001b[31m</script>\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.util.concurrent.atomic.AtomicBoolean delivered=new java.util.concurrent.atomic.AtomicBoolean();
    v.script="";
    Thread reader=new Thread(()->{try{delivered.set(w.bytes(payload,payload.length,()->true));}catch(Exception e){throw new RuntimeException(e);}});
    reader.start();
    long deadline=System.nanoTime()+2_000_000_000L;
    while(!v.script.startsWith("terminalBytes(")&&System.nanoTime()<deadline)Thread.sleep(1);
    String script=v.script;
    String encoded=java.util.Base64.getEncoder().encodeToString(payload);
    check(script.equals("terminalBytes(\""+encoded+"\",1)"),"only base64 enters JS");
    bridge.ack(0);check(reader.isAlive(),"stale ack rejected");
    // A hidden WebView may not acknowledge for longer than the former watchdog.
    Thread.sleep(10200);check(reader.isAlive(),"background render delay does not close session");
    bridge.ack(1);reader.join(2000);check(delivered.get(),"delayed write ack unblocks reader");
    w.background();check(v.script.equals("terminalBackground()"),"background gesture/IME cleanup");
    w.resume();check(v.script.equals("terminalResume()"),"resume refresh without navigation");
    check(!w.bytes(payload,payload.length,()->false),"stale generation rejected");
    v.script="";
    Thread pendingReader=new Thread(()->{try{w.bytes(payload,payload.length,()->true);}catch(Exception e){throw new RuntimeException(e);}});
    pendingReader.start();
    deadline=System.nanoTime()+2_000_000_000L;
    while(!v.script.startsWith("terminalBytes(")&&System.nanoTime()<deadline)Thread.sleep(1);
    v.client.onPageStarted(v,"https://evil.test/",null);
    pendingReader.join(2000);check(!pendingReader.isAlive(),"destroy releases pending output");
    check(v.removed&&v.destroyed,"unexpected document destroys bridge");
    bridge.ready();bridge.input("late");bridge.showKeyboard();check(shows[0]==1,"closed show IME rejected");bridge.hideKeyboard();bridge.copy("late");
    check(hides[0]==1&&copies.size()==1,"closed UI callbacks rejected");check(input.size()==1,"closed bridge cannot revive");
    check(!w.bytes(payload,payload.length,()->true),"no output after destroy");
    w.close();
    System.out.println("terminal Java bridge: origin allowlist, navigation, data quoting, backpressure, stale ack and teardown PASS");
  }
}''',
}


class TerminalBridgeBehaviorTest(unittest.TestCase):
    def test_real_bridge(self):
        jar = testdeps.org_json_jar()
        self.assertIsNotNone(jar, 'cached org.json jar required')
        with tempfile.TemporaryDirectory(prefix='terminal-bridge-') as directory:
            work = Path(directory)
            for name, text in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            sources = list(map(str, work.rglob('*.java')))
            sources.append(str(ROOT / 'app/src/main/java/app/remotessh/TerminalWebView.java'))
            subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', jar, '-d', str(work), *sources], check=True)
            subprocess.run(['java', '-cp', str(work) + os.pathsep + jar, 'app.remotessh.BridgeHarness'],
                           cwd=ROOT, check=True)


if __name__ == '__main__':
    unittest.main()
