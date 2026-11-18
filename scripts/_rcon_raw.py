"""临时：用 rcon_cmd 同款协议读完整回显（服务器按包发，读够一个包就返回）。

    python scripts/_rcon_raw.py "data get entity ShopTester Inventory"
"""
import socket
import struct
import sys

TYPE_AUTH = 3
TYPE_COMMAND = 2


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


def main() -> None:
    command = sys.argv[1] if len(sys.argv) > 1 else "list"
    sock = socket.create_connection(("127.0.0.1", 25576), 5)
    sock.settimeout(6)
    sock.sendall(pack(1, TYPE_AUTH, "probe123"))
    recv_packet(sock)
    sock.sendall(pack(2, TYPE_COMMAND, command))
    _, _, text = recv_packet(sock)
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(text)
    sock.close()


if __name__ == "__main__":
    main()
