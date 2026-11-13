"""往线上实例发一条命令，打印日志增量（只读除命令本身外无副作用）。

用法：
    python scripts/_live_cmd.py "execute in minecraft:hub run seed"
    python scripts/_live_cmd.py --wait 6 "place template plugmc:arch 0 65 0"
"""
from __future__ import annotations

import argparse
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import deploy_test as dt  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("commands", nargs="+", help="要执行的命令（可多条）")
    ap.add_argument("--wait", type=float, default=4.0, help="每条命令等日志多少秒")
    args = ap.parse_args()

    env = dt.load_env(dt.ENV_FILES)
    api = dt.Api(
        env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
        dt.need(env, "MINEKUAI_API_KEY"),
        dt.need(env, "MINEKUAI_SERVER_ID"),
    )
    print(f"[state] {api.state()}")
    for command in args.commands:
        before = api.log()
        api.request("POST", "/command", json={"command": command})
        deadline = time.time() + args.wait
        fresh = ""
        while time.time() < deadline:
            time.sleep(0.8)
            log = api.log()
            fresh = log[len(before):] if log.startswith(before) else log
            if fresh.strip():
                break
        print(f"===== {command}")
        for line in fresh.splitlines():
            line = line.strip()
            if line:
                print("   ", line)
        if not fresh.strip():
            print("    （日志无新增）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
