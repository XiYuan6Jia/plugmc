#!/usr/bin/env python3
"""把本地生成的建筑结构放到服务器的指定位置，并校验它真的落块了。

为什么单独做这个脚本（而不是做成插件）：
  用户明确要求「本地生成建筑之后再将建筑上传到服务器上的合适位置」——
  建筑生成只有管理员会用，没必要常驻线上，也就不做运行时插件。

设计前提（全部来自实测，改之前先看 §依据）：
  - 线上实例没有 RCON，只能走面板 API 的 POST /command
  - /place template 会先 checkLoaded，目标区块没加载会直接失败 → 必须先 forceload
  - `execute if block ... run say ...` 永远没有回显（say 是广播）→ 判据用 run seed
  - 放置后「回 success 但零方块」是本项目踩过最深的坑 → 本脚本默认做落块校验

用法：
  # 打本机测试服（RCON）
  python place_template.py --local --template plugmc:house --at 120 -30 --ground \
      --local-nbt datapacks/plugmc-structures/data/plugmc/structure/house.nbt

  # 打线上实例（面板 API）
  python place_template.py --template plugmc:house --at 120 -30 --ground \
      --local-nbt datapacks/plugmc-structures/data/plugmc/structure/house.nbt

  # 只算不执行
  python place_template.py --local --template plugmc:house --at 0 0 --y 64 --dry-run
"""

from __future__ import annotations

import argparse
import pathlib
import re
import socket
import struct
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
RCON_SCRIPT = REPO_ROOT.parent / "server" / "scripts" / "rcon.ps1"

SEED_RE = re.compile(r"Seed: \[(-?\d+)\]", re.IGNORECASE)
NO_TEMPLATE_RE = re.compile(r'There is no template with ID "([^"]+)"', re.IGNORECASE)
FAILED_RE = re.compile(r"Failed to place template", re.IGNORECASE)


# ------------------------------------------------------------------ 传输层

class ApiTransport:
    """打线上实例：面板 API + 日志增量。"""

    kind = "api"

    def __init__(self) -> None:
        import deploy_test as dt

        env = dt.load_env(dt.ENV_FILES)
        self.api = dt.Api(
            env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
            dt.need(env, "MINEKUAI_API_KEY"),
            dt.need(env, "MINEKUAI_SERVER_ID"),
        )

    def state(self) -> str:
        return self.api.state()

    def run(self, command: str, wait: float = 3.0) -> str:
        before = self.api.log()
        self.api.request("POST", "/command", json={"command": command})
        deadline = time.time() + wait
        fresh = ""
        while time.time() < deadline:
            time.sleep(0.6)
            log = self.api.log()
            fresh = log[len(before):] if log.startswith(before) else log
            if fresh.strip():
                break
        return fresh


class RconTransport:
    """打本机测试服：直接用 Python 说 Source RCON 协议。

    原来想复用 server/scripts/rcon.ps1，但这台机器只有 Windows PowerShell 5.1
    （没有 pwsh），每次起一个 PowerShell 进程约 1 秒，找地面要几百次往返根本跑不动。
    这里自己实现：单条长连接 + 逐条命令，一次往返几毫秒。
    """

    kind = "rcon"

    def __init__(self, host: str, port: int, password: str) -> None:
        self.host = host
        self.port = port
        self.password = password
        self.sock = None
        self.req_id = 0
        self.connect()

    # -- 协议细节
    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("RCON 连接被对端关闭")
            buf += chunk
        return buf

    def _read_packet(self) -> tuple[int, int, str]:
        (length,) = struct.unpack("<i", self._recv_exact(4))
        body = self._recv_exact(length)
        req_id, ptype = struct.unpack("<ii", body[:8])
        text = body[8:-2].decode("utf-8", errors="replace")
        return req_id, ptype, text

    def _send_packet(self, ptype: int, text: str) -> int:
        self.req_id += 1
        payload = struct.pack("<ii", self.req_id, ptype) + text.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self.req_id

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        self.sock.settimeout(10)
        want = self._send_packet(3, self.password)  # SERVERDATA_AUTH
        # 鉴权失败时服务端会先回一个 id=-1 的空包
        for _ in range(2):
            req_id, _ptype, _text = self._read_packet()
            if req_id == -1:
                raise RuntimeError("RCON 鉴权失败：密码错误")
            if req_id == want:
                return
        raise RuntimeError("RCON 鉴权无响应")

    # -- 对外接口
    def state(self) -> str:
        try:
            out = self.run("list")
        except Exception as exc:  # noqa: BLE001
            return f"unreachable: {exc}"
        return "running" if ("players online" in out or "There are" in out) else "unknown"

    def run(self, command: str, wait: float = 0.0) -> str:
        self._send_packet(2, command)  # SERVERDATA_EXECCOMMAND
        parts: list[str] = []
        self.sock.settimeout(20)
        while True:
            req_id, _ptype, text = self._read_packet()
            if req_id != self.req_id:
                break
            parts.append(text)
            # 长回显会被拆成多个包：把超时压到很短，看还有没有后续
            self.sock.settimeout(0.15)
            try:
                self._peek_one()
            except (socket.timeout, TimeoutError):
                pass
            else:
                self.sock.settimeout(20)
                continue
            self.sock.settimeout(20)
            break
        return "".join(parts).strip()

    def _peek_one(self) -> None:
        """确认 socket 里还有可读数据（无数据则抛 socket.timeout）。"""
        if self.sock.recv(1, socket.MSG_PEEK):
            return
        raise socket.timeout()

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            finally:
                self.sock = None


# ------------------------------------------------------------------ 查询

def is_block(transport, x: int, y: int, z: int, block: str, wait: float = 2.5) -> bool:
    """该坐标是不是指定方块。用 run seed 做判据：命中才有 Seed 回显。

    区块没加载时 execute if block 会静默不输出，看起来与"不是这个方块"一样，
    所以调用方必须先 forceload 目标区块。
    """
    fresh = transport.run(f"execute if block {x} {y} {z} {block} run seed", wait=wait)
    return bool(SEED_RE.search(fresh))


def find_ground(transport, x: int, z: int, start_y: int, floor_y: int) -> int | None:
    """从 start_y 往下找最上面一块非空气的 y。找不到返回 None。

    先粗扫（步长 4）定位边界，再在最后一段逐格精扫 —— 逐格从 200 扫到 -64
    要 260 多次往返，太慢。
    """
    def solid(y: int) -> bool:
        return not is_block(transport, x, y, z, "minecraft:air", wait=1.2)

    coarse = 4
    above = None
    below = None
    y = start_y
    while y >= floor_y:
        if solid(y):
            below = y
            break
        above = y
        y -= coarse

    if below is None:
        return None
    if above is None:
        return below

    for yy in range(above, below - 1, -1):
        if solid(yy):
            return yy
    return below


def load_meta(local_nbt: pathlib.Path) -> dict:
    import blueprint_to_nbt as bp

    return bp.read_structure(local_nbt)


# ------------------------------------------------------------------ 主流程

def main() -> int:
    ap = argparse.ArgumentParser(description="把结构模板放到服务器指定位置并校验")
    ap.add_argument("--local", action="store_true", help="打本机测试服（RCON）而不是线上实例")
    ap.add_argument("--rcon-host", default="127.0.0.1")
    ap.add_argument("--rcon-port", type=int, default=25576)
    ap.add_argument("--rcon-password", default="probe123")
    ap.add_argument("--template", required=True, help="模板 id：<命名空间>:<名字>")
    ap.add_argument("--at", nargs=2, type=int, metavar=("X", "Z"), required=True)
    ap.add_argument("--y", type=int, default=None, help="放置基准 Y")
    ap.add_argument("--ground", action="store_true", help="自动向下找地面，放在地面上方一格")
    ap.add_argument("--search-from", type=int, default=200)
    ap.add_argument("--search-to", type=int, default=-64)
    ap.add_argument("--local-nbt", default=None, help="本地 .nbt 路径；给了就能算出范围并抽样校验")
    ap.add_argument("--rotation", type=int, default=None, help="0/90/180/270")
    ap.add_argument("--clear", action="store_true", help="放置前清空结构体积")
    ap.add_argument("--yes-clear", action="store_true", help="确认清空")
    ap.add_argument("--samples", type=int, default=12)
    ap.add_argument("--reload", action="store_true",
                    help="放置前先 reload（换过数据包文件必须加，否则服务端用的还是内存里的旧模板）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.y is None and not args.ground:
        raise SystemExit("要么给 --y，要么给 --ground")
    if args.clear and not args.yes_clear:
        raise SystemExit("--clear 会清空结构体积，必须再加 --yes-clear 确认")

    if args.local:
        transport = RconTransport(args.rcon_host, args.rcon_port, args.rcon_password)
    else:
        transport = ApiTransport()
    state = transport.state()
    print(f"==> 目标 {transport.kind}（state={state}）")
    if transport.kind == "api" and state != "running":
        raise SystemExit(f"实例状态是 {state}，不是 running —— 先启动再来")

    meta = load_meta(pathlib.Path(args.local_nbt)) if args.local_nbt else None
    x, z = args.at
    print(f"==> 模板 {args.template}")
    if meta:
        sx, sy, sz = meta["size"]
        print(f"    尺寸 {sx}x{sy}x{sz}，{len(meta['blocks'])} 个方块")

    if args.reload and not args.dry_run:
        # 换了数据包文件（比如重新打了 zip）必须先 reload —— 服务端缓存的是内存里的旧模板，
        # 不 reload 会一直放到上一版结构上，表现为"脚手架全对、屋顶整片 MISS"。
        print("==> reload 数据包")
        transport.run("reload", wait=10.0)
        time.sleep(2)

    if args.ground:
        # 找地面之前必须先加载这一列区块，否则 execute if block 静默返回 false，
        # 会把"最高处"当成地面（实测就是这么错的：直接报地面 y=200）。
        print(f"==> forceload 目标列 ({x},{z})")
        if not args.dry_run:
            print("    " + transport.run(f"forceload add {x} {z} {x} {z}", wait=6.0).strip())
            time.sleep(2)
        ground = find_ground(transport, x, z, args.search_from, args.search_to)
        if ground is None:
            raise SystemExit(f"在 ({x},{z}) 从 y={args.search_from} 向下找不到任何非空气方块")
        y = ground + 1
        print(f"    地面 y={ground} → 放在 y={y}")
    else:
        y = args.y

    print(f"==> 目标位置 ({x}, {y}, {z})")

    if args.clear and meta:
        sx, sy, sz = meta["size"]
        box = f"{x} {y} {z} {x + sx - 1} {y + sy - 1} {z + sz - 1}"
        print(f"==> 清空 {box}")
        if not args.dry_run:
            print("    " + transport.run(f"fill {box} minecraft:air", wait=6.0).strip())

    if meta:
        sx, _, sz = meta["size"]
        print(f"==> forceload ({x},{z}) .. ({x + sx - 1},{z + sz - 1})")
        if not args.dry_run:
            print("    " + transport.run(
                f"forceload add {x} {z} {x + sx - 1} {z + sz - 1}", wait=6.0).strip())

    place_cmd = f"place template {args.template} {x} {y} {z}"
    if args.rotation is not None:
        place_cmd += f" {args.rotation}"
    print(f"==> {place_cmd}")
    if args.dry_run:
        print("（--dry-run，未执行）")
        return 0

    result = transport.run(place_cmd, wait=6.0)
    print("    " + result.strip())
    if NO_TEMPLATE_RE.search(result):
        raise SystemExit("服务端找不到这个模板 —— 数据包没部署/没 reload，或命名空间名字写错")
    if FAILED_RE.search(result):
        raise SystemExit("放置失败（多数是区块没加载）")

    if not meta:
        print("==> 未给 --local-nbt，跳过落块校验")
        return 0

    blocks = meta["blocks"]
    step = max(1, len(blocks) // max(1, args.samples))
    picks = blocks[::step][: args.samples]
    print(f"==> 落块校验（抽 {len(picks)}/{len(blocks)} 块）")
    hit = 0
    for b in picks:
        bx, by, bz = x + b["x"], y + b["y"], z + b["z"]
        ok = is_block(transport, bx, by, bz, b["block"])
        hit += 1 if ok else 0
        print(f"    {'OK  ' if ok else 'MISS'} ({bx},{by},{bz}) {b['block']}")

    print(f"==> 结果：{hit}/{len(picks)} 命中")
    if hit == 0:
        print("    全部未命中 —— 先确认结构文件 palette 用的是 id/properties（全小写）")
        return 2
    if hit < len(picks):
        print("    部分未命中：可能被别的插件覆盖，或本地 .nbt 与线上模板不是同一份")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
