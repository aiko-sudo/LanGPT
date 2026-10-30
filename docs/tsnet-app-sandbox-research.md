# LanGPT：跨应用 tsnet 沙箱研究

## 状态与边界

仅研究，未改 V1.3.6 运行代码、手机 VPN 或 NAS。当前 LanGPT 以同进程 tsnet 流服务自身，正式版 IPv6 直连已获用户实测；用户希望选择若干第三方 App 使用同一网络，不允许其余 App 借用。已有 P0c 暴露回环端口的实验中第三方错误认证请求可使单次监听关闭，不能复用为共享入口。

## 结论

Android VpnService 的 `addAllowedApplication` 可做透明选择性接入；它不是目标访问权限控制，也不会把未选择 App 的普通互联网/局域网访问关掉。优先隔离原型验证 TUN 入站→包处理→现有 tsnet 节点；确认接入与隔离均可行后再考虑正式集成。按同一身份走 tsnet，远端 Tailscale ACL 无法天然区分使用该节点的各手机 App。

当前尚不能承诺同时做到：无需系统 VPN、普通未改造的任意第三方 App 无感透明使用、且只让指定 App 进出；这三项需要不同机制，不能把应用自身配置 SOCKS 当成透明白名单。

## 关键系统事实和安全约束

- Android 官方文档：`addAllowedApplication` 至少成功添加一个应用后才启用只允许名单；如果从未成功调用（包括名单空/所有包消失/添加失败后仍建立）则默认所有应用走 VPN。实现必须先解析安装包与调用成功计数，成功数为 0 禁止 `establish()`。不要同时调用 disallowed；用户变更名单须重建 VPN，并处理短暂中断。
- 包名不等于不可变身份。确认包确实已安装，持久记录包名及签名证书指纹（实现需考虑合法签名轮换、卸载重装），包变化失配时暂停该授权而不是静默继承；共享 UID、工作资料、分身、系统服务代理和 Android 版本差异需要真机覆盖，不能仅凭包名显示宣称隔离通过。工作资料/用户 VPN 各自独立，不跨 Android 用户扩大权限。
- VPN 服务应保护声明 `BIND_VPN_SERVICE`，外部组件/广播不暴露可任意开启、改名单的控制面；首次执行 `VpnService.prepare()` 得到用户明确同意。要保护隧道自身的公网/控制面套接字：排除 VPN 自身进允许名单或使用 `VpnService.protect()`，检查调用返回值、UDP/IPv4/IPv6 都覆盖，防 TUN→节点→TUN 环路；两种方式在实现中测真实效果。
- 选中 App 的目标路由仅按 Tailscale IPv4/IPv6 前缀与用户明确批准的子网建立；默认不启 `0.0.0.0/0` 或 `::/0`，不自行发布或批准 NAS 子网。现有路由冲突和与本地同网段优先级必须明确显示并实测，不因局域网与远端 CIDR 同名自动错路由。
- 路由不在隧道内的互联网流量仍走普通网络。用户要求“所选 App 全部强制加密/断网不泄漏”是另一种需求，不能由上述 split 模式承诺。VPN 撤销、服务终止或系统杀进程后 Android 可能恢复底层网络；默认分流并不等于 kill switch。Android 的 always-on/lockdown 是用户设置、可能影响未选 App 和网络，原型不自动打开，需按用户需求另行设计及实测。
- 不调用 `allowBypass()`；但仍要测试系统组件、App 自带 DNS/DoH、受控 DNS、Private DNS、IPv4/IPv6、路由切换，不能声称所有泄漏已排除。仅 IP 阶段不承诺 MagicDNS、UDP、ICMP、QUIC 通用支持，DNS 方案必须另测。
- Android 常规每用户仅一个活动 VPN；占用 VPN 可与其他 VPN 冲突，新 VPN 可能替换旧服务。界面先告知并需用户明确点选；已排除的应用可能仍检测到设备有 VPN，兼容性需实测。纯 LanGPT 进程内模式保持不占用 VPN。
- 使用前应有明确停止按钮，撤销时关闭现有会话，清理服务、fds、后台流，记录非敏感统计；`onRevoke` 不保证主线程执行。默认不记录域名、IP、流量内容、证书或凭据。

## 具体可选实现

| 路线 | 机制 | 能否复用当前节点 | 关键问题 | 结论 |
|---|---|---|---|---|
| A. Android TUN→直接用户态转发→同一 tsnet | VpnService whitelist + TUN 报文拆 TCP/UDP + 调现有 tsnet | 原则上有机会；本地 `tsnet.Server.Dial` 接受网络参数，当前 LanGPT gomobile `Dial` 包装却固定 TCP | TCP 状态机/UDP 会话、MTU、DNS、资源释放及用户态栈与 tsnet 的适配均未验证；不能把 tsnet Dial 说成 IP 包接口 | 首选探索，不加公开本地代理；必须先做独立 PoC |
| B. TUN→hev→私有 SOCKS→tsnet | Android TUN 交给 hev-socks5-tunnel，再走 SOCKS 到 tsnet | 理论可扩展当前 Go 封装 | hev 常规配置是 IP:port SOCKS，回环端口可被第三方访问；若改为私有 FD/Unix 域或有效的权限隔离、认证及抗 DoS，需源码级改造和测试，现阶段未验证；UDP ASSOCIATE / DNS 不能只凭 README 认定可用 | 可做实验对照，但安全未闭合前不能并入 |
| C. 原生 tailscaled + TUN | 仿照官方客户端或 TailSocks 原生模式，使用真实 VPN TUN fd | 非直接复用；可能引入独立 daemon/新节点，或需要深改现有 tsnet | 不同进程/版本/登录状态与路由配置、fd 生命周期和节点互踢；破坏 V1.3.6 已验证链的风险较高 | 可作为后备技术验证，不应立刻替换当前核心 |
| D. 供已适配 App 连接受控 IPC | 服务端校验 Binder 调用 UID/包签名，应用配套集成 | 可以在已有进程内实现 | 普通未改造 App 不能透明接入；API 并非系统 VPN | 符合定制 App，非通用主路线 |

### 上游证据与冲突纠正

- [Android VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder) 与 [VPN 开发指南](https://developer.android.com/develop/connectivity/vpn)：白名单、空名单默认行为、授权、单 VPN、protect 与 onRevoke。
- [Tailscale Android 分流](https://tailscale.com/docs/features/client/android-app-split-tunneling)：官方普通设置默认按排除列表，正向 included list 的官方现成配置需要 MDM；这不限制我们在自己的 VpnService 里用 Android 的 addAllowedApplication。
- [TailSocks 仓库](https://github.com/bropines/tailsocks) 是第三方而非官方客户端。核对 tree `4cf00e58fbbb40d2ca9f4cda6a9d0a0189ab3a30` 的 [TunVpnService.kt](https://github.com/bropines/tailsocks/blob/4cf00e58fbbb40d2ca9f4cda6a9d0a0189ab3a30/app/src/main/java/io/github/bropines/tailscaled/core/TunVpnService.kt) 和 [TunRoutes.kt](https://github.com/bropines/tailsocks/blob/4cf00e58fbbb40d2ca9f4cda6a9d0a0189ab3a30/app/src/main/java/io/github/bropines/tailscaled/core/TunRoutes.kt)：代码声明默认是 native TUN，hev 为兼容路径，且进程为独立 tailscaled；其 CONTRIBUTING 中“始终 hev”的说法与代码和设计文档冲突，以已固定版本代码为准。代码自述不是我们的实测。
- [hev-socks5-tunnel LICENSE](https://github.com/heiher/hev-socks5-tunnel/blob/0166c4765e9e42eee7d18923026f88fda91bd9fa/LICENSE) 为 MIT；[TailSocks LICENSE](https://github.com/bropines/tailsocks/blob/4cf00e58fbbb40d2ca9f4cda6a9d0a0189ab3a30/LICENSE) 为 BSD-3-Clause。真正采用前必须逐项审计子模块、静态依赖、NOTICE 与商标；许可初查不等于整个依赖链合规。禁止下载第三方 APK 直接安装用户手机。

## 原型与验收门

P0（不连接手机、不触碰现役 LanGPT）：冻结 V1.3.6 当前 Go/AAR/Java 资产并建立独立私有快照，保证可复原、签名和存量授权不变。做 TUN 接口与引擎接口的可编译结构，先确认同一 tsnet 节点能以独立嵌入方式对 TCP/UDP 进行调用，不能用 mock 当成真链。组件版本固定，审计构建及 LICENSE，不安装软件。

P1（需用户知情批准安装原型与系统 VPN 授权）：原型包独立 applicationId、独立存储及节点，不覆盖 V1.3.6；界面明确 VPN 占用。构建受控测试 App A/B：只授权 A；二者对同一真实 tailnet 内 TCP 目标发起连接，验证 A 成功、B 无法通过测试 VPN，B 的普通网络正常。记录 VpnService 路由及 NAS 端实际连接，不以模拟组件或设备 VPN 图标冒充真实验收。安装任何辅助 App 前说明包名、用途、权限，获得用户明确同意。

P2：空名单/无效包/最后一个包被卸载/恶意包伪装/共享 UID→默认不建立；撤销 A 后 A 已建立的会话应断、B 仍无隧道、重新加入可用；A 移除并重新安装、证书变更要重新授权。连续测试 DNS、TCP、UDP、IPv6、QUIC、私有 DNS、LAN 同网段冲突、无白名单恢复、跨资料。测试进程崩溃、VPN 被替换或撤销、飞行模式/网络切换以及隐私：记录截包位置与可能因锁定模式造成的系统影响，先告知再测。

P3：V1.3.6 原有 SSH、会话、连接速度不能退化；在同一移动网络与目标测真实传输字节/时间、CPU/功耗/内存；指定固定时间、关闭可重复对照；配置/组件错误的详细反馈与恢复闭环；独立 App 与原系统 VPN 占用模式冲突提示；回滚开关与注销干净。P0-P3 全通过并经用户确认安装后，才能声称正式沙箱可用。

## 其他未决问题

1. 当前同进程 tsnet 能否安全接 TUN 包而不增加可被第三方访问的代理？需做工程 spike，不能仅靠查询 API 文档推断。
2. 子网掩码和当前手机 Wi-Fi 同网段重叠时，如何同时满足“LAN 本地优先”与“明确指定远端 Jetson”？需按网络与目标配置建判定表后真机验证。
3. tsnet 网络状态变化回调是否完整；目前实现的接口枚举已修 IPv6，但稳定切换不等于已验证。
4. 用户既要部分应用共享，又有不允许 VPN 的 App：实际设备/这些 App 对全局 VPN 存在性的检测行为，需要用户指定应用后测试。
5. 建筑结构上“未授权 App 不能借用”是通道权限，不是把设备其他途径彻底隔离；不要做超出 Android 控制面的绝对安全承诺。
