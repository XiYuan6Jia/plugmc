#!/usr/bin/env python3
"""把一份简单的方块清单转成 Minecraft 结构文件（.nbt），供 /place template 使用。

为什么需要它：26.3 服务端没有第三方插件时，外部工具产出的建筑唯一能进世界的原生通道是
`/place template <name>`，而它读的是数据包里的 `data/<ns>/structure/<name>.nbt`
（gzip 压缩的 NBT，键为 size / entities / blocks / palette / DataVersion）。

格式依据（实测自 26.3 原版 structure 文件，非推测）：
  - blocks[].pos    = TAG_List of 3 x TAG_Int，相对结构原点的方块坐标
  - blocks[].state  = TAG_Int，是 palette 列表的下标，不是方块名字符串
  - palette[]       = TAG_Compound，{Name: "minecraft:xxx", Properties: {...}}
  - DataVersion     = TAG_Int，26.3 为 5023

用法：
  python blueprint_to_nbt.py --spec 蓝图.json --out 输出.nbt
  python blueprint_to_nbt.py --spec 蓝图.json --ns myserver --name fountain \
      --datapack-dir <服务端>/world/datapacks
"""
from __future__ import annotations

import argparse
import gzip
import json
import pathlib
import struct
import sys

# 26.3 的 DataVersion（= version.json 里的 world_version），实测值
DATA_VERSION_26_3 = 5023

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _w_string(out: bytearray, s: str) -> None:
    data = s.encode("utf-8")
    out += struct.pack(">H", len(data))
    out += data


def _w_int(out: bytearray, v: int) -> None:
    out += struct.pack(">i", int(v))


def _w_compound_head(out: bytearray, name: str) -> None:
    out.append(TAG_COMPOUND)
    _w_string(out, name)


def _w_int_list(out: bytearray, name: str, values: list[int]) -> None:
    out.append(TAG_LIST)
    _w_string(out, name)
    out.append(TAG_INT)
    _w_int(out, len(values))
    for v in values:
        _w_int(out, v)


def _w_empty_compound_list(out: bytearray, name: str) -> None:
    out.append(TAG_LIST)
    _w_string(out, name)
    out.append(TAG_COMPOUND)
    _w_int(out, 0)


# ---------- 方块状态规范化 ----------

def normalize_block(raw: str) -> tuple[str, dict[str, str]]:
    """把 "minecraft:oak_stairs[facing=north,half=bottom]" 拆成 (name, properties)。"""
    raw = raw.strip()
    if not raw:
        raise ValueError("空方块名")
    if ":" not in raw.split("[", 1)[0]:
        raw = "minecraft:" + raw
    if "[" not in raw:
        return raw, {}
    name, rest = raw.split("[", 1)
    rest = rest.rstrip("]")
    props: dict[str, str] = {}
    for part in rest.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"方块状态属性缺少 '='：{part}")
        k, v = part.split("=", 1)
        props[k.strip()] = v.strip()
    return name.strip(), props


# ---------- 结构构建 ----------

def build_structure(blocks: list[dict]) -> bytes:
    """blocks: [{"x":0,"y":0,"z":0,"block":"stone"}]，坐标必须是非负（结构坐标从 0 开始）。"""
    if not blocks:
        raise ValueError("方块清单为空")

    palette_index: dict[tuple[str, tuple], int] = {}
    palette: list[tuple[str, dict[str, str]]] = []
    packed: list[tuple[int, int, int, int]] = []

    min_x = min(b["x"] for b in blocks)
    min_y = min(b["y"] for b in blocks)
    min_z = min(b["z"] for b in blocks)
    max_x = max(b["x"] for b in blocks)
    max_y = max(b["y"] for b in blocks)
    max_z = max(b["z"] for b in blocks)

    for b in blocks:
        name, props = normalize_block(b["block"])
        key = (name, tuple(sorted(props.items())))
        idx = palette_index.get(key)
        if idx is None:
            idx = len(palette)
            palette_index[key] = idx
            palette.append((name, props))
        packed.append((
            b["x"] - min_x,
            b["y"] - min_y,
            b["z"] - min_z,
            idx,
        ))

    size = [max_x - min_x + 1, max_y - min_y + 1, max_z - min_z + 1]

    out = bytearray()
    # 根节点：TAG_Compound(0x0a) + 空名字。原版 structure 文件开头就是 0a 00 00，
    # 少了这个 0x0a 会被当成非法 NBT，表现为「There is no template with ID ...」。
    out.append(TAG_COMPOUND)
    _w_string(out, "")

    _w_int_list(out, "size", size)
    _w_empty_compound_list(out, "entities")

    # blocks
    out.append(TAG_LIST)
    _w_string(out, "blocks")
    out.append(TAG_COMPOUND)
    _w_int(out, len(packed))
    for (px, py, pz, st) in packed:
        _w_int_list(out, "pos", [px, py, pz])
        out.append(TAG_INT)
        _w_string(out, "state")
        _w_int(out, st)
        out.append(TAG_END)

    # palette
    out.append(TAG_LIST)
    _w_string(out, "palette")
    out.append(TAG_COMPOUND)
    _w_int(out, len(palette))
    for name, props in palette:
        # 26.3 的 structure palette 用 id / properties（全小写）。
        # 旧版的 Name / Properties（大写）在 26.3 仍能被 NBT 读出来，
        # 但方块解析会全部失败 —— 表现是 /place template 报 success 却一块都不落。
        out.append(TAG_STRING)
        _w_string(out, "id")
        _w_string(out, name)
        if props:
            _w_compound_head(out, "properties")
            for k, v in props.items():
                out.append(TAG_STRING)
                _w_string(out, k)
                _w_string(out, v)
            out.append(TAG_END)
        out.append(TAG_END)

    out.append(TAG_INT)
    _w_string(out, "DataVersion")
    _w_int(out, DATA_VERSION_26_3)
    out.append(TAG_END)

    return bytes(out)


def write_nbt(path: pathlib.Path, raw: bytes) -> int:
    payload = gzip.compress(raw, compresslevel=9, mtime=0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return len(payload)


# ---------- 附带的生成器：主城用的几种装饰 ----------

def gen_lamp_post(base: str = "minecraft:stone_bricks",
                  pillar: str = "minecraft:oak_fence",
                  lamp: str = "minecraft:lantern[hanging=false]") -> list[dict]:
    """路灯：底座 1 块 + 3 格柱 + 顶部灯。"""
    blocks = [{"x": 0, "y": 0, "z": 0, "block": base}]
    for y in (1, 2, 3):
        blocks.append({"x": 0, "y": y, "z": 0, "block": pillar})
    blocks.append({"x": 0, "y": 4, "z": 0, "block": lamp})
    return blocks


def gen_bench() -> list[dict]:
    """长椅：2 格台阶 + 靠背。"""
    return [
        {"x": 0, "y": 0, "z": 0, "block": "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"},
        {"x": 1, "y": 0, "z": 0, "block": "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"},
        {"x": 0, "y": 1, "z": 0, "block": "minecraft:oak_trapdoor[facing=north,half=top,open=false]"},
        {"x": 1, "y": 1, "z": 0, "block": "minecraft:oak_trapdoor[facing=north,half=top,open=false]"},
    ]


def gen_fountain() -> list[dict]:
    """喷泉：3x3 石砖池 + 中间水柱 + 四角灯。"""
    blocks: list[dict] = []
    for x in range(3):
        for z in range(3):
            edge = (x in (0, 2)) or (z in (0, 2))
            if edge:
                blocks.append({"x": x, "y": 0, "z": z, "block": "minecraft:stone_bricks"})
            else:
                blocks.append({"x": x, "y": 0, "z": z, "block": "minecraft:water[level=0]"})
    blocks.append({"x": 1, "y": 1, "z": 1, "block": "minecraft:stone_bricks"})
    blocks.append({"x": 1, "y": 2, "z": 1, "block": "minecraft:water[level=0]"})
    for (x, z) in ((0, 0), (2, 0), (0, 2), (2, 2)):
        blocks.append({"x": x, "y": 1, "z": z, "block": "minecraft:sea_lantern"})
    return blocks


GENERATORS = {
    "lamp_post": gen_lamp_post,
    "bench": gen_bench,
    "fountain": gen_fountain,
}


def main() -> int:
    ap = argparse.ArgumentParser(description="方块清单 / 内置装饰 → .nbt 结构文件")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--spec", help="蓝图 JSON：{\"blocks\":[{x,y,z,block}]} 或直接是数组")
    src.add_argument("--preset", choices=sorted(GENERATORS), help="内置装饰生成器")
    ap.add_argument("--out", required=True, help="输出 .nbt 路径")
    ap.add_argument("--ns", default="plugmc", help="数据包命名空间（--datapack-dir 时需要）")
    ap.add_argument("--name", default=None, help="结构名（--datapack-dir 时需要，默认取输出文件名）")
    ap.add_argument("--datapack-dir", default=None,
                    help="同时写入数据包：<dir>/<ns>-structures/data/<ns>/structure/<name>.nbt")
    args = ap.parse_args()

    if args.preset:
        blocks = GENERATORS[args.preset]()
    else:
        spec = json.loads(pathlib.Path(args.spec).read_text(encoding="utf-8"))
        blocks = spec["blocks"] if isinstance(spec, dict) else spec
        if not isinstance(blocks, list):
            raise SystemExit("蓝图格式不对：需要 blocks 数组")

    raw = build_structure(blocks)
    out_path = pathlib.Path(args.out)
    size = write_nbt(out_path, raw)
    print(f"已写出 {out_path}  {size} B（原始 NBT {len(raw)} B，{len(blocks)} 个方块）")

    if args.datapack_dir:
        name = args.name or out_path.stem
        pack_root = pathlib.Path(args.datapack_dir) / f"{args.ns}-structures"
        nbt_path = pack_root / "data" / args.ns / "structure" / f"{name}.nbt"
        _ = write_nbt(nbt_path, raw)
        mcmeta = {
            "pack": {
                "description": f"{args.ns} 结构包（26.3 / format 121）",
                "min_format": 121,
                "max_format": 121,
            }
        }
        (pack_root / "pack.mcmeta").write_text(
            json.dumps(mcmeta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"已写入数据包 {pack_root}")
        print(f"游戏内使用： /place template {args.ns}:{name}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
