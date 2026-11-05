# 2026-11-05 历史目录过滤与项目路径隔离

恢复基线 `69d1a4d6`。历史列表及工作面板对服务器确认缺失的目录隐藏关联会话；目录恢复可再次出现，不删除远端会话或清除本地缓存。路径未知、检查失败及权限错误不作为缺失。只读页保留全宽接续，主菜单常驻新会话。连接配置路径与会话工作目录分离：主页显示配置目录，手动选定的目录通过已有 ConversationState.cwd 给新会话使用，不改变 Vault 格式。

- 合成 JVM 测试通过：目录缺失、恢复、错误时未知、检查结果乱序，以及主页、已有会话和草稿路径；工作面板过滤保留原缓存；历史导航/菜单接线通过。
- `./gradlew :app:assembleDebug :app:compileDebugUnitTestJavaWithJavac --offline`：通过，为调试构建和源码编译，不代表完整验收。
- `python tools/verify.py` 在结构检查失败，已有 Java 和 Python 文件超过预算，未进入完整验收；生成图标检查仍报告现有 launcher 资源与规格不一致。
- 未部署或修改真实远端数据，未在手机和真实服务测试。历史未提供工作目录时无法确认是否失效，故保留；服务器权限或连接错误时亦保留。Android 真机、不同模式的实际列表和目录恢复仍需验证。
- 两项新增 Robolectric 会话路径测试未能运行成功：当前 Linux aarch64 环境的 Robolectric native runtime 不受支持；不能据此声称 Android 测试通过。
- 修复只阻止未来自动改写连接配置；如果旧版本已把配置目录覆盖，需在连接设置中手动恢复期望目录。
- `python -m unittest discover -s tools -p 'test_*.py'`：138 项通过；独立历史导航、工作面板与 shell 任务回归也通过。
