# 2026-11-05 自定义模式历史及项目修复

恢复基线 `7615e926`，桥接脚本版本 37。项目移除使用独立首选项保存路径摘要，主动选择可恢复；会话删除标记保留在本地并由桥接共享标记过滤，使用文件锁和原子替换避免并发覆盖。跨模式会话只读展示，明确接续后在当前模式创建新会话并携带有限上下文；返回主页先释放空闲会话。历史模型为空时恢复本连接最近一次选择；目录和模型的现有持久格式未改。

- `python -m unittest discover -s tools -p 'test_*.py'`：137 项通过（运行期间之后有补充的历史导航测试，另见下一项）。
- `python tools/test_history_navigation.py`：通过，包括生产跨模式方法的 JVM 测试替身、显式接续和历史模型继承。
- `python tools/test_work_sync.py`：通过；跨模式桥接及自定义 API 共 22 项 unittest 通过，含 20 个并发删除标记写入。
- `./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac --offline`：通过。
- 新增的两个 Robolectric 用例未通过：Linux aarch64 不支持 Robolectric native runtime；首次运行另遇构建目录 FUSE 临时文件问题，重试后两个用例均因 native runtime 启动失败。
- `python tools/verify.py`：在既有结构预检查失败，多个 Java 和 Python 资产超行数预算，未运行完整验收后续步骤。`python tools/generate_icons.py --check` 报现有 launcher 前景和背景不一致。未构建验收 APK、未完成 Lint。
- 未部署远端、未安装手机、未调用真实模型；需在真实手机及服务验证删除、返回手势、两种模式历史和续接。旧版本桥接需按现有升级机制更新后才能获得共享删除过滤；运行中的任务未被重启。
