"""诊断 6：把「区块是否真的加载」作为自变量，验证 summon 后能否查到实体。

关键背景：Minecraft 的选择器只搜索**已加载**区块里的实体。往未加载区块 summon 会回
"Summoned new X"，但实体随区块卸载而离开实体列表（存盘后从内存移除），于是查不到。

流程：先 forceload 目标区块 → forceload query 确认 → summon → 多个时间点查询。
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

    def run(self, command, wait=0.4):
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
    d = f"execute in minecraft:{args.dim} run "
    x, z = args.x, args.z

    print("forceload add :", r.run(d + f"forceload add {x} {z} {x} {z}", 1.2))
    print("forceload qry :", r.run(d + f"forceload query {x} {z}", 0.5))
    print("setblock 探针 :", r.run(d + f"setblock {x} {args.y} {z} minecraft:glass", 0.6))
    print()

    for etype in ("minecraft:pig", "minecraft:armor_stand", "minecraft:mannequin",
                  "minecraft:text_display", "minecraft:item_display"):
        tag = "p" + etype.split(":")[1][:6]
        print(f"--- {etype} ---")
        print("  summon :", r.run(d + f'summon {etype} {x + 0.5} {args.y + 1} {z + 0.5} {{Tags:["{tag}"]}}', 0.5))
        for label, ms in (("立即", 0), ("+300ms", 300), ("+1500ms", 1500)):
            if ms:
                if ms > 300:
                    time.sleep(1.2)
            alive = r.run(d + f"execute if entity @e[tag={tag}] run seed", 0.2)
            print(f"  {label:8s}: {'存在' if alive.startswith('Seed') else '不存在'}  ({alive[:40]})")
        print("  kill   :", r.run(d + f"kill @e[tag={tag}]", 0.4))

    print("\n复位:", r.run(d + f"setblock {x} {args.y} {z} minecraft:air", 0.4))
    r.close()


if __name__ == "__main__":
    main()
