# LanGPT

**中文** | [English](README.en.md)

通过 SSH 在 Android 手机上使用远端 Codex 的原生客户端。以图形界面管理项目、对话、权限审批和附件，并内置 SFTP 文件管理、Termux 风格 SSH 终端。

模型推理与 Codex 登录发生在远端；手机负责交互和文件传输。应用通过 SSH 启动 `codex app-server`，无需另行部署 Web 服务。

**[下载 APK](https://github.com/aiko-sudo/LanGPT/releases/latest)** · [发布说明](https://github.com/aiko-sudo/LanGPT/releases)

## 使用许可

本项目是由 aiko-sudo 修改的 [RemoteGPT](https://github.com/qzh775226913-ai/RemoteGPT) 非官方独立客户端，与 OpenAI 无关联；修改包括 SSH 客户端、终端和网络能力。现行许可原文为 **[RemoteGPT 使用与再利用许可 1.2](LICENSE)**，原 [1.0 许可](LICENSE.RemoteGPT-1.0)保留供历史版本查阅。

- **使用与协作**：允许使用、修改和公开协作；符合许可条件时，可非商业分发修改版并改名以区分来源。
- **分发条件**：保留版权、来源、许可及第三方声明；注明修改者、改动及非官方身份，安装包须同时提供完整对应源码的公开链接。
- **须事先书面授权**：对外商业提供、闭源交付、改换许可或不满足第 2 条分发条件。历史材料的既有许可不因新版本自动替换，具体以许可原文为准。

本应用引用 Tailscale 技术，可选择配置使用。

## 主要功能

- **SSH 直连**：连接自己的服务器，无需中转服务；断线自动重连与活动会话恢复
- **多会话并行**：管理多个主机和项目，同一连接运行多个会话，各自保留草稿、附件与权限选择
- **SSH 终端**：Termux 风格全功能终端（xterm.js），支持快捷键栏、双指缩放、选区复制与历史命令；会话可后台保活
- **SFTP 文件管理**：浏览、排序、预览编辑远端文件，下载保护与草稿恢复
- **交互式审批**：命令／文件权限弹窗、方案选择与补充信息表单；支持临时"强制允许"
- **流体云／实时通知**：显示思考、执行、待审批和完成状态，通知内可直接处理权限请求
- **模型设置**：按远端能力选择模型、推理强度，查看执行过程与账户额度
- **首次连接诊断**：区分 SSH 认证、Python、Codex、SFTP 和登录问题，给出对应处理说明

## 开始使用

| 位置 | 要求 |
| --- | --- |
| 手机 | Android 8 或以上 |
| 服务器 | Unix 环境、SSH 与 SFTP；SSH 用户已安装并登录 Codex CLI（支持 `app-server`），Python ≥ 3.8 |

1. 在远端安装、登录并确认 Codex 可用。
2. 安装 APK，填写主机、端口、用户名及 SSH 认证信息。
3. 启动命令填 `codex app-server`；找不到程序时改用绝对路径。
4. 核对首次连接的主机指纹，选择项目目录，发送消息。

详细步骤与常见问题见 **[使用说明](docs/USAGE.md)**。

## 构建与验证

原生 Android Java 17、JSch SSH/SFTP、stdio JSON-RPC；远端持久托管使用 Python 标准库。

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements-dev.txt
.venv/bin/python tools/verify.py
```

验证运行合成 SSH 夹具、Python／Android 测试与 APK 构建，不连接生产 SSH 或调用真实模型。仅需构建时运行 `./gradlew :app:assembleDebug`。GitHub Actions 使用同一验证入口，结果以仓库 Actions 页面为准。

## 项目文档

- [使用说明](docs/USAGE.md)：服务器准备、手机配置、权限与故障排查
- [开发手册](docs/DEVELOPMENT.md)：工具链、测试入口、CI 和构建产物
- [需求](docs/REQUIREMENTS.md)、[架构](docs/ARCHITECTURE.md)、[验收边界](docs/ACCEPTANCE.md)：当前行为和证据范围

协议依据：[Codex App Server](https://learn.chatgpt.com/docs/app-server)。
