# xterm.js third-party notice

- Component: `@xterm/xterm` 5.5.0
- Upstream: https://github.com/xtermjs/xterm.js
- Source artifacts: https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/
- License: MIT. The upstream copyright and license notice is included in `LICENSE` in this directory and must accompany redistribution.
- xterm.js is a terminal emulator frontend, not an SSH client.
- `xterm.js` SHA-256: `1f991ac3b4b283ebf96e60ae23a00a52765dd3a2e46fa6fdda9f1aab032f7495`
- `xterm.css` SHA-256: `ba8e6985669488981ccf40c0cefe3aba80722cb6c92de7ad628b0bd717faf2b6`

## Canvas integration pending artifact acquisition (2026-10-28)

The page optionally loads `vendor/addon-canvas.js` and falls back to DOM if it is
missing or fails initialization. Intended component: `@xterm/addon-canvas` 0.7.0
(compatibility with the current core still requires verification), upstream MIT xterm.js project.
Fixed artifact URL: https://cdn.jsdelivr.net/npm/@xterm/addon-canvas@0.7.0/lib/addon-canvas.js

**The artifact has NOT been downloaded or vendored.** This environment could not
connect to the configured proxy or resolve/reach the public CDN directly; the
alternate download route was also unavailable. There is no addon SHA-256 or
verified addon license file to report. Do not describe this work as a delivered
canvas performance fix. Before completion, acquire the original artifact and its
MIT license, verify its core compatibility, record their hashes here, and exercise
actual canvas rendering on Android. The xterm core hashes above remain verified.

Device interaction, rendering performance and OEM background behavior still need
acceptance; source-level tests cannot establish them.
