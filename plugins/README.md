# plugins/

每款 **Paper / Bukkit 插件**占一个子目录，目录名即插件工程 id（如 `plugins/my-plugin/`），
每个子目录都是独立的 Gradle 工程。产物是普通 Bukkit 插件 jar，放进服务端的 `plugins/` 目录即可运行。

当前包含：

- `demo/`：最小示例，注册一条命令，用于验证构建、部署与本地开发服链路。
- `tpa/`：申请传送，`/tpa`、`/tpahere` 发起申请，对方在聊天栏一键接受/拒绝，带读秒热身与冷却，详见 [tpa/README.md](tpa/README.md)。
- `sethome/`：家园与死亡点传送，`/sethome`、`/home`、`/delhome`、`/homes`、`/deathpoint`，
  带传送读秒/冷却与可点击的死亡点按钮，详见 [sethome/README.md](sethome/README.md)。
- `sleepvote/`：入睡半径，半径内达到比例的玩家入睡后跳过夜晚，详见 [sleepvote/README.md](sleepvote/README.md)。
- `anti-xray/`：反矿物透视，隐藏矿挖掘预警 + `/antixray apply` 管理 Paper 内置防透视配置，
  详见 [anti-xray/README.md](anti-xray/README.md)。
- `hub/`：主城大厅，启动时创建独立虚空维度 `hub` 并用代码生成一座带喷泉/柱子/天窗的大厅，
  `/hub` 进入、`/hub back` 返回原位置，禁建造/禁 PvP/禁刷怪/掉虚空拉回，
  东门外还有 20 段跳台的跑酷赛道（`/hub parkour`），详见 [hub/README.md](hub/README.md)。
- `minigames/`：主城小游戏，21 点 / 幸运转盘 / 老虎机，下注与派彩走 economy 账本；
  大厅北侧三个实体摊位右键柜台即可开局，详见 [minigames/README.md](minigames/README.md)。
- `economy/`：复式记账经济系统，支持余额查询、转账、管理员管理、系统商店 GUI、建材银行、玩家市场、收购订单、邮箱、商品每日限额、账本自检与备份，并保留可供其他插件调用的 `EconomyService` API，详见 [economy/README.md](economy/README.md)。
- `server-menu/`：测试服功能菜单，支持 `Shift+F` 打开、右侧金币 HUD、已启用插件目录及可配置菜单操作，详见 [server-menu/README.md](server-menu/README.md)。
- `llm-chat/`：聊天栏常驻大模型 NPC，`@npc <内容>` 触发对话、进服欢迎、按概率吐槽；
  由 `hotplugins/llm-chat` 移植，配置字段兼容，详见 [llm-chat/README.md](llm-chat/README.md)。

## 新建插件

```powershell
python scripts/new_plugin.py --id my-plugin --name "我的插件"
```

脚本默认从 `templates/paper-plugin/` 生成完整工程（入口类、`plugin.yml`、`build.gradle.kts`），
并把占位符替换为实际的 id、包名与类名。新增目录会被 `settings.gradle.kts` 自动纳入构建。

> 生成 Fabric 模组（历史）用 `python scripts/new_plugin.py --type fabric --id my-mod`。

## 约定

- 目录名只能包含小写字母、数字、`-`、`_`，且以字母开头；它同时是工程 id 与 jar 前缀。
- Java 包名默认 `com.xiyuan.plugmc.<去分隔符的 id>`，可用 `--package` 覆盖。
- 版本号默认 `0.1.0`，可在 `plugins/<id>/build.gradle.kts` 里单独调整。
- `plugin.yml` 里支持 `${version}` 与 `${api_version}` 占位符，构建时由根脚本展开
  （`api_version` 取自 `gradle.properties` 的 `paper_minecraft_version`），不要写死。
- Paper 版本：`gradle.properties` 的 `paper_minecraft_version` / `paper_api_version` / `paper_build`，
  paper-api 只在编译期引入，运行期由服务端提供。
- 入口类保留一行 `getLogger().info("<名称> 已启用")`，部署脚本依靠它在服务端日志中校验加载结果。

## 常用命令

```powershell
.\gradlew :plugins:<id>:build      # 只构建该插件
.\gradlew build                    # 构建全部插件（含 Fabric 历史模块与数据包）
python scripts/run_paper.py        # 本地 Paper 开发服（自动装入已构建插件）
python scripts/deploy_paper.py     # 构建并部署到麦块联机测试服
```
