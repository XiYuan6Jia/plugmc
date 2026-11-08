#!/usr/bin/env python3
"""上传热加载插件到麦块联机测试服，并触发热加载（无需重启）。

用法：
  python scripts/deploy_hot.py hotplugins/demo/build/libs/demo-0.1.0.jar
  python scripts/deploy_hot.py --project demo
  python scripts/deploy_hot.py <jar> --no-load

凭据与 mods 部署脚本共用：
  .local/minekuai.env   MINEKUAI_API_KEY
  .local/sftp.env       MINEKUAI_SFTP_HOST / PORT / USER / PASSWORD、MINEKUAI_SERVER_ID
可选：MINEKUAI_HOT_PATH（默认 plugmc-hot）
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import time
import zipfile

import deploy_test as dt

DEFAULT_HOT_PATH = "plugmc-hot"
LOAD_COMMAND = "plugmc reloadall"


def build_project(name: str) -> pathlib.Path:
    gradlew = dt.REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    task = f":hotplugins:{name}:build"
    print(f"==> 构建 ({task})")
    result = subprocess.run(
        [str(gradlew), task, "--console=plain"],
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

    libs = dt.REPO_ROOT / "hotplugins" / name / "build" / "libs"
    jars = [
        path
        for path in libs.glob(f"{name}-*.jar")
        if not path.name.endswith(("-sources.jar", "-dev.jar"))
    ]
    if not jars:
        sys.exit(f"在 {libs} 未找到热插件 jar")
    return max(jars, key=lambda path: path.stat().st_mtime)


def read_descriptor(jar: pathlib.Path) -> dict:
    try:
        with zipfile.ZipFile(jar) as archive:
            return json.loads(archive.read("plugmc-plugin.json").decode("utf-8"))
    except KeyError:
        sys.exit(f"{jar.name} 缺少 plugmc-plugin.json")
    except ValueError as exc:
        sys.exit(f"{jar.name} 的 plugmc-plugin.json 不合法：{exc}")


def upload(env: dict[str, str], jar: pathlib.Path, remote_name: str) -> str:
    import paramiko

    host = dt.need(env, "MINEKUAI_SFTP_HOST")
    port = int(dt.need(env, "MINEKUAI_SFTP_PORT"))
    user = dt.need(env, "MINEKUAI_SFTP_USER")
    password = dt.need(env, "MINEKUAI_SFTP_PASSWORD")
    hot_path = env.get("MINEKUAI_HOT_PATH", DEFAULT_HOT_PATH).strip("/") or DEFAULT_HOT_PATH

    print(f"==> SFTP {user}@{host}:{port} -> {hot_path}/")
    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    transport.connect(username=user, password=password)
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            try:
                sftp.stat(hot_path)
            except FileNotFoundError:
                sftp.mkdir(hot_path)
                print(f"    创建目录 {hot_path}/")

            remote = f"{hot_path}/{remote_name}"
            sftp.put(str(jar), remote, confirm=True)
            size = sftp.stat(remote).st_size
            print(f"    上传完成 {remote} ({size / 1024:.0f} KB)")
            return remote
        finally:
            sftp.close()
    finally:
        transport.close()


def trigger_reload(api: dt.Api, plugin_id: str, remote_name: str, timeout: int) -> None:
    print(f"==> 发送命令 {LOAD_COMMAND}")
    before = api.log()
    api.request("POST", "/command", json={"command": LOAD_COMMAND})

    success_marker = f"已热加载插件 {plugin_id} "
    failure_marker = f"加载热插件失败 {remote_name}"
    deadline = time.time() + timeout
    log = before
    while time.time() < deadline:
        time.sleep(2)
        log = api.log()
        fresh = log[len(before):] if log.startswith(before) else log
        if failure_marker in fresh:
            print("\n".join(fresh.splitlines()[-25:]))
            sys.exit(f"热加载失败：{plugin_id}")
        if success_marker in fresh:
            hit = next(line for line in fresh.splitlines() if success_marker in line)
            print(f"    {hit.strip()}")
            print("    热加载成功")
            return

    print("    超时，日志末尾：")
    print("\n".join(log.splitlines()[-25:]))
    sys.exit("未在限定时间内看到热加载结果")


def main() -> None:
    parser = argparse.ArgumentParser(description="上传热加载插件并触发热加载")
    parser.add_argument("jar", nargs="?", help="热插件 jar 路径")
    parser.add_argument("--project", help="先构建 hotplugins/<name>/ 再上传")
    parser.add_argument("--file-name", help="服务器上的文件名，默认 <id>.jar")
    parser.add_argument("--no-load", action="store_true", help="只上传，不触发 reloadall")
    parser.add_argument("--timeout", type=int, default=60, help="等待热加载结果的秒数（默认 60）")
    args = parser.parse_args()

    if args.project:
        jar = build_project(args.project)
    elif args.jar:
        jar = pathlib.Path(args.jar).resolve()
    else:
        sys.exit("请提供 jar 路径，或使用 --project <name>")

    if not jar.is_file():
        sys.exit(f"文件不存在：{jar}")

    descriptor = read_descriptor(jar)
    plugin_id = descriptor.get("id")
    if not plugin_id:
        sys.exit("plugmc-plugin.json 缺少 id")
    # 默认用 <id>.jar，覆盖旧版本，避免同一 id 的多个 jar 同时留在目录里
    remote_name = args.file_name or f"{plugin_id}.jar"
    print(f"==> 热插件 {plugin_id} {descriptor.get('version', '?')} ({remote_name})")

    env = dt.load_env(dt.ENV_FILES)
    upload(env, jar, remote_name)

    if args.no_load:
        print("    已上传，未触发热加载")
        return

    api = dt.Api(
        env.get("MINEKUAI_API_BASE", dt.API_DEFAULT),
        dt.need(env, "MINEKUAI_API_KEY"),
        dt.need(env, "MINEKUAI_SERVER_ID"),
    )
    trigger_reload(api, plugin_id, remote_name, args.timeout)


if __name__ == "__main__":
    main()
