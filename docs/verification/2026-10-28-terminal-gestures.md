# SSH 终端手势修复（2026-10-28）

范围：V1.2.26 真机反馈对应 R-24。保留既有工作区，不 commit/push，不改变版本，不构建、发包、安装或连接真实主机／使用凭据。用户明确禁止提交，故不创建恢复提交；修改前 terminal.js 与行为测试副本位于 /tmp/terminal-touch-before，仅供本次局部恢复，不覆盖其他工作。

定位与实现：

- 原生桥按一个 8KiB 分片 write/ack 背压增量传输；未发现滚动调用全历史重绘，无需更改桥。原页面普通 touchmove 同时经过 xterm viewport，在滑动开始无条件请求 hideKeyboard，引发不必要的布局路径。本地滚动改为 capture 消费并按帧累计像素，远端鼠标模式保持原路径；隐藏键盘时不发 hide，单手势最多一次。
- 本地触摸 pointer/mouse 焦点路径在确认短点击前拦截，长按不再调用 hideKeyboard，连接 mode 不再自动 focus；选区失效手势也不转成输入点击。保留复制、手柄、吞掉撤选点击和缓冲区变化释放选择。
- 双指优先状态按距离缩放 8–28px，按帧只应用最后字号（0.1px），随后 fit 同步网格和去重 PTY。字形／光标使用 xterm fontSize，手势使用同一网格矩形。缩放期间不调用 focus/hide、选择、滚动或远端输入，剩余单指直到抬完保持隔离。旧实例排队帧在重建后无效。

验证：

- 生产 JS 通过 Node 执行，使用真实 vendored xterm parser/buffer；DOM／selection renderer／字体几何为替身。新增 queued RAF burst、真实 scrollLines、历史回看持续输出、focus/hide 计数、字号上下限／连续缩放、PTY 同帧去重、残留单指、正常输入恢复及远端鼠标事件放行断言。
- `python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge`：3 tests，通过（无跳过）。
- `python3 tools/test_terminal_command.py`：18/18，通过。
- `python3 tools/test_terminal_wiring.py`：6/6，通过。
- `.venv` 不存在，使用现有 python3，未安装软件。`python3 tools/verify.py --python-only`：FAIL，既有模块未登记、Java/Python 超预算、文档断链和 README/REQUIREMENTS 版本声明不一致，止于 check_project。未运行全量测试发现（既有夹具会 commit）；未运行构建／Android tests／Lint，不能声称完整验收。

未验收：无目标手机。不能从 Node 替身推断真实 WebView 帧率、字体测量、IME 闪现、厂商输入法、触摸／手柄命中、远端鼠标 TUI 或真实 SSH 兼容性；这些仍须设备验证。

补充检查：`git diff --check` 在本环境无输出并于 5 秒超时（exit 124），不能记为通过；Git status 同样未返回，因此未依赖 Git 写操作或恢复命令。对本轮修改文件另行检查尾随空白。
