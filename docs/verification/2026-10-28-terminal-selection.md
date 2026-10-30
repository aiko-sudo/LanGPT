# SSH 终端选择修订（2026-10-28）

范围：保留进入任务时已有未提交修改，仅修改 terminal.js/index.html、SshTerminalActivity 的选区复制提示、生产页面行为测试及对应文档。不改版本、不提交／push、不构建／发布／安装、不连接真实主机、不读取真实凭据。按用户要求未创建恢复提交；修改前本轮涉及的原文件已另存 `/tmp/terminal-selection-before-20261028/`（临时恢复副本，不包含凭据）。

行为：逐行高亮截至实际文字，排除行尾空白；复制保留实际写入的空格、缩进与硬换行，去掉未写入单元填充，软换行合并，宽字符边界向外对齐。拖选及手柄释放自动复制，Java 既有桥写剪贴板并显示短 Toast“选择已复制”；移除选区操作框。下一次终端触摸仅撤选，拦截远端鼠标／输入焦点事件。写入／reflow 安全释放旧选区。保留现有字体及零 letterSpacing，将完整网格横向缩放至 90%，fit、光标、文本与触点使用同一变换几何。

## 实际检查

- 最终 `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge`：3 tests，OK。
- `node tools/terminal_behavior_test.js`：PASS。执行生产 terminal.js 和实际 vendored xterm 解析器／缓冲区；覆盖逐行高亮宽度、尾部填充、实际空格、缩进、空行、宽字符半格、软换行、自动复制／手柄更新、撤选／取消，及原 UTF8/ANSI、会话隔离、输入／快捷栏／resize 回归。
- `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge tools.test_terminal_command tools.test_terminal_wiring`：3 tests，OK（command/wiring 为独立脚本，不能把该命令视作它们已执行）。Java 桥检查包含 origin 白名单、导航、数据引用、背压、迟到 ack 与销毁。
- 开发 venv 的两个候选路径不存在，未安装软件；本轮使用现有 `python3`。`python3 tools/verify.py --python-only` 在 structure 前置检查失败：既有模块未注册、Java/Python 文件超预算、旧文档链接失效和 README/REQUIREMENTS 版本声明不匹配。未修改这些用户已有内容或版本。

- `python3 tools/test_terminal_command.py`：`terminal command: 18/18 OK`。
- `python3 tools/test_terminal_wiring.py`：`ssh-terminal wiring: 6/6 OK`。
- `python3 tools/generate_icons.py --check`：FAIL，已有 `launcher_foreground.xml`／`launcher_background.xml` 与 spec 不同步；本轮未修改或生成图标。

## 验证边界

本轮未执行 Gradle、Android 单元测试、Lint 或 APK 构建，不满足完整验收。DOM、选区 renderer、IME、事件分派与几何为 Node 替身；实际 parser/buffer 与生产页面逻辑并非替身。

仍须设备验证：90% 网格缩放后的字体外观／清晰度、中英文及 emoji 与光标对齐、真实手柄命中与边缘滚动、IME 自动快捷栏及撤选后再次输入、Android 剪贴板与底部短 Toast、远端鼠标模式 TUI 点击隔离、软键盘／屏幕旋转／流式写入撤选、长历史滑动性能。不能据本轮结果声称真机、OEM、Tailscale 或真实模型兼容。
