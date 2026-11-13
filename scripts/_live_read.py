"""读线上实例的文件内容（只读，走面板 API /files/contents）。

用法：
    python scripts/_live_read.py plugins/hub/config.yml
    python scripts/_live_read.py --dir world/dimensions/minecraft

线上容器里文件属主是 root，SFTP 下载会 EACCES，所以只能走 API。
"""
from __future__ import annotations

import argparse
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import deploy_test as dt  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*", help="要读的文件路径（相对服务器根）")
    ap.add_argument("--dir", default=None, help="列出某个目录（走 SFTP）")
    args = ap.parse_args()

    env = dt.load_env(dt.ENV_FILES)
    if args.dir:
        import paramiko

        transport = paramiko.Transport(
            (
                env["MINEKUAI_SFTP_HOST"],
                int(env["MINEKUAI_SFTP_PORT"]),
            )
        )
        transport.connect(
            username=env["MINEKUAI_SFTP_USER"],
            password=env["MINEKUAI_SFTP_PASSWORD"],
        )
        client = paramiko.SFTPClient.from_transport(transport)
        try:
            for entry in sorted(client.listdir_attr(args.dir), key=lambda a: a.filename):
                kind = "DIR" if entry.st_mode & 0o40000 else ""
                print(f"{entry.filename:<44} {entry.st_size:>12} {kind}")
        finally:
            transport.close()
        return 0

    api = dt.Api(
        env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
        dt.need(env, "MINEKUAI_API_KEY"),
        dt.need(env, "MINEKUAI_SERVER_ID"),
    )
    for path in args.paths:
        resp = api.request("GET", "/files/contents", params={"file": path})
        print(f"===== {path}  [{resp.status_code}]")
        print(resp.content.decode("utf-8", "replace"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
