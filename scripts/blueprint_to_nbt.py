#!/usr/bin/env python3
"""把一份简单的方块清单转成 Minecraft 结构文件（.nbt），供 /place template 使用。

为什么需要它：26.3 服务端没有第三方插件时，外部工具产出的建筑唯一能进世界的原生通道是
`/place template <name>`，而它读的是数据包里的 `data/<ns>/structure/<name>.nbt`
（gzip 压缩的 NBT，键为 size / entities / blocks / palette / DataVersion）。

格式依据（实测自 26.3 原版 structure 文件，非推测）：
  - blocks[].pos    = TAG_List of 3 x TAG_Int，相对结构原点的方块坐标
  - blocks[].state  = TAG_Int，是 palette 列表的下标，不是方块名字符串
  - palette[]       = TAG_Compound，{id: "minecraft:xxx", properties: {...}}
                      **键名必须是 id / properties（全小写）**。写成旧版的
                      Name / Properties，NBT 照样能读进来、/place template 也照样
                      报 success，但一个方块都不会落下，且日志无任何报错。
  - DataVersion     = TAG_Int，26.3 为 5023
  - 根节点要先写 TAG_Compound 的标记字节 0x0a，否则报
    `There is no template with ID "..."`

两种输入形态：
  1) 方块清单 {"blocks":[{x,y,z,block}]}  —— 手摆 / 外部工具直接给坐标
  2) 建筑设计 {"palette":{...},"volumes":[...],"windows":[...],"roof":{...},"details":[...]}
     —— 脚本自己展开成方块，适合做主城单体建筑

用法：
  python blueprint_to_nbt.py --spec 蓝图.json --out 输出.nbt
  python blueprint_to_nbt.py --spec 蓝图.json --ns myserver --name house \
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
    raw, _ = _build_with_meta(blocks)
    return raw


def _build_with_meta(blocks: list[dict]) -> tuple[bytes, dict]:
    """返回 (NBT 字节, 元信息)。元信息给 place_template.py 做放置后校验用。"""
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

    meta = {
        "size": size,
        "count": len(packed),
        "blocks": [
            {"x": px, "y": py, "z": pz, "block": palette[st][0]}
            for (px, py, pz, st) in packed
        ],
    }
    return bytes(out), meta


def read_structure(path: pathlib.Path) -> dict:
    """把已经写出的 .nbt 反读回来，供放置后校验用（只支持本脚本产出的子集）。"""
    raw = gzip.decompress(path.read_bytes())
    if not raw or raw[0] != TAG_COMPOUND:
        raise ValueError("不是本脚本产出的结构文件")

    pos = [1]  # 跳过根节点的 TAG_Compound 标记字节

    def rd(n: int) -> bytes:
        data = raw[pos[0]:pos[0] + n]
        pos[0] += n
        return data

    def name() -> str:
        (ln,) = struct.unpack(">H", rd(2))
        return rd(ln).decode("utf-8")

    def ints(n: int) -> list[int]:
        return list(struct.unpack(f">{n}i", rd(4 * n)))

    name()  # 根名（空）
    size: list[int] = []
    palette: list[str] = []
    blocks: list[dict] = []

    tag = rd(1)[0]
    while tag != TAG_END:
        key = name()
        if key == "size":
            rd(1)
            (cnt,) = ints(1)
            size = ints(cnt)
        elif key == "entities":
            rd(1)
            ints(1)
        elif key == "blocks":
            rd(1)
            (cnt,) = ints(1)
            for _ in range(cnt):
                # TAG_List 的每个元素没有各自的类型字节，直接就是 compound 的内容
                px = py = pz = st = 0
                sub = rd(1)[0]
                while sub != TAG_END:
                    skey = name()
                    if skey == "pos":
                        rd(1)
                        (n,) = ints(1)
                        px, py, pz = ints(n)
                    elif skey == "state":
                        (st,) = ints(1)
                    else:
                        raise ValueError(f"blocks 里有意外键 {skey!r}")
                    sub = rd(1)[0]
                blocks.append({"x": px, "y": py, "z": pz, "state": st})
        elif key == "palette":
            rd(1)
            (cnt,) = ints(1)
            for _ in range(cnt):
                pid = ""
                sub = rd(1)[0]
                while sub != TAG_END:
                    skey = name()
                    if skey == "id":
                        # name() 已经把 id 后面的「2 字节长度 + UTF-8」读走了类型字节
                        pid = name()
                    elif skey == "properties":
                        # sub 已经吃掉了 TAG_Compound 的类型字节，这里直接读内容
                        sub2 = rd(1)[0]
                        while sub2 != TAG_END:
                            name()
                            name()  # 属性值也是 TAG_String：2 字节长度 + UTF-8
                            sub2 = rd(1)[0]
                    else:
                        raise ValueError(f"palette 里有意外键 {skey!r}（是不是写成了旧版 Name/Properties？）")
                    sub = rd(1)[0]
                palette.append(pid)
        elif key == "DataVersion":
            ints(1)
        else:
            raise ValueError(f"根节点里有意外键 {key!r}")
        tag = rd(1)[0]

    for b in blocks:
        b["block"] = palette[b.pop("state")]
    return {"size": size, "blocks": blocks}


def write_nbt(path: pathlib.Path, raw: bytes) -> int:
    payload = gzip.compress(raw, compresslevel=9, mtime=0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return len(payload)


# ---------- 体量展开：把"建筑设计"展开成方块 ----------

def _put(store: dict, x: int, y: int, z: int, mat: str) -> None:
    store[(x, y, z)] = mat


def _fill(store: dict, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, mat: str) -> None:
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                _put(store, x, y, z, mat)


def _palette_lookup(palette: dict, key: str) -> str:
    if key not in palette:
        raise ValueError(f"palette 里没有 {key!r}；现有：{', '.join(sorted(palette))}")
    return palette[key]


def _expand_volume(store: dict, vol: dict, palette: dict) -> None:
    """一个体量：外壳 + 逐层楼板。体量内部留空，供开窗与内装。"""
    x0, y0, z0 = int(vol["x"]), int(vol["y"]), int(vol["z"])
    w, h, d = int(vol["w"]), int(vol["h"]), int(vol["d"])
    if w < 2 or h < 2 or d < 2:
        raise ValueError(f"体量至少 2x2x2：{vol}")
    wall = _palette_lookup(palette, vol.get("mat", "wall"))
    x1, y1, z1 = x0 + w - 1, y0 + h - 1, z0 + d - 1

    # 外壳：四面墙 + 地板 + 天花板
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            _put(store, x, y0, z, wall)
            _put(store, x, y1, z, wall)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            _put(store, x, y, z0, wall)
            _put(store, x, y, z1, wall)
        for z in range(z0, z1 + 1):
            _put(store, x0, y, z, wall)
            _put(store, x1, y, z, wall)

    # 楼板：把层高切成 floors 段，每段底部铺一层
    floors = max(1, int(vol.get("floors", 1)))
    if floors > 1:
        floor_mat = _palette_lookup(palette, vol.get("floor_mat", "floor"))
        for i in range(1, floors):
            fy = y0 + (h * i) // floors
            if fy >= y1:
                continue
            for x in range(x0 + 1, x1):
                for z in range(z0 + 1, z1):
                    _put(store, x, fy, z, floor_mat)

    # 内部掏空（去掉外壳后剩下的都清成空气占位，后续开窗/内装再覆盖）
    for y in range(y0 + 1, y1):
        for x in range(x0 + 1, x1):
            for z in range(z0 + 1, z1):
                if (x, y, z) not in store:
                    _put(store, x, y, z, "minecraft:air")

    # 基座：往下多铺一层，房子才不会像"贴"在草皮上
    base_key = vol.get("base_mat")
    if base_key:
        base = _palette_lookup(palette, base_key)
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                _put(store, x, y0 - 1, z, base)

    # 室内照明：每层房间中心立一盏灯。室内不放光，窗户在白天也是一片黑。
    if vol.get("lit"):
        lamp = _palette_lookup(palette, vol.get("light_mat", "light"))
        levels = [y0] + [y0 + (h * i) // floors for i in range(1, floors)] + [y1]
        cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
        for lo, hi in zip(levels, levels[1:]):
            fy = lo + 1
            if fy >= hi:
                continue
            for xx, zz in ((cx, cz), (cx, z0 + 1 + (z1 - z0 - 1) // 2)):
                if x0 < xx < x1 and z0 < zz < z1:
                    _put(store, xx, fy, zz, lamp)


def _face_axes(face: str) -> tuple[str, int]:
    """返回 (水平轴 'x'|'z', 该面的固定坐标表达式用的方向符号)。"""
    if face in ("north", "south"):
        return "x", (-1 if face == "north" else 1)
    if face in ("east", "west"):
        return "z", (1 if face == "east" else -1)
    raise ValueError(f"face 只能是 north/south/east/west：{face}")


def _expand_windows(store: dict, vol: dict, win: dict, palette: dict) -> None:
    """在一个面上按 cols×rows 网格开窗。u/v 是网格起点在面内的偏移。"""
    x0, y0, z0 = int(vol["x"]), int(vol["y"]), int(vol["z"])
    w, h, d = int(vol["w"]), int(vol["h"]), int(vol["d"])
    face = win["face"]
    axis, sign = _face_axes(face)
    glass = _palette_lookup(palette, win.get("mat", "glass"))
    frame_mat = palette.get(win.get("frame") or "trim")

    span = w if axis == "x" else d
    cell_w, cell_h = int(win.get("w", 1)), int(win.get("h", 1))
    cols, rows = int(win.get("cols", 1)), int(win.get("rows", 1))
    gap_u, gap_v = int(win.get("gap_u", 0)), int(win.get("gap_v", 0))
    u0, v0 = int(win.get("u", 1)), int(win.get("v", 1))

    for r in range(rows):
        for c in range(cols):
            u = u0 + c * (cell_w + gap_u)
            v = v0 + r * (cell_h + gap_v)
            if u + cell_w > span - 1:
                break
            for dy in range(cell_h):
                yy = y0 + v + dy
                if yy <= y0 or yy >= y0 + h - 1:
                    continue
                for du in range(cell_w):
                    uu = u + du
                    if axis == "x":
                        xx, zz = x0 + uu, (z0 if sign < 0 else z0 + d - 1)
                    else:
                        xx, zz = (x0 + w - 1 if sign > 0 else x0), z0 + uu
                    # 只有两个方向都够大（>=3）才画边框。任一方向 <3 时整格都是"边缘"，
                    # 照常画边框会把玻璃全涂成木头（曾整扇窗丢失）；1×2 的窄窗还会因为
                    # 玻璃板互相连接在客户端拼成"十"字，所以窄窗一律实心玻璃、不画框。
                    has_frame = frame_mat is not None and cell_w >= 3 and cell_h >= 3
                    is_edge = has_frame and (
                        du in (0, cell_w - 1) or dy in (0, cell_h - 1)
                    )
                    mat = frame_mat if is_edge else glass
                    _put(store, xx, yy, zz, mat)


def _stair(mat: str, facing: str) -> str:
    """楼梯必须带朝向，否则所有台阶朝同一侧，屋顶看起来像一堆方块。"""
    return f"{mat}[facing={facing},half=bottom]"


def _expand_roof(store: dict, spec: dict, volumes: list[dict], palette: dict) -> None:
    """屋顶只按「主楼」= 第一个体量算，足够做单体建筑。

    斜坡用楼梯而不是整块：楼梯的 facing 决定高的一侧朝哪 ——
    facing=north 时高的一侧在南边（step 面朝玩家）。所以北坡写 facing=north。
    """
    if not spec:
        return
    vol = volumes[0]
    x0, y0, z0 = int(vol["x"]), int(vol["y"]), int(vol["z"])
    w, h, d = int(vol["w"]), int(vol["h"]), int(vol["d"])
    top = y0 + h
    over = int(spec.get("overhang", 0))
    ax0, az0 = x0 - over, z0 - over
    aw, ad = w + over * 2, d + over * 2
    kind = spec.get("type", "flat")
    mat = _palette_lookup(palette, spec.get("mat", "roof"))
    # 每上升一层向内收几格。默认 2 是"缓坡"：收 1 格是 45°，9 格宽的房子会叠出
    # 5 层、和楼体一样高，看起来像金字塔而不是屋顶。
    step = max(1, int(spec.get("step", 2)))

    if kind in ("flat", "none"):
        _fill(store, ax0, top - 1, az0, ax0 + aw - 1, top - 1, az0 + ad - 1, mat)
        if spec.get("parapet"):
            pm = palette.get(spec.get("parapet_mat") or "trim", mat)
            for x in range(ax0, ax0 + aw):
                _put(store, x, top, az0, pm)
                _put(store, x, top, az0 + ad - 1, pm)
            for z in range(az0, az0 + ad):
                _put(store, ax0, top, z, pm)
                _put(store, ax0 + aw - 1, top, z, pm)
        return

    if kind == "gable":
        # 脊沿较长边；短边方向逐层内收，两端用整块封山墙
        span = min(aw, ad)
        ridge_along_x = aw >= ad
        gable_mat = palette.get(spec.get("gable_mat") or "wall", mat)
        for i, inset in enumerate(range(0, span, step)):
            y = top + i
            if ridge_along_x:
                zn, zs = az0 + inset, az0 + ad - 1 - inset
                if zn > zs:
                    break
                # 整层铺满：只画一圈边框的话，圈内会直接露出天花板，俯视像个坑
                for x in range(ax0 + inset, ax0 + aw - inset):
                    for z in range(zn, zs + 1):
                        _put(store, x, y, z, mat)
                # 上表面边缘做成台阶，才看得出坡度
                for x in range(ax0 + inset, ax0 + aw - inset):
                    if zn != zs:
                        _put(store, x, y, zn, _stair(mat, "north"))
                        _put(store, x, y, zs, _stair(mat, "south"))
                # 两端山墙：把三角形补满
                for zz in range(zn, zs + 1):
                    _put(store, ax0 + inset, y, zz, gable_mat)
                    _put(store, ax0 + aw - 1 - inset, y, zz, gable_mat)
            else:
                xw, xe = ax0 + inset, ax0 + aw - 1 - inset
                if xw > xe:
                    break
                for z in range(az0 + inset, az0 + ad - inset):
                    for x in range(xw, xe + 1):
                        _put(store, x, y, z, mat)
                for z in range(az0 + inset, az0 + ad - inset):
                    if xw != xe:
                        _put(store, xw, y, z, _stair(mat, "west"))
                        _put(store, xe, y, z, _stair(mat, "east"))
                for xx in range(xw, xe + 1):
                    _put(store, xx, y, az0 + inset, gable_mat)
                    _put(store, xx, y, az0 + ad - 1 - inset, gable_mat)
        return

    if kind == "hip":
        span = min(aw, ad)
        for i, inset in enumerate(range(0, span, step)):
            y = top + i
            xw, xe = ax0 + inset, ax0 + aw - 1 - inset
            zn, zs = az0 + inset, az0 + ad - 1 - inset
            if xw > xe or zn > zs:
                break
            for x in range(xw, xe + 1):
                for z in range(zn, zs + 1):
                    _put(store, x, y, z, mat)
            if xw != xe:
                for x in range(xw, xe + 1):
                    _put(store, x, y, zn, _stair(mat, "north"))
                    _put(store, x, y, zs, _stair(mat, "south"))
            if zn != zs:
                for z in range(zn, zs + 1):
                    _put(store, xw, y, z, _stair(mat, "west"))
                    _put(store, xe, y, z, _stair(mat, "east"))
        return

    raise ValueError(f"未知屋顶类型 {kind!r}（可用：flat / gable / hip）")


def _expand_details(store: dict, details: list[dict], volumes: list[dict], palette: dict) -> None:
    """细节：门洞、台阶、柱子、灯。全部按「第一个体量」定位。"""
    if not details:
        return
    vol = volumes[0]
    x0, y0, z0 = int(vol["x"]), int(vol["y"]), int(vol["z"])
    w, h, d = int(vol["w"]), int(vol["h"]), int(vol["d"])

    for det in details:
        kind = det.get("type")
        if kind == "door":
            face = det.get("face", "south")
            axis, sign = _face_axes(face)
            u = int(det.get("u", (w if axis == "x" else d) // 2))
            dh = int(det.get("h", 2))
            dw = int(det.get("w", 1))
            for du in range(dw):
                uu = u + du
                for dy in range(dh):
                    yy = y0 + 1 + dy
                    if axis == "x":
                        xx, zz = x0 + uu, (z0 if sign < 0 else z0 + d - 1)
                    else:
                        xx, zz = (x0 + w - 1 if sign > 0 else x0), z0 + uu
                    _put(store, xx, yy, zz, "minecraft:air")
            if det.get("door_block"):
                # 门板放在门洞内侧一格
                for dy in range(dh):
                    yy = y0 + 1 + dy
                    if axis == "x":
                        xx, zz = x0 + u, (z0 + 1 if sign < 0 else z0 + d - 2)
                    else:
                        xx, zz = (x0 + w - 2 if sign > 0 else x0 + 1), z0 + u
                    _put(store, xx, yy, zz, det["door_block"])
        elif kind == "stairs":
            face = det.get("face", "south")
            axis, sign = _face_axes(face)
            u = int(det.get("u", (w if axis == "x" else d) // 2))
            steps = int(det.get("steps", 3))
            mat = _palette_lookup(palette, det.get("mat", "trim"))
            for i in range(steps):
                yy = y0 - steps + i
                off = steps - i
                if axis == "x":
                    xx = x0 + u
                    zz = (z0 - off) if sign < 0 else (z0 + d - 1 + off)
                else:
                    zz = z0 + u
                    xx = (x0 + w - 1 + off) if sign > 0 else (x0 - off)
                _put(store, xx, yy, zz, mat)
        elif kind == "pillar":
            mat = _palette_lookup(palette, det.get("mat", "trim"))
            for p in det.get("at", []):
                px, pz = int(p[0]), int(p[1])
                for yy in range(y0, y0 + h):
                    _put(store, px, yy, pz, mat)
        elif kind == "light":
            mat = det.get("mat", "minecraft:lantern")
            for p in det.get("at", []):
                _put(store, int(p[0]), int(p[1]), int(p[2]), mat)
        else:
            raise ValueError(f"未知细节类型 {kind!r}")


def expand_blueprint(spec: dict) -> list[dict]:
    """把一份"建筑设计"展开成方块清单。

    支持的最小 schema：
        palette:  {wall, trim, roof, glass, floor, ...: "minecraft:xxx"}
        volumes:  [{x,y,z,w,h,d,mat?,floors?,floor_mat?}]
        windows:  [{face,volume?,u,v,w,h,cols,rows,gap_u,gap_v,mat?,frame?}]
        roof:     {type: flat|gable|hip, mat?, overhang?, parapet?, parapet_mat?}
        details:  [{type: door|stairs|pillar|light, ...}]
    """
    palette = spec.get("palette") or {}
    volumes = spec.get("volumes") or []
    if not volumes:
        raise ValueError("blueprint 至少要有一个 volume")

    store: dict[tuple[int, int, int], str] = {}
    for vol in volumes:
        _expand_volume(store, vol, palette)

    for win in spec.get("windows") or []:
        idx = int(win.get("volume", 0))
        _expand_windows(store, volumes[idx], win, palette)

    _expand_roof(store, spec.get("roof") or {}, volumes, palette)
    _expand_details(store, spec.get("details") or [], volumes, palette)

    # 去掉占位空气：structure 里没写的格子本来就是空气，写进去反而增大体积
    blocks = [
        {"x": x, "y": y, "z": z, "block": mat}
        for (x, y, z), mat in store.items()
        if mat != "minecraft:air"
    ]
    if not blocks:
        raise ValueError("展开后没有方块")
    return blocks


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

# 建筑设计类的预设（走 expand_blueprint，比手摆方块更适合做单体建筑）
PRESET_SPECS: dict[str, dict] = {
    "house": {
        "palette": {
            "wall": "minecraft:oak_planks",
            "trim": "minecraft:stripped_oak_log",
            "roof": "minecraft:dark_oak_stairs",
            "glass": "minecraft:glass",
            "floor": "minecraft:oak_planks",
            "base": "minecraft:stone_bricks",
            "light": "minecraft:lantern[hanging=false]",
        },
        "volumes": [{"x": 0, "y": 0, "z": 0, "w": 9, "h": 5, "d": 7, "floors": 2,
                     "base_mat": "base", "lit": True}],
        "windows": [
            {"face": "south", "u": 1, "v": 1, "w": 2, "h": 1, "cols": 2, "rows": 2, "gap_u": 3, "gap_v": 1},
            {"face": "north", "u": 1, "v": 1, "w": 2, "h": 1, "cols": 2, "rows": 2, "gap_u": 3, "gap_v": 1},
            {"face": "east", "u": 1, "v": 1, "w": 2, "h": 1, "cols": 2, "rows": 2, "gap_u": 1, "gap_v": 1},
        ],
        "roof": {"type": "hip", "mat": "roof", "overhang": 1, "step": 1},
        "details": [
            {"type": "door", "face": "south", "u": 4, "h": 2, "w": 1},
            {"type": "pillar", "mat": "trim", "at": [[0, 0], [8, 0], [0, 6], [8, 6]]},
        ],
    },
}


def main() -> int:
    ap = argparse.ArgumentParser(description="方块清单 / 建筑设计蓝图 → .nbt 结构文件")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--spec", help="蓝图 JSON：方块清单 {blocks:[...]} 或建筑设计 {volumes:[...]}")
    src.add_argument("--preset", choices=sorted(list(GENERATORS) + list(PRESET_SPECS)),
                     help="内置预设（lamp_post/bench/fountain 是装饰件；house 是单体建筑）")
    ap.add_argument("--out", required=True, help="输出 .nbt 路径")
    ap.add_argument("--ns", default="plugmc", help="数据包命名空间（--datapack-dir 时需要）")
    ap.add_argument("--name", default=None, help="结构名（--datapack-dir 时需要，默认取输出文件名）")
    ap.add_argument("--datapack-dir", default=None,
                    help="同时写入数据包：<dir>/<ns>-structures/data/<ns>/structure/<name>.nbt")
    args = ap.parse_args()

    if args.preset:
        if args.preset in PRESET_SPECS:
            blocks = expand_blueprint(PRESET_SPECS[args.preset])
            print(f"已展开预设 {args.preset} → {len(blocks)} 个方块")
        else:
            blocks = GENERATORS[args.preset]()
    else:
        spec = json.loads(pathlib.Path(args.spec).read_text(encoding="utf-8"))
        # 两种输入形态：{blocks:[...]} 直接给方块；{volumes:[...]} 是建筑设计，需要展开
        if isinstance(spec, list):
            blocks = spec
        elif isinstance(spec.get("blocks"), list):
            blocks = spec["blocks"]
        elif isinstance(spec.get("volumes"), list):
            blocks = expand_blueprint(spec)
            print(f"已展开建筑设计：{len(spec['volumes'])} 个体量 → {len(blocks)} 个方块")
        else:
            raise SystemExit("蓝图格式不对：需要 blocks 数组，或含 volumes 的建筑设计")
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
