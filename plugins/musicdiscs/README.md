# MusicDiscs

自定义唱片：玩家上传自己的 OGG 音乐 → 插件自动打包资源包 + **每首歌独立的唱片物品**，
既可以用插件播放，也可以一键导出**数据包**让原版唱机识别。

**每个账号限一首音频，单文件 ≤ 5 MB，时长 ≤ 6 分钟。**

## 玩家流程

1. 游戏内执行 `/music upload`；
2. 聊天栏会给出网页地址和 6 位上传码（15 分钟有效、只能用一次）；
3. 浏览器打开地址，填上传码、选 `.ogg` 文件、点「开始上传」；
4. 上传成功后执行 `/music get` 领取自己的唱片，`/music play <ID>` 试听；
5. 想换歌：先 `/music delete <ID>`，再重新 `/music upload`。

限制（可在 `config.yml` 调整）：

- 每账号 **1 首**；重新上传前必须先删除旧曲；
- 单文件 **≤ 5 MB**（`upload.max-bytes`）；
- 时长 **≤ 6 分钟**（`upload.max-seconds`）；
- 只接受 **OGG Vorbis**，mp3/wav 会被拒绝。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/music` | 打开唱片列表（左键试听、Shift+左键领取自己的唱片） | `music.use` |
| `/music upload` | 申请上传码并显示上传网址 | `music.upload`（默认所有玩家） |
| `/music get` | 领取自己的唱片（背包里已有时不重复发） | `music.use` |
| `/music fix` | 把背包里的旧版唱片升级成新格式（可放进原版唱机） | `music.use` |
| `/music mine` | 查看自己上传的唱片 | `music.use` |
| `/music play <ID>` / `/music stop` | 试听 / 停止 | `music.use` |
| `/music list` / `/music info <ID>` | 列表 / 详情 | `music.use` |
| `/music delete <ID>` | 删除自己的唱片（管理员可删任意） | `music.use` / `music.admin` |
| `/music give [玩家] <ID>` | 发放唱片 | `music.admin` |
| `/music import <ID> [标题] [作者] [比较器]` | 从 `incoming/` 导入服务器曲目 | `music.admin` |
| `/music pack [玩家]` | 重新下发资源包 | `music.use`（指定他人需 `music.admin`） |
| `/music export` | 导出 `jukebox_song` 数据包 | `music.admin` |
| `/music reload` | 重载配置与唱片库 | `music.admin` |

别名：`/disc`、`/juke`、`/record`。

## 不占用原版物品

- 唱片物品默认材质是 **`PAPER`**（`disc.material`），不使用原版音乐唱片；
- 每首歌在资源包里生成独立模型与 16×16 贴图：
  `assets/plugmc/items/disc_<ID>.json`、`assets/plugmc/models/item/disc_<ID>.json`、
  `assets/plugmc/textures/item/disc_<ID>.png`（颜色按曲目 ID 生成）；
- 物品通过 `minecraft:item_model` 组件指向自己的模型，**不覆盖任何原版物品模型**；
- 唱片不可堆叠（`max_stack_size = 1`），靠持久化数据 `musicdiscs:song` 绑定曲目 ID。

## 两种播放方式

**插件播放（默认可用）**

- 右键手持唱片播放、潜行右键或 `/music stop` 停止；
- 声音按唱片机音效分类在附近播放，`playback.range` 内玩家都能听到；
- 时长来自 OGG 元数据，到点自动停止。

**数据包 + 原版唱机（推荐）**

- 插件会把 `jukebox_song` 数据包**自动写入主世界存档**（`<存档>/datapacks/plugmc-music/`）
  并尝试启用；`datapack.auto-install: false` 可关闭；
- 数据包加载后，唱片自动带 `minecraft:jukebox_playable`，**右键唱机即可插碟播放、再右键取出**；
- 注意：`jukebox_song` 属于**注册表**，只在服务端启动时载入，因此**新增曲目后需要重启一次**
  才会出现在原版唱机里（在此之前右键唱机会退化为“在唱机位置直接播放”）；
- 旧版唱片（没有 `jukebox_playable` 组件）在玩家右键时会自动升级，也可以用 `/music fix` 批量升级；
- `/music info <ID>` 会显示「数据包注册表：已加载 / 未加载」。

> 音频始终来自资源包，两种方式都需要玩家接受资源包。

## 资源包与上传服务

内置 HTTP 服务（`pack.mode: builtin`，默认 TCP **20103**）只暴露：

| 路径 | 说明 |
| --- | --- |
| `/` | 上传页面（填上传码 + 选文件） |
| `POST /upload?token=...&title=...` | 上传原始 ogg 字节 |
| `/plugmc-music.zip` | 资源包本体 |
| `/plugmc-music.sha1` | 资源包 SHA-1 |

`pack.public-url` 必须填玩家可访问的域名/端口；也可以 `pack.mode: external` 改用自己托管
（此时上传页面不可用，可由管理员用 `/music import` 代传）。

## 配置（`plugins/musicdiscs/config.yml`）

| 键 | 说明 |
| --- | --- |
| `pack.mode` / `pack.external-url` / `pack.public-url` | 资源包下发方式与地址 |
| `pack.format` / `pack.bind-address` / `pack.port` | 资源包版本、监听地址与端口 |
| `pack.join-delay-ticks` / `pack.required` / `pack.prompt` | 进服下发与提示 |
| `disc.material` / `disc.use-item-model` | 唱片基础材质与独立模型开关 |
| `playback.volume` / `playback.range` / `playback.stop-on-quit` | 播放音量、清理半径、下线停止 |
| `upload.max-bytes` | 单文件上限，默认 `5242880`（5 MB） |
| `upload.max-seconds` | 时长上限，默认 `360`（6 分钟） |
| `upload.token-seconds` | 上传码有效期，默认 `900` |
| `datapack.format` / `datapack.namespace` | 导出数据包格式与命名空间 |
| `datapack.auto-install` | 自动把数据包写入主世界存档并启用，默认 `true` |

## 文件结构

```
plugins/musicdiscs/
├── config.yml
├── songs.yml               # 元数据（标题/作者/时长/比较器/上传者）
├── incoming/               # 管理员落地区：<ID>.ogg → /music import
├── songs/<ID>.ogg          # 已导入的音频（玩家上传的 ID 为 u_<uuid前12位>）
├── pack/plugmc-music.zip   # 生成的资源包
└── datapack/plugmc-music/  # /music export 生成的数据包
```

## 实现要点

- 声音事件 ID：`<namespace>:disc.<ID>`，音频路径 `assets/<ns>/sounds/disc/<ID>.ogg`，
  `sounds.json` 使用 `"stream": true`；- 导入时校验 `OggS` + `\x01vorbis` 头，mp3/wav 直接被拒；
- 时长来自最后一个 Ogg 页的 granule position ÷ 采样率，不需要解码；
- 上传走原始字节 POST（不用 multipart），服务端边读边限长，超过 `upload.max-bytes` 立即拒绝；
- 上传码绑定玩家 UUID、一次性、15 分钟过期；该账号已有曲目时直接拒绝；
- 资源包每次变更重新生成 zip 并重算 SHA-1，客户端只会下载变化的包。
