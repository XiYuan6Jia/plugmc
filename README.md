# PlugMC

面向 Minecraft 的 **Paper 插件 / 数据包测试开发仓库**。一个仓库内可以并存多款插件与多个数据包：
每款插件是独立的 Gradle 工程，每个数据包是纯数据文件夹，既能单独构建运行，也能一次性装进同一台测试服。

测试服已切换为 **Paper 26.3**，插件产物直接放进服务端 `plugins/` 目录即可加载，无需任何客户端模组。
仓库早期基于 Fabric 的模组（[mods/](mods/README.md)）与热加载方案（[hotplugins/](hotplugins/README.md)）
原样保留，仅作历史参考；新功能请写在 [plugins/](plugins/README.md) 下。

## 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 26.3 | `gradle.properties` 中的 `paper_minecraft_version` |
| Paper | 26.3（build 229） | 测试服运行的核心，`paper_build` |
| paper-api | 26.3.build.229-stable | 编译期依赖，`paper_api_version`（**不要**打进插件 jar） |
| Java | 25 | Minecraft 26.3 / Paper 26.3 要求；缺失时由 Gradle 按 `gradle/gradle-daemon-jvm.properties` 自动获取 |
| Gradle | 9.8.2 | 通过 `gradlew` 使用，无需本地安装 |

<details>
<summary>Fabric（历史）依赖</summary>

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Fabric Loader | 0.20.1 | `loader_version` |
| Fabric API | 0.165.2+26.3 | `fabric_api_version` |
| Fabric Loom | 1.19-SNAPSHOT | 统一在 `settings.gradle.kts` 声明 |

</details>

> **Fabric（历史）**：Minecraft 26.3 起 Fabric Loom 要求 **运行 Gradle 的 JVM 本身是 Java 25**，该要求已通过
> `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）固化，无需手动设置 `JAVA_HOME`。
>
> 当前环境提供的 Minecraft 26.3 与 Fabric API 制品已是 Mojang 命名（未混淆），因此
> `gradle.properties` 设置了 `fabric.loom.disableObfuscation=true`，关闭 Loom 的映射与重映射。
> 若改用官方混淆制品，请删除该行并按 Loom 常规方式声明 `mappings`。

## 目录结构

```
plugins/<id>/                 每款 Paper 插件一个独立 Gradle 工程（目录名 = 工程 id）
datapacks/<id>/               每个数据包一个纯数据文件夹（pack.mcmeta + data/）
templates/paper-plugin/       新 Paper 插件模板
templates/datapack/           新数据包模板
scripts/new_plugin.py         从模板生成插件工程（默认 Paper，--type fabric 生成 Fabric 模组）
scripts/new_datapack.py       从模板生成数据包
scripts/run_paper.py          本地起 Paper 开发服（自动下载核心并装入已构建插件）
scripts/deploy_paper.py       构建并部署 Paper 插件到麦块联机测试服
run-paper/                    Paper 开发服目录（世界、配置，已 gitignore）
mods/<id>/                    Fabric 插件（历史，保留）
hotplugins/<id>/              热加载插件（历史，需 Fabric 宿主）
templates/fabric-plugin/      Fabric 模组模板（历史）
scripts/deploy_test.py        构建并部署 Fabric 插件到麦块联机测试服（历史）
scripts/deploy_hot.py         上传热插件并触发热加载（历史）
dev/                          Fabric 联调工程：自动聚合 mods/ 下全部插件（历史）
run/                          Fabric 开发运行目录（已 gitignore）
```

- 根 [build.gradle.kts](build.gradle.kts) 统一注入 paper-api（`plugins/`）、Minecraft / Loader / Fabric API（`mods/`）依赖、Java 25 工具链与版本展开。
- [settings.gradle.kts](settings.gradle.kts) 自动扫描 `plugins/`、`mods/`、`hotplugins/` 下的工程目录，新增插件无需手改 include。
- [datapacks/build.gradle.kts](datapacks/build.gradle.kts) 自动扫描数据包，提供打包与同步任务。

## 快速开始

```powershell
# 新建一款 Paper 插件（生成 plugins/my-plugin/）
python scripts/new_plugin.py --id my-plugin --name "我的插件"

# 构建全部插件 + 数据包
.\gradlew build

# 本地起 Paper 开发服，自动装入 plugins/ 下已构建的插件
python scripts/run_paper.py --build-plugins

# 构建并部署到测试服（上传到 plugins/ 后重启并校验日志）
python scripts/deploy_paper.py
```

`run_paper.py` 首次运行会把 Paper 核心下载到 `.local/thirdparty/`，世界与配置落在 `run-paper/`，
并自动写入 `eula.txt`；控制台直接输入 `stop` 停止服务端。需要 Java 25，可用 `--java` 指定；
要同时起第二个开发服（避免抢世界锁和端口）可用 `--dir <目录> -- --port 25566`。

Fabric（历史）：

```powershell
.\gradlew :mods:my-mod:runServer      # 只跑某一款 Fabric 插件
.\gradlew :dev:runServer              # 全部 Fabric 插件联调
```

首次执行 Fabric 的 `runServer` 前需在 `run/eula.txt` 中写入 `eula=true`（目录会在首次运行时生成）。

## 新建数据包

```powershell
python scripts/new_datapack.py --id my-pack --name "我的数据包"

.\gradlew :datapacks:buildDatapacks   # 产出 datapacks/build/libs/my-pack.zip
.\gradlew :datapacks:syncToRun        # 同步到 run/world/datapacks
```

Fabric 开发服（`runServer`）启动前会自动执行 `:datapacks:syncToRun`；
Paper 开发服用 `python scripts/run_paper.py` 启动时会自动把 `datapacks/<id>/` 复制进 `run-paper/world/datapacks/`。
本地改完数据包重启开发服（或游戏内 `/reload`）即可生效。

## 内置插件（Paper 主线）

- `plugins/demo`：最小示例，一条 `/demo` 命令，用于验证构建、部署与本地开发服链路。
- `plugins/tpa`：申请传送，`/tpa`、`/tpahere` 发起，聊天栏一键接受/拒绝，带热身读秒与冷却。
- `plugins/hub`：主城大厅，独立虚空维度 + 代码生成的大厅建筑，`/hub` 进入、`/hub back` 返回，
  主城内禁建造/禁 PvP/禁刷怪/掉虚空拉回，详见 [plugins/hub/README.md](plugins/hub/README.md)。
- `plugins/economy`：复式记账经济系统，支持余额查询、玩家转账、管理员余额管理、系统商店 GUI、
  玩家市场、收购订单、拍卖行、直接交易、邮箱、余额排行、商品每日限额、账本自检与备份，
  并提供可扩展的服务 API，详见 [plugins/economy/README.md](plugins/economy/README.md)。
- `plugins/server-menu`：服务器功能菜单（`Shift+F` 打开）、右侧金币 HUD、插件目录与可配置按钮，
  联动经济系统各入口，详见 [plugins/server-menu/README.md](plugins/server-menu/README.md)。
- `plugins/llm-chat`：聊天栏常驻 NPC（Fabric 版 `hotplugins/llm-chat` 的 Paper 移植），
  `@npc <内容>` 调用大模型并广播回复；玩家进服发送欢迎语，公开聊天按概率触发 NPC 吐槽。
  配置在服务器 `plugins/llm-chat/config.json`，详见 [plugins/llm-chat/README.md](plugins/llm-chat/README.md)。

Fabric 时代的热加载方案（`mods/plugmc` + `hotplugins/`）保留在仓库中，见下一节。

## 热加载（Fabric 历史方案）

> 测试服已切换为 Paper，这一节只适用于 Fabric 服务端；Paper 插件请用
> `python scripts/deploy_paper.py` 部署并重启。

`mods/plugmc` 是正式宿主插件：服务器启动时扫描根目录的 `plugmc-hot/`（开发服为 `run/plugmc-hot/`），
加载其中的热插件 jar。热插件可以注册命令与服务器 tick 回调，卸载时注册会被自动注销。

```powershell
.\gradlew :hotplugins:demo:build              # 构建示例热插件
python scripts/deploy_hot.py --project demo   # 上传到测试服并触发热加载
```

服务器命令（需要管理员权限）：

| 命令 | 作用 |
| --- | --- |
| `/plugmc list` | 查看已加载的热插件 |
| `/plugmc path` | 查看热加载目录 |
| `/plugmc load <文件>` | 加载目录内的 jar（可省略 `.jar`） |
| `/plugmc reload <id>` | 卸载并重新加载同一 jar |
| `/plugmc unload <id>` | 卸载 |
| `/plugmc reloadall` | 重新扫描并加载整个目录 |

本地开发时把 jar 放进 `run/plugmc-hot/`，服务器启动会自动加载；运行中替换 jar 后在控制台执行
`/plugmc reloadall` 即可生效。热插件工程约定见 [hotplugins/README.md](hotplugins/README.md)。

内置示例：

- `hotplugins/demo`：最小热插件示例。
- `hotplugins/llm-chat`：聊天栏常驻 NPC，玩家发送 `@npc <内容>` 即调用大模型并在聊天栏回复；
  新玩家进服时自动发送欢迎语，公开聊天与系统聊天（成就/公告等）还会按概率触发 NPC 吐槽（带冷却与时间窗上限），
  配置见 [hotplugins/llm-chat/README.md](hotplugins/llm-chat/README.md)。默认使用硅基流动 `Qwen/Qwen3.5-9B`。
  密钥只放在 `plugmc-hot/llm-chat.json` 或环境变量中，**不要提交到仓库**。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `.\gradlew build` | 构建全部插件与数据包 |
| `.\gradlew :plugins:<id>:build` | 只构建指定 Paper 插件（`build/libs/` 下产出 jar 与 sources jar） |
| `python scripts/run_paper.py` | 本地起 Paper 开发服（`run-paper/`），自动装入已构建插件 |
| `python scripts/deploy_paper.py` | 构建并部署全部 Paper 插件到麦块联机测试服 |
| `python scripts/deploy_paper.py --plugin llm-chat --upload-file llm-chat=.local/llm-chat/config.json` | 单插件部署并附带插件数据文件 |
| `.\gradlew :datapacks:buildDatapacks` | 打包全部数据包 |
| `.\gradlew :datapacks:syncToRun` | 把数据包同步到 `run/world/datapacks` |
| `.\gradlew :mods:<id>:build` | 只构建指定 Fabric 插件（历史） |
| `.\gradlew :mods:<id>:runClient` | 只带指定 Fabric 插件启动开发客户端（历史） |
| `.\gradlew :mods:<id>:runServer` | 只带指定 Fabric 插件启动开发服务端（历史） |
| `.\gradlew :dev:runServer` | 聚合全部 Fabric 插件启动开发服务端（历史） |
| `.\gradlew :hotplugins:<id>:build` | 构建指定热加载插件（历史） |
| `python scripts/deploy_test.py` | 构建并部署全部 Fabric 插件到测试服（历史） |
| `python scripts/deploy_hot.py --project <id>` | 上传热插件并触发热加载（历史） |

Paper 开发服的运行目录是 `run-paper/`，Fabric 是 `run/`，两者互不影响。

## 版本管理

- Paper / Minecraft 版本：根目录 [gradle.properties](gradle.properties) 的
  `paper_minecraft_version`、`paper_api_version`、`paper_build`。
- Fabric（历史）：同一文件的 `minecraft_version` / `loader_version` / `fabric_api_version`，
  Loom 插件版本在 `settings.gradle.kts` 的 `pluginManagement.plugins`。
- 单款插件可在自己的 `build.gradle.kts` 里用 `version = "x.y.z"` 覆盖默认版本。
- `plugin.yml` 支持 `${version}`、`${api_version}`，`fabric.mod.json` 支持
  `${version}`、`${minecraft_version}`、`${loader_version}`，构建时自动展开。
- 数据包格式版本：`gradle.properties` 的 `datapack_format`（26.3 为 `121`）。

升级 Paper / Minecraft 时：

1. 在 [fill.papermc.io](https://fill.papermc.io/v3/projects/paper) 查询目标版本的构建号；
2. 更新 `gradle.properties` 中的 `paper_minecraft_version` / `paper_api_version` / `paper_build`；
3. 测试服的面板核心也要同步升级（整合包广场 → 纯净服务端 → Paper → 对应版本）；
4. 执行 `.\gradlew build` 验证。

## 部署到测试服

测试服运行 Paper 26.3，插件目录为服务端根目录的 `plugins/`。

`scripts/deploy_paper.py` 会构建 `plugins/` 下的全部插件，上传 jar 到测试服 `plugins/`，
然后重启实例并校验日志中的 `Enabling <插件名>`。

- 默认部署全部插件；`--plugin <id>` 可重复指定，只部署其中几款。
- `--upload-file <id>=<本地文件>` 可把插件数据文件传到 `plugins/<id>/`（如 llm-chat 的 `config.json`）。
- `--skip-build` 复用已有产物，`--upload-only` 只上传不重启，`--check-only` 只读日志校验。
- 部署前会自动把线上 `plugins/<id>/` 数据目录下载到 `.local/backup/remote-<时间>/`，避免误覆盖线上数据；
  加 `--no-remote-backup` 可跳过。
- 凭据只从本地文件读取，不打印、不入库：`.local/minekuai.env`（`MINEKUAI_API_KEY`）与
  `.local/sftp.env`（SFTP 与实例配置，`MINEKUAI_PLUGINS_PATH` 可覆盖远程插件目录）。

```powershell
python scripts/deploy_paper.py
python scripts/deploy_paper.py --plugin demo --skip-build
python scripts/deploy_paper.py --plugin llm-chat --upload-file llm-chat=.local/llm-chat/config.json
```

Fabric（历史）：`scripts/deploy_test.py` 会构建并上传 `mods/` 下的插件 jar（自动附带 Fabric API），
参数形式与上面一致（`--mod` 代替 `--plugin`）。

## 安装（正式服）

Paper：

1. 准备 Minecraft 26.3 的 [Paper](https://papermc.io/downloads/paper) 服务端；
2. 把 `plugins/<id>/build/libs/<id>-<version>.jar` 放入服务端 `plugins/`；
3. 数据包分发时使用 `datapacks/build/libs/<id>.zip`，放入世界的 `datapacks/` 目录。

Fabric（历史）：

1. 为 Minecraft 26.3 安装 [Fabric Loader](https://fabricmc.net/use/)；
2. 将 [Fabric API](https://modrinth.com/mod/fabric-api) 与 `mods/<id>/build/libs/<id>-<version>.jar` 放入 `mods/`；
3. 热加载功能 jar 放入服务器根目录的 `plugmc-hot/`，启动时自动加载，或用 `/plugmc reloadall` 热加载。

## 许可

尚未选择开源许可证，默认保留所有权利。
