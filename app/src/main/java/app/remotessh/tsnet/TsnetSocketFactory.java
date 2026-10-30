package app.remotessh.tsnet;

import com.jcraft.jsch.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import tailscale.Connection;
import tailscale.Tailscale;

/** Adapts a tsnet stream to the socket interface used by JSch. */
public final class TsnetSocketFactory implements SocketFactory {
  private volatile TsnetSocket socket;
  private volatile boolean closed;

  @Override public Socket createSocket(String host, int port) throws IOException {
    String address = (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    try {
      Connection connection = Tailscale.dial(address);
      TsnetSocket result = new TsnetSocket(connection);
      socket = result;
      if (closed) { result.close(); throw new IOException("连接已取消"); }
      return result;
    } catch (IOException e) { throw e; }
    catch (Exception e) { throw new IOException("tsnet 连接失败", e); }
  }

  @Override public InputStream getInputStream(Socket socket) throws IOException { return socket.getInputStream(); }
  @Override public OutputStream getOutputStream(Socket socket) throws IOException { return socket.getOutputStream(); }
  public void close() throws IOException {
    closed = true;
    try { Tailscale.cancelAllDials(); } catch (Throwable ignored) { }
    TsnetSocket current = socket; if (current != null) current.close();
  }

  private static final class TsnetSocket extends Socket {
    private final Connection connection;
    private boolean closed;
    private final InputStream input = new InputStream() {
      @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xff;
      }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        try {
          byte[] result = connection.read(Math.min(length, 65536));
          if (result == null || result.length == 0) return -1;
          System.arraycopy(result, 0, bytes, offset, result.length);
          return result.length;
        } catch (Exception e) {
          if (e.getMessage() != null && e.getMessage().contains("TSNET_READ_TIMEOUT")) throw new java.net.SocketTimeoutException("tsnet 读取超时");
          throw new IOException("tsnet 读取失败", e);
        }
      }
      @Override public void close() throws IOException { TsnetSocket.this.close(); }
    };
    private final OutputStream output = new OutputStream() {
      @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
      @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        while (length > 0) {
          try {
            long written = connection.write(Arrays.copyOfRange(bytes, offset, offset + length));
            if (written <= 0 || written > length) throw new IOException("tsnet 写入不完整");
            offset += (int) written;
            length -= (int) written;
          } catch (IOException e) { throw e; }
          catch (Exception e) { throw new IOException("tsnet 写入失败", e); }
        }
      }
      @Override public void close() throws IOException { TsnetSocket.this.close(); }
    };
    TsnetSocket(Connection connection) { this.connection = connection; }
    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      try { connection.close(); } catch (Exception e) { throw new IOException("tsnet 关闭失败", e); }
    }
    @Override public void setTcpNoDelay(boolean value) {}
    @Override public void setSoTimeout(int timeout) throws java.net.SocketException {
      try { connection.setReadTimeout(timeout); }
      catch (Exception e) { java.net.SocketException ex = new java.net.SocketException("tsnet 设置读取超时失败"); ex.initCause(e); throw ex; }
    }
  }
}
