#!/usr/bin/env python3
"""按一张「摆放清单」批量放置结构，一次连接跑完，每项都做落块校验。

为什么需要它：
  主城不是一座房子，是几十上百个模块拼出来的。逐个调 place_template.py 有两个问题——
  ① 每个都要重连一次（RCON 还好，线上 API 每次都要拉日志增量，很慢）；
  ② 中途失败不知道停在哪一项，容易把街区摆成半成品。

和 place_template.py 的分工：
  - place_template.py：单座建筑，带 --ground 自动找地面、--clear 清体积，人盯着跑；
  - place_plan.py：一整张清单，批量跑，适合「街区级布景」这一步。

清单格式（JSON）：
  {
    "world": "world",
    "defaults": {"samples": 12, "reload": true},
    "items": [
      {"template": "plugmc:house",   "at": [100, -200], "y": -60},
      {"template": "plugmc:lamp_post","at": [96, -200], "ground": true},
      {"template": "plugmc:bench",   "at": [98, -196], "ground": true, "rotation": 90},
      {"template": "plugmc:fountain", "at": [104, -200], "ground": true, "clear": true, "yes_clear": true}
    ]
  }

用法：
  # 只算不执行，先看清单对不对
  python place_plan.py --plan plans/market.json --local --dry-run

  # 本机测试服
  python place_plan.py --plan plans/market.json --local

  # 线上实例
  python place_plan.py --plan plans/market.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import place_template as pt

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
STRUCT_DIR = REPO_ROOT / "datapacks" / "plugmc-structures" / "data"

# /place template 的 rotation 参数不是角度数字，而是 Rotation 枚举的序列化名
# （javap 读 net/minecraft/world/level/block/Rotation 的 ldc 常量：none / clockwise_90 / 180 / counterclockwise_90）。
# 实测 map：交给服务端的字符串 -> (顺时针角度, 坐标变换用的 quarter turns)
ROTATION_ARG: dict[int, str] = {
    0: "none",
    90: "clockwise_90",
    180: "180",
    270: "counterclockwise_90",
}


def rotation_arg(deg) -> str:
    """把清单里的 0/90/180/270 换成服务端认的枚举名。"""
    if deg is None:
        return ""
    d = int(deg) % 360
    if d not in ROTATION_ARG:
        raise SystemExit(f"rotation 只支持 0/90/180/270，给的是 {deg}")
    return ROTATION_ARG[d]


def rotate_local(x: int, z: int, deg: int) -> tuple[int, int]:
    """服务端绕「放置点」为原点旋转，所以旋转后坐标会跑到负方向。

    实测（bench 2x1，逐点扫 oak_stairs 落点）：
      none                local (1,0) -> ( 1, 0)
      clockwise_90         local (1,0) -> ( 0, 1)
      180                  local (1,0) -> (-1, 0)
      counterclockwise_90  local (1,0) -> ( 0,-1)
    归纳成：none (x,z) / cw90 (-z,x) / 180 (-x,-z) / ccw90 (z,-x)。
    """
    d = int(deg) % 360
    if d == 90:
        return -z, x
    if d == 180:
        return -x, -z
    if d == 270:
        return z, -x
    return x, z


def template_to_nbt(template: str) -> pathlib.Path | None:
    """plugmc:house -> datapacks/plugmc-structures/data/plugmc/structure/house.nbt"""
    if ":" not in template:
        return None
    ns, name = template.split(":", 1)
    p = STRUCT_DIR / ns / "structure" / f"{name}.nbt"
    return p if p.is_file() else None


def plan_floor_box(plan: dict, y: int) -> str | None:
    """清单里可选的 floor 字段 -> fill 用的坐标串。

    floor 是"整片街区共用的地面"，用原版 fill 铺一次，比给每个模块单独做底座省事：
      "floor": {"x0": -458, "z0": -458, "x1": -442, "z1": -438,
                "mat": "minecraft:stone_bricks", "depth": 1}
    depth 向下铺几层，默认 1。
    """
    f = plan.get("floor")
    if not f:
        return None
    x0, z0, x1, z1 = int(f["x0"]), int(f["z0"]), int(f["x1"]), int(f["z1"])
    depth = max(1, int(f.get("depth", 1)))
    return f"{min(x0, x1)} {y - depth} {min(z0, z1)} {max(x0, x1)} {y - 1} {max(z0, z1)}"


def load_plan(path: pathlib.Path) -> dict:
    plan = json.loads(path.read_text(encoding="utf-8"))
    items = plan.get("items")
    if not isinstance(items, list) or not items:
        raise SystemExit(f"{path} 里的 items 必须是非空数组")
    for i, it in enumerate(items):
        if "template" not in it:
            raise SystemExit(f"第 {i + 1} 项缺 template")
        if "at" not in it or len(it["at"]) != 2:
            raise SystemExit(f"第 {i + 1} 项（{it['template']}）的 at 必须是 [x, z]")
        if "y" not in it and not it.get("ground"):
            raise SystemExit(f"第 {i + 1} 项（{it['template']}）要么给 y，要么给 ground: true")
        if it.get("clear") and not it.get("yes_clear"):
            raise SystemExit(
                f"第 {i + 1} 项（{it['template']}）开了 clear 但没写 yes_clear —— "
                "清空会真的删方块，必须在清单里显式确认")
    return plan


def run_item(transport, item: dict, defaults: dict, dry_run: bool,
             anchor_y: int | None = None) -> tuple[int, int]:
    """放一项，返回 (命中数, 抽样数)。失败抛 SystemExit。

    anchor_y：整张清单共用的基准高度。给了就不逐项找地面 ——
    否则先放的喷泉、灯柱会被后面的项当成"地面"，一个广场摆出三种高度。
    """
    template = item["template"]
    x, z = item["at"]
    samples = int(item.get("samples", defaults.get("samples", 12)))

    nbt = template_to_nbt(template)
    meta = None
    if nbt is not None:
        meta = pt.load_meta(nbt)
        sx, sy, sz = meta["size"]
        print(f"    尺寸 {sx}x{sy}x{sz}，{len(meta['blocks'])} 个方块")
    else:
        print(f"    ! 本地找不到 {template} 的结构文件，跳过落块校验")
        sx = sy = sz = 0

    if anchor_y is not None:
        y = anchor_y
        print(f"    共用基准高度 y={y}")
    elif item.get("ground"):
        print(f"    forceload 目标列 ({x},{z})")
        if not dry_run:
            transport.run(f"forceload add {x} {z} {x} {z}", wait=6.0)
        # 找地面之前必须先把这一列 forceload，否则 execute if block 静默返回 false，
        # 会把"最高处"当成地面（实测就这么错过：直接报地面 y=200）。
        start_y = int(item.get("search_from", defaults.get("search_from", 200)))
        floor_y = int(item.get("search_to", defaults.get("search_to", -64)))
        ground = pt.find_ground(transport, x, z, start_y, floor_y)
        if ground is None:
            raise SystemExit(f"    ({x},{z}) 从 y={start_y} 向下找不到非空气方块")
        y = ground + 1
        print(f"    地面 y={ground} → 放在 y={y}")
    else:
        y = int(item["y"])

    # 旋转后结构会往放置点的负方向长（服务端绕放置点转），所以清空/预加载的范围
    # 要按 size 向两侧各留一份，不能只往正方向算。
    span_x = (sx - 1) if sx else 0
    span_z = (sz - 1) if sz else 0

    if item.get("clear") and meta:
        box = (f"{x - span_x} {y} {z - span_z} "
               f"{x + span_x} {y + sy - 1} {z + span_z}")
        print(f"    清空 {box}")
        if not dry_run:
            print("       " + transport.run(f"fill {box} minecraft:air", wait=6.0).strip())

    if meta:
        print(f"    forceload ({x - span_x},{z - span_z}) .. ({x + span_x},{z + span_z})")
        if not dry_run:
            transport.run(
                f"forceload add {x - span_x} {z - span_z} {x + span_x} {z + span_z}", wait=6.0)

    cmd = f"place template {template} {x} {y} {z}"
    arg = rotation_arg(item.get("rotation"))
    if arg:
        cmd += f" {arg}"
    print(f"    {cmd}")
    if dry_run:
        return 0, 0

    result = transport.run(cmd, wait=6.0)
    print("       " + result.strip())
    if pt.NO_TEMPLATE_RE.search(result):
        raise SystemExit("    服务端找不到这个模板 —— 数据包没部署/没 reload，或命名空间名字写错")
    if pt.FAILED_RE.search(result):
        raise SystemExit("    放置失败（多数是区块没加载）")

    if not meta:
        return 0, 0

    blocks = meta["blocks"]
    step = max(1, len(blocks) // max(1, samples))
    picks = blocks[::step][:samples]

    # 旋转放置时坐标和方块朝向都会变：
    #   - 坐标按 rotate_local 绕放置点转（服务端有 bug 也照它的实际行为来）
    #   - 方块 state 会被顺时针旋转，所以带属性的只比 id（execute if block 不带属性 = 任意 state）
    deg = int(item.get("rotation") or 0) % 360

    def rotated(b: dict) -> tuple[int, int, int, str]:
        rx, rz = rotate_local(b["x"], b["z"], deg)
        by = b["y"]
        name = b["block"]
        if deg and "[" in name:
            name = name.split("[", 1)[0]
        return rx, by, rz, name

    hit = 0
    misses = []
    for b in picks:
        bx, by, bz, want = rotated(b)
        if pt.is_block(transport, x + bx, y + by, z + bz, want):
            hit += 1
        else:
            misses.append((x + bx, y + by, z + bz, want))
    print(f"    落块校验 {hit}/{len(picks)} 命中")
    for m in misses[:5]:
        print(f"      MISS ({m[0]},{m[1]},{m[2]}) {m[3]}")
    if len(misses) > 5:
        print(f"      …… 另有 {len(misses) - 5} 处未命中")
    return hit, len(picks)


def main() -> int:
    ap = argparse.ArgumentParser(description="按摆放清单批量放置结构")
    ap.add_argument("--plan", required=True, help="清单 JSON 路径")
    ap.add_argument("--local", action="store_true", help="打本机测试服（RCON）")
    ap.add_argument("--rcon-host", default="127.0.0.1")
    ap.add_argument("--rcon-port", type=int, default=25576)
    ap.add_argument("--rcon-password", default="probe123")
    ap.add_argument("--only", default=None, help="只跑 template 名字里含这个子串的项")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--stop-on-miss", action="store_true",
                    help="有一项没全中就停下（默认跑完整张清单再汇总）")
    args = ap.parse_args()

    plan = load_plan(pathlib.Path(args.plan))
    defaults = plan.get("defaults") or {}
    items = plan["items"]
    if args.only:
        items = [it for it in items if args.only in it["template"]]
        if not items:
            raise SystemExit(f"--only {args.only} 没匹配到任何项")

    print(f"==> 清单 {args.plan}：{len(items)} 项")
    for it in items:
        nbt = template_to_nbt(it["template"])
        print(f"    - {it['template']:<24} at {tuple(it['at'])}"
              f"{' ground' if it.get('ground') else ' y=' + str(it.get('y'))}"
              f"{' rot=' + str(it['rotation']) if it.get('rotation') is not None else ''}"
              f"{'  [nbt 缺失]' if nbt is None else ''}")

    if args.local:
        transport = pt.RconTransport(args.rcon_host, args.rcon_port, args.rcon_password)
    else:
        transport = pt.ApiTransport()
    state = transport.state()
    print(f"==> 目标 {transport.kind}（state={state}）")
    if transport.kind == "api" and state != "running":
        raise SystemExit(f"实例状态是 {state}，不是 running —— 先启动再来")

    if defaults.get("reload") and not args.dry_run:
        # 换了数据包文件必须 reload，否则服务端用的还是内存里的旧模板
        print("==> reload 数据包")
        transport.run("reload", wait=10.0)

    # 清单级基准高度：给了 anchor 就只探测一次，整张清单共用同一个 y。
    # 不这么做的话，先放的喷泉/灯柱会被后面的项当成"地面"，一个广场摆出三种高度。
    anchor_y: int | None = None
    anchor = plan.get("anchor")
    if anchor and len(anchor) == 2:
        ax, az = anchor
        if args.dry_run:
            print(f"==> 基准高度锚点 ({ax},{az})：dry-run 不探测")
        else:
            print(f"==> 基准高度锚点 ({ax},{az})")
            transport.run(f"forceload add {ax} {az} {ax} {az}", wait=6.0)
            g = pt.find_ground(transport, ax, az,
                               int(defaults.get("search_from", 200)),
                               int(defaults.get("search_to", -64)))
            if g is None:
                raise SystemExit(f"锚点 ({ax},{az}) 向下找不到非空气方块")
            anchor_y = g + 1
            print(f"    锚点地面 y={g} → 全清单统一放在 y={anchor_y}")

    total_hit = total_pick = 0
    bad: list[str] = []

    # 地面：在放模块之前铺，否则模块会被地面盖住。
    floor_spec = plan.get("floor")
    if floor_spec and anchor_y is not None:
        box = plan_floor_box(plan, anchor_y)
        mat = floor_spec.get("mat", "minecraft:stone_bricks")
        print(f"==> 铺地面 {box}  {mat}")
        if not args.dry_run:
            r = transport.run(f"fill {box} {mat}", wait=10.0)
            print("    " + r.strip())
            if "Too many blocks" in r:
                raise SystemExit("    单次 fill 上限 32768 格，把 floor 拆成几段")
    elif floor_spec and anchor_y is None:
        print("==> 清单有 floor 但没 anchor，跳过铺地面（需要基准高度才知道铺在哪一层）")

    for i, it in enumerate(items, 1):
        print(f"==> [{i}/{len(items)}] {it['template']}")
        try:
            hit, pick = run_item(transport, it, defaults, args.dry_run, anchor_y)

        except SystemExit as exc:
            bad.append(f"{it['template']} @ {tuple(it['at'])}：{exc}")
            print(f"    XX 失败：{exc}")
            if args.stop_on_miss:
                break
            continue
        total_hit += hit
        total_pick += pick
        if pick and hit < pick:
            bad.append(f"{it['template']} @ {tuple(it['at'])}：{hit}/{pick}")
            if args.stop_on_miss:
                break

    print("==> 汇总")
    if total_pick:
        print(f"    落块校验合计 {total_hit}/{total_pick} 命中")
    if bad:
        print(f"    {len(bad)} 项未全中：")
        for b in bad:
            print(f"      - {b}")
        return 1
    print("    全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
