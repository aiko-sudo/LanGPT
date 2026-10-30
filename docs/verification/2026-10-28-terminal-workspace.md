# 2026-10-28 终端与工作区局部验证

初始 HEAD：`64a6a7fc4b1b343c8a13dfbd0b3ae3a41f49420a`。按用户要求不创建提交；保留原有未提交内容。Git status 在本环境中阻塞，无法完整核对初始工作树清单。

- `python3 -m unittest discover -s tools -p 'test_terminal*.py'`：终端页面与桥接、生命周期及接线测试通过（见本次运行输出）。
- `python3 -m unittest tools.test_git_workspace`：11 项通过，0 跳过；fixture 使用本地 /tmp 仓库。
- `javac` 使用 Android 36、现有 R.jar、已有编译类和 JSch 缓存，局部编译 GitWorkspaceDialog、SshTerminalActivity、TerminalWebView 通过，只有弃用 API 提示。
- 按用户要求未构建 APK 或运行包含 APK 构建的完整 `tools/verify.py`；因此不是完整验收。未访问真实主机或凭据，真机缩放显示、IME、持久化和真实 Git 服务仍待验收。
