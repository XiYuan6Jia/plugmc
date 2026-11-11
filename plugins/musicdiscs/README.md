# MusicDiscs

自定义唱片：上传自己的 OGG 音乐 → 自动打包资源包 + 生成唱片物品，
既可以用插件的 `/music play` 播放，也可以一键导出 **数据包** 让原版唱机识别。

## 工作流程

1. 把音乐转成 **OGG Vorbis**（推荐单声道 / 44100 Hz）；
2. 上传到服务器 `plugins/musicdiscs/incoming/<ID>.ogg`（SFTP 或面板文件管理）；
3. 控制台或游戏内执行：

   ```
   /music import <ID> [标题] [作者] [比较器0-15]
   ```

   插件会校验 OGG 头、读取出时长、把文件搬到 `plugins/musicdiscs/songs/`，
   重新生成资源包并让下载服务加载新包；
4. 玩家进服时自动收到资源包；也可以手动 `/music pack` 重新下发；
5. 发放唱片：`/music give <玩家> <ID>`，或让玩家在 `/music` 界面 Shift+左键领取。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/music` | 打开唱片列表（左键试听、Shift+左键领取） | `music.use` |
| `/music list` | 文本列出全部唱片 | `music.use` |
| `/music play <ID>` | 试听 | `music.use` |
| `/music stop` | 停止播放（潜行右键唱片也可以停止） | `music.use` |
| `/music give [玩家] <ID>` | 发放唱片 | `music.give`（默认 OP） |
| `/music info <ID>` | 查看时长、声音事件、数据包是否已加载 | `music.use` |
| `/music import <ID> [标题] [作者] [比较器]` | 从 incoming 导入 | `music.admin` |
| `/music delete <ID>` | 删除唱片 | `music.admin` |
| `/music pack [玩家]` | 重新下发资源包 | `music.use`（指定他人需 `music.admin`） |
| `/music export` | 导出 `jukebox_song` 数据包 | `music.admin` |
| `/music reload` | 重载配置与唱片库 | `music.admin` |

别名：`/disc`、`/juke`、`/record`。

## 两种播放方式

**插件播放（默认可用）**

- 右键手持的自定义唱片即播放，潜行右键或 `/music stop` 停止；
- 声音按唱片机音效分类在附近播放，`playback.range` 内的玩家都能听到；
- 播放时长来自 OGG 元数据，到点自动停止。

**数据包 + 原版唱机（可选）**

- 执行 `/music export` 会在 `plugins/musicdiscs/datapack/plugmc-music/` 生成数据包：
  - `data/plugmc/jukebox_song/<ID>.json`（`sound_event` 指向资源包里的自定义声音）
  - `data/plugmc/function/give_<ID>.mcfunction`（`/function plugmc:give_<ID>` 直接给带组件唱片）
  - `pack.mcmeta`（格式取 `datapack.format`，26.3 = 121）
- 把整个目录复制到 `<世界存档>/datapacks/` 后重启或 `/reload`；
- 数据包加载后，`/music give` 发出的唱片会自动带上 `minecraft:jukebox_playable` 组件，
  放进**原版唱机**即可正常播放并显示曲名；
- `/music info <ID>` 会显示「数据包注册表：已加载 / 未加载」。

> 音频永远来自资源包，所以两种方式都必须让玩家接受资源包。

## 资源包下发

两种模式（`pack.mode`）：

| 模式 | 说明 |
| --- | --- |
| `builtin`（默认） | 插件内置 HTTP 服务（JDK HttpServer），监听 `pack.port`（默认 **20103**，测试服已分配），对外用 `pack.public-url` 下载 |
| `external` | 插件只生成 zip，由你自己托管，`pack.external-url` 填完整地址 |

内置服务只暴露三个路径：`/plugmc-music.zip`、`/plugmc-music.sha1`、`/`（包信息）。
资源包文件位于 `plugins/musicdiscs/pack/plugmc-music.zip`，可以随时手工下载或分发。

> 端口需要在机房/面板放行；如果外网访问不到，把 `pack.mode` 改成 `external`，
> 把 zip 传到自己的对象存储再把地址填进 `pack.external-url`。

## 配置（`plugins/musicdiscs/config.yml`）

| 键 | 说明 |
| --- | --- |
| `pack.mode` | `builtin` 或 `external` |
| `pack.external-url` | external 模式的完整下载地址 |
| `pack.public-url` | 玩家实际请求的地址（builtin 模式也要填对外域名/端口） |
| `pack.format` | 资源包 `pack_format`，客户端提示不兼容时调整 |
| `pack.bind-address` / `pack.port` | 内置下载服务监听地址与端口 |
| `pack.join-delay-ticks` | 进服后延迟多少 tick 下发 |
| `pack.required` | 是否强制接受资源包 |
| `disc.material` | 唱片物品材质，默认 `MUSIC_DISC_13` |
| `playback.volume` / `playback.range` | 播放音量与停止时的清理半径 |
| `datapack.format` / `datapack.namespace` | 导出数据包的格式与命名空间 |

## 文件结构

```
plugins/musicdiscs/
├── config.yml
├── songs.yml               # 元数据（标题/作者/时长/比较器/模型数据）
├── incoming/               # 上传落地区：把 <ID>.ogg 放这里再 /music import
├── songs/<ID>.ogg          # 已导入的音频
├── pack/plugmc-music.zip   # 生成的资源包（内置 HTTP 直接读这个文件）
└── datapack/plugmc-music/  # /music export 生成的数据包
```

## 实现要点

- 声音事件 ID 为 `<namespace>:disc.<ID>`，音频路径 `assets/<namespace>/sounds/disc/<ID>.ogg`，
  `sounds.json` 里使用 `"stream": true`，长曲目按流式播放；
- 导入时只接受真正的 OGG Vorbis（校验 `OggS` + `\x01vorbis` 头），mp3/wav 会被拒绝，
  避免客户端拿到无法解码的文件；
- 时长来自最后一个 Ogg 页的 granule position ÷ 采样率，不需要额外解码；
- 唱片用持久化数据 `musicdiscs:song` 标记曲目 ID，改名/复制都不会丢失曲目绑定；
- 资源包每次变更都重新生成 zip 并重算 SHA-1，客户端只会重新下载发生变化的包。
