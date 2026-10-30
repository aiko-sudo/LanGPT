package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Termux-style SSH terminal. Nothing connects on launch: the user types
 * {@code ssh user@host} (optionally {@code -p port}), then logs in with a saved
 * or freshly entered password. Host keys are checked against the encrypted
 * vault pins, exactly like the SFTP transport.
 */
public final class SshTerminalActivity extends Activity {
  private RemoteScreens ui;
  private TerminalWebView terminal;
  private RemoteApp app;
  private volatile Session session;
  private volatile app.remotessh.tsnet.DirectOrTsnetFactory terminalRoute;
  private volatile Channel channel;
  private volatile OutputStream shellIn;
  private volatile boolean connected, connecting, destroyed;
  private String currentHost;
  private volatile int generation;
  private volatile int cols = 80, rows = 24, pixelWidth = 640, pixelHeight = 480;
  private AlertDialog ownedDialog;
  private CountDownLatch trustWait;
  private Thread connector;
  private final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(
      1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128),
      task -> new Thread(task, "terminal-input"));

  public static void open(Activity owner, boolean dark) {
    owner.startActivity(new Intent(owner, SshTerminalActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("dark", dark));
  }

  @Override protected void onCreate(Bundle state) {
    boolean dark = getIntent().getBooleanExtra("dark", false);
    setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(
        getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    app = (RemoteApp) getApplication();
    ui = new RemoteScreens(this, dark);
    LinearLayout root = ui.column();
    root.setBackgroundColor(ui.bg);
    root.setFitsSystemWindows(true);

    LinearLayout header = ui.row();
    header.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
    View back = ui.icon("back", "主机菜单", this::showHostMenu);
    back.setBackground(null);
    header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    TextView title = ui.text("SSH 终端", 20);
    title.setTypeface(null, Typeface.BOLD);
    title.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(title, new LinearLayout.LayoutParams(0, ui.dp(56), 1));
    root.addView(header, new LinearLayout.LayoutParams(-1, ui.dp(64)));

    terminal = new TerminalWebView(this, new TerminalWebView.Listener() {
      public void closed() { finish(); }
      public void showKeyboard() {
        terminal.view.requestFocus();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .showSoftInput(terminal.view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
      }
      public void hideKeyboard() {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(terminal.view.getWindowToken(), 0);
      }
      public void copy(String text) {
        if (text.isEmpty()) return;
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(android.content.ClipData.newPlainText("终端选区", text));
        android.widget.Toast.makeText(SshTerminalActivity.this, "选择已复制", android.widget.Toast.LENGTH_SHORT).show();
      }
      public void input(String data) {
        if (connected) writeShell(data);
        else if (!connecting && data.endsWith("\r")) send(data.substring(0, data.length() - 1));
      }
      public void resize(int c, int r, int w, int h) {
        cols = c; rows = r; pixelWidth = w; pixelHeight = h;
        final int token = generation;
        enqueue(() -> {
          Channel current = channel;
          if (token == generation && current instanceof ChannelShell)
            ((ChannelShell) current).setPtySize(c, r, w, h);
        });
      }
    });
    root.addView(terminal.view, new LinearLayout.LayoutParams(-1, 0, 1));
    getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setContentView(root);
    // IME insets are authoritative; layout fallback supports Android 8–10.
    root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
      if (destroyed) return;
      boolean visible;
      if (android.os.Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null) {
        visible = root.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
      } else {
        android.graphics.Rect frame = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(frame);
        visible = root.getRootView().getHeight() - frame.bottom > ui.dp(160);
      }
      terminal.keyboard(visible);
    });
    if (state != null && !connected) print("[页面已重建，会话未恢复，请重新输入 ssh 命令]\n\n");
  }

  @Override protected void onDestroy() {
    destroyed = true;
    disconnectAll();
    writer.shutdownNow();
    if (terminal != null) terminal.close();
    super.onDestroy();
  }

  private void confirmExit() {
    if (!connected) { finish(); return; }
    if (ownedDialog != null) ownedDialog.dismiss();
    ownedDialog = new AlertDialog.Builder(this)
        .setMessage("断开 SSH 连接并退出？")
        .setNegativeButton("取消", null)
        .setPositiveButton("确认", (dialog, which) -> finish())
        .show();
  }

  private void showHostMenu() {
    java.util.List<String> hosts = HostHistory.merge(HostHistory.list(this), SshCredentials.list(this));
    if (connected && currentHost != null && !hosts.contains(currentHost)) hosts.add(currentHost);
    String[] labels = new String[hosts.size() + 1];
    for (int i = 0; i < hosts.size(); i++)
      labels[i] = hosts.get(i) + (connected && hosts.get(i).equals(currentHost) ? " ✓" : "");
    labels[hosts.size()] = "退出";
    if (ownedDialog != null) ownedDialog.dismiss();
    ownedDialog = CenteredMenu.show(this, ui.fg, ui.muted, null, labels, which -> {
      ownedDialog = null;
      if (which == hosts.size()) { confirmExit(); return; }
      String target = hosts.get(which);
      if (connected && target.equals(currentHost)) return;
      TerminalCommand command = TerminalCommand.parse("ssh " + target, "", 22);
      SshCredentials.Entry saved = command.error == null ? SshCredentials.load(this, command) : null;
      if (command.error != null) return;
      disconnectAll();
      print("\n[已切换到 " + target + "]\n");
      if (saved == null) promptPassword(command);
      else startConnect(command, saved.password, false);
    });
  }

  private void print(String text) { if (!destroyed) terminal.status(text); }

  private void post(Runnable action) { runOnUiThread(() -> { if (!destroyed) action.run(); }); }

  private void enqueue(Runnable task) {
    try { writer.execute(task); }
    catch (java.util.concurrent.RejectedExecutionException busy) {
      if (!destroyed) print("\n[输入队列已满，本次输入未发送，请稍后重试]\n");
    }
  }

  private void writeShell(String data) {
    final int token = generation;
    final OutputStream out = shellIn;
    enqueue(() -> {
      if (token != generation || out == null) return;
      try { out.write(data.getBytes(StandardCharsets.UTF_8)); out.flush(); }
      catch (Exception failure) { post(() -> { if (token == generation) disconnectAll(); }); }
    });
  }

  private void send(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty()) return;
    if (trimmed.equals("/help")) {
      print("/help 帮助　/saved 已存凭证列表（用 ssh 命令自动匹配）\n"
          + "/forget user@host 删除该主机凭证　exit 断开并退出\n");
      return;
    }
    if (trimmed.startsWith("/forget")) {
      forgetCredential(trimmed.substring("/forget".length()).trim());
      return;
    }
    if (trimmed.equals("/saved")) {
      print("已存凭证按主机自动匹配；输入 ssh user@host 即可使用。\n");
      return;
    }
    if (trimmed.equals("exit") || trimmed.equals("退出")) { finish(); return; }
    TerminalCommand command = TerminalCommand.parse(trimmed,
        app.profile == null ? "" : app.profile.user,
        app.profile == null ? 22 : app.profile.port);
    if (command.error != null) {
      print("✗ " + command.error + "\n");
      return;
    }
    SshCredentials.Entry saved = SshCredentials.load(this, command);
    if (saved != null) {
      print("\n使用已保存的凭证连接 " + command.credentialKey() + "\n");
      startConnect(command, saved.password, false);
    } else promptPassword(command);
  }

  private void forgetCredential(String target) {
    if (target.isEmpty()) { print("用法：/forget user@host[:port]\n"); return; }
    TerminalCommand parsed = TerminalCommand.parse("ssh " + target,
        app.profile == null ? "" : app.profile.user, app.profile == null ? 22 : app.profile.port);
    if (parsed.error != null) { print("✗ " + parsed.error + "\n"); return; }
    SshCredentials.forget(this, parsed.user, parsed.host, parsed.port);
    HostHistory.remove(this, parsed.credentialKey());
    print("已删除 " + parsed.credentialKey() + " 的凭证和主机历史\n");
  }

  private void promptPassword(TerminalCommand command) {
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
    TextView hint = ui.text(command.credentialKey(), 13);
    hint.setTextColor(ui.muted);
    column.addView(hint);
    EditText password = new EditText(this);
    password.setHint("密码");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setSingleLine(true);
    column.addView(password);
    CheckBox remember = new CheckBox(this);
    remember.setText("保存凭证（加密存储）");
    remember.setChecked(true);
    column.addView(remember);
    if (ownedDialog != null) ownedDialog.dismiss();
    ownedDialog = new AlertDialog.Builder(this)
        .setTitle("SSH 登录")
        .setView(column)
        .setNegativeButton("取消", null)
        .setPositiveButton("连接", (dialog, which) -> {
          String secret = password.getText().toString();
          if (secret.isEmpty()) { print("✗ 密码不能为空\n"); return; }
          startConnect(command, secret, remember.isChecked());
        })
        .show();
  }

  private void startConnect(TerminalCommand command, String password, boolean remember) {
    if (connecting || connected || destroyed) return;
    connecting = true;
    final int token = ++generation;
    print("连接 " + command.credentialKey() + " …\n");
    connector = new Thread(() -> {
      Session open = null;
      Channel active = null;
      app.remotessh.tsnet.DirectOrTsnetFactory route = new app.remotessh.tsnet.DirectOrTsnetFactory();
      try {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(hostKeyRepository(token));
        open = jsch.getSession(command.user, command.host, command.port);
        synchronized (this) {
          if (token != generation || destroyed) return;
          session = open;
          terminalRoute = route;
        }
        open.setSocketFactory(route);
        open.setConfig("StrictHostKeyChecking", "yes");
        open.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        open.setPassword(password);
        open.setServerAliveInterval(15000);
        open.setServerAliveCountMax(3);
        open.connect(20000);
        if (token != generation || destroyed) return;
        HostHistory.record(this, command.credentialKey());
        if (remember) SshCredentials.save(this, command, password);
        InputStream in;
        if (!command.remoteCommand.isEmpty()) {
          ChannelExec exec = (ChannelExec) open.openChannel("exec");
          exec.setCommand(command.remoteCommand);
          // Merge stderr into the same bounded byte stream; never read two pipes serially.
          java.io.PipedInputStream pipe = new java.io.PipedInputStream(32768);
          java.io.PipedOutputStream sink = new java.io.PipedOutputStream(pipe);
          exec.setOutputStream(sink);
          exec.setErrStream(sink);
          active = exec;
          in = pipe;
        } else {
          ChannelShell shell = (ChannelShell) open.openChannel("shell");
          shell.setPtyType("xterm-256color");
          shell.setPtySize(cols, rows, pixelWidth, pixelHeight);
          active = shell;
          in = shell.getInputStream();
        }
        final Channel current = active;
        final OutputStream out = current.getOutputStream();
        synchronized (this) {
          if (token != generation || destroyed) return;
          channel = current;
        }
        current.connect(15000);
        post(() -> {
          if (token != generation) return;
          connecting = false; connected = true; shellIn = out; currentHost = command.credentialKey();
          terminal.mode(true);
          if (current instanceof ChannelShell)
            enqueue(() -> { if (token == generation) ((ChannelShell) current)
                .setPtySize(cols, rows, pixelWidth, pixelHeight); });
        });
        byte[] chunk = new byte[8192];
        while (token == generation && !destroyed) {
          // Exec output streams do not signal EOF; poll channel completion without blocking read.
          if (current instanceof ChannelExec && in.available() == 0) {
            if (current.isClosed()) break;
            Thread.sleep(20); continue;
          }
          int count = in.read(chunk);
          if (count < 0) break;
          if (count > 0 && !terminal.bytes(chunk, count, () -> token == generation && !destroyed)) break;
        }
        in.close();
      } catch (Exception failure) {
        post(() -> { if (token == generation) print("\n[SSH 连接结束或失败]\n"); });
      } finally {
        if (active != null) active.disconnect();
        if (open != null) open.disconnect();
        try { route.close(); } catch (java.io.IOException ignored) {}
        synchronized (this) { if (terminalRoute == route) terminalRoute = null; }
        post(() -> {
          if (token != generation) return;
          if (ownedDialog != null) { ownedDialog.dismiss(); ownedDialog = null; }
          trustWait = null;
          connected = connecting = false; shellIn = null; channel = null; session = null; currentHost = null;
          terminal.mode(false);
          print("\n[连接已断开，请输入 ssh user@host]\n");
        });
      }
    }, "ssh-terminal");
    connector.start();
  }

  /** Vault pins + explicit confirmation, same policy as the SFTP transport. */
  private HostKeyRepository hostKeyRepository(int connectionToken) {
    final Vault vault = app.vault;
    return new HostKeyRepository() {
      public int check(String host, byte[] key) {
        if (connectionToken != generation || destroyed) return NOT_INCLUDED;
        try {
          String target = host;
          String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
              .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
          synchronized (vault) {
            String pinned = vault.getPin(target);
            if (pinned != null) return pinned.equals(fingerprint) ? OK : CHANGED;
          }
          if (!confirmFingerprint(target, fingerprint, connectionToken)) return NOT_INCLUDED;
          synchronized (vault) { vault.rememberPin(target, fingerprint); }
          return OK;
        } catch (Exception failure) {
          return NOT_INCLUDED;
        }
      }

      public void add(HostKey hostKey, com.jcraft.jsch.UserInfo info) {}
      public void remove(String host, String type) {}
      public void remove(String host, String type, byte[] key) {}
      public String getKnownHostsRepositoryID() { return "Android encrypted host pins"; }
      public HostKey[] getHostKey() { return new HostKey[0]; }
      public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    };
  }

  private boolean confirmFingerprint(String target, String fingerprint, int token) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean accepted = new AtomicBoolean(false);
    post(() -> {
      if (token != generation || !connecting) { latch.countDown(); return; }
      if (ownedDialog != null) ownedDialog.dismiss();
      trustWait = latch;
      ownedDialog = new AlertDialog.Builder(this)
          .setTitle("首次连接该主机")
          .setMessage(target + "\n指纹 " + fingerprint + "\n\n确认这是你要登录的主机？")
          .setNegativeButton("取消", (dialog, which) -> latch.countDown())
          .setPositiveButton("信任并记住", (dialog, which) -> {
            if (token == generation) accepted.set(true);
            latch.countDown();
          }).setOnDismissListener(dialog -> latch.countDown()).show();
    });
    try {
      latch.await(120, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    return accepted.get() && token == generation && !destroyed;
  }

  private synchronized void disconnectAll() {
    generation++;
    connected = connecting = false;
    currentHost = null;
    if (trustWait != null) { trustWait.countDown(); trustWait = null; }
    if (ownedDialog != null) { ownedDialog.dismiss(); ownedDialog = null; }
    if (connector != null) connector.interrupt();
    Channel oldChannel = channel;
    Session oldSession = session;
    app.remotessh.tsnet.DirectOrTsnetFactory oldRoute = terminalRoute;
    terminalRoute = null;
    channel = null; session = null; shellIn = null;
    writer.getQueue().clear();
    // Socket close may block: keep all transport work off the UI thread.
    new Thread(() -> {
      if (oldRoute != null) try { oldRoute.close(); } catch (java.io.IOException ignored) {}
      if (oldChannel != null) oldChannel.disconnect();
      if (oldSession != null) oldSession.disconnect();
    }, "terminal-close").start();
    if (!destroyed) { terminal.mode(false); print("\n[已断开，请输入 ssh user@host]\n"); }
  }

  private void hideTerminalKeyboard() {
    if (terminal == null) return;
    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(terminal.view.getWindowToken(), 0);
    terminal.keyboard(false);
    terminal.view.clearFocus();
    terminal.background();
  }

  @Override protected void onStop() {
    hideTerminalKeyboard();
    super.onStop();
  }

  @Override protected void onResume() {
    super.onResume();
    if (terminal != null) terminal.resume();
  }

  @Override public void onBackPressed() {
    hideTerminalKeyboard();
    startActivity(new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
  }
}
