#!/usr/bin/env python3
"""检查装备外观各部位的显示体有没有埋进玩家模型（或护甲层）里。

玩家模型几何不是我猜的，是从 26.3 客户端 jar 的
``net/minecraft/client/model/HumanoidModel.class`` 的 ``createMesh`` 字节码里读出来的：

    head       box(-4,-8,-4) size(8,8,8)   pose(0, 0, 0)
    body       box(-4, 0,-2) size(8,12,4)  pose(0, 0, 0)
    right_arm  box(-3,-2,-2) size(4,12,4)  pose(-5, 2, 0)
    left_arm   box(-1,-2,-2) size(4,12,4)  pose( 5, 2, 0) mirror
    right_leg  box(-2, 0,-2) size(4,12,4)  pose(-1.9, 12, 0)
    left_leg   box(-2, 0,-2) size(4,12,4)  pose( 1.9, 12, 0) mirror

模型坐标 16 像素 = 1 格，y 轴朝下、y=24 处是脚底，渲染时整体还要乘 0.9375。
所以 1 像素 = 0.9375 / 16 = 0.05859375 格。

护甲层用 ``CubeDeformation(1.0F)`` 向外扩 1 像素（本文件按 +1px 算，属于宽松估计）。

用法::

    python scripts\\check_cosmetic_clearance.py
    python scripts\\check_cosmetic_clearance.py --config 其它config.yml --verbose

判定：部位中心 ± 半尺寸的立方体只要和任一模型盒子相交，就算"埋进去了"。
半尺寸按 display 的 scale 折半算 —— ItemDisplay 的 FIXED 朝向内部还会再缩 0.5，
所以这是**上界**：这里报 clear，实际一定 clear；这里报 overlap，实际可能只是贴着。
"""

from __future__ import annotations

import argparse
import math
import pathlib
import sys

import yaml

PX = 0.9375 / 16.0  # 1 像素 = 多少格


def box(x0, y0, z0, w, h, d, pose_x, pose_y, pose_z, grow=0.0):
    """把 createMesh 里的 box+pose（像素、y 朝下）换成玩家脚底坐标系（格、y 朝上）。"""
    # 像素 → 格，y 从"模型 y 朝下、脚底在 y=24"翻成"脚底在 y=0、朝上"
    def px(v):
        return v * PX

    left = px(pose_x + x0 - grow)
    right = px(pose_x + x0 + w + grow)
    front = px(pose_z + z0 - grow)
    back = px(pose_z + z0 + d + grow)
    low = px(24.0 - (pose_y + y0 + h) - grow)
    high = px(24.0 - (pose_y + y0) + grow)
    return (left, right, front, back, low, high)


# 玩家模型（不含护甲）
HUMANOID = {
    "head": box(-4, -8, -4, 8, 8, 8, 0, 0, 0),
    "body": box(-4, 0, -2, 8, 12, 4, 0, 0, 0),
    "right_arm": box(-3, -2, -2, 4, 12, 4, -5, 2, 0),
    "left_arm": box(-1, -2, -2, 4, 12, 4, 5, 2, 0),
    "right_leg": box(-2, 0, -2, 4, 12, 4, -1.9, 12, 0),
    "left_leg": box(-2, 0, -2, 4, 12, 4, 1.9, 12, 0),
}

# 护甲层：向外扩 1 像素
ARMORED = {
    "head+头盔": box(-4, -8, -4, 8, 8, 8, 0, 0, 0, grow=1.0),
    "body+胸甲": box(-4, 0, -2, 8, 12, 4, 0, 0, 0, grow=1.0),
    "right_arm+护臂": box(-3, -2, -2, 4, 12, 4, -5, 2, 0, grow=1.0),
    "left_arm+护臂": box(-1, -2, -2, 4, 12, 4, 5, 2, 0, grow=1.0),
    "right_leg+护腿": box(-2, 0, -2, 4, 12, 4, -1.9, 12, 0, grow=1.0),
    "left_leg+护腿": box(-2, 0, -2, 4, 12, 4, 1.9, 12, 0, grow=1.0),
}

# 模型 x 轴与"玩家右手方向"相反（MC 里 -x 是右手边），下面的盒子左右对称，
# 判定时用绝对值即可，方向只影响提示文案。


def interval_gap(a0, a1, b0, b1):
    """两个闭区间的重叠量；≤0 表示分开（返回负值就是间隙）。"""
    if a1 < b0:
        return a1 - b0
    if b1 < a0:
        return b1 - a0
    return min(a1, b1) - max(a0, b0)


def effective_half(slot):
    """显示体真正占多大。

    ItemDisplay 的 FIXED 朝向内部还会把模型再缩一半（和展示框里一样），
    所以 FIXED 的实际半尺寸是 scale/4；其它朝向按整块 scale 算（保守）。
    """
    scale = float(slot.get("scale", 1.0))
    transform = str(slot.get("transform", "FIXED")).upper()
    return scale / 4.0 if transform == "FIXED" else scale / 2.0


def analyze_slot(slot):
    """算出一个部位的占位立方体与所有模型盒子的间隙。

    返回 ``(forward, up, right, half, rows, orbit)``；``orbit`` 不为 None 时
    表示这是环绕槽，里面是 ``(radius, inner)``，``rows`` 的 r 列含义变成
    "模型盒子角点到轴线的最远距离 - 圆的内圈半径"（正数=撞上）。
    """
    offset = slot.get("offset") or [0.0, 0.0, 0.0]
    forward, up, right = (float(offset[0]), float(offset[1]), float(offset[2]))
    half = effective_half(slot)
    boxes = {**HUMANOID, **ARMORED}

    if slot.get("orbit"):
        # 环绕槽不站固定点，它绕玩家轴线画一个半径 radius 的圆。
        # 判定：圆的内圈如果比模型盒子的最远角点还远，就永远撞不上。
        radius = float(slot.get("orbit-radius", 0.8))
        inner = radius - half
        rows = []
        for label, (left, right_b, front, back, low, high) in boxes.items():
            corner = max(
                math.hypot(abs(left), abs(front)),
                math.hypot(abs(left), abs(back)),
                math.hypot(abs(right_b), abs(front)),
                math.hypot(abs(right_b), abs(back)),
            )
            gap_u = interval_gap(up - half, up + half, low, high)
            gap_r = corner - inner
            rows.append((label, gap_u > 0 and gap_r > 0, gap_r, gap_u, 0.0))
        return forward, up, right, half, rows, (radius, inner)

    # 玩家朝 +Z 时的右方向是 -X，模型里的 +x 就是左手边；这里只用绝对值判定左右。
    span_r = (abs(right) - half, abs(right) + half)
    span_u = (up - half, up + half)
    span_f = (forward - half, forward + half)

    rows = []
    for label, (left, right_b, front, back, low, high) in boxes.items():
        br = (min(abs(left), abs(right_b)), max(abs(left), abs(right_b)))
        gap_r = interval_gap(span_r[0], span_r[1], br[0], br[1])
        gap_u = interval_gap(span_u[0], span_u[1], low, high)
        gap_f = interval_gap(span_f[0], span_f[1], front, back)
        # 三轴同时重叠才算撞上
        hit = gap_r > 0 and gap_u > 0 and gap_f > 0
        rows.append((label, hit, gap_r, gap_u, gap_f))
    return forward, up, right, half, rows, None


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="检查装备外观部位的穿模间隙")
    ap.add_argument(
        "--config",
        default=str(
            pathlib.Path(__file__).resolve().parent.parent
            / "plugins"
            / "cosmetic"
            / "src"
            / "main"
            / "resources"
            / "config.yml"
        ),
    )
    ap.add_argument("--verbose", action="store_true", help="列出每个部位对所有模型盒子的间隙")
    args = ap.parse_args(argv)

    data = yaml.safe_load(pathlib.Path(args.config).read_text(encoding="utf-8"))
    slots = data.get("slots") or {}
    bad = 0
    for name, slot in slots.items():
        got = analyze_slot(slot)
        forward, up, right, half, rows, orbit = got
        hits = [r for r in rows if r[1]]
        if orbit is not None:
            radius, inner = orbit
            print(
                f"[环] {name:9s} 半径 {radius:.2f} 内圈 {inner:.2f} "
                f"up={up:.2f} scale={float(slot.get('scale', 1.0)):.2f}"
            )
        else:
            print(
                f"[{'撞' if hits else '净'}] {name:9s} "
                f"offset=[{forward:+.2f}, {up:.2f}, {right:+.2f}] "
                f"scale={float(slot.get('scale', 1.0)):.2f} 半尺寸≈{half:.3f}"
            )
        if hits:
            bad += 1
            for label, _, gr, gu, gf in hits:
                print(f"        ∩ {label}  间隙 r={gr:+.3f} u={gu:+.3f} f={gf:+.3f}")
        if args.verbose:
            for label, hit, gr, gu, gf in rows:
                mark = "撞" if hit else "净"
                print(f"        {mark} {label:14s} r={gr:+.3f} u={gu:+.3f} f={gf:+.3f}")

    print(f"\n结论：{len(slots)} 个部位，{bad} 个埋进模型。")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
