# SSH 终端 V1.2.27 真机反馈修复（2026-10-28）

范围：R-24；只修改终端页面、原生焦点桥、对应测试及行为文档。版本号、依赖、凭据格式和已确认的缩放／自动复制／单击撤选／紧凑字体／IME 快捷栏规则不变。

## 基线与恢复

初始 HEAD：`5d949ee7e0af9560f6933532dac0bf6913dea97d`。工作区包含用户既有未提交与未跟踪代码，保留原状并在其上局部修改。用户明确禁止 commit/push，因此本轮没有创建恢复提交或阶段提交，未 reset/clean。修改前的 terminal.js 已另存 `/tmp/terminal-before-task.js`（临时备份，不作为长期恢复承诺）。回退应只撤回本记录描述的局部变更，不能以 HEAD 覆盖整个工作区。

## 改动

- 普通点击保留 16px 内手指抖动容差；缩放结束重置 origin/moved，并清除缩放进入时的撤选手势状态。下一次 touchstart 可恢复普通点击，不依赖浏览器合成 click。确认点击调用 xterm.focus，再通过 ready/closed 限定的主线程桥请求 WebView 焦点和 Android showSoftInput。缩放、滚动、长按、复制与撤选不请求 IME，撤选后的下一次点击可输入。
- 启动文案由可信本地 JS 一次性写入 xterm，精确按用户指定的七行显示，分隔线后直接是空输入行。删除 Activity 的旧启动文案，避免重复。输入 ssh 命令仍在本地回显，后续状态位于独立行。
- JS 页面内最多 100 条完整文本去重历史，最新在前；terminalMode 重建解析器保留历史，WebView 销毁丢弃，无持久化。↑ 循环向旧命令，↓ 向新命令并恢复草稿／空行。已连接空行透传远端 readline；有本地可跟踪文本或已进入历史时拦截，使用 DEL + 替换文本更新远端行。CTRL/ALT、备用屏幕 TUI、未知远端光标编辑均不劫持。Tab、控制编辑等无法可靠重建远端行时，保守透传直至回车／Ctrl-C／Ctrl-U；不猜测远端自行调出的历史。

## 实际验证

执行现有 Python3（项目 `.venv` 不存在），未安装任何软件。

```text
python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge -v
terminal behavior: ... tap focus/IME, startup and memory history PASS
terminal Java bridge: ... PASS
Ran 3 tests
OK

python3 tools/test_terminal_command.py
terminal command: 18/18 OK

python3 tools/test_terminal_wiring.py
ssh-terminal wiring: 6/6 OK
```

生产 JS 使用实际 vendored xterm；新增精确启动文字、光标行、命令回显与状态分行、缩放后不经 click 的输入点击、9px 轻微抖动、撤选后下一次点击、原生 IME 请求计数、历史去重／上限／循环／草稿恢复／跨实例保留、远端空行／修饰键／备用屏幕与未知光标编辑透传测试。Java 桥验证 showKeyboard 在 ready 前和关闭后不发布。

`python3 tools/verify.py --python-only`：FAIL，停在 `check_project.py`，既有未登记模块、Java/Python 文件预算、文档链接及 README/REQUIREMENTS 版本声明问题；未执行全量测试发现。保留用户禁止 commit 的限制，未运行其他可能包含 Git 提交夹具的全量测试。

`python3 tools/generate_icons.py --check`：FAIL，既有 launcher_foreground.xml、launcher_background.xml 生成结果过期，本轮未修改图标及 spec。

定向 `git diff --check`：PASS。由于终端部分文件原本未跟踪，另外对本轮修改文件逐行检查行尾空白；无新增行尾空白。

## 未验证与限制

没有构建、安装、发包、提交、推送、访问真实主机或凭据，也没有调用模型。没有运行 Android/Robolectric/Lint，不属于完整验收。

Node 的 DOM、focus、IME 和几何为替身；Java 使用 WebView API doubles。仍须目标手机验证双指缩放／撤选后点击的可见光标与实际 IME、厂商键盘与快捷栏联动、字体／手柄／帧率，以及真实 readline 下中英文、组合字符、跨行编辑与 TUI 按键。内存历史只可靠记录本地可跟踪的输入；远端自主编辑、补全、密码提示及自定义 shell 输入语义无法由终端字节流完整推断，不能宣称任意 shell/TUI 编辑兼容。
