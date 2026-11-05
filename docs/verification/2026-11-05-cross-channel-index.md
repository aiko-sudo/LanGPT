# 2026-11-05 跨模式历史索引与读取

起始恢复基线 `d6b94d12`。脚本版本 36；未部署或重启远端。已知 ID 的来源优先于缓存标记，跨模式读取结果按 ConversationStore 隔离；列表使用 Codex 状态索引并排除归档、删除标记与缺失日志，不再把缓存作为远端列表补充。索引缺失或不兼容时返回错误；续接仍创建当前模式的新会话，基于有限历史上下文。

- 合成桥接分派及 SQLite 索引测试：2 项通过；导航 JVM 测试及 `tools/test_work_sync.py` 通过。异步跨模式结果的真实 Android 集成仍待验证。
- `python tools/verify.py` 在结构预检查失败：既有多个 Java 文件及 Python 资产超预算，未进入其余全量步骤。
- `./gradlew :app:testDebugUnitTest --offline` 编译通过，417 项中 296 失败、15 跳过：其中 276 项报告 Linux aarch64 不支持 Robolectric native runtime，15 项缺少 aarch64 Conscrypt；其余包含断言及语言预期不符。不是完整验收。
- `python tools/generate_icons.py --check` 失败，现有 launcher 前景和背景与规格不一致；未改图标。
- 真机、远端实际索引版本与双模式续接均未验证。旧版 Codex 缺少可用索引时不会显示跨模式 Codex 历史。
- 遗留风险：Codex 桥接删除逻辑按文件名包含匹配直接删除 rollout，且吞掉文件异常，可能误删或错误报告成功；本阶段未改删除逻辑。
- `python -m unittest discover -s tools -p 'test_*.py'`：136 项通过（仅 Python unittest 发现范围；不替代完整验收）。
