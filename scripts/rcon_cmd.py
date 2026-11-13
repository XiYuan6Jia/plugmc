"""往本机测试服的 RCON 发一条命令并打印回显。

    python scripts/rcon_cmd.py "bazaar check"
    python scripts/rcon_cmd.py --port 25576 --password probe123 "list"
"""
import argparse
import socket
import struct
import sys

TYPE_AUTH = 3
TYPE_AUTH_RESPONSE = 2
TYPE_COMMAND = 2
TYPE_RESPONSE = 0


def pack(req_id: int, ptype: int, text: str) -> bytes:
    body = struct.pack("<ii", req_id, ptype) + text.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def recv_packet(sock: socket.socket) -> tuple[int, int, str]:
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return 0, 0, ""
    (length,) = struct.unpack("<i", raw_len)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    resp_id, ptype = struct.unpack("<ii", data[:8])
    return resp_id, ptype, data[8:-2].decode("utf-8", "replace")


def main() -> int:
    # 服务器回显里有 ✔/✘ 这类符号，Windows 控制台默认 GBK 会直接抛 UnicodeEncodeError
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("command", nargs="+")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25576)
    ap.add_argument("--password", default="probe123")
    ap.add_argument("--timeout", type=float, default=15.0)
    args = ap.parse_args()

    command = " ".join(args.command)
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.settimeout(args.timeout)
        sock.sendall(pack(1, TYPE_AUTH, args.password))
        resp_id, _, _ = recv_packet(sock)
        if resp_id == -1:
            print("RCON 鉴权失败：密码错误", file=sys.stderr)
            return 2
        sock.sendall(pack(2, TYPE_COMMAND, command))
        print(recv_packet(sock)[2])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
