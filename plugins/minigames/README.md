# minigames — 主城小游戏

主城大厅北侧的三个实体摊位 + `/games` 菜单：**21 点**、**幸运转盘**、**老虎机**。
所有注额与派彩都通过 economy 的 `EconomyService` 走账本，投注记为 `GAME_*_BET`、
派彩记为 `GAME_*_WIN`，`/eco audit` 能查到每一笔。

## 实体店（主城大厅北侧）

- 从大厅北门出来，走廊直接通到三个摊位（默认坐标 `(0,64,-40)`，间距 14 格）：
  - 红顶摊位 = **21 点**（柜台：金块）
  - 黄顶摊位 = **幸运转盘**（柜台：磁石）
  - 蓝顶摊位 = **老虎机**（柜台：铁块）
- 每个摊位有石英柱、海晶灯照明、地毯和悬浮字（游戏名 + `/games <id>`）。
- **右键柜台方块**直接打开对应游戏界面（`minigames.use`）。
- `/games build`（管理员）可清空重建三个摊位；改位置改 `venue.*` 配置后重建即可。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/games` | 打开小游戏菜单，别名 `/game`、`/casino` | `minigames.use` |
| `/games blackjack [注额]` | 直接打开 21 点 | `minigames.use` |
| `/games wheel [注额]` | 直接打开幸运转盘 | `minigames.use` |
| `/games slots [注额]` | 直接打开老虎机 | `minigames.use` |
| `/games build` | 重建实体店 | `minigames.admin` |
| `/games reload` | 重载配置 | `minigames.admin` |

服务器菜单（`server-menu`）第三区新增「小游戏」按钮，点击执行 `/games`。

## 玩法与赔率

### 21 点（blackjack）

- 开局扣注额，庄家 17 点停牌（含软 17），A 可当 1 或 11。
- 操作：**要牌** / **停牌** / **加倍**（起手两张牌时，注额翻倍且只补一张）。
- 结算：赢返还 2 倍注额；**黑杰克按 1.5 倍赔付**（3:2，即返还 2.5 倍）；平局退注。
- 天然黑杰克（首两张 21 点）自动结算。

### 幸运转盘（wheel）

6 个扇区按权重抽取（合计 100）：

| 扇区 | 权重 | 返还 |
| --- | --- | --- |
| 空手 | 32% | 0 |
| 0.5 倍 | 30% | 0.5× |
| 1 倍 | 22% | 1× |
| 2 倍 | 10% | 2× |
| 5 倍 | 5% | 5× |
| 10 倍 | 1% | 10× |

理论返还率约 **92%**（`GamesMathTest` 会校验权重合计与返还区间）。

### 老虎机（slots）

三个滚轮各自按权重抽符号（樱桃 30 / 柠檬 25 / 铃铛 20 / 钻石 15 / 幸运 7 10）：

- **三连**：樱桃 4×、柠檬 6×、铃铛 10×、钻石 15×、幸运 7 **30×**
- **两个相同**：返还 1.1 倍
- 其余无奖；理论返还率约 **92%**

## 配置（`plugins/minigames/config.yml`）

| 键 | 说明 |
| --- | --- |
| `limits.min-bet` / `limits.max-bet` | 单次注额范围（整数），默认 1 ~ 100 |
| `limits.cooldown-seconds` | 两次开局冷却，默认 2 秒 |
| `games.blackjack.*` | `enabled` / `default-bet` / `blackjack-payout`（默认 1.5） |
| `games.wheel.*` | `enabled` / `default-bet` |
| `games.slots.*` | `enabled` / `default-bet` |
| `venue.enabled` | 是否建造实体店 |
| `venue.world` | 实体店所在世界，默认 `hub` |
| `venue.floor-y` | 摊位地板 Y，默认 64 |
| `venue.center-x` / `venue.center-z` | 中间摊位中心坐标，默认 `(0, -40)` |
| `venue.spacing` | 摊位间距，默认 14 |

## 经济与安全

- 下注前校验余额（`EconomyService#has`），扣款失败不会开局。
- 派彩统一走 `deposit(uuid, amount, "GAME_*_WIN")`，便于审计；经济插件未加载时自动禁用下注。
- 界面所有点击被取消，物品不会被拖出；开奖动画期间忽略点击。
- 主城世界默认禁止非 `hub.build` 玩家建造，实体店不会被玩家拆改。

## 构建与部署

```powershell
.\gradlew :plugins:minigames:test        # 赔率与返还率单测
.\gradlew :plugins:minigames:build
python scripts/deploy_paper.py --plugin minigames
```

> 依赖 economy 0.6.5+（新增带原因的 `deposit`/`withdraw` 重载）。
