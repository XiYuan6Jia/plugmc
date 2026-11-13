"""诊断 5：用 tag 判断实体是否真的存在（不依赖 type 名与距离过滤）。

每轮：summon 带 Tag 的实体 → `execute if entity @e[tag=X] run seed` → kill @e[tag=X]。
对所有维度都用 `execute in <dim> run ...`，且不追加 positioned（tag 不受距离影响）。
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

    def run(self, command, wait=0.3):
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


TYPES = ("minecraft:pig", "minecraft:armor_stand", "minecraft:mannequin",
         "minecraft:text_display", "minecraft:item_display")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    ap.add_argument("--dims", nargs="+", default=["hub", "overworld"])
    args = ap.parse_args()

    r = Rcon("127.0.0.1", args.port, args.password)

    for dim in args.dims:
        d = f"execute in minecraft:{dim} run "
        print(f"\n########## {dim} ##########")
        for i, etype in enumerate(TYPES):
            tag = f"pb{i}"
            print(f"\n--- {etype} ---")
            print("  summon  :", r.run(d + f'summon {etype} -39.5 66 44.5 {{Tags:["{tag}"]}}', 0.5))
            time.sleep(0.5)
            print("  by tag  :", r.run(d + f"execute if entity @e[tag={tag}] run seed", 0.4))
            print("  by type :", r.run(d + f"execute if entity @e[type={etype}] run seed", 0.4))
            print("  kill tag:", r.run(d + f"kill @e[tag={tag}]", 0.4))

    r.close()


if __name__ == "__main__":
    main()
