#!/usr/bin/env python3
"""构建并部署 plugins/ 下的 Paper 插件到麦块联机（minekuai）测试服。

流程：gradlew 构建 -> SFTP 上传 jar 到服务端 plugins/ -> RESTART -> 轮询日志校验。

凭据只从本地文件读取，从不打印：
  .local/minekuai.env   MINEKUAI_API_KEY
  .local/sftp.env       MINEKUAI_SFTP_HOST / PORT / USER / PASSWORD、MINEKUAI_SERVER_ID
可选：MINEKUAI_PLUGINS_PATH（默认 plugins）

用法：
  python scripts/deploy_paper.py                    # 构建并部署 plugins/ 下全部插件
  python scripts/deploy_paper.py --plugin a --plugin b
  python scripts/deploy_paper.py --skip-build       # 复用已有 jar
  python scripts/deploy_paper.py --upload-only      # 只上传，不重启
  python scripts/deploy_paper.py --check-only       # 只读当前日志校验插件是否加载
"""

from __future__ import annotations

import argparse
import fnmatch
import pathlib
import re
import subprocess
import sys
import time
from dataclasses import dataclass

import deploy_test as dt

PLUGINS_DIR = dt.REPO_ROOT / "plugins"
DEFAULT_PLUGINS_PATH = "plugins"
NAME_PATTERN = re.compile(r"^name:\s*['\"]?([^'\"\n#]+)", re.MULTILINE)


@dataclass(frozen=True)
class Plugin:
    """plugins/ 下的一个 Paper 插件工程。"""

    id: str
    name: str
    project_dir: pathlib.Path

    @property
    def gradle_path(self) -> str:
        return f":plugins:{self.id}"

    def jar(self) -> pathlib.Path:
        libs = self.project_dir / "build" / "libs"
        jars = [
            path
            for path in libs.glob(f"{self.id}-*.jar")
            if not path.name.endswith(("-sources.jar", "-dev.jar"))
        ]
        if not jars:
            sys.exit(f"在 {libs} 未找到 {self.id} 的插件 jar，请先构建")
        return max(jars, key=lambda path: path.stat().st_mtime)


def read_plugin_name(plugin_yml: pathlib.Path, fallback: str) -> str:
    match = NAME_PATTERN.search(plugin_yml.read_text(encoding="utf-8"))
    return match.group(1).strip() if match else fallback


def discover_plugins() -> list[Plugin]:
    """扫描 plugins/ 下带 plugin.yml 的工程目录。"""
    plugins: list[Plugin] = []
    if not PLUGINS_DIR.is_dir():
        return plugins
    for path in sorted(PLUGINS_DIR.iterdir()):
        if not path.is_dir() or not (path / "build.gradle.kts").is_file():
            continue
        plugin_yml = path / "src" / "main" / "resources" / "plugin.yml"
        if not plugin_yml.is_file():
            print(f"    跳过 {path.name}：缺少 plugin.yml")
            continue
        plugins.append(
            Plugin(
                id=path.name,
                name=read_plugin_name(plugin_yml, path.name),
                project_dir=path,
            )
        )
    return plugins


def parse_upload_files(raw: list[str], known_ids: set[str]) -> list[tuple[str, pathlib.Path]]:
    """把 --upload-file 的 ID=PATH 参数解析成 (插件 id, 本地文件)。"""
    entries: list[tuple[str, pathlib.Path]] = []
    for item in raw:
        plugin_id, _, path_text = item.partition("=")
        plugin_id = plugin_id.strip()
        path_text = path_text.strip()
        if not plugin_id or not path_text:
            sys.exit(f"--upload-file 需要 <插件id>=<本地文件>：{item}")
        if plugin_id not in known_ids:
            sys.exit(f"--upload-file 指定的插件不存在：{plugin_id}；可选：{', '.join(sorted(known_ids))}")
        local = pathlib.Path(path_text).expanduser()
        if not local.is_file():
            sys.exit(f"--upload-file 找不到文件：{local}")
        entries.append((plugin_id, local))
    return entries


def run_build(targets: list[Plugin], all_plugins: list[Plugin]) -> None:
    gradlew = dt.REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if len(targets) == len(all_plugins):
        tasks = ["build"]
    else:
        tasks = [f"{plugin.gradle_path}:build" for plugin in targets]

    print(f"==> 构建 ({' '.join(tasks)})")
    result = subprocess.run(
        [str(gradlew), *tasks, "--console=plain"],
        cwd=dt.REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        print(result.stdout[-4000:])
        sys.exit("构建失败")
    print("    构建成功")


def upload(
    env: dict[str, str],
    files: list[pathlib.Path],
    deployed_ids: list[str],
    all_ids: list[str],
    data_files: list[tuple[str, pathlib.Path]] | None = None,
) -> list[str]:
    import paramiko

    host = dt.need(env, "MINEKUAI_SFTP_HOST")
    port = int(dt.need(env, "MINEKUAI_SFTP_PORT"))
    user = dt.need(env, "MINEKUAI_SFTP_USER")
    secret = dt.need(env, "MINEKUAI_SFTP_PASSWORD")
    plugins_path = (
        env.get("MINEKUAI_PLUGINS_PATH", DEFAULT_PLUGINS_PATH).strip("/")
        or DEFAULT_PLUGINS_PATH
    )

    print(f"==> SFTP {user}@{host}:{port} -> {plugins_path}/")
    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    transport.connect(username=user, password=secret)

    uploaded: list[str] = []
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            try:
                sftp.stat(plugins_path)
            except FileNotFoundError:
                sftp.mkdir(plugins_path)
                print(f"    创建目录 {plugins_path}/")

            existing = set(sftp.listdir(plugins_path))

            # 只清理本次要部署的插件旧版本，避免误删其它插件
            for name in sorted(existing):
                if not name.endswith(".jar"):
                    continue
                for plugin_id in deployed_ids:
                    if not fnmatch.fnmatch(name, f"{plugin_id}-*.jar"):
                        continue
                    if any(
                        other != plugin_id and fnmatch.fnmatch(name, f"{other}-*.jar")
                        for other in all_ids
                    ):
                        continue
                    sftp.remove(f"{plugins_path}/{name}")
                    existing.discard(name)
                    print(f"    删除旧版本 {name}")
                    break

            for local in files:
                remote = f"{plugins_path}/{local.name}"
                if local.name in existing and local.stat().st_size == sftp.stat(remote).st_size:
                    print(f"    已存在且大小一致 {remote}")
                    uploaded.append(remote)
                    continue
                sftp.put(str(local), remote, confirm=True)
                size = sftp.stat(remote).st_size
                print(f"    上传完成 {remote} ({size / 1024:.0f} KB)")
                uploaded.append(remote)

            # 插件数据文件（如 llm-chat 的 config.json）上传到 plugins/<id>/
            for plugin_id, local in data_files or []:
                folder = f"{plugins_path}/{plugin_id}"
                try:
                    sftp.stat(folder)
                except FileNotFoundError:
                    sftp.mkdir(folder)
                remote = f"{folder}/{local.name}"
                sftp.put(str(local), remote, confirm=True)
                print(f"    上传数据文件 {remote} ({local.stat().st_size / 1024:.1f} KB)")
                uploaded.append(remote)
            return uploaded
        finally:
            sftp.close()
    finally:
        transport.close()


def wait_ready_after_restart(api: "dt.Api", before: str, timeout: int) -> str:
    """重启后等待就绪。

    刚发出 restart 时实例可能还是 running、日志也还是重启前那一份，
    直接复用 ``dt.wait_ready`` 会被旧的 Done 标记骗过，所以这里要求
    先离开 running，或者日志内容已经变化。
    """
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


def report(log: str, plugins: list[Plugin]) -> None:
    print("==> 日志校验")
    lowered = log.lower()
    missing = [
        plugin
        for plugin in plugins
        if f"enabling {plugin.name.lower()}" not in lowered
        and f"[{plugin.name.lower()}]" not in lowered
    ]
    if missing:
        print("    未在日志中找到启用记录：" + "、".join(f"{p.name} ({p.id})" for p in missing))
        print("    可能插件加载失败，日志末尾：")
        print("\n".join(log.splitlines()[-25:]))
        sys.exit("插件未全部加载")

    for plugin in plugins:
        print(f"    {plugin.name} ({plugin.id}) 已启用")
    print("    插件加载校验 OK")


def backup_remote_data(env: dict[str, str], plugin_ids: list[str]) -> pathlib.Path | None:
    """部署前把线上插件数据目录下载到 .local/backup/remote-<时间>/，避免误覆盖线上数据。"""
    import paramiko
    import stat

    host = dt.need(env, "MINEKUAI_SFTP_HOST")
    port = int(dt.need(env, "MINEKUAI_SFTP_PORT"))
    user = dt.need(env, "MINEKUAI_SFTP_USER")
    secret = dt.need(env, "MINEKUAI_SFTP_PASSWORD")
    plugins_path = (
        env.get("MINEKUAI_PLUGINS_PATH", DEFAULT_PLUGINS_PATH).strip("/")
        or DEFAULT_PLUGINS_PATH
    )
    stamp = time.strftime("%Y%m%d-%H%M%S")
    root = dt.REPO_ROOT / ".local" / "backup" / f"remote-{stamp}"

    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    transport.connect(username=user, password=secret)
    downloaded = 0
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            def download(remote_dir: str, local_dir: pathlib.Path) -> int:
                count = 0
                try:
                    entries = sftp.listdir_attr(remote_dir)
                except IOError:
                    return 0
                local_dir.mkdir(parents=True, exist_ok=True)
                for entry in entries:
                    remote = f"{remote_dir}/{entry.filename}"
                    local = local_dir / entry.filename
                    if stat.S_ISDIR(entry.st_mode):
                        count += download(remote, local)
                    else:
                        sftp.get(remote, str(local))
                        count += 1
                return count

            print(f"==> 备份线上插件数据到 {root}")
            for plugin_id in plugin_ids:
                count = download(f"{plugins_path}/{plugin_id}", root / plugin_id)
                downloaded += count
                if count:
                    print(f"    {plugin_id}: {count} 个文件")
                else:
                    print(f"    {plugin_id}: 线上暂无数据目录")
        finally:
            sftp.close()
    finally:
        transport.close()

    if not downloaded:
        return None
    return root


def main() -> None:
    parser = argparse.ArgumentParser(description="部署 plugins/ 下的 Paper 插件到麦块联机测试服")
    parser.add_argument(
        "--plugin",
        action="append",
        dest="selected",
        metavar="ID",
        help="只部署指定插件（可重复）",
    )
    parser.add_argument(
        "--upload-file",
        action="append",
        default=[],
        metavar="ID=PATH",
        help="额外上传插件数据文件到 plugins/<ID>/，如 llm-chat=.local/llm-chat/config.json",
    )
    parser.add_argument("--skip-build", action="store_true", help="跳过 gradlew 构建")
    parser.add_argument(
        "--no-remote-backup",
        action="store_true",
        help="跳过部署前的线上插件数据备份（默认备份到 .local/backup/remote-<时间>/）",
    )
    parser.add_argument("--upload-only", action="store_true", help="只上传，不重启")
    parser.add_argument("--check-only", action="store_true", help="只读日志校验，不上传")
    parser.add_argument("--timeout", type=int, default=300, help="等待就绪的秒数（默认 300）")
    args = parser.parse_args()

    all_plugins = discover_plugins()
    if not all_plugins:
        sys.exit("plugins/ 下没有可部署的插件，请先用 scripts/new_plugin.py 创建")

    by_id = {plugin.id: plugin for plugin in all_plugins}
    if args.selected:
        unknown = [plugin_id for plugin_id in args.selected if plugin_id not in by_id]
        if unknown:
            sys.exit(f"未知插件：{', '.join(unknown)}；可选：{', '.join(by_id)}")
        targets = [by_id[plugin_id] for plugin_id in dict.fromkeys(args.selected)]
    else:
        targets = all_plugins

    env = dt.load_env(dt.ENV_FILES)
    api = dt.Api(
        env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
        dt.need(env, "MINEKUAI_API_KEY"),
        dt.need(env, "MINEKUAI_SERVER_ID"),
    )

    if args.check_only:
        report(api.log(), targets)
        return

    if not args.skip_build:
        run_build(targets, all_plugins)

    jars = [plugin.jar() for plugin in targets]
    data_files = parse_upload_files(args.upload_file, set(by_id))
    print("==> 待部署：" + "、".join(f"{p.name} ({p.id})" for p in targets))

    if not args.no_remote_backup:
        backup_remote_data(env, [plugin.id for plugin in targets])

    uploaded = upload(
        env,
        jars,
        [plugin.id for plugin in targets],
        [plugin.id for plugin in all_plugins],
        data_files,
    )

    if args.upload_only:
        print("==> 已上传：")
        for remote in uploaded:
            print(f"    {remote}")
        print("    未重启（重启后生效）")
        return

    print("==> 重启实例")
    before = api.log()
    api.power("restart")
    report(wait_ready_after_restart(api, before, args.timeout), targets)


if __name__ == "__main__":
    main()
