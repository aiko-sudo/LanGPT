#!/usr/bin/env python3
"""Structural checks for the SSH terminal entry: it must live under the SFTP
menu, never auto-connect, reuse the encrypted host-pin vault, and seal saved
credentials through CryptoBox."""
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java/app/remotessh"
ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    browser = (SRC / "RemoteBrowser.java").read_text()
    labels = '"SFTP", "SSH 终端", "独立工作区"'
    assert labels in browser, "SSH terminal entry sits directly below SFTP in the project menu"
    assert "if(which==1)SshTerminalActivity.open(activity,dark);" in browser, "second row opens the terminal"
    assert browser.index('actions.sftp(current)') < browser.index('SshTerminalActivity.open'), \
        "SFTP stays first, terminal second"

    terminal = (SRC / "SshTerminalActivity.java").read_text()
    assert "setNavigationBarColor(android.graphics.Color.BLACK)" in terminal
    assert "setNavigationBarContrastEnforced(false)" in terminal
    assert "setFitsSystemWindows(true)" in terminal and "SOFT_INPUT_ADJUST_RESIZE" in terminal
    # No auto-login: connecting only happens from send()/startConnect, onCreate never calls it.
    assert "startConnect(" in terminal
    oncreate = terminal.split("protected void onCreate")[1].split("@Override protected void onDestroy")[0]
    assert "startConnect" not in oncreate, "terminal must not connect on launch"
    assert "复制终端文字" not in oncreate and "terminalCopy()" not in oncreate
    assert "root.addView(copy)" not in oncreate
    for layout in (ROOT / "app/src/main/res/layout").glob("*.xml"):
        assert "复制终端文字" not in layout.read_text()
    page = (ROOT / "app/src/main/assets/terminal/terminal.js").read_text()
    assert "输入 ssh user@host 连接" in page, "local banner explains manual connection"
    assert "showSoftInput(terminal.view" in terminal and "terminal.view.requestFocus()" in terminal
    # Credentials are sealed, and typed-command login uses them by host key.
    assert "CryptoBox.writeSealed" in (SRC / "SshCredentials.java").read_text(), \
        "saved passwords must be sealed with CryptoBox"
    assert "SshCredentials.load(this, command)" in terminal, "typed ssh command matches saved credentials"
    assert "保存凭证" in terminal, "password dialog offers to save the credential"
    # Host keys reuse the encrypted vault pins, same policy as SFTP.
    assert "jsch.setHostKeyRepository(hostKeyRepository(token))" in terminal
    assert "vault.getPin(" in terminal and "vault.rememberPin(" in terminal, \
        "host fingerprints go through the encrypted vault"
    # PTY remains native; renderer receives original bytes with bounded backpressure.
    assert 'openChannel("shell")' in terminal and 'setPtyType("xterm-256color")' in terminal
    assert "terminal.bytes(chunk, count" in terminal and "ANSI.matcher" not in terminal
    bridge = (SRC / "TerminalWebView.java").read_text()
    assert "setBlockNetworkLoads(true)" in bridge and "setAllowFileAccess(false)" in bridge
    assert "setAllowContentAccess(false)" in bridge and "shouldOverrideUrlLoading" in bridge
    assert "Base64.getEncoder()" in bridge and "JSONObject.quote(encoded)" in bridge
    assert "while (!latch.await(250, TimeUnit.MILLISECONDS))" in bridge, "renderer delay must retain bounded backpressure without disconnect"
    assert "terminal.close()" in terminal and "generation++" in terminal
    assert "setPrimaryClip" in terminal, "copy remains an explicit user action"
    # One-shot remote command support.
    assert 'openChannel("exec")' in terminal and "remoteCommand" in terminal

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    assert 'android:name=".SshTerminalActivity"' in manifest, "activity must be registered"
    line = [l for l in manifest.splitlines() if ".SshTerminalActivity" in l][0]
    assert 'android:exported="false"' in line, "terminal must not be exported"
    assert "keyboard" in line and "screenSize" in line, "rotation must keep the session alive"

    assert "disconnectButton" not in terminal
    assert 'ui.icon("back", "主机菜单", this::showHostMenu)' in terminal
    menu = terminal.split("private void showHostMenu()")[1].split("private void print")[0]
    assert 'HostHistory.merge(HostHistory.list(this), SshCredentials.list(this))' in menu and 'CenteredMenu.show(this, ui.fg, ui.muted, null, labels' in menu
    assert 'labels[hosts.size()] = "退出"' in menu and 'which == hosts.size()' in menu
    assert 'hosts.get(i).equals(currentHost) ? " ✓"' in menu
    assert 'connected && currentHost != null && !hosts.contains(currentHost)' in menu
    assert 'connected && target.equals(currentHost)' in menu
    assert menu.index('SshCredentials.load(this, command)') < menu.index('disconnectAll()') < menu.index('startConnect(command, saved.password, false)')
    assert '[已切换到 ' in menu and 'if (saved == null) promptPassword(command)' in menu
    login = terminal.split('open.connect(20000);')[1].split('InputStream in;')[0]
    assert login.index('token != generation || destroyed') < login.index('HostHistory.record(this, command.credentialKey())') < login.index('if (remember)')
    forget = terminal.split('private void forgetCredential(')[1].split('private void promptPassword')[0]
    assert 'SshCredentials.forget(this, parsed.user, parsed.host, parsed.port)' in forget
    assert 'HostHistory.remove(this, parsed.credentialKey())' in forget
    confirm = terminal.split("private void confirmExit()")[1].split("private void showHostMenu")[0]
    assert 'if (!connected) { finish(); return; }' in confirm
    assert '.setMessage("断开 SSH 连接并退出？")' in confirm
    assert '.setNegativeButton("取消", null)' in confirm
    assert '.setPositiveButton("确认", (dialog, which) -> finish())' in confirm
    assert 'ownedDialog = new AlertDialog.Builder(this)' in confirm
    back = terminal.split("@Override public void onBackPressed()")[1]
    assert "MainActivity.class" in back and "FLAG_ACTIVITY_REORDER_TO_FRONT" in back
    assert "finish(" not in back and "disconnectAll(" not in back
    entry = terminal.split("public static void open(")[1].split("@Override")[0]
    assert "FLAG_ACTIVITY_REORDER_TO_FRONT" in entry
    destroy = terminal.split("protected void onDestroy()")[1].split("private void print")[0]
    assert "disconnectAll()" in destroy and "terminal.close()" in destroy
    for method in ("onStop", "onResume"):
        body = terminal.split("protected void " + method + "()")[1].split("\n  }")[0]
        assert "disconnectAll" not in body and "terminal.close" not in body and "loadUrl" not in body
    assert 'android:hardwareAccelerated="true"' in line
    assert 'android:enableOnBackInvokedCallback="false"' in line
    assert 'case "vendor/addon-canvas.js"' in bridge
    page_html = (ROOT / "app/src/main/assets/terminal/index.html").read_text()
    assert page_html.index('vendor/xterm.js') < page_html.index('vendor/addon-canvas.js') < page_html.index('src="terminal.js"')
    print("ssh-terminal wiring: entry, lifecycle, back/reuse, hardware acceleration and sealed canvas path OK")


if __name__ == "__main__":
    main()
