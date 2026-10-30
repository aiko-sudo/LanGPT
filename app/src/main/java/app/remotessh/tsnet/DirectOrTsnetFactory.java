package app.remotessh.tsnet;
import com.jcraft.jsch.SocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Terminal route: direct first; an enabled in-process node is the fallback. */
public final class DirectOrTsnetFactory implements SocketFactory {
  private volatile boolean closed;
  private volatile Socket direct;
  private volatile TsnetSocketFactory tsnet;
  @Override public Socket createSocket(String host, int port) throws IOException {
    Socket s = new Socket(); direct = s;
    try {
      if (closed) throw new IOException("连接已取消");
      s.connect(new InetSocketAddress(host, port), 3000);
      if (closed) throw new IOException("连接已取消");
      return s;
    } catch (IOException e) {
      try { s.close(); } catch (IOException ignored) {}
      if (closed) throw e;
      TsnetSocketFactory f = new TsnetSocketFactory(); tsnet = f;
      if (closed) { f.close(); throw new IOException("连接已取消"); }
      return f.createSocket(host, port);
    }
  }
  @Override public InputStream getInputStream(Socket s) throws IOException { return s.getInputStream(); }
  @Override public OutputStream getOutputStream(Socket s) throws IOException { return s.getOutputStream(); }
  public void close() throws IOException {
    closed = true;
    try { if (direct != null) direct.close(); } finally { if (tsnet != null) tsnet.close(); }
  }
}
