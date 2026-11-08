# PlugMC

面向原版 Minecraft 的 Fabric 模组（插件）工程骨架。原版客户端 / 服务端安装 Fabric Loader 后即可加载，无需修改官方游戏文件。

## 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 26.3 | `gradle.properties` 中的 `minecraft_version` |
| Fabric Loader | 0.20.1 | `loader_version` |
| Fabric API | 0.165.2+26.3 | `fabric_api_version` |
| Java | 25 | Minecraft 26.3 要求；缺失时由 Gradle 按 `gradle/gradle-daemon-jvm.properties` 自动获取 |
| Gradle | 9.8.2 | 通过 `gradlew` 使用，无需本地安装 |

> Minecraft 26.3 起 Fabric Loom 要求 **运行 Gradle 的 JVM 本身是 Java 25**，该要求已通过
> `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）固化，无需手动设置 `JAVA_HOME`。

## 常用命令

```powershell
.\gradlew build        # 编译 + 打包
.\gradlew runServer    # 启动开发用服务端
.\gradlew runClient    # 启动开发用客户端
.\gradlew test         # 运行测试
```

构建产物：`build/libs/plugmc-0.1.0.jar`（`-sources` 为源码包）。

首次执行 `.\gradlew runServer` 前需在 `run/eula.txt` 中写入 `eula=true`（目录会在首次运行时生成）。

## 部署到测试服

项目附带脚本，可一键「构建 → SFTP 上传 → 重启 → 读日志校验」：

```powershell
pip install paramiko requests
python scripts/deploy_test.py
```

凭据放在 `.local/minekuai.env`（API 密钥）与 `.local/sftp.env`（SFTP 连接信息），该目录已被 `.gitignore` 排除，不会入库。详见 [scripts/deploy_test.py](scripts/deploy_test.py) 顶部说明。

| 参数 | 作用 |
| --- | --- |
| 无参数 | 构建 + 上传 + 重启 + 校验 |
| `--skip-build` | 复用已有 jar |
| `--upload-only` | 只上传，不重启 |
| `--check-only` | 只读日志判断模组是否已加载 |


## 安装

1. 为 Minecraft 26.3 安装 [Fabric Loader](https://fabricmc.net/use/)；
2. 将 [Fabric API](https://modrinth.com/mod/fabric-api) 与 `build/libs/plugmc-*.jar` 一同放入 `mods/` 目录；
3. 启动游戏或服务端。

## 目录结构

```
src/main/java/com/xiyuan/plugmc/PlugMC.java   模组入口（ModInitializer）
src/main/resources/fabric.mod.json            模组元数据
scripts/deploy_test.py                        部署到麦块联机测试服（构建 / 上传 / 重启 / 校验）
build.gradle.kts                              Gradle（Kotlin DSL）+ Loom 配置
gradle.properties                             Minecraft / Loader / Fabric API 版本
gradle/gradle-daemon-jvm.properties           Gradle 守护进程 JVM 要求（Java 25）
```

## 版本升级

1. 在 [fabricmc.net/develop](https://fabricmc.net/develop) 查询目标版本对应的 Fabric Loader 与 Fabric API 版本；
2. 更新 `gradle.properties` 的版本号；
3. 检查 Loom 插件版本（见 `build.gradle.kts`）与 Java 要求是否同步变化；
4. 执行 `.\gradlew build` 验证。

## 许可

尚未选择开源许可证，默认保留所有权利。
