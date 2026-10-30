# SSH 终端触控改进验证（2026-10-28）

范围：R-24。直接修改私有树，遵照用户要求未 commit/push、未构建发布、未安装软件、未连接真实主机或读取凭据。现有未提交内容保留；本次修改前终端源码和测试副本在 `/tmp/terminal-before-20261028/`（临时恢复副本，不是持久 Git 恢复提交）。仅恢复这些副本会保留其他文件，但会覆盖之后对同文件的新编辑，恢复前应先比较。

变更：IME 可见状态控制快捷栏；普通滑动请求隐藏 IME，保持 xterm 滚动事件；500ms 长按进入真实 buffer 坐标选区，拖动、两端手柄及显式 Android 剪贴板复制；13px 系统等宽字体栈；尺寸更新按帧合并并去重。不改 vendor、SSH 信任/认证、版本、依赖、连接隔离或 8KiB/10秒输出回压。

执行：

- `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge`：3 个测试通过。涵盖实际 vendored xterm UTF8/ANSI/备用屏幕、历史 viewport 在增量输出时保留、快捷键、IME 状态、普通滑动不拦截、长按/拖选/手柄/复制、重建监听器清理、PTY 尺寸去重，以及 Java 桥安全生命周期与复制大小限制。
- `python3 tools/test_terminal_command.py`、`python3 tools/test_terminal_wiring.py`：通过。
- `python3 tools/verify.py --offline`：失败于既有 `check_project.py`，未进入 Android/构建步骤。包括未登记类、既有文件超预算、历史文档断链、文档版本不一致；本次未扩大预算或修改版本。
- `python3 tools/generate_icons.py --check`：失败，既有 launcher_foreground.xml / launcher_background.xml 与生成规范不一致；未改图标。
- `node --check app/src/main/assets/terminal/terminal.js`：通过。`git diff --check` 在本树等待不返回，15 秒超时，未宣称通过；本次文件另做尾空白检查。
- `.venv/bin/python` 不存在；使用当前 Python 3.12 环境执行。未安装依赖。

限制：没有真机、Android Chromium 或真实 SSH/TUI 验收。Node 使用真实解析器/缓冲区，但选区渲染、DOM 几何和键盘均为替身；Java 桥用 API doubles。没有滚动帧率数据，不能宣称卡顿已在手机消除；系统字体回退、中英文外观、OEM IME、触摸鼠标模式及长按手柄均待设备验证。完整验收未通过。用户禁止构建发布，本轮没有构建 APK，也不以旧产物交付。
