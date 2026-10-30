# V1.2.11：SSH 通道保护与 Peer 并发读写修复

## 已确认的证据

- 服务器 auth.log 在用户 SFTP 报错时反复出现 `sshd: error: no more sessions`。这证明该 SSH 连接的 session channel 已耗尽，不是文件目录权限问题。
- 后台每 5 秒调用 pollConversationJournals；旧实现仅按会话去重，对多个缓存会话可同时启动多个 jsonCommand / SSH exec，没有连接级总并发限制。
- Peer.write 对与 reader 共用的 socket 临时 settimeout(10)，finally 恢复仍会影响并发读；此前“只影响发送”的注释错误。

## 修改

- 日志轮询按连接单飞、按会话轮转；失败退避5秒，连续3次失败退避30秒，不永久停用。
- 成功和失败都在主线程更新状态；先核 generation 与 transport，旧回调不得误清新连接同名会话的在途状态；disconnect 清旧代际。
- SFTP 浏览加载期间拒绝叠加目录请求；通道打开失败给出可理解提示，不把所有 channel-open 错误都宣称为配额耗尽。
- Peer 发送使用 select 与 MSG_DONTWAIT，在10秒发送截止内推进，不修改共享 socket 的 timeout/blocking 模式。SCRIPT_VERSION 升至7。

## 验证

- test_journal_circuit.py：提取生产轮询方法，14项确定性队列回归；覆盖20会话单飞、轮转、退避、自动恢复与重连旧回调隔离。
- test_peer_socket.py：4项真实 socketpair 测试，覆盖双向读写、不读端发送截止与共享读模式不变。
- test_work_sync.py、test_sftp_listing.py：回归任务同步与真实 JSch→Paramiko 目录枚举。
- 编译、assembleDebug 和 badging 核验 versionCode63 / V1.2.11。

## 未证实的部分与部署边界

没有手机侧同时间窗异常栈，不能断言此次 EOF 必定由通道耗尽或 Peer timeout race 触发；两项代码缺陷与通道耗尽风险已修。未修改服务器 MaxSessions、未重启或 attach 现役 daemon。新 APK 重连时按现有空闲升级协议更新 Host；忙碌 Host 不强杀，可能延后生效。安装后的长时稳定性和手势效果仍需手机实测。
