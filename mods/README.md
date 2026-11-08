# mods/

每款 Fabric 插件占一个子目录，目录名即模组 id（如 `mods/my-plugin/`），每个子目录都是独立的 Gradle 工程。

> `mods/plugmc/` 是正式宿主插件，负责热加载 `plugmc-hot/` 下的功能 jar（见
> [hotplugins/README.md](../hotplugins/README.md)），不要删除或改名。

## 新建插件

```powershell
python scripts/new_plugin.py --id my-plugin --name "我的插件"
```

脚本会从 `templates/fabric-plugin/` 生成完整工程（入口类、`fabric.mod.json`、`build.gradle.kts`），
并把占位符替换为实际的 id、包名与类名。新增目录会被 `settings.gradle.kts` 自动纳入构建。

## 约定

- 目录名只能包含小写字母、数字、`-`、`_`，且以字母开头；它同时是模组 id 与 jar 前缀。
- Java 包名默认 `com.xiyuan.plugmc.<去分隔符的 id>`，可用 `--package` 覆盖。
- 版本号默认 `0.1.0`，可在 `mods/<id>/build.gradle.kts` 里单独调整；Minecraft / Loader / Fabric API 版本统一由根目录 `gradle.properties` 提供。
- 入口类保留一行 `LOGGER.info("<名称> 已加载")`，部署脚本依靠它在服务端日志中校验加载结果。
- 不要在模块里重复声明 `minecraft` / `fabric-loader` / `fabric-api` 依赖，根构建脚本已统一注入。

## 常用命令

```powershell
.\gradlew :mods:<id>:build        # 只构建该插件
.\gradlew :mods:<id>:runClient    # 只带该插件启动客户端
.\gradlew :mods:<id>:runServer    # 只带该插件启动服务端
.\gradlew build                   # 构建全部插件与数据包
.\gradlew :dev:runServer          # 全部插件 + 全部数据包联调
```
