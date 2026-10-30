package app.remotessh.tsnet;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import org.json.JSONArray;
import org.json.JSONObject;
import tailscale.InterfaceSource;

/** Supplies current Android interface addresses; contains no persistent identifiers. */
public final class AndroidInterfaceSource implements InterfaceSource {
  @Override public String snapshotJSON() {
    JSONArray result = new JSONArray();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (!ni.isUp() || ni.isLoopback() || ni.getIndex() <= 0) continue;
        JSONArray addresses = new JSONArray();
        for (InterfaceAddress a : ni.getInterfaceAddresses()) {
          if (a.getAddress() == null || a.getNetworkPrefixLength() < 0) continue;
          String host = a.getAddress().getHostAddress();
          int zone = host.indexOf('%');
          if (zone >= 0) host = host.substring(0, zone);
          addresses.put(host + "/" + a.getNetworkPrefixLength());
        }
        if (addresses.length() == 0) continue;
        JSONObject record = new JSONObject();
        record.put("name", ni.getName()); record.put("index", ni.getIndex());
        record.put("up", true); record.put("loopback", false); record.put("addrs", addresses);
        result.put(record);
      }
    } catch (Exception ignored) { return "[]"; }
    return result.toString();
  }
}
