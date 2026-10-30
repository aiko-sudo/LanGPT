# 终端与独立工作区界面调整（2026-10-28）

基线恢复参考 HEAD：`15bd6f38c9a2f9159eb24c3193e9d154497099c9`；依用户要求保留未提交改动，不创建提交。

- `for f in tools/test_terminal*.py; do python3 "$f"; done`：全部通过，包括桥接、命令、生命周期、网页行为及接线检查。
- `python3 -m unittest tools.test_git_workspace`：12 项通过，无跳过；测试输出有 Python TemporaryDirectory 的 ResourceWarning。
- 两份修改的 Java 文件以 Android 36 SDK、缓存 JSch 和已有 debug 类文件执行 `javac`：通过；使用临时最小 `R.style` 替身，输出仅有弃用 API 提示。该编译不等于完整 Android 构建。
- 按要求未构建 APK、未执行完整 `tools/verify.py`，未访问真实主机或凭据。IME、底部安全区及下拉手势的实际设备表现仍需验收。
