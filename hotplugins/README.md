# hotplugins/

> **历史方案**：热加载依赖 Fabric 宿主 `mods/plugmc`，测试服已切换为 Paper，本目录不再部署到测试服。
> Paper 插件请写到 [plugins/](../plugins/README.md)，用 `scripts/deploy_paper.py` 部署（重启生效）。

热加载插件源码目录。这里的工程编译成普通 jar（不是 Fabric 模组），由服务器上的
`mods/plugmc` 宿主在运行时加载，可在不重启服务器的情况下新增、替换、卸载功能。

当前包含：

- `demo/`：最小示例，注册命令 + tick 回调。
- `llm-chat/`：聊天栏常驻大模型 NPC，配置与密钥说明见 [llm-chat/README.md](llm-chat/README.md)。

## 新建与构建

```powershell
.\gradlew :hotplugins:demo:build
```

产物：`hotplugins/demo/build/libs/demo-0.1.0.jar`。

新增热插件时，复制 `hotplugins/demo/` 为一个新目录并修改：

- `build.gradle.kts`：`version`，按需添加依赖（Minecraft / Fabric API 已由根构建注入）。
- `src/main/resources/plugmc-plugin.json`：`id`、`name`、`version`、`main`。
- `src/main/java/...`：实现 `com.xiyuan.plugmc.api.HotPlugin`。

`settings.gradle.kts` 会自动发现 `hotplugins/*/build.gradle.kts`，无需手改 include。

## 描述文件

```json
{
  "id": "hotdemo",
  "name": "Hot Demo",
  "version": "0.1.0",
  "main": "com.xiyuan.plugmc.hot.demo.DemoHotPlugin"
}
```

- `id`：全局唯一，只能包含小写字母、数字、`-`、`_`。
- `main`：`HotPlugin` 实现类的全限定名，必须有公开无参构造器。
- 版本以描述文件为准（`/plugmc list` 显示该版本）。

## 安装与热加载

开发服：把 jar 放进 `run/plugmc-hot/`；正式服：放进服务器根目录的 `plugmc-hot/`。
部署到麦块联机测试服可直接用脚本（上传为 `<id>.jar` 并发送 `plugmc reloadall`）：

```powershell
python scripts/deploy_hot.py --project demo
python scripts/deploy_hot.py hotplugins/demo/build/libs/demo-0.1.0.jar --no-load
```

```
/plugmc list                 查看已加载的热插件
/plugmc path                 查看热加载目录
/plugmc load <文件>          加载目录内的 jar（可省略 .jar）
/plugmc reload <id>          卸载并重新加载同一 jar
/plugmc unload <id>          卸载
/plugmc reloadall            卸载全部后重新扫描目录
```

启动服务器时会自动加载目录内全部 jar。热插件可以注册命令与服务器 tick 回调，
卸载时这些注册会被自动移除；不要在静态字段里保存外部生命周期资源。

## 依赖约定

- 使用 `compileOnly(project(":mods:plugmc"))` 引用宿主 API。
- Minecraft、Fabric API、Brigadier、SLF4J 都由宿主服务器提供，**不要**打进热插件 jar。
- 热插件之间默认相互隔离（各自独立类加载器），需要协作时通过宿主 API 或服务端共享状态。
