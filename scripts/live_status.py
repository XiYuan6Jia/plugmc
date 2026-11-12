#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""线上实况快照（只读）。

一条命令把「线上到底在跑什么」抓下来，供更新计划文档 §7.6 与动手前比对。
只发查询类命令（version / tps / plugins / list / datapack list），
不上传、不下载、不重启、不改任何文件。

用法：
    python scripts/live_status.py              # 全部
    python scripts/live_status.py --no-files   # 跳过目录树与体积统计（更快）
    python scripts/live_status.py --json       # 末尾附带机器可读 JSON

依赖：.local/minekuai.env（MINEKUAI_API_KEY / MINEKUAI_SERVER_ID）
      .local/sftp.env（MINEKUAI_SFTP_*，仅 --no-files 之外需要）
"""

from __future__ import annotations

import argparse
import json
import posixpath
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import deploy_test as dt  # noqa: E402

PLUGIN_COUNT_RE = re.compile(r"Server Plugins \((\d+)\)", re.IGNORECASE)
BUKKIT_LIST_RE = re.compile(r"Bukkit Plugins:\s*(.*)", re.IGNORECASE | re.DOTALL)
VERSION_BEHIND_RE = re.compile(r"You are (\d+) version\(s\) behind", re.IGNORECASE)
PAPER_VERSION_RE = re.compile(r"Paper version ([^\s(]+)", re.IGNORECASE)
PLAYERS_RE = re.compile(r"There are (\d+) of a max of (\d+) players online:?\s*(.*)", re.IGNORECASE)
DATAPACK_RE = re.compile(r"There are (\d+) data pack\(s\) enabled:\s*(.*)", re.IGNORECASE | re.DOTALL)
TPS_RE = re.compile(r"TPS from last 1m, 5m, 15m:\s*([\d.,\s]+)", re.IGNORECASE)

# /plugins 回显里常混进别的日志行（玩家动态、跑酷纪录等），需要剥掉时间戳前缀
LOG_PREFIX_RE = re.compile(r"\[[\d:]{8}\]\s*\[[^\]]*\]\s*:?\s*")
PLUGIN_TOKEN_RE = re.compile(r"[A-Za-z0-9_.\-]{2,32}")

PROPS_OF_INTEREST = (
    "server-port", "online-mode", "white-list", "enforce-whitelist",
    "enable-rcon", "rcon.port",
    "resource-pack", "resource-pack-id", "resource-pack-sha1",
    "resource-pack-prompt", "require-resource-pack",
    "level-type", "max-players", "view-distance", "simulation-distance",
    "difficulty", "gamemode", "spawn-protection", "pause-when-empty-seconds",
    "enforce-secure-profile", "enable-command-block",
    "function-permission-level", "allow-flight",
    "entity-broadcast-range-percentage",
)


# ---------------------------------------------------------------- API 封装
def build_api() -> dt.Api:
    env = dt.load_env(dt.ENV_FILES)
    base = env.get("MINEKUAI_API_BASE") or dt.API_DEFAULT
    return dt.Api(base, dt.need(env, "MINEKUAI_API_KEY"), dt.need(env, "MINEKUAI_SERVER_ID"))


def send_command(api: dt.Api, command: str, timeout: float = 15.0) -> str:
    """发一条控制台命令，返回执行后新增的日志片段（与 deploy_datapack.send_command 同实现）。"""
    import time

    before = api.log()
    api.request("POST", "/command", json={"command": command})
    deadline = time.time() + timeout
    fresh = ""
    while time.time() < deadline:
        time.sleep(1.0)
        log = api.log()
        fresh = log[len(before):] if log.startswith(before) else log
        if fresh.strip():
            return fresh
    return fresh


def read_remote(api: dt.Api, path: str) -> str | None:
    """经面板 API 读文件（SFTP 对 root 属主文件会 EACCES，只能走这条路）。"""
    try:
        return api.request("GET", "/files/contents", params={"file": path}).content.decode(
            "utf-8", "replace"
        )
    except SystemExit:
        return None
    except Exception:  # noqa: BLE001 - 单个文件读不到不该中断整次快照
        return None


def open_sftp(env: dict[str, str]):
    import paramiko

    transport = paramiko.Transport((dt.need(env, "MINEKUAI_SFTP_HOST"),
                                    int(dt.need(env, "MINEKUAI_SFTP_PORT"))))
    transport.banner_timeout = 30
    transport.connect(username=dt.need(env, "MINEKUAI_SFTP_USER"),
                      password=dt.need(env, "MINEKUAI_SFTP_PASSWORD"))
    return transport, paramiko.SFTPClient.from_transport(transport)


def walk_size(sftp, root: str) -> tuple[int, int, list[tuple[str, int]]]:
    """递归统计目录体积（只列属性，不下载）。返回 (文件数, 总字节, 最大 5 项)。"""
    import stat as statmod

    files, total, biggest = 0, 0, []
    stack = [root]
    while stack:
        cur = stack.pop()
        try:
            entries = sftp.listdir_attr(cur)
        except Exception:  # noqa: BLE001
            continue
        for e in entries:
            p = posixpath.join(cur, e.filename)
            if statmod.S_ISDIR(e.st_mode or 0):
                stack.append(p)
            else:
                files += 1
                size = e.st_size or 0
                total += size
                biggest.append((p, size))
    biggest.sort(key=lambda x: x[1], reverse=True)
    return files, total, biggest[:5]


def main() -> int:
    ap = argparse.ArgumentParser(description="线上实况快照（只读）")
    ap.add_argument("--no-files", action="store_true", help="跳过目录树与体积统计")
    ap.add_argument("--json", action="store_true", help="末尾输出机器可读 JSON")
    args = ap.parse_args()

    env = dt.load_env(dt.ENV_FILES)
    api = build_api()
    snap: dict = {}

    print("=" * 70)
    print("线上实况快照（只读）")
    print("=" * 70)

    snap["state"] = api.state()
    print(f"\n[实例] state={snap['state']}")

    out = send_command(api, "version")
    m = PAPER_VERSION_RE.search(out)
    b = VERSION_BEHIND_RE.search(out)
    snap["paper_version"] = m.group(1) if m else None
    snap["version_behind"] = int(b.group(1)) if b else None
    print(f"[版本] {snap['paper_version'] or '解析失败'}   落后 {snap['version_behind']} 版")

    out = send_command(api, "tps")
    m = TPS_RE.search(out)
    snap["tps"] = m.group(1).strip() if m else None
    print(f"[TPS] {snap['tps'] or '解析失败'}")

    # ---- 插件 ----
    out = send_command(api, "plugins", timeout=20.0)
    count_m = PLUGIN_COUNT_RE.search(out)
    snap["plugins_runtime_count"] = int(count_m.group(1)) if count_m else None
    if count_m:
        print(f"\n[插件] Server Plugins ({count_m.group(1)}) —— 名称与版本以 plugins/ 下的 jar 为准")
    else:
        print("\n[插件] 未能解析 'Server Plugins (N)'，原始回显：")
        print(out[:800])

    # ---- 插件目录里的 jar（比 /plugins 输出更可信，能看出版本号） ----
    jars: list[str] = []
    transport, sftp = (None, None)
    if not args.no_files:
        try:
            transport, sftp = open_sftp(env)
            for e in sftp.listdir_attr("plugins"):
                if e.filename.endswith(".jar"):
                    jars.append(e.filename)
        except SystemExit:
            pass
        except Exception as exc:  # noqa: BLE001
            print(f"   (SFTP 列 plugins/ 失败：{exc})")
    if jars:
        print(f"   plugins/ 下 jar {len(jars)} 个：")
        for j in sorted(jars):
            print(f"     {j}")
    snap["plugins_jars"] = sorted(jars)

    # ---- 在线玩家 ----
    out = send_command(api, "list")
    m = PLAYERS_RE.search(out)
    if m:
        names = [x.strip() for x in m.group(3).split(",") if x.strip()]
        snap.update(players_online=int(m.group(1)), players_max=int(m.group(2)), player_names=names)
        print(f"\n[在线] {m.group(1)}/{m.group(2)}  {', '.join(names) if names else '(无)'}")

    # ---- 数据包 ----
    out = send_command(api, "datapack list", timeout=20.0)
    m = DATAPACK_RE.search(out)
    if m:
        # 明细行后面可能粘着下一条日志（如 "There are no more data packs available"），剥掉
        body = LOG_PREFIX_RE.sub(" ", m.group(2)).split("There are no more")[0]
        body = body.split("System chat:")[0]
        packs = [x.strip() for x in re.split(r",\s*(?=\[)", body) if x.strip()]
        packs = [p for p in packs if p.startswith("[")]
        snap["datapacks"] = packs
        print(f"\n[数据包] {m.group(1)} 个")
        for p in packs:
            print(f"   - {p}")

    # ---- server.properties ----
    raw = read_remote(api, "/server.properties")
    if raw:
        props: dict[str, str] = {}
        for line in raw.splitlines():
            if line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            props[k.strip()] = v
        stamp = next((l[1:] for l in raw.splitlines() if l.startswith("#") and " CST " in l), None)
        snap["properties_timestamp"] = stamp
        snap["server_properties"] = {k: props.get(k) for k in PROPS_OF_INTEREST if k in props}
        print(f"\n[server.properties 关键项]{'  （文件时间戳：' + stamp + '）' if stamp else ''}")
        for k, v in snap["server_properties"].items():
            print(f"   {k:34s} = {'(空)' if v == '' else v}")
    else:
        print("\n[server.properties] 读取失败")

    # ---- 经济账本 ----
    raw = read_remote(api, "/plugins/economy/state.json")
    if raw:
        try:
            d = json.loads(raw)
        except json.JSONDecodeError:
            print("\n[经济] state.json 不是合法 JSON")
        else:
            accounts = d.get("accounts", {}) or {}
            players = d.get("players", {}) or {}
            snap["economy"] = {
                "schemaVersion": d.get("schemaVersion"),
                "lastSeq": d.get("lastSeq"),
                "account_count": len(accounts),
                "player_count": len(players),
                "accounts": accounts,
            }
            print(f"\n[经济] schemaVersion={d.get('schemaVersion')} lastSeq={d.get('lastSeq')} "
                  f"accounts={len(accounts)} players={len(players)}")
            for k, v in accounts.items():
                print(f"   {k:52s} {v}")
    else:
        print("\n[经济] state.json 读取失败")

    # ---- 世界 / 日志体积 ----
    if sftp is not None:
        try:
            for d in ("world", "logs"):
                files, total, biggest = walk_size(sftp, d)
                snap[f"{d}_files"] = files
                snap[f"{d}_bytes"] = total
                print(f"\n[{d}] {files} 文件 / {total:,} B（{total / 1048576:.1f} MB）")
                for p, s in biggest:
                    print(f"   最大：{p}  {s:,} B")
        finally:
            try:
                sftp.close()
            except Exception:  # noqa: BLE001
                pass
            try:
                transport.close()
            except Exception:  # noqa: BLE001
                pass

    if args.json:
        print("\n--- JSON ---")
        print(json.dumps(snap, ensure_ascii=False, indent=2))

    print("\n完成。把上面的数字对照计划文档 §7.6 更新。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
