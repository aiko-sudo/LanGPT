# 自定义模型配置保护

初始恢复提交：`1f5acf17`。远端配置与认证 JSON 均先解析，解析失败不开始写入。

`python tools/test_custom_api.py`：19 项通过。未运行完整验收；未验证设备、真实服务或真实模型。两文件写入仍非事务，其他自定义模型审计问题尚待处理。

后续阶段恢复提交：`fceab62f`。`python tools/test_custom_switch_safety.py`：3 项通过；`python tools/test_remote_session.py`：12 项通过；Java 编译通过。`ConversationStoreTest` 的 4 项均因 Robolectric 原生运行时不支持 Linux aarch64 在初始化阶段失败，未完成 Android 测试。`python tools/verify.py` 在结构检查阶段失败：既有模块登记缺失、超预算文件和文档版本不匹配等，未进入完整验收。没有验证真实服务或手机。远端两文件写入仍非事务，密钥仍明文保存，其他进程可竞争同一配置；本轮不声称全部风险已修复。

事务阶段恢复提交：`b59162e0`。用户明确允许在当前方案下明文保存 API 密钥。新增事务恢复日志、快照检查与逐文件原子替换；`python tools/test_custom_config_transaction.py` 5 项通过，`python tools/test_custom_api.py` 19 项通过，`python tools/test_custom_switch_safety.py` 3 项通过。完整 `python tools/verify.py` 仍因既有结构检查失败而中止。恢复只在下一次事务执行时发生，两文件并非同时原子可见；不遵守同一锁的外部程序仍可能竞争。未进行真实 SSH 中断或 OpenCode 设备验收。

APK 测试阶段恢复提交：`11528b0d`。事务脚本增加独立进程入口测试（6 项通过），拒绝关闭的旧桥接不再强杀（安全测试 4 项通过），远端会话测试 12 项通过。APK 使用本机 Android debug 签名，非已发布版本签名；完整验收仍受既有结构检查与 Linux aarch64 Robolectric 限制。冷切换遇到已占用端口仍拒绝，真实设备与服务尚未验证。
