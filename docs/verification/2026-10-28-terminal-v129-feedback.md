# SSH 终端 V1.2.29 反馈修复（2026-10-28，R-24）

## 范围与恢复

本轮在已有未提交工作上局部修改，未 reset／clean、stash 或覆盖其他功能。当前分支引用基线为 `5d949ee7e0af9560f6933532dac0bf6913dea97d`；该提交不包含此前未提交的终端功能，不能单独作为工作区恢复快照。用户禁止 commit／push，因此没有创建初始恢复提交或阶段提交；回退时仅反向应用本轮局部补丁，不恢复整个文件。未修改版本号、依赖、凭据、存储格式或签名；未构建、发包、安装软件或连接真实主机。

## 修改

- 箭头调用 confirmExit：connected 时弹出 AlertDialog“断开 SSH 连接并退出？”，取消继续，确认 finish 后由 onDestroy 清理；未连接直接 finish。使用既有 ownedDialog 管理窗口。系统返回／边缘手势仍保留 REORDER_TO_FRONT 后台路径。
- pinch 按帧修改字号并 refresh，复用当前 xterm、canvas／DOM renderer 与缓冲区。缩放状态阻止包括布局观察器在内的 fit；不在手势中 term.resize 或发布 PTY。全部手指抬起、取消或进入后台先应用最后待处理字号，再合并 fit；尺寸变化只同步一次 PTY。剩余单指隔离、选区、历史及其他触控路径保留。
- 更新需求、架构、验收及 JS 行为／Java 生命周期／接线测试。

## 验证

使用现有 Python 3.12、Node、JDK；未安装依赖。定向命令：

```sh
python3 -m unittest tools.test_terminal_web tools.test_terminal_bridge tools.test_terminal_lifecycle
python3 tools/test_terminal_wiring.py
python3 tools/test_terminal_command.py
```

最终结果：4 tests，OK，零失败／错误／跳过；接线 OK；命令／凭证合成测试 18/18 OK。JS PASS 包含实际 vendored xterm 缓冲区、逐帧字号与 refresh、缩放期间零网格／PTY resize、内容保留、结束一次 resize、未执行帧就抬手、取消及 DOM 回退；Java 生命周期测试执行生产 confirmExit 方法体，覆盖取消／确认／离线箭头及原后台保活路径。既有桥背压、历史命令、选择复制、滚动、缩放和解析测试通过。

首次新增 Java 弹窗测试因夹具从外部直接调用 private 方法而编译失败；改由夹具内箭头入口调用，生产方法可见性不变，复跑通过。限定本轮文件的 `git diff --check` 通过。

## 未验证项

按用户禁止构建的范围，未执行完整 tools/verify.py、Android／Robolectric、Lint 或 APK 构建，不能声明完整验收。JS DOM 几何、canvas 激活与 refresh 为替身，实际 parser／buffer 是 vendored xterm；不能据此证明真实 Android Chromium 每一像素帧无空白或帧率改善。需目标手机检查 canvas／DOM 缩放全过程可见、字号反馈、最终 PTY/TUI 重排、IME／选区坐标，以及箭头取消／确认与边缘返回保活差异。真实 SSH、网络、OEM 后台及模型服务均未验收。
