"""诊断：summon 出来的实体为什么查不到。

假设：往「未真正加载」的区块 summon，命令会回 Summoned new X，但实体随即消失。
判据：同一个坐标先 setblock —— 未加载区块 setblock 会明确报 That position is not loaded。
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
            return raw[12:-2].decode("utf-8", "replace")
        except OSError as exc:
            return f"<no reply: {exc}>"

    def close(self):
        self.sock.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    args = ap.parse_args()

    r = Rcon("127.0.0.1", args.port, args.password)

    cases = [
        ("hub", -40, 65, 44),
        ("overworld", 300, 71, 300),
        ("overworld", 100, 70, 100),
    ]

    for dim, x, y, z in cases:
        prefix = f"execute in minecraft:{dim} run "
        print(f"\n=== {dim} {x} {y} {z} ===")
        print("  setblock 探针 :", r.run(prefix + f"setblock {x} {y} {z} minecraft:glass", 0.6))
        print("  回读         :", r.run(prefix + f"execute if block {x} {y} {z} minecraft:glass run seed", 0.4))
        print("  summon       :", r.run(prefix + f"summon minecraft:armor_stand {x + 0.5} {y + 1} {z + 0.5}", 0.6))
        time.sleep(1.0)
        print("  if entity    :", r.run(
            prefix + f"positioned {x + 0.5} {y + 1} {z + 0.5} run execute if entity @e[distance=..6] run seed", 0.6))
        print("  kill         :", r.run(prefix + "kill @e[type=minecraft:armor_stand]", 0.5))
        print("  复位         :", r.run(prefix + f"setblock {x} {y} {z} minecraft:air", 0.4))

    r.close()


if __name__ == "__main__":
    main()
