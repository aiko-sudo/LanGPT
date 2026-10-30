# LanGPT

[中文](README.md) | **English**

A native Android client for driving a remote Codex over SSH. Manage projects, conversations, permission approvals and attachments in a GUI, with built-in SFTP file management, a Termux-style SSH terminal.

Model inference and Codex login happen on the remote host; the phone handles interaction and file transfer. The app starts `codex app-server` over SSH — no web service to deploy.

**[Download APK](https://github.com/aiko-sudo/LanGPT/releases/latest)** · [Release notes](https://github.com/aiko-sudo/LanGPT/releases)

## License

This project is derived from [RemoteGPT](https://github.com/qzh775226913-ai/RemoteGPT); the original copyright and license are fully retained, under its **[Free Use and Commercial Reuse License 1.0](LICENSE)** (Chinese text authoritative). Key points:

- **Free use**: individuals and organizations may install and run it free of charge (including for work and commercial projects) and modify/build it for internal use.
- **Distribution**: unmodified copies may be shared free of charge with this license, copyright and source intact; no charging or ad monetization on that distribution.
- **Prior written authorization required**: selling or paid hosting/subscription/ad-supported offerings; publishing rebranded, renamed or repackaged modifications; closed-source delivery or relicensing.

The app optionally uses Tailscale technology.

## Features

- **Direct SSH** — connect to your own server, no relay service; automatic reconnect and live session recovery
- **Parallel sessions** — multiple hosts and projects, several sessions per connection, each keeping its own draft, attachments and permission choices
- **SSH terminal** — full Termux-style terminal (xterm.js) with shortcut bar, pinch zoom, selection copy and command history; sessions survive in the background
- **SFTP** — browse, sort, preview and edit remote files, with download protection and draft recovery
- **Interactive approvals** — command/file permission dialogs, option pickers and free-form question forms, including temporary "always allow"
- **Live notifications** — thinking, running, awaiting-approval and completed states; handle permission requests right from the notification
- **Model settings** — choose model and reasoning effort per remote capability, watch execution progress and account quota
- **First-connection diagnostics** — distinguish SSH auth, Python, Codex, SFTP and login problems with targeted guidance

## Getting started

| Side | Requirement |
| --- | --- |
| Phone | Android 8 or newer |
| Server | Unix environment with SSH and SFTP; the SSH user has Codex CLI installed and logged in (`app-server` support), Python ≥ 3.8 |

1. Install, log in and verify Codex on the remote host.
2. Install the APK and enter host, port, username and SSH credentials.
3. Set the launch command to `codex app-server`; use an absolute path if it is not found.
4. Verify the host fingerprint on first connection, pick a project directory and send a message.

Detailed steps and troubleshooting: **[Usage guide](docs/USAGE.md)**.

## Build and verify

Native Android in Java 17, JSch for SSH/SFTP, stdio JSON-RPC for the protocol; the remote persistent host uses the Python standard library.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements-dev.txt
.venv/bin/python tools/verify.py
```

Verification runs synthetic SSH fixtures, Python/Android tests and an APK build without touching production SSH or real models. For a build only, run `./gradlew :app:assembleDebug`. GitHub Actions uses the same entry point; results live on the repository's Actions page.

## Documentation

- [Usage guide](docs/USAGE.md) — server preparation, phone setup, permissions and troubleshooting
- [Development guide](docs/DEVELOPMENT.md) — toolchain, test entry points, CI and artifacts
- [Requirements](docs/REQUIREMENTS.md), [Architecture](docs/ARCHITECTURE.md), [Acceptance scope](docs/ACCEPTANCE.md) — current behavior and evidence boundaries

Protocol reference: [Codex App Server](https://learn.chatgpt.com/docs/app-server).
