"""诊断 4：LivingEntity 是「从未加入世界」还是「加入后被移除」？

对照组：item_display / text_display 能查到，armor_stand / mannequin / pig 查不到。
本脚本在 summon 之后按 0/100/400/1200ms 多次查询，看是否短暂存在。
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

    def run(self, command, wait=0.0):
        self.sock.sendall(packet(self.req, 2, command))
        self.req += 1
        self.sock.settimeout(8)
        if wait:
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
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    args = ap.parse_args()

    r = Rcon("127.0.0.1", args.port, args.password)
    d = f"execute in minecraft:{args.dim} run "

    for etype in ("minecraft:pig", "minecraft:armor_stand", "minecraft:mannequin", "minecraft:item_display"):
        print(f"\n=== {etype} ===")
        print("  summon :", r.run(d + f"summon {etype} -39.5 66 44.5", 0.05))
        for label, ms in (("立即", 0), ("+100ms", 100), ("+400ms", 400), ("+1200ms", 1200)):
            if ms:
                time.sleep(ms / 1000.0)
            alive = r.run(d + f"execute if entity @e[type={etype},distance=..8] run seed", 0.08)
            print(f"  {label:7s}: {'存在' if alive.startswith('Seed') else '不存在'}")
        print("  kill   :", r.run(d + f"kill @e[type={etype}]", 0.4))

    r.close()


if __name__ == "__main__":
    main()
