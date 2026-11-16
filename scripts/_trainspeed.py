"""连续采样某台矿车的 x 坐标，算出瞬时速度，用来验证「进站减速」。

用法：
    python scripts\\_trainspeed.py <UUID> [秒数]

为什么不直接用 rcon_cmd.py 循环：每次调用都要起一个 python 进程（约 1~3 秒），
采样间隔根本不受控，算出来的「速度」是采样间隔的函数而不是车速。
这里复用一条长连接，按固定 100ms 采样。
"""

import argparse
import socket
import struct
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def pack(req_id: int, ptype: int, text: str) -> bytes:
    body = struct.pack("<ii", req_id, ptype) + text.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def read_packet(sock: socket.socket) -> str:
    raw = sock.recv(4)
    if len(raw) < 4:
        return ""
    length = struct.unpack("<i", raw)[0]
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    return data[12:-2].decode("utf-8", errors="replace")


class Rcon:
    def __init__(self, host: str, port: int, password: str) -> None:
        self.sock = socket.create_connection((host, port), timeout=15)
        self.sock.sendall(pack(1, 3, password))
        read_packet(self.sock)
        self.next_id = 2

    def run(self, command: str) -> str:
        self.next_id += 1
        self.sock.sendall(pack(self.next_id, 2, command))
        return read_packet(self.sock)


def parse_x(text: str):
    """从 `... entity data: [213.35d, 100.0625d, 200.5d]` 里抠出 x。"""
    marker = "data: ["
    at = text.find(marker)
    if at < 0:
        return None
    tail = text[at + len(marker):]
    first = tail.split(",")[0].strip().rstrip("dD")
    try:
        return float(first)
    except ValueError:
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("uuid")
    parser.add_argument("seconds", nargs="?", type=float, default=30.0)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25576)
    parser.add_argument("--password", default="probe123")
    parser.add_argument("--interval", type=float, default=0.1)
    args = parser.parse_args()

    rcon = Rcon(args.host, args.port, args.password)
    command = (
        "execute in minecraft:overworld as "
        + args.uuid
        + " run data get entity @s Pos"
    )
    started = time.time()
    last_x = None
    last_t = None
    while time.time() - started < args.seconds:
        x = parse_x(rcon.run(command))
        now = time.time()
        if x is None:
            print("读不到位置（实体不存在？）")
            return 1
        if last_x is not None and last_t is not None:
            dt = now - last_t
            if dt > 1e-6:
                speed = abs(x - last_x) / dt
                print("%6.2f  x=%8.2f  速度=%5.2f 格/秒" % (now - started, x, speed))
        else:
            print("%6.2f  x=%8.2f" % (now - started, x))
        last_x = x
        last_t = now
        time.sleep(args.interval)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
