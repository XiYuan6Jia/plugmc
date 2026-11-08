# PlugMC

面向原版 Minecraft 的 **Fabric 插件 / 数据包测试开发仓库**。一个仓库内可以并存多款插件与多个数据包：
每款插件是独立的 Gradle 工程，每个数据包是纯数据文件夹，既能单独构建运行，也能通过联调工程一次性装进同一台开发服。

原版客户端 / 服务端安装 Fabric Loader 后即可加载插件，无需修改官方游戏文件。

## 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 26.3 | `gradle.properties` 中的 `minecraft_version` |
| Fabric Loader | 0.20.1 | `loader_version` |
| Fabric API | 0.165.2+26.3 | `fabric_api_version` |
| Java | 25 | Minecraft 26.3 要求；缺失时由 Gradle 按 `gradle/gradle-daemon-jvm.properties` 自动获取 |
| Gradle | 9.8.2 | 通过 `gradlew` 使用，无需本地安装 |
| Fabric Loom | 1.19-SNAPSHOT | 统一在 `settings.gradle.kts` 声明 |

> Minecraft 26.3 起 Fabric Loom 要求 **运行 Gradle 的 JVM 本身是 Java 25**，该要求已通过
> `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）固化，无需手动设置 `JAVA_HOME`。
>
> 当前环境提供的 Minecraft 26.3 与 Fabric API 制品已是 Mojang 命名（未混淆），因此
> `gradle.properties` 设置了 `fabric.loom.disableObfuscation=true`，关闭 Loom 的映射与重映射。
> 若改用官方混淆制品，请删除该行并按 Loom 常规方式声明 `mappings`。

## 目录结构

```
mods/<id>/                    每款 Fabric 插件一个独立 Gradle 工程（目录名 = 模组 id）
hotplugins/<id>/              热加载插件：编译成普通 jar，服务器运行时可加载/卸载
datapacks/<id>/               每个数据包一个纯数据文件夹（pack.mcmeta + data/）
templates/fabric-plugin/      新插件模板
templates/datapack/           新数据包模板
scripts/new_plugin.py         从模板生成插件工程
scripts/new_datapack.py       从模板生成数据包
scripts/deploy_test.py        构建并部署插件到麦块联机测试服
scripts/deploy_hot.py         上传热插件并触发热加载（无需重启）
dev/                          联调工程：自动聚合 mods/ 下全部插件
run/                          开发运行目录（客户端/服务端共用，已 gitignore）
```

- 根 [build.gradle.kts](build.gradle.kts) 统一注入 Minecraft / Loader / Fabric API 依赖、Java 25 工具链与版本展开。
- [settings.gradle.kts](settings.gradle.kts) 自动扫描 `mods/` 下的工程目录，新增插件无需手改 include。
- [datapacks/build.gradle.kts](datapacks/build.gradle.kts) 自动扫描数据包，提供打包与同步任务。

## 快速开始

```powershell
# 新建一款插件（生成 mods/my-plugin/）
python scripts/new_plugin.py --id my-plugin --name "我的插件"

# 构建全部插件 + 数据包
.\gradlew build

# 只跑这一款插件
.\gradlew :mods:my-plugin:runServer

# 全部插件 + 全部数据包联调
.\gradlew :dev:runServer
```

首次执行 `runServer` 前需在 `run/eula.txt` 中写入 `eula=true`（目录会在首次运行时生成）。

## 新建数据包

```powershell
python scripts/new_datapack.py --id my-pack --name "我的数据包"

.\gradlew :datapacks:buildDatapacks   # 产出 datapacks/build/libs/my-pack.zip
.\gradlew :datapacks:syncToRun        # 同步到 run/world/datapacks
```

`runServer` 启动前会自动执行 `:datapacks:syncToRun`，本地改完数据包重启开发服即可生效。

## 热加载（不重启服务器）

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
  默认使用硅基流动 `Qwen/Qwen3.5-9B`，配置见 [hotplugins/llm-chat/README.md](hotplugins/llm-chat/README.md)。
  密钥只放在 `plugmc-hot/llm-chat.json` 或环境变量中，**不要提交到仓库**。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `.\gradlew build` | 构建全部插件与数据包 |
| `.\gradlew :mods:<id>:build` | 只构建指定插件（`build/libs/` 下产出 jar 与 sources jar） |
| `.\gradlew :mods:<id>:runClient` | 只带指定插件启动开发客户端 |
| `.\gradlew :mods:<id>:runServer` | 只带指定插件启动开发服务端 |
| `.\gradlew :dev:runServer` | 聚合全部插件启动开发服务端（联调） |
| `.\gradlew :datapacks:buildDatapacks` | 打包全部数据包 |
| `.\gradlew :datapacks:syncToRun` | 把数据包同步到 `run/world/datapacks` |
| `.\gradlew :hotplugins:<id>:build` | 构建指定热加载插件 |
| `python scripts/deploy_test.py` | 构建并部署全部插件到麦块联机测试服 |
| `python scripts/deploy_hot.py --project <id>` | 上传热插件并触发热加载 |
| `python scripts/deploy_hot.py --project llm-chat --config run/plugmc-hot/llm-chat.json` | 同时上传 LLM 配置 |

所有开发运行都复用根目录的 `run/`，客户端与服务端、各插件之间共享同一个世界与配置。

## 版本管理

- Minecraft / Loader / Fabric API / 统一默认版本：根目录 [gradle.properties](gradle.properties)。
- Loom 插件版本：`settings.gradle.kts` 的 `pluginManagement.plugins`。
- 单款插件可在自己的 `build.gradle.kts` 里用 `version = "x.y.z"` 覆盖默认版本。
- 插件 `fabric.mod.json` 支持 `${version}`、`${minecraft_version}`、`${loader_version}` 占位符，构建时自动展开。
- 数据包格式版本：`gradle.properties` 的 `datapack_format`（26.3 为 `121`）。

升级 Minecraft 时：

1. 在 [fabricmc.net/develop](https://fabricmc.net/develop) 查询目标版本对应的 Loader 与 Fabric API 版本；
2. 更新 `gradle.properties` 中的版本号与 `datapack_format`；
3. 检查 Loom 插件版本与 Java 要求是否同步变化；
4. 执行 `.\gradlew build` 验证。

## 部署到测试服

`scripts/deploy_test.py` 会构建并上传 `mods/` 下的插件 jar（自动附带 Fabric API），随后重启测试服并校验日志。

- 默认部署全部插件；`--mod <id>` 可重复指定，只部署其中几款。
- `--skip-build` 复用已有产物，`--upload-only` 只上传不重启，`--check-only` 只读日志校验。
- 凭据只从本地文件读取，不打印、不入库：`.local/minekuai.env`（`MINEKUAI_API_KEY`）与
  `.local/sftp.env`（SFTP 与实例配置）。

```powershell
python scripts/deploy_test.py
python scripts/deploy_test.py --mod my-plugin --skip-build
```

## 安装（正式服）

1. 为 Minecraft 26.3 安装 [Fabric Loader](https://fabricmc.net/use/)；
2. 将 [Fabric API](https://modrinth.com/mod/fabric-api) 与 `mods/<id>/build/libs/<id>-<version>.jar` 放入 `mods/`；
3. 数据包分发时使用 `datapacks/build/libs/<id>.zip`，放入世界的 `datapacks/` 目录；
4. 热加载功能 jar 放入服务器根目录的 `plugmc-hot/`，启动时自动加载，或用 `/plugmc reloadall` 热加载。

## 许可

尚未选择开源许可证，默认保留所有权利。
