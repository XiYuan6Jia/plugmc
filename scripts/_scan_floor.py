import argparse, socket, struct, time

def pkt(i, t, s):
    b = struct.pack("<ii", i, t) + s.encode() + b"\x00\x00"
    return struct.pack("<i", len(b)) + b

class Rcon:
    def __init__(self, port, password):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.s.sendall(pkt(1, 3, password)); self.s.recv(4096); self.req = 2
    def run(self, cmd, wait=0.35):
        self.s.sendall(pkt(self.req, 2, cmd)); self.req += 1
        self.s.settimeout(10); time.sleep(wait)
        try:
            return self.s.recv(65535)[12:-2].decode("utf-8", "replace").strip()
        except OSError as e:
            return f"<err {e}>"

ap = argparse.ArgumentParser()
ap.add_argument("--y", type=int, default=64)
ap.add_argument("--x", type=int, nargs=2, default=[-60, -24])
ap.add_argument("--z", type=int, nargs=2, default=[24, 62])
ap.add_argument("--step", type=int, default=2)
ap.add_argument("--port", type=int, default=25576)
ap.add_argument("--password", default="probe123")
a = ap.parse_args()

r = Rcon(a.port, a.password)
d = "execute in minecraft:hub run "
r.run(d + f"forceload add {a.x[0]} {a.z[0]} {a.x[1]} {a.z[1]}", 1.0)

xs = list(range(a.x[0], a.x[1] + 1, a.step))
zs = list(range(a.z[0], a.z[1] + 1, a.step))
print("      " + " ".join(f"{z:>4}" for z in zs))
solid = 0
for x in xs:
    row = []
    for z in zs:
        out = r.run(d + f"execute if block {x} {a.y} {z} minecraft:air run seed", 0.15)
        ok = not out.startswith("Seed")
        row.append("#" if ok else ".")
        solid += 1 if ok else 0
    print(f"x={x:>4} " + " ".join(f"{c:>4}" for c in row))
print(f"\ny={a.y} 非空气格 {solid} / {len(xs)*len(zs)}")
r.s.close()
