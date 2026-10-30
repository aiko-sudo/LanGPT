# SSH 终端主机菜单验证（2026-10-28）

起点 HEAD `7e9806a936b08fd7e7ee8cd9254a79d03001450f`；按用户要求保留未提交工作，不创建提交、构建 APK 或连接真实主机。

顶栏菜单列出凭证索引和当前连接，选择其他条目前检查凭证，再断开并连接；保留终端文字快照和会话级历史。无法读取凭证时原连接不变；连接目标失败时旧连接已断开，提示失败并保留文字快照。

- `python3 tools/test_terminal_command.py`：terminal command: 18/18 OK，包含凭证索引测试。
- `python3 tools/test_terminal_wiring.py`：接线检查通过。
- `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge`：3 tests OK。
- `python3 -m unittest tools.test_terminal_lifecycle`：1 test OK。
- `javac` 局部编译 Activity、菜单、凭证及桥：通过；使用 Android 36、现有依赖及项目类缓存，主题 R 常量为临时替身。首次编译缺少生成的 R 类，补充替身后通过，有弃用 API 提示。

以上仅为定向检查；未运行完整 `tools/verify.py`、Android 测试或 Lint。菜单点击、切换期间输出快照及失败路径仍需真实 Android 设备和授权 SSH 主机验收，不能由结构断言或替身证明。
