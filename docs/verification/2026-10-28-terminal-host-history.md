# SSH 终端主机历史验证（2026-10-28）

起点 HEAD `7e9806a936b08fd7e7ee8cd9254a79d03001450f`；按用户要求保留未提交工作，不提交、不构建 APK、不访问真实主机或凭据。

SSH 认证成功后记录最近主机，菜单合并历史和凭证索引，无凭证时提示输入密码；/forget 同时删除历史。历史最多 20 条，写入为尽力而为，存储失败无法保证持久化；旧版不可枚举的凭证需要再次登录或保存才能列出。

- `python3 tools/test_terminal_command.py`：terminal command: 18/18 OK; host history: 8/8 OK，使用临时文件运行真实存储逻辑。
- `python3 tools/test_terminal_wiring.py`：通过。
- `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge`：3 tests OK。
- `javac` 局部编译 Activity、命令、凭证和主机历史：通过，使用 Android 36、项目类缓存、JSch 和临时 R 替身；提示使用弃用 API。
- 本轮相关已跟踪文件 `git diff --check`：通过。

未运行完整 `tools/verify.py`、Android 测试、APK 构建或 Lint，故不构成完整验收。真实设备菜单操作及授权 SSH 服务仍待验收；JVM 和结构测试不能证明真机兼容性。
