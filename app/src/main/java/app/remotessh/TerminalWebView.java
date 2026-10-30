package app.remotessh;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** A sealed local origin. Remote bytes are data for xterm, never markup or script. */
final class TerminalWebView {
  static final String ORIGIN = "https://terminal.invalid/";
  interface Listener {
    void input(String data);
    default void showKeyboard() {}
    default void hideKeyboard() {}
    default void copy(String text) {}
    void closed();
    void resize(int cols, int rows, int width, int height);
  }
  final WebView view;
  private volatile boolean ready, closed;
  private volatile CountDownLatch pending;
  private volatile int pendingId;
  private final Activity owner;
  private final Listener listener;
  private final StringBuilder startup = new StringBuilder();

  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  TerminalWebView(Activity owner, Listener listener) {
    this.owner = owner;
    this.listener = listener;
    view = new WebView(owner);
    view.setBackgroundColor(0xff000000);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setBlockNetworkLoads(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setSaveFormData(false);
    view.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
      @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        String url = r.getUrl().toString();
        String path = url.startsWith(ORIGIN) ? url.substring(ORIGIN.length()) : "";
        String mime;
        switch (path) {
          case "index.html": mime = "text/html"; break;
          case "terminal.js": case "vendor/xterm.js": case "vendor/addon-canvas.js": mime = "application/javascript"; break;
          case "vendor/xterm.css": mime = "text/css"; break;
          default: return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
              java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }
        try { return new WebResourceResponse(mime, "UTF-8", owner.getAssets().open("terminal/" + path)); }
        catch (Exception e) { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }
      }
      @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
        ready = false;
        if (!(ORIGIN + "index.html").equals(url)) { close(); listener.closed(); }
      }
      @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
        close();
        listener.closed();
        return true;
      }
    });
    view.addJavascriptInterface(new Bridge(), "TerminalNative");
    view.loadUrl(ORIGIN + "index.html");
  }

  final class Bridge {
    @JavascriptInterface public void ready() {
      owner.runOnUiThread(() -> {
        if (closed || !(ORIGIN + "index.html").equals(view.getUrl())) return;
        ready = true;
        status(startup.toString());
        startup.setLength(0);
      });
    }
    @JavascriptInterface public void input(String data) {
      if (data == null || data.length() > 65536) return;
      owner.runOnUiThread(() -> { if (ready && !closed) listener.input(data); });
    }
    @JavascriptInterface public void resize(int cols, int rows, int width, int height) {
      owner.runOnUiThread(() -> {
        if (ready && !closed && cols >= 2 && cols <= 1000 && rows >= 1 && rows <= 500)
          listener.resize(cols, rows, Math.max(1, width), Math.max(1, height));
      });
    }
    @JavascriptInterface public void showKeyboard() {
      owner.runOnUiThread(() -> { if (ready && !closed) listener.showKeyboard(); });
    }
    @JavascriptInterface public void hideKeyboard() {
      owner.runOnUiThread(() -> { if (ready && !closed) listener.hideKeyboard(); });
    }
    @JavascriptInterface public void copy(String text) {
      if (text == null || text.length() > 1048576) return;
      owner.runOnUiThread(() -> { if (ready && !closed) listener.copy(text); });
    }
    @JavascriptInterface public void ack(int id) {
      CountDownLatch latch = pending;
      if (id == pendingId && latch != null) latch.countDown();
    }
  }

  void status(String text) {
    if (closed) return;
    // Local diagnostics are plain text too; no terminal control sequences from host labels/errors.
    text = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").replace("\r\n", "\n").replace("\n", "\r\n");
    if (!ready) { if (startup.length() < 32768) startup.append(text); return; }
    view.evaluateJavascript("terminalStatus(" + JSONObject.quote(text) + ")", null);
  }

  /** Called only on the reader worker; bounds pending output to one 8 KiB chunk. */
  synchronized boolean bytes(byte[] bytes, int length, java.util.function.BooleanSupplier active)
      throws InterruptedException {
    if (closed || !active.getAsBoolean()) return false;
    final int id = ++pendingId;
    CountDownLatch latch = new CountDownLatch(1);
    pending = latch;
    java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
    String encoded = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, length));
    owner.runOnUiThread(() -> {
      if (!ready || closed || !active.getAsBoolean()) latch.countDown();
      else {
        submitted.set(true);
        view.evaluateJavascript("terminalBytes(" + JSONObject.quote(encoded) + "," + id + ")", null);
      }
    });
    try {
      // A hidden WebView can defer write callbacks indefinitely. Retain one chunk
      // and apply transport backpressure; renderer timing is not a network error.
      while (!latch.await(250, TimeUnit.MILLISECONDS)) {
        if (closed || !active.getAsBoolean()) return false;
      }
      return submitted.get() && !closed && active.getAsBoolean();
    } finally { pending = null; }
  }
  void keyboard(boolean visible) {
    if (ready && !closed) view.evaluateJavascript("terminalKeyboard(" + visible + ")", null);
  }
  void background() {
    if (ready && !closed) view.evaluateJavascript("terminalBackground()", null);
  }
  void resume() {
    if (ready && !closed) view.evaluateJavascript("terminalResume()", null);
  }
  void mode(boolean connected) {
    if (ready && !closed) view.evaluateJavascript("terminalMode(" + connected + ")", null);
  }
  void close() {
    if (closed) return;
    closed = true;
    ready = false;
    CountDownLatch latch = pending;
    if (latch != null) latch.countDown();
    view.removeJavascriptInterface("TerminalNative");
    view.stopLoading();
    view.destroy();
  }
}
