"""扫描 hub 维度某一层的占用网格（只读）。

用法:
    python scripts/_scan_hub.py --y 64 --x -64 64 --z -64 64 --step 4
输出用 # 表示非空气、. 表示空气。
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import place_template as pt


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--y", type=int, default=64)
    ap.add_argument("--x", type=int, nargs=2, default=[-64, 64])
    ap.add_argument("--z", type=int, nargs=2, default=[-64, 64])
    ap.add_argument("--step", type=int, default=4)
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    args = ap.parse_args()

    base = pt.RconTransport("127.0.0.1", args.port, args.password)
    base.connect()
    t = pt.DimensionTransport(base, "minecraft:hub")

    xs = list(range(args.x[0], args.x[1] + 1, args.step))
    zs = list(range(args.z[0], args.z[1] + 1, args.step))

    # 先 forceload 整片，否则虚空维度读不到方块
    t.run("forceload add %d %d %d %d" % (args.x[0], args.z[0], args.x[1], args.z[1]), wait=10.0)

    print("y=%d  step=%d" % (args.y, args.step))
    print("      " + "".join("%4d" % x for x in xs))
    for z in zs:
        row = []
        for x in xs:
            solid = pt.is_block(t, x, args.y, z, "minecraft:air", wait=2.0)
            row.append("." if solid else "#")
        print("z=%4d " % z + "".join("   %s" % c for c in row))

    t.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
