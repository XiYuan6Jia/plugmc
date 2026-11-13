"""线上主城连通性抽查（只读）。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.path.insert(0, str(Path(__file__).resolve().parent))

import place_template as pt  # noqa: E402

POINTS = [
    (0, 64, 30, "南大街地面"),
    (0, 65, 30, "南大街通行"),
    (0, 64, 60, "南大街尽头"),
    (-40, 64, 0, "西广场地面"),
    (-52, 65, 0, "西广场通行"),
    (-56, 64, -16, "西广场西北角"),
    (-40, 64, 29, "商业街地面"),
    (-40, 65, 29, "商业街通行"),
    (-18, 64, -28, "北庭地面"),
    (-27, 64, 0, "环廊西"),
    (27, 64, 0, "环廊东"),
    (0, 64, -27, "环廊北"),
    (0, 64, 27, "环廊南"),
    (20, 64, 20, "环廊西南角"),
    (-20, 64, -20, "环廊东北角"),
]


def main() -> int:
    transport = pt.ApiTransport()
    bad = 0
    for x, y, z, label in POINTS:
        out = transport.run(
            f"execute in minecraft:hub run execute if block {x} {y} {z} minecraft:air run seed",
            wait=1.4,
        )
        is_air = "Seed:" in (out or "")
        state = "空气" if is_air else "有方块"
        expect = "空气" if label.endswith("通行") else "有方块"
        ok = state == expect
        if not ok:
            bad += 1
        print(f"{'OK ' if ok else '!! '} {label:<10} ({x:>4},{y},{z:>4}) {state}")
    print(f"\n抽查 {len(POINTS)} 点，异常 {bad} 点")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
