# 当前需求与行为契约

适用：V1.0.23 / versionCode 24。最后核对：2026-10-26。

本文件是当前产品行为的唯一需求清单。版本记录说明曾经发生什么，不覆盖此处。编号永久保留；修改行为时修改对应条目和验收，取消需求时写明取消原因，不把编号转用于别的需求。

## 用户与运行模型

用户在 Android 手机连接自己有权限使用的 SSH 主机，控制远端 Codex 对话。模型登录和推理发生在远端，Android 客户端负责协议、交互与文件传输。用户可以浏览历史，也可以显式接续取得写入权限；这些状态不等于 Codex 的沙盒只读权限。

## 需求与自动化对应关系

“自动化”列代表相关回归测试入口，不代表全部真实环境已经验收。具体限制见 [验收边界](ACCEPTANCE.md)。

| 编号 | 当前行为及失败边界 | 自动化入口 |
| --- | --- | --- |
| R-01 | 保存多个 SSH 连接；密码、加密私钥、Tailscale SSH 身份认证。凭据可选择加密保存；验证失败保留编辑内容，不误保存无效端口。 | [ProfileTest](../app/src/test/java/app/remotessh/ProfileTest.java)、[SshIntegrationTest](../app/src/test/java/app/remotessh/SshIntegrationTest.java) |
| R-02 | 首次连接由用户核对主机指纹；已信任主机密钥变化时拒绝连接。SSH 认证后以有界辅助通道检查非交互环境中的 Python 3.8／托管标准库（仅持久模式）、可直接识别的 Codex 程序／app-server、SFTP 和家目录；托管写入失败独立提示。自定义启动器不猜路径、不执行额外启动；握手后检查服务器公开的登录状态，不读取凭据、不调用模型。未知旧服务登录能力不伪造成功；环境通过不保证真实模型网络可用。Python 不可用时必须用户明确选择关闭持久模式后重连，不能静默降级；诊断区分认证、程序缺失和上游拒绝。 | [ConnectionPreflightTest](../app/src/test/java/app/remotessh/ConnectionPreflightTest.java)、[SshIntegrationTest](../app/src/test/java/app/remotessh/SshIntegrationTest.java)、[ConnectionFailureTest](../app/src/test/java/app/remotessh/ConnectionFailureTest.java) |
| R-03 | stdio JSON-RPC 双向通信，支持分片、并发请求和服务器请求；未知交互明确不支持，不能自动批准。自定义命令必须兼容协议。 | [RpcClientTest](../app/src/test/java/app/remotessh/RpcClientTest.java)、[InitializationTest](../app/src/test/java/app/remotessh/InitializationTest.java) |
| R-04 | 默认远端持久托管，SSH 断开后保留模型进程；重连恢复会话、输出和待审批，不重发旧 `turn/start`。主机／进程崩溃不保证继续执行。 | [托管测试](../tools/test_remote_session.py)、[BackgroundConnectionTest](../app/src/test/java/app/remotessh/BackgroundConnectionTest.java)、[FinalAuditTest](../app/src/test/java/app/remotessh/FinalAuditTest.java) |
| R-05 | 一条连接支持多个会话并行；输出、停止、审批、草稿、附件、权限和速度各归其所属会话。切换、迟到回调和恢复不得串会话。 | [MultiConversationTest](../app/src/test/java/app/remotessh/MultiConversationTest.java)、[ConversationStoreTest](../app/src/test/java/app/remotessh/ConversationStoreTest.java) |
| R-06 | 按当前服务器浏览项目和历史，目录浏览／创建沿用 SSH。项目路径选择默认对文件夹和文件混合按修改时间倒序，未知时间排末尾，同时间按名称排序，仍可切换排序。只查看历史不接续、不更新远端会话时间；恢复空闲历史不占用写入者。 | [RemoteDirectoryTest](../app/src/test/java/app/remotessh/RemoteDirectoryTest.java)、[RecoveryPerformanceTest](../app/src/test/java/app/remotessh/RecoveryPerformanceTest.java)、[MainActivityTest](../app/src/test/java/app/remotessh/MainActivityTest.java) |
| R-07 | 未接续历史可准备文字和上传附件；接续保留草稿，必须手动发送。发送失败保留可恢复内容，空消息不得无故创建任务。 | [QuestionRecoveryTest](../app/src/test/java/app/remotessh/QuestionRecoveryTest.java)、[AttachmentStateTest](../app/src/test/java/app/remotessh/AttachmentStateTest.java) |
| R-08 | 工作中可立即补充输入或排队下一条；每个会话最多一条待发消息，取消返还附件。仅实时成功完成才允许自动发送；重放、失败、只读和恢复期间不得误发送。 | [ConversationStateTest](../app/src/test/java/app/remotessh/ConversationStateTest.java)、[AttachmentStateTest](../app/src/test/java/app/remotessh/AttachmentStateTest.java)、[ResumeRegressionTest](../app/src/test/java/app/remotessh/ResumeRegressionTest.java) |
| R-09 | 命令／文件审批按服务器请求身份回复，重复或过期请求不再执行。同一对话前 30 秒（含边界）已有 3 次不同的实时命令／文件权限审批时，从第 4 次及后续符合条件的请求才提供红字“全部允许(强制)”；当前请求在到达时取得资格，阅读时不闪退按钮。重复身份、重放、只读历史、方案选择／补充信息不计数，跨会话不累计；断开清空计数。确认警告“新的审批权限生效前，客户端将会自动允许全部审批请求，继续吗？”后仅为请求所属对话自动允许当前及后续命令／文件权限审批；方案选择、补充信息和未知交互不自动回答。后台授权不改变选中对话，重放屏障内不发送审批；提交失败结束临时授权并保留手动请求。通知和气泡操作绑定服务器、会话与请求；锁屏操作遵守系统解锁要求。 | [ApprovalBurstTest](../app/src/test/java/app/remotessh/ApprovalBurstTest.java)、[ForcedApprovalTest](../app/src/test/java/app/remotessh/ForcedApprovalTest.java)、[ForcedApprovalUiTest](../app/src/test/java/app/remotessh/ForcedApprovalUiTest.java)、[LiveStatusTest](../app/src/test/java/app/remotessh/LiveStatusTest.java)、[BubbleTest](../app/src/test/java/app/remotessh/BubbleTest.java) |
| R-10 | 原生结构化提问显示可点击表单；兼容已接受异步提问的日志恢复、答案历史和同身份副本合并，包括附完整选项列表的提问副本。同轮次、请求身份和完整题目／选项均须匹配，额外回复、图片和不同请求不得隐藏。问答记录定位到原始提问调用／工具的位置；回答及同轮次后续输出不将旧卡片下移，多题按来源顺序显示，同轮次的未来同题不能取得较早答案。普通文字问句不伪造为服务器请求；失败可重试。 | [QuestionFormTest](../app/src/test/java/app/remotessh/QuestionFormTest.java)、[QuestionRecoveryTest](../app/src/test/java/app/remotessh/QuestionRecoveryTest.java)、[InteractionHistoryTest](../app/src/test/java/app/remotessh/InteractionHistoryTest.java)、[ReasoningStateTest](../app/src/test/java/app/remotessh/ReasoningStateTest.java) |
| R-11 | 模型分页动态发现、刷新失败保留上次目录；推理强度和速度按远端能力提供。沿用默认时不覆盖远端；选项失效明确处理，切换连接后的旧响应无效。 | [ModelCatalogTest](../app/src/test/java/app/remotessh/ModelCatalogTest.java)、[ModelRefreshTest](../app/src/test/java/app/remotessh/ModelRefreshTest.java)、[ServiceTierStateTest](../app/src/test/java/app/remotessh/ServiceTierStateTest.java) |
| R-12 | 权限可选沿用、按需审批、只读、完全访问，下一轮生效，保存会话选择并展示可确定的实际权限。权限面板下方仅显示“发送消息以更新权限”，不再显示当前远端／长说明。强制允许是独立的会话临时授权：仅在符合 R-09 的交互式审批中确认后启用，正常权限菜单隐藏该项、不能从菜单开启；启用时下一轮选择完全访问，用户仍可更改下一轮权限。临时授权生效期间底部显示与完全访问同形的红色闪电，权限菜单才显示红色闪电“强制允许”，点击可关闭；关闭或结束后再次隐藏。只有包含当前权限选择的下一轮启动成功才结束临时授权并清空审批频率窗口，失败、旧轮完成／重放和迟到响应不能误清除或误显示新权限生效。断开／换连接清除临时授权，不持久保存；只读历史不能启用。只读不是禁用所有工具或免费聊天。 | [ForcedApprovalTest](../app/src/test/java/app/remotessh/ForcedApprovalTest.java)、[ForcedApprovalUiTest](../app/src/test/java/app/remotessh/ForcedApprovalUiTest.java)、[PermissionModeTest](../app/src/test/java/app/remotessh/PermissionModeTest.java)、[QuestionRecoveryTest](../app/src/test/java/app/remotessh/QuestionRecoveryTest.java) |
| R-13 | 图片来源选择／系统回退、多选和取消；每条最多 4 张、每张 20 MiB。上传结果必须属于原连接／对话；中途失败保留成功项；支持格式按平台解码能力判断。 | [ImagePickerTest](../app/src/test/java/app/remotessh/ImagePickerTest.java)、[ImageUploadTest](../app/src/test/java/app/remotessh/ImageUploadTest.java)、[ImageFormatTest](../app/src/test/java/app/remotessh/ImageFormatTest.java) |
| R-14 | 普通文件每条最多 8 个、每个 100 MiB；流式原样上传，以远端路径引用；文件名不能越界。下载限制 100 MiB，失败不留下伪装成功的目标文件。 | [FilePickerTest](../app/src/test/java/app/remotessh/FilePickerTest.java)、[MediaTest](../app/src/test/java/app/remotessh/MediaTest.java)、[SshIntegrationTest](../app/src/test/java/app/remotessh/SshIntegrationTest.java) |
| R-15 | 消息／草稿图片共享原图预览，双指缩放、拖动、双击复位和保存；关闭／切换取消迟到结果。同服务器／会话重连后可见缩略图恢复加载，即使正在阅读旧消息；单次可见加载最多尝试 3 次，短暂失败以 500／1500ms 间隔重试，最终失败仍可点按重试；离屏取消任务及重试。阅读旧消息不被新输出强制拉回；正文复制保留原文。 | [MessageImageTileTest](../app/src/test/java/app/remotessh/MessageImageTileTest.java)、[ZoomImageViewTest](../app/src/test/java/app/remotessh/ZoomImageViewTest.java)、[MessageBodyViewTest](../app/src/test/java/app/remotessh/MessageBodyViewTest.java)、[MainActivityTest](../app/src/test/java/app/remotessh/MainActivityTest.java) |
| R-16 | 请求并显示远端公开的思考摘要，兼容空摘要和最终快照，不推测未公开内容。旧历史错误不冒充当前连接失败；标题不被空元数据覆盖。 | [ReasoningStateTest](../app/src/test/java/app/remotessh/ReasoningStateTest.java)、[ResumeRegressionTest](../app/src/test/java/app/remotessh/ResumeRegressionTest.java) |
| R-17 | 前台服务维持连接；每个活跃会话独立通知／气泡。普通状态无审批按钮，待答提供入口，完成可确认隐藏。系统决定流体云资格和数量。 | [LiveStatusTest](../app/src/test/java/app/remotessh/LiveStatusTest.java)、[MultiConversationTest](../app/src/test/java/app/remotessh/MultiConversationTest.java)、[BackgroundConnectionTest](../app/src/test/java/app/remotessh/BackgroundConnectionTest.java) |
| R-18 | 账户额度来自服务器；未知或不支持时显示原因，不伪造百分比；账户额度与上下文 token 区分。手动刷新实时重绘，失败或超时保留上次数据并允许重试，过期回调不得覆盖新请求。 | [UsageLimitsTest](../app/src/test/java/app/remotessh/UsageLimitsTest.java) |
| R-19 | 远端版本检查分清运行版本与磁盘安装版本；更新和重启需明确用户操作，检查失败不宣称最新，不自动重启活跃任务。 | [RemoteVersionTest](../app/src/test/java/app/remotessh/RemoteVersionTest.java)、[维护脚本测试](../tools/test_remote_maintenance.py)、[MainActivityTest](../app/src/test/java/app/remotessh/MainActivityTest.java) |
| R-20 | 对话操作从右侧面板“更多”进入；保留 1.0.47 导航操作：远程控制菜单为添加连接、编辑当前连接、刷新项目与对话、设置；对话菜单为新对话、最近一次错误、当前项目与对话。主题／字体／后台设置可用；Activity 销毁释放所拥有的模型监听器。 | [MainActivityTest](../app/src/test/java/app/remotessh/MainActivityTest.java)、[DialogStyleTest](../app/src/test/java/app/remotessh/DialogStyleTest.java)、[DialogLifecycleTest](../app/src/test/java/app/remotessh/DialogLifecycleTest.java) |

## 本轮 CLI 与工作面板增量

| 编号 | 当前行为及失败边界 | 自动化入口 |
| --- | --- | --- |
| R-21 | 持久模式增加 Claude Code stream-json 与 DSH 0.2.0-rc.2 headless CLI Host；编辑命令以 argv 解析，prompt 经 stdin。适配器原子保存本地历史并续接其记录的原生 session；运行中拒绝删除/重启。删除仅删除适配器记录。CLI 缺失、权限拒绝、未知送达、凭据未确认均不冒充成功；DSH 无无模型账户检查，账户状态明确未知。手机不能回答 CLI 审批，Claude manual/none 与 DSH 无回答者采用拒绝路径，不提供手机完全访问选项。不承诺任意版本/自定义插件配置兼容。 | [CLI fixtures](../tools/test_cli_host.py) |
| R-22 | remote/session 增量公开 runtime capabilities；旧 Codex 无字段时保留行为；CLI 模型目录/额度/附件/手机审批不伪造，禁用或阻止对应操作并说明。CLI 恢复使用锁内权威快照，重复 replay 不重放文本；epoch/seq 为 CLI 元数据，尚非跨 runtime 客户端 cursor/gap 协议。 | [RuntimeCapabilitiesTest](../app/src/test/java/app/remotessh/RuntimeCapabilitiesTest.java)、[CLI fixtures](../tools/test_cli_host.py) |
| R-23 | 右侧工作面板展示选中会话的进度、只读变更、待审批；工具摘要默认折叠，授权仍走原审批窗口且不新增授权提交入口；更多保留原对话菜单。远程控制显示 runtime 与可确定的运行/审批/空闲状态，未知不推断；提示同目录 Agent 冲突。非 floating 导航页保留安全区/IME。无真机视觉验收。 | [MainActivityTest](../app/src/test/java/app/remotessh/MainActivityTest.java)、[DialogLifecycleTest](../app/src/test/java/app/remotessh/DialogLifecycleTest.java) |

## 当前不承诺的能力

- 零 token／免扣 Codex 额度的聊天开关，以及技术上彻底禁用所有工具的独立聊天后端。
- 语音、远端文件内容编辑器、全量历史服务器搜索、应用内远端 Codex 登录。
- 任意 CLI/TUI 自动适配；仅提供明确列出的 CLI 适配器，其他后端仍需兼容 JSON-RPC。
- 手机重启／系统强杀后无条件后台存活，主机重启后任务无损继续，跨多控制端同时写同一会话。
- 所有厂商系统均显示同样的流体云、所有图像格式均可解码、完整复刻官方 ChatGPT 的界面或动画。

新能力先补需求编号、行为／失败场景和验收方式，再实施；研究中提到的方案不自动变为已接受需求。

## SSH 终端增量（2026-10-28）

凭证索引增量：新保存的 SSH 终端密码对应的规范化 `user@host:port` 写入明文索引，密码仍加密保存；删除凭证时移除索引项。索引损坏时视为空；旧版哈希文件无法反查，重新保存前不进入索引。

主机菜单修订（2026-10-28）：SSH 认证成功后持久保存最近 20 个去重主机键，最新在前，不依赖是否保存密码；一次性命令同样记录。菜单合并主机历史与已存凭证索引，补充并标记当前连接，最后一项为退出；连接时退出需确认。选择其他主机先断开旧连接并保留文字快照，已存凭证直接连接，无凭证时提示输入密码及可选保存；取消或连接失败后旧连接已断开。/forget 同时删除凭证和历史。历史文件损坏或写入失败时不阻止连接；旧版不可枚举的凭证需再次成功登录或保存后出现。系统边缘返回保持后台连接。

| 编号 | 当前行为及失败边界 | 自动化入口 |
| --- | --- | --- |
| R-24 | SFTP 菜单下的独立 SSH 终端使用黑色系统导航栏，保留系统安全区和输入法调整，使用本地 MIT xterm.js 5.5.0，黑底区域直接输入；两排键为 ESC / - HOME ↑ END PGUP 与 TAB CTRL ALT ← ↓ → PGDN。只在手动输入 ssh user@host 后连接，复用加密密码及严格 host pin 确认／变更拒绝。原始字节经 base64 交给 xterm 增量解析 UTF8／ANSI，输入实时写入 shell，布局变化设置 PTY。WebView 只供应固定 assets 白名单、拒绝导航／网络／文件访问，远端输出不可成为 HTML 或脚本；复制为显式操作。断开／销毁取消旧连接与对话框；新终端实例隔离跨连接的半个 UTF8／控制序列。后台渲染不消费输出时保留单个 8KiB 分片背压等待，不以渲染超时关闭 SSH；不保证系统强杀后恢复。 | [终端页面行为](../tools/test_terminal_web.py)、[Java 桥行为](../tools/test_terminal_bridge.py)、[命令及凭证](../tools/test_terminal_command.py)、[接线回归](../tools/test_terminal_wiring.py) |

R-24 触控补充（2026-10-28）：快捷栏默认隐藏，仅 IME 可见时展示；普通滑动收起 IME，保留 xterm 原生滚动和远端鼠标模式。静止长按进入本地历史选区，可拖动范围及两端手柄，拖选／手柄调整完成后自动复制到 Android 剪贴板，并以底部短 Toast 提示“选择已复制”，不显示复制／结束操作框；随后单击终端任意位置仅释放选区，吞掉该次远端鼠标／焦点事件，不立即打开键盘。逐行高亮排除行尾空白，复制排除未写入的填充单元并保留实际空格、缩进、硬换行；软换行不新增换行，宽字符边界向外对齐。缓冲区写入或 resize 时安全释放旧选区。紧凑 13px 系统等宽字体栈，整个终端网格横向缩放至 90%（包含文字、光标及坐标映射），不使用负 letter-spacing；仍按 xterm 字符单元处理宽字符；具体字体和中英文视觉对齐需手机验收。尺寸按帧合并、无变化不重复 resize；保留 3000 行回滚和增量输出，不在滚动时重建历史。

R-24 手势修复（2026-10-28，V1.2.26 反馈）：本地单指历史滚动由页面按帧累计像素并调用 xterm scrollLines，避免与 viewport 的触摸处理重复竞争；键盘可见时仅在滑动开始请求收起一次。远端鼠标模式保留 xterm 单指事件路径。本地触摸仅确认短点击后 focus，长按、拖选、调整、复制和撤选不请求输入法；输出使选区失效也不得将该手势变成输入点击。双指按距离连续缩放字体，范围 8–28px、精度 0.1px；手势期间仅以 CSS 缩放预览，缩放全部抬起后一次应用字号及网格 fit／PTY 去重，使用同一 xterm 字体及网格几何；缩放释放选区，剩余单指直到全部抬起前不能滚动或选择。字号写入本地 WebView origin 的 localStorage，下次启动时恢复。


R-24 V1.2.27 反馈修复（2026-10-28）：普通单指点击允许 16px 内抖动；缩放全部抬起后重置手势来源，下一次点击不依赖合成 click。无选区／缩放／滚动时确认点击须 focus xterm 并经受 ready/closed 保护的原生桥请求 WebView 焦点与 IME；撤选仍仅释放选区，再次点击恢复输入。启动本地文字逐行显示“支持 -p 端口”“/help 命令帮助”“/saved 已存凭证”“/forget user@host 删除凭证”“输入 ssh user@host 连接”“-------------------------”，紧跟空输入光标行，不混为远端输出。

R-24 内存命令历史：页面生命周期内记录回车发送的本地命令与可可靠跟踪的 shell 输入，非空命令按完整文本去重，最新在前，最多 100 条；连接实例重建保留、Activity/WebView 销毁丢弃，不新增存储键。快捷栏 ↑ 循环向旧命令、↓ 向新命令并回到原草稿（可为空），输入内容即为可见反馈。未连接时可直接调出历史；已连接时仅输入行有文字或正在浏览本地历史才拦截，否则方向键透传远端 readline；CTRL/ALT 修饰不拦截，应用光标模式保持原序列。连接时使用退格及替换文字更新远端输入行；光标移动、Tab 或未知控制编辑后无法推断远端行内容，保守透传直至回车／Ctrl-C／Ctrl-U，避免猜测并删除未知远端内容。远端 readline 自行调出的命令不复制进本地历史，不承诺任意 TUI 的输入行语义。

R-24 V1.2.28 真机反馈修订（2026-10-28）：启动文字去掉重复“SSH终端”首行，其余顺序／换行不变。顶栏移除“断开”；左上角箭头已连接时弹出“断开 SSH 连接并退出？”确认框，取消继续使用，确认 finish；未连接时直接 finish，onDestroy 释放 SSH／PTY／WebView。系统返回（包括边缘返回手势）收起 IME／快捷栏并用同 task 的 REORDER_TO_FRONT 提前 MainActivity，不 finish；菜单入口同样 REORDER_TO_FRONT 复用终端实例。后台／恢复不重建 WebView 或解析器，保留字号、历史、缓冲区、PTY 和连接；暂停／停止不关闭传输，恢复只 fit／refresh。输出回执没有断开看门狗，保留单个分片背压；输入队列满提示未发送而不主动断开。连接／通道建立超时仅限握手，server-alive 只检测网络失联，远端 EOF／关闭仍结束连接；渲染进程崩溃属于页面不可恢复错误，结束 Activity 并在 onDestroy 清理。目标为本地固定版 MIT canvas addon，加载／激活失败回退新 DOM 实例，开启终端 Activity 硬件加速；目前仅接线完成，vendor 原始制品未能下载，性能修复未完成（见本轮验证记录）。

R-24 V1.2.29 反馈修复（2026-10-28）：pinch 期间按帧修改字号并请求完整可见行重绘，复用现有 canvas／DOM renderer 和 buffer，不 dispose／重建、不执行 term.resize 或 PTY resize；ResizeObserver／IME 布局引起的 fit 同样推迟。全部手指抬起、touchcancel 或进入后台时应用最后待处理字号，合并一次 fit，尺寸变化时只回传一次 PTY；剩余单指仍隔离。箭头退出确认与系统边缘返回主界面保活为独立路径。

独立工作区预检（2026-10-28）：先显示 Git 根目录、脏文件数量、进行中操作及支持状态；只有预检通过才提供创建按钮，创建时仍重新核验。

2026-10-28 界面调整：SSH 终端移除底部复制说明条，终端及 IME 快捷栏使用可用底部空间；长按选择自动复制仍保留。独立工作区移除顶部关闭按钮，内容顶部下拉刷新状态并显示进度；仅状态获取失败时底部显示重试，成功后隐藏。创建和合并流程保持原有确认。
