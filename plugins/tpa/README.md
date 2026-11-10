# TPA

申请传送插件：玩家之间发起传送申请，对方在聊天栏点击「接受 / 拒绝」即可，无需手动输入指令。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/tpa` | 打开在线玩家列表，点击头像发起「我过去」申请 |
| `/tpa <玩家>` | 申请传送到该玩家身边 |
| `/tpahere <玩家>` | 申请把该玩家传送到你身边 |
| `/tpaccept [玩家]` | 接受最近一条申请（可省略玩家名） |
| `/tpadeny [玩家]` | 拒绝申请 |
| `/tpcancel [玩家]` | 撤回自己发出的申请 |
| `/tpa reload` | 重载配置（`tpa.admin`） |

玩家列表里可以点击底部「切换」按钮，在「我过去」和「请对方过来」之间切换。

## 交互

- 发起申请后，对方会收到可点击的 `[点击接受]` / `[拒绝]` 按钮，点击即完成操作。
- 申请人会收到 `[撤回]` 按钮。
- 接受后进入读秒热身（默认 3 秒），期间移动或受伤会取消，屏幕 action bar 显示剩余时间。
- 请求默认 60 秒过期；再次发起有 10 秒冷却，防止刷屏。

## 配置

`plugins/tpa/config.yml`：

```yaml
enabled: true
request-timeout-seconds: 60
warmup-seconds: 3
cooldown-seconds: 10
cancel-on-move: true
cancel-on-damage: true
allow-cross-world: true
```

改完执行 `/tpa reload`。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `tpa.use` | true | 使用申请传送 |
| `tpa.admin` | op | 重载配置 |

## 与菜单联动

`server-menu` 主菜单「社交与信息」区有「申请传送」按钮（`tpa.use`），点击打开玩家列表。

## 构建与部署

```powershell
.\gradlew :plugins:tpa:build
python scripts/deploy_paper.py --plugin tpa
```
