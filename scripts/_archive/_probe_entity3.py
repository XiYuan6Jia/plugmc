"""诊断 3：修正语法后再测（上一版漏了 `run`，所有命令都变成 Incorrect argument）。

正确形式：`execute in <dim> run <cmd>`，`execute in <dim> positioned x y z run <cmd>`。
"""
import argparse
import socket
import struct
import time


def packet(req_id, ptype, text):
    body = struct.pack("<ii", req_id, ptype) + text.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


class Rcon:
    def __init__(self, host, port, password):
        self.sock = socket.create_connection((host, port), timeout=8)
        self.sock.sendall(packet(1, 3, password))
        self.sock.recv(4096)
        self.req = 2

    def run(self, command, wait=0.5):
        self.sock.sendall(packet(self.req, 2, command))
        self.req += 1
        self.sock.settimeout(8)
        time.sleep(wait)
        try:
            raw = self.sock.recv(65535)
            return raw[12:-2].decode("utf-8", "replace").strip()
        except OSError as exc:
            return f"<no reply: {exc}>"

    def close(self):
        self.sock.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dim", default="hub")
    ap.add_argument("--x", type=int, default=-40)
    ap.add_argument("--y", type=int, default=65)
    ap.add_argument("--z", type=int, default=44)
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    args = ap.parse_args()

    r = Rcon("127.0.0.1", args.port, args.password)
    base = f"execute in minecraft:{args.dim} run "
    at = f"execute in minecraft:{args.dim} positioned {args.x + 0.5} {args.y + 1} {args.z + 0.5} run "
    x, y, z = args.x, args.y, args.z

    print("1. 区块加载（setblock 探针）:", r.run(base + f"setblock {x} {y} {z} minecraft:glass", 0.6))
    print("2. 回读探针块             :", r.run(base + f"execute if block {x} {y} {z} minecraft:glass run seed", 0.5))

    for etype in ("minecraft:mannequin", "minecraft:armor_stand", "minecraft:text_display", "minecraft:item_display"):
        print(f"\n--- {etype} ---")
        print("  summon    :", r.run(base + f"summon {etype} {x + 0.5} {y + 1} {z + 0.5}", 0.6))
        time.sleep(1.0)
        print("  附近有实体 :", r.run(at + "execute if entity @e[distance=..6] run seed", 0.6))
        print("  同类型在   :", r.run(at + f"execute if entity @e[type={etype},distance=..6] run seed", 0.6))
        print("  data get   :", r.run(base + f"data get entity @e[type={etype},limit=1,sort=nearest] Pos", 0.5))
        print("  kill       :", r.run(base + f"kill @e[type={etype}]", 0.5))

    print("\n3. 复位探针块:", r.run(base + f"setblock {x} {y} {z} minecraft:air", 0.4))
    r.close()


if __name__ == "__main__":
    main()
