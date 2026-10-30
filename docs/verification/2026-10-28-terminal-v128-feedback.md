# 2026-10-28 SSH 终端 V1.2.28 反馈修订（部分完成）

## 范围与恢复

本次用户明确禁止 commit/push、构建发包、安装软件、访问真实主机或凭据；优先于项目默认的分阶段提交协议。没有提交、修改版本、打 APK、安装或访问生产服务。开始时 HEAD 为 `5d949ee7e0af9560f6933532dac0bf6913dea97d`，工作区已有大量未提交终端实现及其他文件。本次未 reset/clean，没有覆盖其他模块。变更前相关文件（含未跟踪终端资产和测试）逐文件备份在 `/tmp/terminal-v128-before`；这不是可跨机器持久保存的恢复提交。

## 实现及审计

- 删除本地 banner 的“SSH终端”首行，其余六行与空输入光标行保留。移除顶栏“断开”，左上角箭头直接 finish，由 onDestroy 清理。
- 系统返回收起 IME／快捷栏、取消旧触摸手势，REORDER_TO_FRONT 将 MainActivity 提至同一 task 前台；open 菜单同样复用现有终端。未采用 singleTask，避免其清栈语义。终端 manifest 关闭新 predictive-back 回调，让系统手势走 onBackPressed；已有旋转 configChanges 保留。
- onStop 仅隐藏输入法与清焦点／触摸；onResume 仅 fit／refresh。WebView、解析器、字号、命令历史、缓冲区不重建；连接、PTY、读取线程保留。
- 找到提前关闭的实际路径：TerminalWebView.bytes 原先等待 write 回执 10 秒，超时返回 false，读取循环结束后 finally 断开通道／Session。改为单个 8KiB 分片背压等待，以 250ms 检查 closed／generation，不因后台渲染延迟关闭；销毁或旧连接失效仍能解除等待。后台 WebView 被系统暂停消费时会暂停读取并向 SSH 施加背压，不保证此时持续绘制。
- 队列满原先 disconnectAll，现仅提示本次输入未发送。写入网络异常仍取消连接；读取 EOF、远端命令／shell 退出、网络异常仍由 finally 释放。20s Session／15s Channel 超时仅用于建立连接；15s server-alive、3 次失联门限保留。120s 首次指纹等待属于连接建立阶段。渲染进程崩溃／非法页面会结束 Activity 并经 onDestroy 清理，无法承诺软件崩溃仍保持会话。
- 终端 Activity 显式 hardwareAccelerated=true，无软件层覆盖。canvas 脚本顺序、原生资源白名单、激活失败释放／重建 DOM 回退已接入；自定义选区隐藏 canvas 原生 selection 层，保留既有缩放、自动复制、点击恢复输入、命令历史、IME 快捷栏和 3000 行回滚。

## 未完成：canvas vendor

**尚无 addon-canvas.js，实际运行仍为 DOM，不能声称已治愈滚动卡顿。** 尝试固定版本 `@xterm/addon-canvas@0.7.0` 公共 CDN 下载：配置代理连接失败；绕过代理无法 DNS 解析，固定公网地址连接亦失败；备用网页下载入口不可用。没有安装依赖或伪造 vendor。需要取得原始制品及许可证、核对核心兼容性、记录 SHA-256 并验证真实 canvas 激活。NOTICE 已明确登记这一待完成状态，没有给未取得制品编造摘要。

已存在 vendor 文件重新校验：

| 文件 | SHA-256 |
| --- | --- |
| xterm.js（5.5.0） | `1f991ac3b4b283ebf96e60ae23a00a52765dd3a2e46fa6fdda9f1aab032f7495` |
| xterm.css（5.5.0） | `ba8e6985669488981ccf40c0cefe3aba80722cb6c92de7ad628b0bd717faf2b6` |
| LICENSE | `b569f629d00f2626a8100df2a1798210535621e42164dfd426a6fe5aac7b0ccd` |
| addon-canvas.js | 未取得，无可验证摘要 |

## 实际验证结果

使用现有 `/usr/local/bin/python3`（Python 3.12，开发依赖已有）、Node 和 JDK 17，不安装软件。

```text
python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge tools.test_terminal_lifecycle
Ran 4 tests in 18.657s
OK

python3 tools/test_terminal_command.py
terminal command: 18/18 OK

python3 tools/test_terminal_wiring.py
ssh-terminal wiring: entry, lifecycle, back/reuse, hardware acceleration and sealed canvas path OK

javac [现有 android-36 / JSch / org.json / 已有项目类缓存] TerminalWebView.java SshTerminalActivity.java
javac exit=0
Note: Some input files use or override a deprecated API.
```

日志：`/tmp/terminal-v128-targeted.log`、`/tmp/terminal-v128-javac.log`。Java 类仅编译至临时目录，无 Gradle／APK 构建。JS 使用实际 xterm 解析器；canvas 激活及 DOM 几何为替身，覆盖成功、缺失、激活与释放异常的回退，后台恢复实例／字号／viewport／命令历史与半个 UTF8 字节序列保留。桥测试真实等待 10.2s 后回执成功、销毁解除待处理读取。生命周期测试执行生产 Java 方法体但以 task／IME 替身承载，并非 Android Activity 栈测试。

```text
python3 tools/verify.py --python-only
exit=1（check_project.py 失败，未进入后续阶段）

python3 tools/generate_icons.py --check
Stale icon outputs: app/src/main/res/drawable/launcher_foreground.xml, app/src/main/res/drawable/launcher_background.xml

timeout 180 python3 -m unittest discover -s tools -p 'test_*.py'
exit=124（超时，有 E，无完整通过摘要）
```

全量 Python 诊断另外即时记录错误至 `/tmp/terminal-v128-suite-errors.log`：`test_peer_socket` 四项 socketpair 测试因 `PermissionError: [Errno 1] Operation not permitted` 出错，当前沙盒禁止 socket 操作；不能报告全量全绿。结构检查日志 `/tmp/terminal-v128-verify.log` 列出既有未登记模块、SshTransport／RemoteApp／MainActivity 及 Python 资产超预算、旧实验文档链接失效、README／需求文件头版本不匹配。相关模块／版本／图标本次均未修改，不扩大本次任务来掩盖这些问题。SshTerminalActivity 为 424 行，未提升预算。

`git diff --check` 对本次涉及的已跟踪文件通过。没有运行完整 acceptance（用户禁止 APK 构建），没有 Android／Lint 的本轮执行结果。

## 设备与服务未验收

实际 canvas 包兼容性、CSP／纹理图集、渲染失败回退、长历史滑动帧率；系统边缘返回／IME 优先消费手势、菜单复用同一个 WebView、旋转、字号与光标／手柄坐标；后台持续 SSH／PTY 输出、恢复后积压数据、网络中断、OEM 省电／进程终止行为均未真机验证。没有真实 SSH、模型、Tailscale 或前台服务兼容性结论。
