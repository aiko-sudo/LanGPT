# 用量、终端导航栏与项目路径修复（2026-10-28）

开始时 HEAD 为 `e83df0c31a114a5a73ae9e03c05d7e47e8d9a967`，作为已提交源码的恢复参考；工作区已有修改，未提交、重置或清理，不能用该提交恢复未提交修改。本次按用户要求不提交、不推送、不发包、不改版本、不安装软件、不访问真实 SSH 或凭据。

用量弹窗旧窗口的延迟关闭回调可能释放新窗口的 header 引用，导致加载结果无法重绘。请求超时后旧回调以及 request 的通用失败分支可覆盖较新状态；完成、超时、断开均使 attempt 失效，失败保留额度。终端浅色主题的系统导航栏为白色，WebView／页面为黑色，设置终端窗口黑色导航栏并保留系统布局。项目路径原按名称且目录优先；改为混合类型按时间倒序，缺失排末尾，时间相同按名称稳定排序，排序交互及选择行为保留。

实际运行：`python3 tools/test_directory_picker.py`、`python3 tools/test_usage_lifecycle.py`、`python3 tools/test_usage_loading.py`、`python3 tools/test_terminal_wiring.py` 均通过；`python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge` 3 项通过。请求测试以确定性队列执行生产刷新方法，窗口引用和终端导航栏为接线检查，不是 Android 真机测试。`python tools/verify.py` 因当前 Python 环境缺少 `tools/requirements-dev.txt` 所需依赖在启动前退出（代码 2）；未安装依赖，未完成 Android 测试、APK 构建、Lint 或完整验收。实际手机导航模式、浅／深主题、白边、IME、安全区，以及真实 SSH／服务与模型仍待验收。
