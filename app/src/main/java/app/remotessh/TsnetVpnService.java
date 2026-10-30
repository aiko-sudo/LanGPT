package app.remotessh;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tailscale.Tailscale;

/**
 * 应用沙箱：只把白名单应用的流量送入内置 tsnet（进程内 Tailscale）。
 * 安全铁律：一个 addAllowedApplication 都未成功时绝不 establish——
 * 空名单会被系统解释为“所有应用走 VPN”，等于沙箱失效。
 * LanGPT 自身不进白名单：它有进程内通道，且必须避开 TUN 环路。
 */
public final class TsnetVpnService extends VpnService {
  public static final String ACTION_START = "app.remotessh.sandbox.START";
  public static final String ACTION_STOP = "app.remotessh.sandbox.STOP";
  static final int VPN_REQUEST = 45;

  private static volatile TsnetVpnService active;
  private ParcelFileDescriptor tunnel;
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static final AtomicInteger generation = new AtomicInteger();
  private final Handler main = new Handler(Looper.getMainLooper());

  static boolean running() { return active != null; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "" : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      // 同步停：不等 worker 队列（可能被启动流程阻塞），立即关 TUN 让 VPN 图标消失。
      generation.incrementAndGet();
      shutdown();
      stopSelf();
      return Service.START_NOT_STICKY;
    }
    int token = generation.incrementAndGet();
    worker.execute(() -> start(token));
    return Service.START_NOT_STICKY;
  }

  private void start(int token) {
      shutdown();
      if (generation.get() != token) return;
      Set<String> pkgs = new HashSet<>(SandboxSettings.whitelist(this));
      android.content.SharedPreferences prefs = getSharedPreferences("sandbox", 0);
      boolean useExit = prefs.getBoolean("exit_enabled", false);
      String exitId = prefs.getString("exit_id", "");
      Builder b = new Builder()
          .setSession("LanGPT 应用沙箱")
          .setMtu(1280)
          .addAddress("198.18.0.1", 15)
          .addAddress("fd00::1", 64)
          .addDnsServer("198.18.0.2")
          .addRoute("198.18.0.0", 15) // DNS 服务器所在段必须进 TUN，否则 App 的域名解析根本到不了沙箱
          .addRoute("100.64.0.0", 10)
          .addRoute("fd7a:115c:a1e0::", 48)
          .setBlocking(true);
      // 局域网段从当前主机地址派生（/24），不硬编码任何人的网段——
      // 沙箱内应用访问主机所在局域网需该段路由；IPv4 主机且非公网才加。
      String lanSubnet = lanSubnetOf(mainHost());
      if (lanSubnet != null) b.addRoute(lanSubnet, 24);
      int allowed = 0;
      for (String pkg : pkgs) {
        if (getPackageName().equals(pkg)) continue;
        try { b.addAllowedApplication(pkg); allowed++; }
        catch (Exception ignored) { /* 未安装的应用跳过 */ }
      }
      if (allowed == 0) {
        notify("白名单内没有已安装的应用，沙箱未启用");
        main.post(() -> { if (generation.get() == token) stopSelf(); });
        return;
      }
      try {
        // 确保内置节点已在运行（startIfEnabled 是异步的，这里等待就绪）。
        TsnetSetup.startIfEnabled(getApplicationContext());
        waitNodeReady();
        if (generation.get() != token) return;
        if (useExit && exitId.isEmpty()) throw new IllegalStateException("未选择出口节点");
        if (useExit || ExitNode.supported()) ExitNode.select(useExit ? exitId : "");
        if (generation.get() != token) { shutdown(); return; }
        if (useExit) b.addRoute("0.0.0.0", 0).addRoute("::", 0);
        tunnel = b.establish();
        if (tunnel == null) { notify("VPN 建立失败（可能被其他 VPN 占用）"); main.post(() -> { if (generation.get() == token) stopSelf(); }); return; }
        // fd 所有权留在 Java（getFd 不转移）：停用时 tunnel.close() 同步关闭 fd，
        // Go 泵 Read 立即 EBADF 退出，系统即刻撤销 VPN 网络/图标（实测 detachFd 后图标残留 60s+）。
        Tailscale.startTunVpn(tunnel.getFd(), 1280);
        if (generation.get() != token) { shutdown(); return; }
        active = this;
        notify("应用沙箱已启用：" + allowed + " 个应用");
      } catch (Throwable e) {
        shutdown();
        notify("沙箱启动失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        main.post(() -> { if (generation.get() == token) stopSelf(); });
      }
  }

  /** 等待内置 tsnet 节点进入 Running（最多 15 秒）。 */
  private void waitNodeReady() throws InterruptedException {
    for (int i = 0; i < 30; i++) {
      try {
        org.json.JSONObject s = new org.json.JSONObject(Tailscale.statusJSON());
        if (s.optBoolean("running") && "Running".equals(s.optString("backend"))) return;
      } catch (Throwable ignored) { }
      Thread.sleep(500);
    }
    throw new IllegalStateException("内置 Tailscale 未就绪，请先在「内置 Tailscale」完成授权");
  }

  private void shutdown() {
    active = null;
    android.util.Log.i("TsnetVpn", "shutdown: closing tunnel fd");
    try { Tailscale.stopTunVpn(); } catch (Throwable t) { android.util.Log.w("TsnetVpn", "stopTunVpn: " + t); }
    // fd 所有权在 Java：close 同步关闭（Go 侧共享同号 fd，泵随之退出）。
    if (tunnel != null) { try { tunnel.close(); } catch (Exception ignored) { } }
    tunnel = null;
    if (ExitNode.supported()) { try { ExitNode.select(""); } catch (Throwable ignored) { } }
  }

  private void notify(String message) {
    main.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show());
  }

  @Override
  public void onRevoke() { stopSelf(); }

  /** App 从最近任务划掉时停沙箱：VPN 图标随之消失（用户预期行为）。 */
  @Override
  public void onTaskRemoved(android.content.Intent rootIntent) {
    generation.incrementAndGet();
    shutdown();
    stopSelf();
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public void onDestroy() {
    generation.incrementAndGet();
    // 同步关 fd（立即撤销 VPN 图标），再经 worker 复跑 shutdown 幂等清场。
    shutdown();
    worker.execute(this::shutdown);
    super.onDestroy();
  }

  /** 当前主连接的主机地址；读不到返回空串。 */
  private String mainHost() {
    try {
      String id = getSharedPreferences("startup", MODE_PRIVATE).getString("lastProfileId", "");
      app.remotessh.Vault vault = new app.remotessh.Vault(getApplicationContext());
      vault.read();
      app.remotessh.Profile chosen = null;
      if (!id.isEmpty()) {
        for (app.remotessh.Profile p : vault.profiles) {
          if (id.equals(p.id)) { chosen = p; break; }
        }
      }
      if (chosen == null && !vault.profiles.isEmpty()) chosen = vault.profiles.get(0);
      return chosen == null || chosen.host == null ? "" : chosen.host.trim();
    } catch (Throwable ignored) {
      return "";
    }
  }

  /** 由 IPv4 主机地址派生 /24 网段；公网地址或非法输入返回 null。 */
  static String lanSubnetOf(String host) {
    if (host == null || host.isEmpty()) return null;
    String[] parts = host.split("\\.");
    if (parts.length != 4) return null;
    try {
      int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
      for (String part : parts) {
        int v = Integer.parseInt(part);
        if (v < 0 || v > 255) return null;
      }
      boolean privateV4 = a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
      return privateV4 ? parts[0] + "." + parts[1] + "." + parts[2] + ".0" : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
