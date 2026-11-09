# datapacks/

每个子目录是一个数据包，以文件夹形式直接维护（`pack.mcmeta` + `data/<命名空间>/...`），
由聚合工程 `:datapacks` 统一打包与同步，不需要为每个包单独写构建脚本。

## 新建数据包

```powershell
python scripts/new_datapack.py --id my-pack --name "我的数据包"
```

`--pack-format` 默认读取根目录 `gradle.properties` 中的 `datapack_format`（当前 Minecraft 26.3 为 `121`）。

## 构建与测试

```powershell
.\gradlew :datapacks:buildDatapacks   # 产出 datapacks/build/libs/<id>.zip（可分发给正式服）
python scripts/run_paper.py           # Paper 开发服：启动前自动把数据包复制进 run-paper/world/datapacks
.\gradlew :datapacks:syncToRun        # 同步到 run/world/datapacks（Fabric 历史开发服）
.\gradlew :dev:runServer              # Fabric 开发服，启动前自动执行 syncToRun
```

## 约定

- 目录名即命名空间（`data/<id>/...`），只能包含小写字母、数字、`-`、`_`、`.`。
- 资源目录使用 1.21+ 的单数形式，例如 `function/`、`advancement/`、`loot_table/`。
- `pack.mcmeta` 使用 26.x 的 `pack.min_format` / `pack.max_format` 字段。
- 删除或改名数据包后，`run/world/datapacks/` 下的旧副本不会自动清理，需要手动删除。
