# SSH 终端凭证索引工作记录（2026-10-28）

本次起点 HEAD 为 `7e9806a936b08fd7e7ee8cd9254a79d03001450f`；用户禁止提交，未建立恢复提交。工作区原有未提交内容须保留。

新增明文凭证键索引，旧哈希文件无法枚举；损坏索引视为空。`python3 tools/test_terminal_command.py` 通过，使用临时文件和 CryptoBox 替身，覆盖保存、去重、删除与损坏索引。`python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge tools.test_terminal_lifecycle tools.test_terminal_wiring` 报 4 tests OK；`python3 tools/test_terminal_wiring.py` 通过。以上不是完整 Android 验收。

主机菜单、多会话或降级切换、回放、会话独立历史及其测试均尚未实现。本次禁止构建，未运行完整 `tools/verify.py`，未验证真机、真实 SSH 或真实服务。
