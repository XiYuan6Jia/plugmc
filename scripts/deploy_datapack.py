#!/usr/bin/env python3
"""构建并部署 datapacks/ 下的数据包到麦块联机（minekuai）测试服。

流程：gradlew :datapacks:buildDatapacks -> SFTP 上传 zip 到 world/datapacks/ -> RESTART
     -> 轮询日志校验 -> 用 /datapack list 与数据包自己的回执确认真的启用。

凭据只从本地文件读取，从不打印：
  .local/minekuai.env   MINEKUAI_API_KEY / MINEKUAI_SERVER_ID
  .local/sftp.env       MINEKUAI_SFTP_HOST / PORT / USER / PASSWORD
可选：MINEKUAI_DATAPACKS_PATH（默认 world/datapacks）

用法：
  python scripts/deploy_datapack.py                     # 构建并部署全部
  python scripts/deploy_datapack.py --pack plugmc-demo  # 只部署指定包（可重复）
  python scripts/deploy_datapack.py --skip-build        # 复用已有 zip
  python scripts/deploy_datapack.py --upload-only       # 只上传，不重启
  python scripts/deploy_datapack.py --check-only        # 只读日志 + 发 /datapack list 校验
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import time
from dataclasses import dataclass

import deploy_test as dt

REPO_ROOT = dt.REPO_ROOT
DATAPACKS_DIR = REPO_ROOT / "datapacks"
DEFAULT_DATAPACKS_PATH = "world/datapacks"
LIST_PATTERN = re.compile(r"There are (\d+) data pack\(s\) enabled: (.+)", re.IGNORECASE)


@dataclass(frozen=True)
class Pack:
    """datapacks/ 下的一个数据包。"""

    id: str
    dir: pathlib.Path

    def zip_path(self) -> pathlib.Path:
        libs = DATAPACKS_DIR / "build" / "libs"
        candidate = libs / f"{self.id}.zip"
        if candidate.is_file():
            return candidate
        sys.exit(f"在 {libs} 未找到 {self.id}.zip，请先构建")


def discover_packs() -> list[Pack]:
    """扫描 datapacks/ 下含 pack.mcmeta 的目录。"""
    packs: list[Pack] = []
    if not DATAPACKS_DIR.is_dir():
        return packs
    for path in sorted(DATAPACKS_DIR.iterdir()):
        if not path.is_dir() or not (path / "pack.mcmeta").is_file():
            continue
        packs.append(Pack(id=path.name, dir=path))
    return packs


def run_build(targets: list[Pack], all_packs: list[Pack]) -> None:
    gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if len(targets) == len(all_packs):
        tasks = [":datapacks:buildDatapacks"]
    else:
        tasks = [f":datapacks:zip-{pack.id}" for pack in targets]

    print(f"==> 构建 ({' '.join(tasks)})")
    result = subprocess.run(
        [str(gradlew), *tasks],
        cwd=str(REPO_ROOT),
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        sys.exit("构建失败，请检查上面的 Gradle 输出")


def upload(env: dict[str, str], files: list[tuple[Pack, pathlib.Path]]) -> list[str]:
    import paramiko

    host = dt.need(env, "MINEKUAI_SFTP_HOST")
    port = int(dt.need(env, "MINEKUAI_SFTP_PORT"))
    user = dt.need(env, "MINEKUAI_SFTP_USER")
    secret = dt.need(env, "MINEKUAI_SFTP_PASSWORD")
    remote_root = (
        env.get("MINEKUAI_DATAPACKS_PATH", DEFAULT_DATAPACKS_PATH).strip("/")
        or DEFAULT_DATAPACKS_PATH
    )

    print(f"==> SFTP {user}@{host}:{port} -> {remote_root}/")
    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    transport.connect(username=user, password=secret)

    uploaded: list[str] = []
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            # 逐级建目录（MineKuai 的 SFTP chroot 到服务端根目录，world/ 可能已存在）
            parts = remote_root.split("/")
            for i in range(1, len(parts) + 1):
                piece = "/".join(parts[:i])
                try:
                    sftp.stat(piece)
                except FileNotFoundError:
                    sftp.mkdir(piece)
                    print(f"    创建目录 {piece}/")

            existing = set(sftp.listdir(remote_root))

            # 数据包以「文件夹」形式生效，同名的 zip 与文件夹不能共存；
            # 上传前清掉同名旧 zip 与同名旧文件夹，避免两个来源打架。
            for pack, local in files:
                for stale in (f"{pack.id}.zip", pack.id):
                    if stale not in existing:
                        continue
                    stale_path = f"{remote_root}/{stale}"
                    if stale.endswith(".zip"):
                        sftp.remove(stale_path)
                    else:
                        remove_tree(sftp, stale_path)
                    existing.discard(stale)
                    print(f"    清理旧版本 {stale}")

                remote = f"{remote_root}/{local.name}"
                sftp.put(str(local), remote, confirm=True)
                size = sftp.stat(remote).st_size
                print(f"    上传完成 {remote} ({size / 1024:.1f} KB)")
                uploaded.append(remote)
            return uploaded
        finally:
            sftp.close()
    finally:
        transport.close()


def remove_tree(sftp, remote_dir: str) -> None:
    """递归删远端目录。"""
    import stat as statmod

    for entry in sftp.listdir_attr(remote_dir):
        path = f"{remote_dir}/{entry.filename}"
        if statmod.S_ISDIR(entry.st_mode):
            remove_tree(sftp, path)
        else:
            sftp.remove(path)
    sftp.rmdir(remote_dir)


def send_command(api: "dt.Api", command: str) -> str:
    """发一条控制台命令，返回执行后新增的日志片段。"""
    before = api.log()
    api.request("POST", "/command", json={"command": command})
    deadline = time.time() + 15
    fresh = ""
    while time.time() < deadline:
        time.sleep(1.0)
        log = api.log()
        fresh = log[len(before):] if log.startswith(before) else log
        if fresh.strip():
            return fresh
    return fresh


def wait_ready_after_restart(api: "dt.Api", before: str, timeout: int) -> str:
    """重启后等待就绪；要求先离开 running 或日志已变化，避免被旧的 Done 标记骗过。"""
    print(f"==> 等待服务端就绪（最长 {timeout}s）")
    deadline = time.time() + timeout
    left_running = False
    last_state = None
    log = before
    while time.time() < deadline:
        state = api.state()
        if state != last_state:
            print(f"    state={state}")
            last_state = state
        if state != "running":
            left_running = True
        log = api.log()
        if state == "running" and dt.DONE_MARKER in log and (left_running or log != before):
            return log
        time.sleep(5)

    print("    超时，日志末尾：")
    print("\n".join(log.splitlines()[-25:]))
    sys.exit("服务端未在限定时间内就绪")


def enabled_packs(api: "dt.Api") -> tuple[int, str] | None:
    """发 /datapack list，解析出「数量 + 明细」。"""
    fresh = send_command(api, "datapack list")
    for line in fresh.splitlines():
        match = LIST_PATTERN.search(line)
        if match:
            return int(match.group(1)), match.group(2).strip()
    return None


def report(api: "dt.Api", packs: list[Pack], log: str) -> None:
    print("==> 日志校验")
    lowered = log.lower()
    missing = [pack for pack in packs if pack.id.lower() not in lowered]
    if missing:
        print("    未在日志中找到：" + "、".join(pack.id for pack in missing))
        print("    数据包可能加载失败，日志末尾：")
        print("\n".join(log.splitlines()[-25:]))
        sys.exit("数据包未全部启用")

    for pack in packs:
        print(f"    {pack.id} 已在日志中出现")

    print("==> 运行时校验（/datapack list）")
    result = enabled_packs(api)
    if result is None:
        print("    未能解析 /datapack list 输出，日志里也没有 —— 请手动确认")
        return
    total, detail = result
    print(f"    共 {total} 个数据包：{detail}")
    for pack in packs:
        if f"file/{pack.id}" in detail:
            print(f"    OK  {pack.id} 已被服务端启用（file/{pack.id}）")
        else:
            print(f"    警告：{pack.id} 未出现在 datapack list 里，可能被 disable 或格式不匹配")


def main() -> None:
    parser = argparse.ArgumentParser(description="部署 datapacks/ 下的数据包到麦块联机测试服")
    parser.add_argument("--pack", action="append", dest="selected", metavar="ID",
                        help="只部署指定数据包（可重复）")
    parser.add_argument("--skip-build", action="store_true", help="跳过 gradlew 构建")
    parser.add_argument("--upload-only", action="store_true", help="只上传，不重启")
    parser.add_argument("--check-only", action="store_true", help="只读日志校验，不上传")
    parser.add_argument("--timeout", type=int, default=300, help="等待就绪的秒数（默认 300）")
    args = parser.parse_args()

    all_packs = discover_packs()
    if not all_packs:
        sys.exit("datapacks/ 下没有可部署的数据包，请先用 scripts/new_datapack.py 创建")

    by_id = {pack.id: pack for pack in all_packs}
    if args.selected:
        unknown = [pid for pid in args.selected if pid not in by_id]
        if unknown:
            sys.exit(f"未知数据包：{', '.join(unknown)}；可选：{', '.join(by_id)}")
        targets = [by_id[pid] for pid in dict.fromkeys(args.selected)]
    else:
        targets = all_packs

    env = dt.load_env(dt.ENV_FILES)
    api = dt.Api(
        env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
        dt.need(env, "MINEKUAI_API_KEY"),
        dt.need(env, "MINEKUAI_SERVER_ID"),
    )

    if args.check_only:
        report(api, targets, api.log())
        return

    if not args.skip_build:
        run_build(targets, all_packs)

    files = [(pack, pack.zip_path()) for pack in targets]
    print("==> 待部署：" + "、".join(f"{pack.id} ({path.name})" for pack, path in files))

    uploaded = upload(env, files)
    if args.upload_only:
        print("==> 已上传：")
        for remote in uploaded:
            print(f"    {remote}")
        print("    未重启（重启后生效）")
        return

    print("==> 重启实例")
    before = api.log()
    api.power("restart")
    report(api, targets, wait_ready_after_restart(api, before, args.timeout))


if __name__ == "__main__":
    main()
