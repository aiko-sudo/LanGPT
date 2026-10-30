# 架构、职责与状态归属

当前链路：原生 Android 界面 → 应用协调层 → SSH 上的 JSON-RPC → 可选 Python 持久托管 → 远端 Codex App Server。

## 模块边界

| 模块 | 入口及职责 |
| --- | --- |
| 界面生命周期 | `MainActivity` 管理页面、消息渲染、系统选择器和 Activity 回调；`BubbleActivity` 管理绑定会话的气泡页面 |
| 独立窗口 | `ProfileEditor` 管理连接草稿／凭据表单／私钥导入；`ModelPicker` 管理模型／强度／速度选择及监听器；`DialogUi` 保持聊天窗口的原有样式与触摸行为 |
| 导航／展示组件 | `RemoteBrowser`、`DirectoryPicker`、`RemoteScreens`；消息、问答、图像预览、缩略图和滚动各有独立组件 |
| 应用协调 | `RemoteApp` 维持连接、初始化、恢复、事件路由和当前选中状态的兼容接口；它不再实现窗口表单、完整发送流程或附件传输流程 |
| 会话容器 | `ConversationStore` 是会话缓存和临时背景分发的唯一所有者；`ConversationState` 保存每条会话的消息、草稿、权限／速度和运行状态 |
| 轮次／队列 | `TurnController` 管理创建／发送／引导轮次、一条待发消息及其附件、可自动发送标记；请求仍走统一回调路由 |
| 附件 | `AttachmentController` 管理批量上传、媒体读取、连接变化取消和主线程结果提交；`MediaFiles`／`FileAttachment` 定义格式、路径与限额 |
| 消息图片 | `MessageImageTile` 拥有可见缩略图、读取任务与有界重试；同服务器／会话的连接 generation 变化时释放旧任务并重新绑定，主线程核对作用域后才显示结果。消息区暂停更新仍刷新图片连接状态；`ThumbnailLoader`／`ThumbnailCache` 分别提供任务调度和共享有界缓存 |
| 目录／会话偏好 | `DirectoryController` 管理目录操作的 worker 和迟到结果；`ConversationPreferences` 管理稳定存储键及权限／速度／问答历史快照 |
| 传输／协议 | `SshTransport` 管理 SSH、SFTP 和辅助通道；`RpcClient` 管理 JSON-RPC framing、请求匹配和事件分发，不操作 Android View |
| 连接诊断 | `ConnectionPreflight` 生成无凭据输出的环境探测并解析分类失败、检查公开账户状态；`ConnectionSetupUi` 拥有用户显式关闭持久模式的确认窗口。探测有超时／输出上限，关闭或连接 generation 变化使后续初始化无效 |
| 强制权限审批 | `ForcedApprovalController` 调度已显式授权的命令／文件审批，按会话和连接 generation 隔离、去重和失败恢复；State 内的 Burst 用单调时钟记录实时唯一权限请求、保留最近 3 个时间点，并在第 4 个到达时判断 30 秒窗口资格。授权与窗口均由现有 `ConversationState.capture/apply` 捕获该 State，不保存到偏好；重放不计数，成功应用新权限结束授权时清空窗口。`PermissionPicker` 拥有权限列表／警告窗口并释放监听器，只在已确认启用期间显示强制允许项；下一轮请求捕获权限选择与授权 revision，成功响应只能结束它实际应用的授权 |
| 配置／数据 | `Vault`／`Profile` 保存连接；模型、额度、权限、版本、线程快照与问答日志由对应类解析 |
| 后台／通知 | `ConnectionService` 维持前台服务与唤醒锁；`LiveStatusNotifications` 及 `LiveActionReceiver` 处理会话通知、请求身份和点击路由 |
| 远端资产 | `remote_session.py` 托管 Codex 进程、SQLite 事件及重放；`conversation_state.py` 有界读取问答日志；`remote_maintenance.py` 检查／更新／明确重启 |

所有主 Java 文件按职责登记在 [模块登记](../tools/project_structure.json)。添加新类时登记所属模块，不靠一个平铺目录猜归属。当前保留包名与目录，避免纯目录搬迁造成大范围引用／测试变更。

`DialogUi` 和 `RemoteScreens` 分别保存聊天窗口与导航页面的现有视觉风格；本轮没有强行统一两套颜色或按钮尺寸。

## 三种状态生命周期

| 生命周期 | 示例 | 约束 |
| --- | --- | --- |
| 连接级 | SSH/RPC、连接 generation、登录初始化、恢复队列、全局模型目录 | 换连接使旧异步结果无效；不可保存为某条会话的状态 |
| 会话级 | threadId/turnId、消息、草稿、审批、权限／速度选择、busy/readOnly、问答历史 | 由 `ConversationState` 和 `ConversationStore` 隔离；后台结果按目标 threadId 分发 |
| 界面级 | View、Dialog、滚动位置、选择器待处理标识、可见缩略图 | Activity 拥有并清理；不能让旋转重启远端，也不能把销毁窗口的监听器留下 |

附件上传批次目前属于连接上的串行操作，上传／创建／恢复中的短暂切换限制是现有行为；不能简单移除这些限制。

## 会话分发与兼容接口

现有 UI 和通知读取 `RemoteApp` 的选中会话字段。为保持行为，本轮保留该兼容接口，`ConversationState.capture/apply` 做显式快照；**它尚未变成完全无副本的状态模型**。

`ConversationStore.dispatch` 按以下顺序运行：保存选中会话 → 应用目标会话 → 执行动作 → 在 finally 中保存目标并恢复选中会话 → 最外层结束后发布合并的变化。嵌套动作／异常也恢复选择、项目和最后会话。缓存的读写及作用域切换必须在主线程；后台分发期间不得让通知或 UI 看见临时选择。

新增会话字段须同时更新 `ConversationState` 的声明、capture/apply，并验证 A/B 隔离、异常恢复和重连。不要增加第二套手写切换代码。直接按状态对象工作的下一阶段迁移见 [技术债](MAINTENANCE.md)。

## 异步和协议规则

- SSH／文件 I/O 在 worker 执行；结果回到主线程。检查 generation，以及必要的传输实例、配置 revision、服务器／会话身份，迟到结果无效。
- `RemoteApp.request` 捕获发起请求的会话，完成时通过 `inConversation` 路由。独立控制器必须复用该入口，不能自行给 RPC 回调更新当前页面。
- 浏览用 `thread/read`，接续用 `thread/resume`；恢复活动会话与只读历史有不同路径。重放是同步屏障，不能当作实时完成来排队发送。
- ThreadStart 的沙盒使用配置枚举，TurnStart 使用 tagged policy；不要互换格式或随意添加未知协议字段。
- `TurnController` 的排队键包含连接配置 ID 和 threadId。断开清除自动发送许可，保留现有进程内待发内容；队列不保证手机进程被杀后的持久恢复。
- Python 桥接会记录恢复基线；新增客户端字段须同时检查透传、基线和旧托管兼容。资产更新不会自动替换正在运行的旧进程。
- 问答日志兼容只处理可确认身份的已接受请求；不从普通文字猜审批或表单，不输出未公开推理。
- `InteractionHistory` 按调用／工具 ID 在原轮次中定位卡片，回答只改变呈现状态；同轮次多题按源消息位置匹配答案。缺失来源时按消息 ID 保留进程内已观察位置，冷恢复缺来源则限于原轮次的第一条最终回复／轮次末尾，不挂到最新任务。位置缓存属于已有会话级 InteractionHistory 实例，不增加持久化字段或修改远端历史。
- 完成快照的消息须保留对应 turnId；缺少轮次字段的晚到 item/completed 优先沿用原消息归属，不能借用新的活动轮次。

## 持久化与安全

公开 APK 分发由独立 `publish-release.yml` 与 `tools/publish_release.py` 承担。维护者使用临时注释标签提供制品 URL／摘要，源码不记录个人上传地址；流程校验安装包、许可、版本标签及对应完整 CI，先上传草稿附件再发布。只在显式发布任务中授予仓库写权限，不上传签名私钥，不重建不同签名的 APK；常规 Verify 仍为只读。

保留已有 Vault 加密格式、SharedPreferences 名称／键、包名和签名。权限／速度键对可能含私人值的启动身份做哈希；不得把命令或凭据直接打印到日志。重构数据格式必须有旧数据迁移和可逆性说明。

远端托管脚本按内容哈希安装到 `~/.remote-gpt`，目录 0700、脚本 0600。生产 SSH、主机信任、上游登录和活跃任务不属于自动化夹具。此轮不更改资产协议或部署到真实远端。

## 2026-10-26 CLI 与面板增量

`cli_host.py` 定义独立 CliHost，`cli_events.py` 投影公开消息与工具进度，Android 将其与 remote_session.py 合并为一个按现有哈希安装的资产，不增加远端目录依赖。daemon 工厂按 runtime 选择 Codex/OpenCode/CliHost。CLI 只以 argv + stdin 启动，stderr 私有有界采集，不写原始 CLI 错误到日志。cli-history.json 以临时文件 fsync + replace 原子发布，threadId 只作字典键，不用于拼接文件路径。重启后的 inProgress 标为送达未知，不自动重启任务。活跃 CLI 进程归 daemon 管理，手机断开只解绑 peer。

CLI 恢复在同一锁下发送完整当前快照；remote/replay 不叠加旧 delta。这是小闭环恢复方案，epoch/seq 尚未形成客户端 cursor/gap 协商，不保证原生 exactly-once。DSH 仅接续本适配器记录的 session，启动时 DSH_PERMISSION_MODE=workspace-write；shipped headless 无审批回答者，approval service 将无回答者归为 unavailable。Claude 使用 manual + permission-prompts none。PC 自定义插件/版本配置仍需单独验证。

`RuntimeCapabilities` 为连接级握手状态，重连重置；旧服务兼容。`WorkPanel` 只投影现有 items、fileChange/diff 与 approvals，不拥有第二份数据，不发送授权；Dialog 关闭及 Activity 销毁解除监听。新增 UI 类已登记，既有超预算文件仍作为未完成结构验收项记录，未扩大预算。

## 本地 SSH 终端（2026-10-28）

`SshTerminalActivity` 拥有独立 JSch 连接、密码／指纹窗口及 generation，不读取或切换对话缓存。连接 reader 与有界串行 input writer 均不操作 View；UI 发布核对 generation，断开关闭通道／Session，销毁释放窗口、线程池和 WebView。

`TerminalWebView` 只从 assets 提供虚拟 HTTPS origin `https://terminal.invalid/` 的固定白名单资源（包含可选 vendor/addon-canvas.js），所有其他请求返回拒绝响应，禁止导航、外网、file/content 访问与子窗口。CSP 禁止 frame/object/connect/form；桥只在精确本地页面 ready 后接收输入／尺寸，页面异常或销毁时失效。仅可信函数名与 JSON 引用的文本／base64 进入 evaluateJavascript，远端字节永不拼接为程序或 HTML。输出最多一个 8 KiB 分片等待带序号的 xterm write 回执；过期 generation 和回执无效，后台回执延迟不结束连接，按 250ms 检查 closed／generation；只允许一个分片在途，不累计无界队列。

`assets/terminal/index.html` 与 `terminal.js` 管理 xterm、IME 输入和快捷键，保留 vendor 的 MIT LICENSE／NOTICE；不加载 CDN，不改第三方库。尺寸依据实际渲染网格计算并回传 PTY。xterm 5.5 的 reset 不清除增量 UTF8 decoder，因此连接边界重建实例；断开保留过滤控制字符后的文字快照，防止未完成 OSC／CSI／UTF8 消费本地输入或污染下一连接。此终端不替代现有应用 RPC／SFTP 连接。

终端触控补充（2026-10-28）：Activity 通过 Android IME insets（旧系统使用可见窗口高度阈值）向页面发布键盘可见状态；受 ready/closed 约束的桥回调只请求收起输入法或复制有界选区文本。页面以 xterm 公共 select 和 buffer/getCell 坐标实现长按、拖选及手柄；仅为可见选中行画高亮矩形，不把终端历史转成 DOM。高亮逐行去掉尾部空白，复制按真实单元读取并保留空格与硬换行、合并软换行；宽字符两端向外对齐。结束手势调用既有 copy 桥，Activity 写剪贴板并显示底部短 Toast。再次触摸终端拦截事件并撤选，避免远端点击和 IME 重开；写入／resize 释放旧选区。CSS 将整个 xterm 网格横向缩放到 90%，fit 与手势均读取变换后的网格矩形，不更改字体或 letterSpacing。普通触摸仍交给 xterm；重新创建终端前释放页面触摸监听器与长按计时器。ResizeObserver 仅调度一次 animation frame，网格及 PTY 尺寸不变时不重复提交。

2026-10-28 手势修复：terminal.js 在 capture 阶段拥有本地触摸滚动，累计位移并每帧一次 scrollLines，避免 xterm viewport 再消费同一 touchmove；远端鼠标模式单指事件继续交给 xterm。触摸焦点延迟至本地短点击确认；长按及连接实例重建不主动 focus。双指状态优先于选择／滚动，按帧应用最后字号并 refresh，全部抬起后再使用实际变换后的 xterm 网格 fit；字形、光标和坐标均来自同一网格。重建通过 alive 标记废弃旧实例排队的帧；传输仍沿用 8KiB write/ack 背压和增量解析，不在滚动时重放输出或刷新全历史。


2026-10-28 V1.2.27 反馈修复：terminal.js 拥有一次性的本地启动文案、页面内 100 条去重历史、历史索引／草稿及保守的 shell 输入影子；终端解析器重建只重置输入跟踪与索引，不清除历史。历史不进入 ConversationStore、Vault 或偏好，也不跨 Activity 保存。普通点击通过 focusInput 先 focus xterm，再调用 TerminalWebView.showKeyboard 桥；桥在主线程核对 ready/closed，Activity 请求 WebView 焦点和 showSoftInput。长按／缩放／撤选不走此路径。16px 内移动保留点击候选，缩放最后一指抬起清除旧 origin/moved；选区及滚动仍拥有独立的事件抑制路径。

2026-10-28 V1.2.28 修订：SshTerminalActivity 的菜单 open 和系统返回使用 FLAG_ACTIVITY_REORDER_TO_FRONT，在同一 task 内切换现有终端／MainActivity；不使用 singleTask，避免清除其他 Activity。终端 manifest 关闭 predictive back 新回调，让系统手势走已覆盖的 onBackPressed；顶栏箭头独立绑定 confirmExit，已连接时确认后 finish，未连接时直接 finish。onStop 仅隐藏输入法／快捷栏、清焦点和取消触摸状态；onResume 调用 terminalResume 做 fit／refresh，不 loadUrl／terminalMode。onDestroy 负责完整传输与渲染资源释放；写入网络异常可取消连接，远端 EOF／通道关闭由读取线程 finally 清理；队列满不主动断开。桥不调用 WebView.onPause/pauseTimers，不增加新的渲染超时。布局／旋转由已有 configChanges 保持实例。

2026-10-28 主机菜单修订：顶栏箭头改用 CenteredMenu，凭证索引和连接成功后的规范化主机键构造菜单；目标凭证读取成功后复用 disconnectAll 和 startConnect，generation 废弃旧回调，终端模式切换保留文字快照。退出确认及系统返回行为继续沿用既有生命周期。

Canvas 由 terminal.js 在 open 后 loadAddon，激活失败释放部分 addon 并重新创建 DOM 终端，再注册输入／手势；每次连接边界重建也走同一路径。自定义逐行选区同时隐藏 DOM 与 canvas 原生选择层。Activity 显式 hardwareAccelerated=true，未设置软件层。固定 vendor 路径只作可选脚本；制品下载受环境阻塞，目前缺少 addon 文件，实际运行仍为 DOM。不得把替身的成功激活测试表述为真实 canvas 已启用。

2026-10-28 V1.2.29 修订：页面 zooming 阻止所有 fit 入口在 pinch 中 reflow／发布 PTY；字号直接交给现有 renderer 的 options 处理并 refresh，不重新加载 CanvasAddon。结束／取消／后台中断时先消费待处理字号，再 scheduleFit；旧实例的帧仍受 alive 保护。退出确认复用 Activity 的 ownedDialog，onDestroy／断线沿用既有窗口清理。

2026-10-28 主机历史：HostHistory 使用应用私有的独立 JSON 文件原子记录最多 20 个规范化主机键；SSH 认证成功后记录，菜单按最近历史合并旧凭证索引，缺少凭证时复用密码对话框。/forget 同步移除历史与凭证；Activity 销毁不清历史。历史写入失败为尽力而为，不阻断连接。

2026-10-28：用量请求的 attempt 在成功、失败、超时和断开时失效；通用请求错误分支不再改写该状态，弹窗关闭回调只释放自身引用。项目路径排序复用 RemoteFiles 的专用比较器，时间缺失排末尾；终端仅设置系统导航栏颜色／对比，保留既有 insets 和 IME 布局。

2026-10-29：`SandboxSettings` 管理应用名单与网络选项（经 Go 绑定桥）；`TsnetVpnService` 串行管理 TUN 与生命周期，排除自身应用。
