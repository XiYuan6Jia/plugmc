#!/usr/bin/env python3
"""构建并部署 mods/ 下的插件到麦块联机（minekuai）测试服。

流程：gradlew 构建 -> SFTP 上传插件 jar（自动附带 Fabric API）-> RESTART -> 轮询日志校验。

凭据只从本地文件读取，从不打印：
  .local/minekuai.env   MINEKUAI_API_KEY
  .local/sftp.env       MINEKUAI_SFTP_HOST / PORT / USER / PASSWORD、MINEKUAI_SERVER_ID、MINEKUAI_MODS_PATH

用法：
  python scripts/deploy_test.py                    # 构建并部署 mods/ 下全部插件
  python scripts/deploy_test.py --mod a --mod b    # 只部署指定插件
  python scripts/deploy_test.py --skip-build       # 复用已有 jar
  python scripts/deploy_test.py --upload-only      # 只上传，不重启
  python scripts/deploy_test.py --check-only       # 只读当前日志校验插件是否加载
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import pathlib
import subprocess
import sys
import time
from dataclasses import dataclass

import requests

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
MODS_DIR = REPO_ROOT / "mods"
ENV_FILES = [
    REPO_ROOT / ".local" / "minekuai.env",
    REPO_ROOT / ".local" / "sftp.env",
]
API_DEFAULT = "https://api.minekuai.cn/panel/client-api"

# 服务端实例自带的基座是空 mods/，而插件硬依赖 Fabric API，需要一并上传。
FABRIC_API_COORDS = "net/fabricmc/fabric-api/fabric-api/{version}/fabric-api-{version}.jar"
MAVEN_BASE = "https://maven.fabricmc.net"
THIRDPARTY_DIR = REPO_ROOT / ".local" / "thirdparty"

ENV_KEYS = (
    "MINEKUAI_API_KEY",
    "MINEKUAI_API_BASE",
    "MINEKUAI_SERVER_ID",
    "MINEKUAI_SFTP_HOST",
    "MINEKUAI_SFTP_PORT",
    "MINEKUAI_SFTP_USER",
    "MINEKUAI_SFTP_PASSWORD",
    "MINEKUAI_MODS_PATH",
)

DONE_MARKER = "Done ("

API_ERRORS = {
    401: "API 密钥无效",
    403: "无权限或来源 IP 被白名单拒绝",
    404: "实例不存在",
    409: "实例当前状态不允许该操作",
    422: "参数错误",
    429: "触发限流（每密钥 300 次/分钟）",
}


@dataclass(frozen=True)
class Mod:
    """mods/ 下的一个插件工程。"""

    id: str
    name: str
    project_dir: pathlib.Path

    @property
    def gradle_path(self) -> str:
        return f":mods:{self.id}"

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


def load_env(files: list[pathlib.Path]) -> dict[str, str]:
    env: dict[str, str] = {}
    for path in files:
        if not path.is_file():
            continue
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()

    # 真实环境变量优先，便于 CI / 临时覆盖
    for key in ENV_KEYS:
        if value := os.environ.get(key):
            env[key] = value
    return env


def need(env: dict[str, str], key: str) -> str:
    value = env.get(key)
    if not value:
        sys.exit(f"缺少配置项 {key}，请写入 .local/ 下的环境文件")
    return value


def read_gradle_properties() -> dict[str, str]:
    path = REPO_ROOT / "gradle.properties"
    props: dict[str, str] = {}
    if not path.is_file():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def discover_mods() -> list[Mod]:
    """扫描 mods/ 下带 fabric.mod.json 的工程目录。"""
    mods: list[Mod] = []
    if not MODS_DIR.is_dir():
        return mods
    for path in sorted(MODS_DIR.iterdir()):
        if not path.is_dir() or not (path / "build.gradle.kts").is_file():
            continue
        meta_path = path / "src" / "main" / "resources" / "fabric.mod.json"
        if not meta_path.is_file():
            print(f"    跳过 {path.name}：缺少 fabric.mod.json")
            continue
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
        except ValueError as exc:
            sys.exit(f"{meta_path} 不是合法 JSON：{exc}")
        mods.append(Mod(id=path.name, name=meta.get("name", path.name), project_dir=path))
    return mods


def run_build(targets: list[Mod], all_mods: list[Mod]) -> None:
    gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if len(targets) == len(all_mods):
        tasks = ["build"]
    else:
        tasks = [f"{mod.gradle_path}:build" for mod in targets]

    print(f"==> 构建 ({' '.join(tasks)})")
    result = subprocess.run(
        [str(gradlew), *tasks, "--console=plain"],
        cwd=REPO_ROOT,
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


def ensure_fabric_api(version: str) -> pathlib.Path:
    """下载并缓存 Fabric API（插件的硬依赖），返回本地路径。"""
    THIRDPARTY_DIR.mkdir(parents=True, exist_ok=True)
    target = THIRDPARTY_DIR / f"fabric-api-{version}.jar"
    if target.is_file() and target.stat().st_size > 0:
        print(f"==> Fabric API 已缓存 {target.name}")
        return target

    url = f"{MAVEN_BASE}/{FABRIC_API_COORDS.format(version=version)}"
    print(f"==> 下载 Fabric API {version}")
    resp = requests.get(url, timeout=120)
    resp.raise_for_status()
    target.write_bytes(resp.content)
    print(f"    已保存 {target.name} ({len(resp.content) / 1024:.0f} KB)")
    return target


def upload(
    env: dict[str, str],
    files: list[pathlib.Path],
    deployed_ids: list[str],
    all_ids: list[str],
) -> list[str]:
    import paramiko

    host = need(env, "MINEKUAI_SFTP_HOST")
    port = int(need(env, "MINEKUAI_SFTP_PORT"))
    user = need(env, "MINEKUAI_SFTP_USER")
    password = need(env, "MINEKUAI_SFTP_PASSWORD")
    mods = need(env, "MINEKUAI_MODS_PATH").strip("/") or "mods"

    print(f"==> SFTP {user}@{host}:{port} -> {mods}/")
    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    transport.connect(username=user, password=password)
    uploaded: list[str] = []
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            try:
                sftp.stat(mods)
            except FileNotFoundError:
                sftp.mkdir(mods)
                print(f"    创建目录 {mods}/")

            existing = set(sftp.listdir(mods))

            # 只清理本次要部署的插件旧版本，避免误删其它插件
            for name in sorted(existing):
                if not name.endswith(".jar"):
                    continue
                for mod_id in deployed_ids:
                    if not fnmatch.fnmatch(name, f"{mod_id}-*.jar"):
                        continue
                    if any(
                        other != mod_id and fnmatch.fnmatch(name, f"{other}-*.jar")
                        for other in all_ids
                    ):
                        continue
                    sftp.remove(f"{mods}/{name}")
                    existing.discard(name)
                    print(f"    删除旧版本 {name}")
                    break

            for local in files:
                remote = f"{mods}/{local.name}"
                if local.name in existing and local.stat().st_size == sftp.stat(remote).st_size:
                    print(f"    已存在且大小一致 {remote}")
                    uploaded.append(remote)
                    continue
                sftp.put(str(local), remote, confirm=True)
                size = sftp.stat(remote).st_size
                print(f"    上传完成 {remote} ({size / 1024:.0f} KB)")
                uploaded.append(remote)
            return uploaded
        finally:
            sftp.close()
    finally:
        transport.close()


class Api:
    def __init__(self, base: str, key: str, server_id: str) -> None:
        self.base = base.rstrip("/")
        self.server_id = server_id
        self.session = requests.Session()
        self.session.headers.update(
            {"Authorization": f"Bearer {key}", "Accept": "application/json"}
        )

    def request(self, method: str, path: str, **kwargs):
        kwargs.setdefault("timeout", 30)
        url = f"{self.base}/servers/{self.server_id}{path}"
        resp = self.session.request(method, url, **kwargs)
        if resp.status_code >= 400:
            reason = API_ERRORS.get(resp.status_code, resp.reason)
            try:
                detail = resp.json().get("error", "")
            except ValueError:
                detail = resp.text[:200]
            sys.exit(f"API {method} {path} 失败：{resp.status_code} {reason} {detail}")
        return resp

    def state(self) -> str:
        data = self.request("GET", "").json()
        # 单实例接口是扁平结构（stats 在顶层）；列表/部分版本会包一层 attributes
        node = data.get("attributes", data)
        stats = node.get("stats") or {}
        stats = stats.get("attributes", stats)
        return stats.get("current_state") or node.get("status") or "unknown"

    def power(self, signal: str) -> None:
        self.request("POST", "/power", json={"signal": signal})
        print(f"    已发送 {signal}")

    def log(self, name: str = "/logs/latest.log") -> str:
        resp = self.request("GET", "/files/contents", params={"file": name})
        # 服务端日志是 UTF-8，requests 对 text/plain 可能按 ISO-8859-1 解码
        return resp.content.decode("utf-8", "replace")


def wait_ready(api: Api, timeout: int) -> str:
    print(f"==> 等待服务端就绪（最长 {timeout}s）")
    deadline = time.time() + timeout
    last_state = None
    log = ""
    while time.time() < deadline:
        log = api.log()
        state = api.state()
        if state != last_state:
            print(f"    state={state}")
            last_state = state
        if state == "running" and DONE_MARKER in log:
            return log
        time.sleep(5)

    print("    超时，日志末尾：")
    print("\n".join(log.splitlines()[-25:]))
    sys.exit("服务端未在限定时间内就绪")


def report(log: str, mods: list[Mod]) -> None:
    print("==> 日志校验")
    lowered = log.lower()
    missing = [
        mod
        for mod in mods
        if mod.id.lower() not in lowered and mod.name.lower() not in lowered
    ]
    if missing:
        print("    未在日志中找到：" + "、".join(f"{mod.name} ({mod.id})" for mod in missing))
        print("    可能缺少依赖或 mods 路径不对，日志末尾：")
        print("\n".join(log.splitlines()[-25:]))
        sys.exit("插件未全部加载")

    for mod in mods:
        print(f"    {mod.name} ({mod.id}) 已加载")
    print("    插件加载校验 OK")


def main() -> None:
    parser = argparse.ArgumentParser(description="部署 mods/ 下的插件到麦块联机测试服")
    parser.add_argument(
        "--mod",
        action="append",
        dest="selected",
        metavar="ID",
        help="只部署指定插件（可重复）",
    )
    parser.add_argument("--skip-build", action="store_true", help="跳过 gradlew 构建")
    parser.add_argument("--upload-only", action="store_true", help="只上传，不重启")
    parser.add_argument("--check-only", action="store_true", help="只读日志校验，不上传")
    parser.add_argument("--timeout", type=int, default=300, help="等待就绪的秒数（默认 300）")
    args = parser.parse_args()

    all_mods = discover_mods()
    if not all_mods:
        sys.exit("mods/ 下没有可部署的插件，请先用 scripts/new_plugin.py 创建")

    by_id = {mod.id: mod for mod in all_mods}
    if args.selected:
        unknown = [mod_id for mod_id in args.selected if mod_id not in by_id]
        if unknown:
            sys.exit(f"未知插件：{', '.join(unknown)}；可选：{', '.join(by_id)}")
        targets = [by_id[mod_id] for mod_id in dict.fromkeys(args.selected)]
    else:
        targets = all_mods

    env = load_env(ENV_FILES)
    api = Api(
        env.get("MINEKUAI_API_BASE", API_DEFAULT),
        need(env, "MINEKUAI_API_KEY"),
        need(env, "MINEKUAI_SERVER_ID"),
    )

    if args.check_only:
        report(api.log(), targets)
        return

    if not args.skip_build:
        run_build(targets, all_mods)

    jars = [mod.jar() for mod in targets]
    print("==> 待部署：" + "、".join(f"{mod.name} ({mod.id})" for mod in targets))

    fabric_api_version = read_gradle_properties().get("fabric_api_version")
    if not fabric_api_version:
        sys.exit("gradle.properties 缺少 fabric_api_version")
    files = jars + [ensure_fabric_api(fabric_api_version)]

    uploaded = upload(
        env,
        files,
        [mod.id for mod in targets],
        [mod.id for mod in all_mods],
    )

    if args.upload_only:
        print("==> 已上传：")
        for remote in uploaded:
            print(f"    {remote}")
        print("    未重启（重启后生效）")
        return

    print("==> 重启实例")
    api.power("restart")
    report(wait_ready(api, args.timeout), targets)


if __name__ == "__main__":
    main()
