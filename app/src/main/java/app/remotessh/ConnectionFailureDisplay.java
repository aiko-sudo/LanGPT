package app.remotessh;

import java.util.Locale;

/** Presentation only: retain the original exception and remote diagnostic text. */
final class ConnectionFailureDisplay {
  private ConnectionFailureDisplay() {}

  static String describe(Throwable error, String phase, int exitStatus, String stderr, Locale locale) {
    if (locale == null || !"en".equals(locale.getLanguage()))
      return ConnectionFailure.describe(error, phase, exitStatus, stderr);
    // Match describe's existing ANSI removal and trimming; never translate remote output.
    if (stderr != null) stderr = stderr.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "");
    String reason = ConnectionFailure.reason(error);
    String displayReason = reason;
    if (ConnectionFailure.unwrap(error).getClass().getSimpleName().equals("NetworkOnMainThreadException"))
      displayReason = "Connection initialization failed: network operation on the main thread (NetworkOnMainThreadException)";
    String result = "Connection failed · " + ConnectionDisplay.state(phase, locale) + "\n\nReason: " + displayReason;
    if (ConnectionFailure.upstreamForbidden(stderr))
      result += "\n\nThe remote Codex connection to the model service received HTTP 403 on its WebSocket. This occurred between the remote host and the model service, not during phone SSH password authentication. Check Codex login, account access, and proxy/network policy under the same remote user; the log alone cannot identify which one is responsible. The UNDICI experimental warning is not itself the cause. If the remote program has exited, the client cannot continue receiving its output.";
    if (phase.contains("TCP") || reason.contains("ConnectException") || reason.contains("UnknownHostException"))
      result += "\n\nSSH authentication has not yet started. Check the destination address and port. If another SSH client on the same phone can connect to the same address, check Tailscale → profile → per-app routing and ensure Remote (app.remotessh) is not excluded; also check whether the system blocks this app from using the network. You can also reconnect Tailscale and retry.";
    else if (phase.contains("SSH"))
      result += "\n\nIf using Tailscale: confirm Tailscale is connected on the phone, the host is online, and access rules allow the destination port. If MagicDNS fails, try the 100.x address. Use a password or private key for ordinary SSH, or passwordless authentication for Tailscale SSH. If reauthentication is required, open the Tailscale app to complete it and retry.";
    if (exitStatus >= 0) result += "\nRemote exit code: " + exitStatus;
    if (exitStatus == 127) result += stderr != null && stderr.contains("python3")
        ? "\nSSH connected, but python3 was not found. Install Python 3.8 or later for the same SSH account and add it to the non-interactive PATH, or manually close the remote persistent session and reconnect."
        : "\nSSH connected, but the remote shell could not find the startup program; this is not an IPv6 connection failure. Run command -v codex in a terminal under the same remote account and enter the resulting absolute path in the startup command (for example, /opt/homebrew/bin/codex app-server). If there is no output, install Codex for that account first.";
    if (stderr != null && !stderr.trim().isEmpty())
      result += "\n\nRemote diagnostic output (warnings are not necessarily the cause):\n" + stderr.trim();
    return result;
  }
}