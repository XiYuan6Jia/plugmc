#!/usr/bin/env python3
"""构建 PlugMC 并部署到麦块联机（minekuai）测试服。

流程：gradlew build -> SFTP 上传 jar 到 mods/ -> RESTART -> 轮询日志校验模组已加载。

凭据只从本地文件读取，从不打印：
  .local/minekuai.env   MINEKUAI_API_KEY
  .local/sftp.env       MINEKUAI_SFTP_HOST / PORT / USER / PASSWORD、MINEKUAI_SERVER_ID、MINEKUAI_MODS_PATH

用法：
  python scripts/deploy_test.py                # 构建 + 上传 + 重启 + 校验
  python scripts/deploy_test.py --skip-build   # 复用已有 jar
  python scripts/deploy_test.py --upload-only  # 只上传，不重启
  python scripts/deploy_test.py --check-only   # 只读当前日志判断模组是否加载
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import pathlib
import subprocess
import sys
import time

import requests

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
ENV_FILES = [
    REPO_ROOT / ".local" / "minekuai.env",
    REPO_ROOT / ".local" / "sftp.env",
]
API_DEFAULT = "https://api.minekuai.cn/panel/client-api"

# 服务端实例自带的基座是空 mods/，而模组硬依赖 Fabric API，需要一并上传。
FABRIC_API_COORDS = "net/fabricmc/fabric-api/fabric-api/{version}/fabric-api-{version}.jar"
FABRIC_API_VERSION = "0.165.2+26.3"
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
MOD_MARKER = "PlugMC"

API_ERRORS = {
    401: "API 密钥无效",
    403: "无权限或来源 IP 被白名单拒绝",
    404: "实例不存在",
    409: "实例当前状态不允许该操作",
    422: "参数错误",
    429: "触发限流（每密钥 300 次/分钟）",
}


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


def run_build() -> None:
    print("==> 构建 (gradlew build)")
    gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    result = subprocess.run(
        [str(gradlew), "build", "--console=plain"],
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


def find_jar() -> pathlib.Path:
    libs = REPO_ROOT / "build" / "libs"
    jars = [
        p
        for p in libs.glob("plugmc-*.jar")
        if not p.name.endswith(("-sources.jar", "-dev.jar"))
    ]
    if not jars:
        sys.exit(f"在 {libs} 未找到模组 jar，请先构建")
    jar = max(jars, key=lambda p: p.stat().st_mtime)
    print(f"==> 产物 {jar.name} ({jar.stat().st_size / 1024:.0f} KB)")
    return jar


def ensure_fabric_api() -> pathlib.Path:
    """下载并缓存 Fabric API（模组的硬依赖），返回本地路径。"""
    THIRDPARTY_DIR.mkdir(parents=True, exist_ok=True)
    target = THIRDPARTY_DIR / f"fabric-api-{FABRIC_API_VERSION}.jar"
    if target.is_file() and target.stat().st_size > 0:
        print(f"==> Fabric API 已缓存 {target.name}")
        return target

    url = f"{MAVEN_BASE}/{FABRIC_API_COORDS.format(version=FABRIC_API_VERSION)}"
    print(f"==> 下载 Fabric API {FABRIC_API_VERSION}")
    resp = requests.get(url, timeout=120)
    resp.raise_for_status()
    target.write_bytes(resp.content)
    print(f"    已保存 {target.name} ({len(resp.content) / 1024:.0f} KB)")
    return target


def upload(env: dict[str, str], files: list[pathlib.Path]) -> list[str]:
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

            for name in sorted(existing):
                if fnmatch.fnmatch(name, "plugmc-*.jar"):
                    sftp.remove(f"{mods}/{name}")
                    existing.discard(name)
                    print(f"    删除旧版本 {name}")

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


def report(log: str) -> None:
    hits = [line for line in log.splitlines() if MOD_MARKER.lower() in line.lower()]
    print("==> 日志校验")
    if not hits:
        print("    未在日志中找到 PlugMC，可能缺少依赖或 mods 路径不对：")
        print("\n".join(log.splitlines()[-25:]))
        sys.exit("模组未加载")
    for line in hits[-6:]:
        print(f"    {line.strip()}")
    print("    模组已加载 OK")


def main() -> None:
    parser = argparse.ArgumentParser(description="部署 PlugMC 到麦块联机测试服")
    parser.add_argument("--skip-build", action="store_true", help="跳过 gradlew build")
    parser.add_argument("--upload-only", action="store_true", help="只上传，不重启")
    parser.add_argument("--check-only", action="store_true", help="只读日志校验，不上传")
    parser.add_argument("--timeout", type=int, default=300, help="等待就绪的秒数（默认 300）")
    args = parser.parse_args()

    env = load_env(ENV_FILES)
    api = Api(
        env.get("MINEKUAI_API_BASE", API_DEFAULT),
        need(env, "MINEKUAI_API_KEY"),
        need(env, "MINEKUAI_SERVER_ID"),
    )

    if args.check_only:
        report(api.log())
        return

    if not args.skip_build:
        run_build()
    jar = find_jar()
    uploaded = upload(env, [jar, ensure_fabric_api()])

    if args.upload_only:
        print("==> 已上传：")
        for remote in uploaded:
            print(f"    {remote}")
        print("    未重启（重启后生效）")
        return

    print("==> 重启实例")
    api.power("restart")
    report(wait_ready(api, args.timeout))


if __name__ == "__main__":
    main()
