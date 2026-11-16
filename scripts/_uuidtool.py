"""把 Minecraft `data get entity @s UUID` 的 [I; a,b,c,d] 换成标准 UUID。

用法：
    python scripts\\_uuidtool.py 1969842166 1582057809 -1149246874 1478711357
    python scripts\\_uuidtool.py --text "Minecart has the following entity data: [I; 1, 2, 3, 4]"

注意：不要把本文件命名为 _uuid.py —— 会遮蔽标准库 uuid。
"""

import re
import sys
import uuid


def convert(values):
    raw = b"".join((v & 0xFFFFFFFF).to_bytes(4, "big") for v in values)
    return uuid.UUID(bytes=raw)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1

    if args[0] == "--text":
        text = " ".join(args[1:])
        blocks = re.findall(r"\[I;\s*([-\d,\s]+)\]", text)
        if not blocks:
            print("没找到 [I; ...] 形式的数据")
            return 1
        for block in blocks:
            values = [int(x.strip()) for x in block.split(",") if x.strip()]
            if len(values) != 4:
                print(f"这段不是 4 个整数：{block}")
                continue
            print(convert(values))
        return 0

    values = [int(x) for x in args]
    if len(values) != 4:
        print("需要 4 个整数（data get entity 的 [I; a,b,c,d]）")
        return 1
    print(convert(values))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
