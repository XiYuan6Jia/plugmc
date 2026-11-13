#!/usr/bin/env python3
"""把 .nbt 结构文件渲染成等轴测预览图（PNG），纯本地、不开游戏。

用途：主城这类建筑落地前先看一眼比例和配色，不必进服务器、不必抢前台窗口。

用法：
    # 单个结构
    python scripts/preview_structure.py datapacks/plugmc-structures/data/plugmc/structure/house.nbt
    python scripts/preview_structure.py <a.nbt> --out preview.png --scale 4 --view iso|top|front

    # 一整张摆放清单（整片街区）
    python scripts/preview_structure.py --plan plans/plaza.json --out plaza.png --view all

坐标：结构文件里 x 向东、y 向上、z 向南。
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import blueprint_to_nbt as bp  # noqa: E402

from PIL import Image, ImageDraw  # noqa: E402

# 常见方块的近似颜色（取 MC 原版贴图的主色，够看清体量与配色）
COLORS: dict[str, tuple[int, int, int]] = {
    "oak_planks": (184, 148, 95),
    "spruce_planks": (114, 84, 48),
    "birch_planks": (196, 178, 123),
    "dark_oak_planks": (66, 43, 20),
    "stripped_oak_log": (185, 160, 106),
    "stripped_spruce_log": (110, 86, 55),
    "oak_log": (109, 84, 51),
    "stone_bricks": (122, 122, 122),
    "stone": (127, 127, 127),
    "cobblestone": (110, 110, 110),
    "deepslate_bricks": (70, 70, 74),
    "oak_stairs": (184, 148, 95),
    "dark_oak_stairs": (74, 48, 24),
    "spruce_stairs": (114, 84, 48),
    "glass": (168, 216, 232),
    "glass_pane": (168, 216, 232),
    "lantern": (245, 215, 110),
    "sea_lantern": (200, 232, 220),
    "torch": (255, 210, 120),
    "water": (60, 110, 200),
    "oak_trapdoor": (150, 118, 72),
    "oak_fence": (150, 118, 72),
    "bricks": (150, 96, 84),
    "white_wool": (233, 236, 236),
    "spruce_trapdoor": (96, 70, 40),
    # 主城风格（与 HubLobby 一致）
    "smooth_quartz": (235, 229, 222),
    "quartz_block": (236, 230, 223),
    "chiseled_quartz_block": (231, 225, 217),
    "quartz_pillar": (233, 227, 219),
    "quartz_bricks": (229, 223, 215),
    "quartz_stairs": (235, 229, 222),
    "smooth_quartz_slab": (235, 229, 222),
    "polished_andesite": (132, 135, 133),
    "andesite": (136, 136, 136),
    "smooth_stone": (158, 158, 158),
    "azalea_leaves": (92, 132, 62),
    "barrel": (140, 100, 60),
}
DEFAULT_COLOR = (200, 90, 200)  # 未知方块用洋红，一眼能看出漏配颜色


def base_name(block: str) -> str:
    """minecraft:dark_oak_stairs[facing=north,...] -> dark_oak_stairs"""
    name = block.split("[", 1)[0]
    return name.split(":", 1)[-1]


def shade(rgb: tuple[int, int, int], k: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(c * k))) for c in rgb)


def render_iso(blocks: list[dict], scale: int = 4) -> Image.Image:
    HW, HH, BH = 12, 6, 12  # 半宽、半高（顶部菱形）、方块高度，最后再乘 scale
    pts = []
    for b in blocks:
        x, y, z = b["x"], b["y"], b["z"]
        sx = (x - z) * HW
        sy = (x + z) * HH - y * BH
        pts.append((sx, sy, b["block"]))
    if not pts:
        raise ValueError("结构里没有方块")
    min_x = min(p[0] for p in pts) - HW * 2
    max_x = max(p[0] for p in pts) + HW * 2
    min_y = min(p[1] for p in pts) - BH * 2
    max_y = max(p[1] for p in pts) + BH * 3

    w = int((max_x - min_x)) * scale
    h = int((max_y - min_y)) * scale
    img = Image.new("RGB", (w, h), (250, 250, 252))
    d = ImageDraw.Draw(img)

    def P(px: float, py: float) -> tuple[float, float]:
        return ((px - min_x) * scale, (py - min_y) * scale)

    # 画家算法：x+z 越大越靠近观察者，所以从远（小）往近（大）画
    ordered = sorted(blocks, key=lambda b: (b["x"] + b["z"], b["y"]))
    for b in ordered:
        x, y, z = b["x"], b["y"], b["z"]
        cx = (x - z) * HW
        cy = (x + z) * HH - y * BH
        col = COLORS.get(base_name(b["block"]), DEFAULT_COLOR)
        top = [P(cx, cy - HH), P(cx + HW, cy), P(cx, cy + HH), P(cx - HW, cy)]
        left = [P(cx - HW, cy), P(cx, cy + HH), P(cx, cy + HH + BH), P(cx - HW, cy + BH)]
        right = [P(cx, cy + HH), P(cx + HW, cy), P(cx + HW, cy + BH), P(cx, cy + HH + BH)]
        d.polygon(left, fill=shade(col, 0.72), outline=shade(col, 0.5))
        d.polygon(right, fill=shade(col, 0.55), outline=shade(col, 0.4))
        d.polygon(top, fill=col, outline=shade(col, 0.8))
    return img


def render_top(blocks: list[dict], scale: int = 8) -> Image.Image:
    """俯视图：看平面布局与占地。"""
    xs = [b["x"] for b in blocks]
    zs = [b["z"] for b in blocks]
    x0, z0 = min(xs), min(zs)
    w, h = max(xs) - x0 + 1, max(zs) - z0 + 1
    img = Image.new("RGB", (w * scale, h * scale), (250, 250, 252))
    d = ImageDraw.Draw(img)
    tops: dict[tuple[int, int], int] = {}
    for b in blocks:
        k = (b["x"], b["z"])
        tops[k] = max(tops.get(k, -999), b["y"])
    for (x, z), ymax in tops.items():
        col = None
        for b in blocks:
            if b["x"] == x and b["z"] == z and b["y"] == ymax:
                col = COLORS.get(base_name(b["block"]), DEFAULT_COLOR)
                break
        d.rectangle(
            [(x - x0) * scale, (z - z0) * scale,
             (x - x0 + 1) * scale - 1, (z - z0 + 1) * scale - 1],
            fill=col or DEFAULT_COLOR, outline=(230, 230, 232),
        )
    return img


def render_front(blocks: list[dict], scale: int = 8) -> Image.Image:
    """正视图（沿 -z 看）：看层高与屋顶轮廓。"""
    xs = [b["x"] for b in blocks]
    ys = [b["y"] for b in blocks]
    x0, y0 = min(xs), min(ys)
    w = max(xs) - x0 + 1
    h = max(ys) - y0 + 1
    img = Image.new("RGB", (w * scale, h * scale), (250, 250, 252))
    d = ImageDraw.Draw(img)
    near: dict[tuple[int, int], tuple[int, str]] = {}
    for b in blocks:
        k = (b["x"], b["y"])
        if k not in near or b["z"] < near[k][0]:
            near[k] = (b["z"], b["block"])
    for (x, y), (_, block) in near.items():
        col = COLORS.get(base_name(block), DEFAULT_COLOR)
        px = (x - x0) * scale
        py = (h - 1 - (y - y0)) * scale
        d.rectangle([px, py, px + scale - 1, py + scale - 1], fill=col, outline=(235, 235, 237))
    return img


def lobby_blocks(floor_y: int = 64, radius: int = 24) -> list[dict]:
    """画出主城大厅的占地与屋顶，用来检查新街区有没有压到大厅或跑到屋顶上。

    只画三层参考物，不做完整建模：
      - y=floor_y        地板整片（square，radius 半径）
      - y=floor_y+11     屋顶整片（同尺寸，中央 13x13 换成玻璃天窗）
      - 四道外墙的顶点    仅 y=floor_y+1 一圈，标出边界
    """
    out: list[dict] = []
    for x in range(-radius, radius + 1):
        for z in range(-radius, radius + 1):
            out.append({"x": x, "y": floor_y, "z": z,
                        "block": "minecraft:smooth_quartz"})
            skylight = abs(x) <= 6 and abs(z) <= 6
            out.append({"x": x, "y": floor_y + 11, "z": z,
                        "block": "minecraft:glass" if skylight else "minecraft:smooth_quartz"})
            if max(abs(x), abs(z)) == radius:
                out.append({"x": x, "y": floor_y + 1, "z": z,
                            "block": "minecraft:stone_bricks"})
    return out


def merge_plan(plan_path: pathlib.Path, include_lobby: bool = False) -> list[dict]:
    """把一张摆放清单展开成一整片街区，返回合并后的方块列表。

    每个模块的局部坐标按清单里的 at 平移；rotation 只支持 0/90/180/270，
    和 /place template 的 rotation 参数语义一致（绕 Y 轴顺时针）。
    """
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    struct_dir = (pathlib.Path(__file__).resolve().parents[1]
                  / "datapacks" / "plugmc-structures" / "data")
    # 清单顶层 "y" 是模块起始层（显式写死，虚空维度必用）；
    # 老清单可能用 "anchor_y"，都没有就当 0。
    anchor_y = int(plan.get("y", plan.get("anchor_y", 0)))
    out: list[dict] = []
    for it in plan.get("items", []):
        tmpl = it.get("template", "")
        if ":" not in tmpl:
            print(f"  跳过 {tmpl!r}：模板 id 必须是 <命名空间>:<名字>", file=sys.stderr)
            continue
        ns, name = tmpl.split(":", 1)
        nbt = struct_dir / ns / "structure" / f"{name}.nbt"
        if not nbt.is_file():
            print(f"  跳过 {tmpl}：找不到 {nbt}", file=sys.stderr)
            continue
        data = bp.read_structure(nbt)
        sx, _, sz = data["size"]
        ox, oz = it["at"]
        oy = it.get("y", anchor_y)
        rot = int(it.get("rotation", 0)) % 360
        for b in data["blocks"]:
            x, y, z = b["x"], b["y"], b["z"]
            # 绕 Y 轴旋转后重新平移到非负，和 /place template 的朝向对齐
            if rot == 90:
                x, z = sz - 1 - z, x
            elif rot == 180:
                x, z = sx - 1 - x, sz - 1 - z
            elif rot == 270:
                x, z = z, sx - 1 - x
            out.append({"x": ox + x, "y": oy + y, "z": oz + z, "block": b["block"]})

    # 清单里的地面也画出来，否则预览看不到广场铺装。
    # floor 允许单个矩形，也允许矩形数组（连接带），这里统一按数组处理。
    fl = plan.get("floor")
    if fl:
        y = anchor_y - 1
        for one in (fl if isinstance(fl, list) else [fl]):
            mat = one.get("mat", "minecraft:stone_bricks")
            for x in range(min(int(one["x0"]), int(one["x1"])), max(int(one["x0"]), int(one["x1"])) + 1):
                for z in range(min(int(one["z0"]), int(one["z1"])), max(int(one["z0"]), int(one["z1"])) + 1):
                    out.append({"x": x, "y": y, "z": z, "block": mat})
    if include_lobby:
        out.extend(lobby_blocks())
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="把 .nbt 结构（或摆放清单）渲染成预览图")
    ap.add_argument("nbt", nargs="?", help="结构文件路径")
    ap.add_argument("--plan", help="摆放清单 JSON；给了它就渲染整片街区而不是单个结构")
    ap.add_argument("--plans", nargs="+", help="多份清单一起渲染（看整座主城），配合 --with-lobby")
    ap.add_argument("--with-lobby", action="store_true",
                    help="叠加主城大厅（地板 + 屋顶 + 外墙轮廓），检查新街区有没有压到大厅")
    ap.add_argument("--out", help="输出 PNG（默认与输入同名 .png）")
    ap.add_argument("--scale", type=int, default=4, help="缩放倍数，默认 4")
    ap.add_argument("--view", choices=["iso", "top", "front", "all"], default="iso")
    args = ap.parse_args()

    if not args.plan and not args.plans and not args.nbt:
        ap.error("要么给结构文件路径，要么给 --plan / --plans")

    if args.plans:
        blocks = []
        for p in args.plans:
            pp = pathlib.Path(p)
            if not pp.is_file():
                print(f"找不到清单：{pp}", file=sys.stderr)
                return 2
            got = merge_plan(pp, include_lobby=args.with_lobby)
            print(f"  {pp.name}: {len(got)} 个方块")
            blocks.extend(got)
        if not blocks:
            print("清单里没有任何可放置的模块", file=sys.stderr)
            return 2
        src = pathlib.Path(args.plans[0])
        xs = [b["x"] for b in blocks]
        ys = [b["y"] for b in blocks]
        zs = [b["z"] for b in blocks]
        size = [max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1]
        print(f"整城：{size[0]}x{size[1]}x{size[2]}，{len(blocks)} 个方块"
              f"（x {min(xs)}..{max(xs)} / y {min(ys)}..{max(ys)} / z {min(zs)}..{max(zs)}）")
    elif args.plan:
        plan_path = pathlib.Path(args.plan)
        if not plan_path.is_file():
            print(f"找不到清单：{plan_path}", file=sys.stderr)
            return 2
        blocks = merge_plan(plan_path, include_lobby=args.with_lobby)
        if not blocks:
            print("清单里没有任何可放置的模块", file=sys.stderr)
            return 2
        src = plan_path
        xs = [b["x"] for b in blocks]
        ys = [b["y"] for b in blocks]
        zs = [b["z"] for b in blocks]
        size = [max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1]
        print(f"街区 {src.name}：{size[0]}x{size[1]}x{size[2]}，{len(blocks)} 个方块")
    else:
        src = pathlib.Path(args.nbt)
        if not src.exists():
            print(f"找不到文件：{src}", file=sys.stderr)
            return 2
        data = bp.read_structure(src)
        blocks = data["blocks"]
        size = data["size"]
        print(f"结构 {src.name}：尺寸 {size[0]}x{size[1]}x{size[2]}，{len(blocks)} 个方块")

    from collections import Counter
    for name, n in Counter(base_name(b["block"]) for b in blocks).most_common():
        mark = "" if name in COLORS else "  ← 预览没配色，用洋红代替"
        print(f"  {name:<24} {n:>5}{mark}")

    views = ["iso", "top", "front"] if args.view == "all" else [args.view]
    for v in views:
        img = {"iso": render_iso, "top": render_top, "front": render_front}[v](blocks, args.scale)
        if args.out:
            out = pathlib.Path(args.out)
            if len(views) > 1:
                out = out.with_name(f"{out.stem}_{v}{out.suffix or '.png'}")
        else:
            out = src.with_name(f"{src.stem}_{v}.png")
        img.save(out)
        print(f"已写出 {out}  （{img.width}x{img.height}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
