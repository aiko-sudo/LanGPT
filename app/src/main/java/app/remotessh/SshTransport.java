package app.remotessh;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class SshTransport implements AutoCloseable {
  public interface Trust {
    boolean confirm(String host, String fingerprint) throws Exception;
  }

  private final java.util.function.Consumer<String> progress;
  private volatile java.net.Socket connectingSocket;
  private volatile app.remotessh.tsnet.TsnetSocketFactory tsnetFactory;

  public SshTransport() { this(phase -> {}); }
  public SshTransport(java.util.function.Consumer<String> progress) { this.progress = progress; }
  private void phase(String value) { phase = value; progress.accept(value); }

  private byte[] persistentScript;
  private final List<String> persistentIdentities = new ArrayList<>();

  public void setPersistentScript(byte[] script) { persistentScript = script; }
  public void setPersistentIdentities(Collection<String> identities) {
    persistentIdentities.clear();
    if (identities != null) persistentIdentities.addAll(identities);
  }

  static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }

  private String persistentCommand(Profile p) throws Exception {
    if (persistentScript == null) throw new IOException("缺少远端会话托管程序");
    phase("准备远端持久会话（需要 Python 3）");
    ChannelSftp files = sftp();
    try {
      String folder = files.getHome() + "/.remote-gpt";
      try { files.stat(folder); } catch (SftpException e) {
        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
        files.mkdir(folder);
      }
      files.chmod(0700, folder);
      String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(persistentScript));
      String script = folder + "/host-" + hash + ".py";
      try { files.stat(script); } catch (SftpException e) {
        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
        String temp = script + "." + UUID.randomUUID();
        files.put(new ByteArrayInputStream(persistentScript), temp);
        files.chmod(0600, temp);
        files.rename(temp, script);
      }
      org.json.JSONArray identities = new org.json.JSONArray();
      for (String id : persistentIdentities) if (id != null && !id.isEmpty()) identities.put(id);
      if (identities.length() == 0) identities.put(p.id);
      return "python3 " + quote(script) + " " + quote(identities.toString()) + " " + quote(p.command)
          + " " + quote(p.runtime == null || p.runtime.isEmpty() ? "codex" : p.runtime);
    } catch (SftpException e) {
      throw ConnectionPreflight.failure("HOST_INSTALL_FAILED", "SFTP 已连接，但无法在家目录的 .remote-gpt 中安装托管脚本。请检查目录归属、写入权限、磁盘空间和 SFTP 的 chmod/rename 支持。");
    } finally { files.disconnect(); }
  }

  private volatile Session session;
  private volatile ChannelExec channel;
  private volatile boolean closed;
  private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
  private volatile String phase = "SSH 连接与认证";

  public RpcClient connect(Profile p, HostPins vault, Trust trust, RpcClient.Events events)
      throws Exception {
    connectFiles(p, vault, trust, 0);
    Session s = session;
    phase("检查远端环境（SSH 已认证）");
    ConnectionPreflight.check(p, this::checkCommand);
    phase("检查 SFTP 与家目录");
    try {
      ChannelSftp files = sftp();
      try { files.stat(files.getHome()); } finally { files.disconnect(); }
    } catch (Exception e) {
      throw ConnectionPreflight.failure("SFTP_UNAVAILABLE", "SSH 已认证，但 SFTP 或家目录不可访问。请启用服务器的 SFTP 子系统并检查账号目录权限；持久部署、目录浏览和附件都依赖 SFTP。");
    }
    ChannelExec c = (ChannelExec) s.openChannel("exec");
    phase("启动远端程序");
    channel = c;
    c.setPty(false);
    c.setCommand(p.persistent ? persistentCommand(p) : p.command);
    c.setErrStream(
        new OutputStream() {
          public void write(int b) {
            synchronized (diagnostics) {
              if (diagnostics.size() < 16384) diagnostics.write(b);
            }
          }

          public void write(byte[] bytes, int offset, int length) {
            synchronized (diagnostics) {
              int count = Math.min(length, 16384 - diagnostics.size());
              if (count > 0) diagnostics.write(bytes, offset, count);
            }
          }
        });
    InputStream in = c.getInputStream();
    OutputStream out = c.getOutputStream();
    c.connect(20000);
    if (closed) {
      close();
      throw new IOException("连接已取消");
    }
    phase("远端协议握手 / 通信");
    return new RpcClient(in, out, events);
  }

  /** SSH authentication only: no app-server, daemon attach or environment checks. */
  void connectFiles(Profile p, HostPins vault, Trust trust, int timeoutMs) throws Exception {
    p.validate();
    JSch jsch = new JSch();
    String target = p.host + ":" + p.port;
    jsch.setHostKeyRepository(
        new HostKeyRepository() {
          public int check(String host, byte[] key) {
            try {
              String fingerprint =
                  "SHA256:"
                      + Base64.getEncoder()
                          .withoutPadding()
                          .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
              synchronized (vault) {
                String old = vault.getPin(target);
                if (old != null) return old.equals(fingerprint) ? OK : CHANGED;
              }
              if (!trust.confirm(target, fingerprint)) return NOT_INCLUDED;
              synchronized (vault) {
                vault.rememberPin(target, fingerprint);
              }
              return OK;
            } catch (Exception e) {
              return NOT_INCLUDED;
            }
          }

          public void add(HostKey k, UserInfo u) {}

          public void remove(String h, String t) {}

          public void remove(String h, String t, byte[] k) {}

          public String getKnownHostsRepositoryID() {
            return "Android encrypted host pins";
          }

          public HostKey[] getHostKey() {
            return new HostKey[0];
          }

          public HostKey[] getHostKey(String h, String t) {
            return new HostKey[0];
          }
        });
    if (!p.tailscaleSsh && !p.privateKey.isEmpty())
      jsch.addIdentity(
          "android-key",
          p.privateKey.getBytes(StandardCharsets.UTF_8),
          null,
          p.passphrase.getBytes(StandardCharsets.UTF_8));
    String fallback = p.fallbackHost == null ? "" : p.fallbackHost.trim();
    int fallbackPort = p.fallbackPort > 0 ? p.fallbackPort : 22;
    Session s = jsch.getSession(p.user, p.host, p.port);
    session = s;
    s.setSocketFactory(new SocketFactory() {
      public java.net.Socket createSocket(String host, int port) throws IOException {
        phase("连接主机（TCP）");
        try {
          // 直连等待封顶 8 秒：无论是否配置备用地址，超时后尽快改走内置 Tailscale。
          int cap = Math.min(timeoutMs > 0 ? timeoutMs : 8000, 8000);
          return direct(host, port, cap);
        } catch (IOException primary) {
          if (closed) throw primary;
          if (!fallback.isEmpty() && !(fallback.equalsIgnoreCase(host) && fallbackPort == port)) {
            phase("直连不通，尝试备用地址");
            try {
              return direct(fallback, fallbackPort, timeoutMs > 0 ? timeoutMs : 6000);
            } catch (IOException external) {
              if (closed) throw external;
            }
          }
          phase("尝试内置 Tailscale 连接");
          app.remotessh.tsnet.TsnetSocketFactory factory = new app.remotessh.tsnet.TsnetSocketFactory();
          tsnetFactory = factory;
          if (closed) { factory.close(); throw new IOException("连接已取消"); }
          return factory.createSocket(fallback.isEmpty() ? host : fallback, fallback.isEmpty() ? port : fallbackPort);
        }
      }
      private java.net.Socket direct(String host, int port, int timeout) throws IOException {
        java.net.Socket socket = new java.net.Socket();
        connectingSocket = socket;
        try {
          if (closed) throw new IOException("连接已取消");
          socket.connect(new java.net.InetSocketAddress(host, port), timeout);
          if (closed) throw new IOException("连接已取消");
          phase("SSH 握手与认证");
          return socket;
        } catch (IOException e) { socket.close(); throw e; }
      }
      public InputStream getInputStream(java.net.Socket socket) throws IOException { return socket.getInputStream(); }
      public OutputStream getOutputStream(java.net.Socket socket) throws IOException { return socket.getOutputStream(); }
    });
    s.setConfig("StrictHostKeyChecking", "yes");
    s.setConfig("PreferredAuthentications", "publickey,password");
    if (!p.tailscaleSsh) s.setPassword(p.password);
    s.setServerAliveInterval(15000);
    s.setServerAliveCountMax(3);
    if (closed) throw new IOException("连接已取消");
    s.connect(timeoutMs > 0 ? timeoutMs : p.tailscaleSsh ? 90000 : 20000);
    if (timeoutMs > 0) s.setTimeout(timeoutMs);
    if (closed) {
      s.disconnect();
      throw new IOException("连接已取消");
    }
  }

  public String phase() {
    return phase;
  }

  private String checkCommand(String command) throws Exception {
    ChannelExec check = (ChannelExec) session.openChannel("exec");
    try {
      check.setPty(false);
      check.setCommand(command);
      check.setInputStream(null);
      check.setErrStream(new OutputStream() { public void write(int value) {} });
      InputStream input = check.getInputStream();
      check.connect(15000);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
      byte[] buffer = new byte[1024];
      while (true) {
        while (input.available() > 0) {
          int count = input.read(buffer);
          if (count < 0) break;
          if (output.size() + count > 8192) throw ConnectionPreflight.failure("CHECK_OUTPUT", "环境检查输出异常，请检查非交互 shell 是否输出了额外内容。");
          output.write(buffer, 0, count);
        }
        if (closed || !session.isConnected()) throw new IOException("连接已取消");
        if (check.isClosed() && input.available() == 0) break;
        if (System.nanoTime() > deadline) throw ConnectionPreflight.failure("CHECK_TIMEOUT", "环境检查超时，请检查远端程序是否卡住或账号是否限制执行命令；尚未启动 Codex。");
        Thread.sleep(25);
      }
      if (check.getExitStatus() != 0) throw ConnectionPreflight.failure("CHECK_UNAVAILABLE", "远端无法执行环境检查，请确认账号允许运行 sh 和非交互命令。");
      return output.toString(StandardCharsets.UTF_8.name());
    } finally { check.disconnect(); }
  }

  public int exitStatus() {
    ChannelExec c = channel;
    return c == null ? -1 : c.getExitStatus();
  }

  public String diagnostic() {
    synchronized (diagnostics) {
      return new String(diagnostics.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /** A separate SSH channel keeps version checks/install output out of JSON-RPC. */
  public org.json.JSONObject maintenance(String script, org.json.JSONObject request) throws Exception {
    return jsonCommand(script,request,request.optString("action").equals("update") ? 650 : 90);
  }

  org.json.JSONObject jsonCommand(String script, org.json.JSONObject request, int timeout) throws Exception {
    Session current = session;
    if (closed || current == null || !current.isConnected()) throw new IOException("SSH 未连接");
    ChannelExec exec = (ChannelExec) current.openChannel("exec");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    OutputStream bounded = new OutputStream() {
      public synchronized void write(int value) throws IOException {
        if (output.size() >= 65536) throw new IOException("远端响应超过大小限制");
        output.write(value);
      }
      public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (output.size() + length > 65536) throw new IOException("远端响应超过大小限制");
        output.write(bytes, offset, length);
      }
    };
    try {
      exec.setPty(false);
      exec.setCommand("python3 -c " + quote(script) + " " + quote(request.toString()));
      exec.setInputStream(null);
      exec.setOutputStream(bounded);
      exec.setErrStream(new OutputStream() { public void write(int value) {} });
      exec.connect(15000);
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
          timeout);
      while (!exec.isClosed()) {
        if (closed || !current.isConnected()) throw new IOException("SSH 已断开，请重连后检查版本和更新结果");
        if (System.nanoTime() > deadline) throw new IOException("远端版本操作超时，请重新检查更新结果");
        Thread.sleep(50);
      }
      if (exec.getExitStatus() != 0) throw new IOException("远端版本检测不可用，请检查 Python 3 和执行权限");
      return new org.json.JSONObject(output.toString(StandardCharsets.UTF_8.name()).trim());
    } finally { exec.disconnect(); }
  }

  /** Simple PTY shell for the in-app terminal; reuses the profile SSH session. */
  com.jcraft.jsch.ChannelShell openShell() throws Exception {
    Session current = session;
    if (closed || current == null || !current.isConnected()) throw new IOException("SSH 未连接");
    ChannelShell shell = (ChannelShell) current.openChannel("shell");
    shell.setPty(true);
    shell.setPtyType("xterm-256color", 120, 40, 0, 0);
    shell.connect(15000);
    return shell;
  }

  SftpContent fileContent() { return new SftpContent(this::sftp); }

  private ChannelSftp sftp() throws Exception {
    Session current = session;
    if (closed || current == null || !current.isConnected()) throw new IOException("SSH 未连接");
    ChannelSftp sftp = (ChannelSftp) current.openChannel("sftp");
    try {
      sftp.connect(15000);
      return sftp;
    } catch (Exception e) {
      sftp.disconnect();
      throw e;
    }
  }

  RemoteFiles listFiles(String path) throws Exception {
    ChannelSftp channel = sftp();
    try {
      String canonical = channel.realpath(path == null || path.isEmpty() ? "." : path);
      if (!channel.stat(canonical).isDir()) throw new IOException("所选路径不是文件夹");
      List<RemoteFiles.Entry> entries = new ArrayList<>();
      channel.ls(canonical, item -> {
        String name = item.getFilename();
        if (!name.equals(".") && !name.equals("..")) {
          SftpATTRS attrs = item.getAttrs();
          entries.add(new RemoteFiles.Entry(name, attrs.isDir(), attrs.isLink(),
              attrs.getSize(), Integer.toUnsignedLong(attrs.getMTime()), attrs.getPermissionsString()));
        }
        return ChannelSftp.LsEntrySelector.CONTINUE;
      });
      return new RemoteFiles(canonical, entries);
    } finally {
      channel.disconnect();
    }
  }

  public RemoteDirectory listDirectories(String path) throws Exception {
    ChannelSftp channel = sftp();
    try {
      String canonical = channel.realpath(path.isEmpty() ? "." : path);
      if (!channel.stat(canonical).isDir()) throw new IOException("所选路径不是文件夹");
      List<String> names = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      boolean[] truncated = {false};
      channel.ls(
          canonical,
          entry -> {
            String name = entry.getFilename();
            if (!name.equals(".")
                && !name.equals("..")
                && (entry.getAttrs().isDir() || entry.getAttrs().isLink())) {
              if (names.size() >= 1000) {
                truncated[0] = true;
                return ChannelSftp.LsEntrySelector.BREAK;
              }
              names.add(name);
              times.add((long) entry.getAttrs().getMTime());
            }
            return ChannelSftp.LsEntrySelector.CONTINUE;
          });
      // 同时排序 names 与 times (按名称, 不区分大小写, 保持原行为)
      Integer[] idx = new Integer[names.size()];
      for (int i = 0; i < idx.length; i++) idx[i] = i;
      java.util.Arrays.sort(idx, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(names.get(a), names.get(b)));
      List<String> sortedNames = new ArrayList<>(names.size());
      List<Long> sortedTimes = new ArrayList<>(times.size());
      for (int i : idx) { sortedNames.add(names.get(i)); sortedTimes.add(times.get(i)); }
      return new RemoteDirectory(canonical, sortedNames, sortedTimes, truncated[0]);
    } finally {
      channel.disconnect();
    }
  }

  public RemoteDirectory createDirectory(String parent, String name) throws Exception {
    ChannelSftp channel = sftp();
    try {
      String canonical = channel.realpath(parent);
      if (!channel.stat(canonical).isDir()) throw new IOException("父路径不是文件夹");
      String target = RemoteDirectory.child(canonical, name.trim());
      channel.mkdir(target);
      return new RemoteDirectory(channel.realpath(target), new ArrayList<>(), false);
    } finally {
      channel.disconnect();
    }
  }

  public String uploadImage(File file) throws Exception {
    return uploadFile(file,file.getName());
  }

  public String uploadFile(File file,String name) throws Exception {
    ChannelSftp c = sftp();
    try {
      String folder = c.getHome() + "/.remote-ssh-attachments";
      try { c.stat(folder); } catch (SftpException e) {
        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
        c.mkdir(folder);
      }
      c.chmod(0700, folder);
      String target = folder + "/" + UUID.randomUUID() + "-" + FileAttachment.safeName(name);
      try {
        try (InputStream in = new FileInputStream(file)) { c.put(in, target); }
        c.chmod(0600, target);
      } catch(Exception failure) {
        try { c.rm(target); } catch(Exception ignored) { }
        throw failure;
      }
      return target;
    } finally { c.disconnect(); }
  }

  public void download(String path, OutputStream out, long limit) throws Exception {
    download(path,out,limit,()->true);
  }

  public void download(String path, OutputStream out, long limit, java.util.function.BooleanSupplier active) throws Exception {
    MediaFiles.check(active);
    ChannelSftp c = sftp();
    try {
      if (c.stat(path).getSize() > limit) throw new IOException("文件超过下载大小限制");
      MediaFiles.check(active);
      try (InputStream in = c.get(path)) { MediaFiles.copy(in, out, limit,active); }
    } finally { c.disconnect(); }
  }

  @Override
  public void close() {
    closed = true;
    java.net.Socket socket = connectingSocket;
    if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    app.remotessh.tsnet.TsnetSocketFactory factory = tsnetFactory;
    if (factory != null) try { factory.close(); } catch (IOException ignored) {}
    ChannelExec c = channel;
    if (c != null) c.disconnect();
    Session s = session;
    if (s != null) s.disconnect();
  }
}
