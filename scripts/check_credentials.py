#!/usr/bin/env python3
"""校验麦块联机（minekuai）测试服的 API 与 SFTP 凭据是否可用。

只读：不发重启、不上传、不删除任何文件。任何凭据值都不会被打印，
输出里只出现键名、主机、端口、实例状态和目录条目数。

凭据来源（两个文件都在 .local/ 下，已被 .gitignore 忽略）：
  .local/minekuai.env   MINEKUAI_API_KEY          面板生成的 API 密钥
                        MINEKUAI_SERVER_ID        实例 ID
                        MINEKUAI_API_BASE         可选，默认官方地址
  .local/sftp.env       MINEKUAI_SFTP_HOST        面板给的 SFTP 主机
                        MINEKUAI_SFTP_PORT        SFTP 端口
                        MINEKUAI_SFTP_USER        SFTP 用户名
                        MINEKUAI_SFTP_PASSWORD    SFTP 密码
                        MINEKUAI_PLUGINS_PATH     可选，默认 plugins

用法：
  python scripts/check_credentials.py            # 全部检查
  python scripts/check_credentials.py --api      # 只查 API
  python scripts/check_credentials.py --sftp     # 只查 SFTP
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import deploy_test as dt

REQUIRED_API = ("MINEKUAI_API_KEY", "MINEKUAI_SERVER_ID")
REQUIRED_SFTP = (
    "MINEKUAI_SFTP_HOST",
    "MINEKUAI_SFTP_PORT",
    "MINEKUAI_SFTP_USER",
    "MINEKUAI_SFTP_PASSWORD",
)

OK = "[ OK ]"
NG = "[FAIL]"
SK = "[SKIP]"


def check_env_present(env: dict[str, str], keys: tuple[str, ...], label: str) -> bool:
    missing = [key for key in keys if not env.get(key)]
    if missing:
        print(f"{NG} {label}：缺少 {', '.join(missing)}")
        for key in missing:
            print(f"       请在 .local/ 的对应文件里补上 {key}=...")
        return False
    print(f"{OK} {label}：{len(keys)} 项配置齐全（值不打印）")
    return True


def check_api(env: dict[str, str]) -> bool:
    print("\n== API ==")
    if not check_env_present(env, REQUIRED_API, "API 配置"):
        return False

    base = env.get("MINEKUAI_API_BASE", dt.API_DEFAULT)
    print(f"       地址 {base}")
    print(f"       实例 {env['MINEKUAI_SERVER_ID']}")

    api = dt.Api(base, env["MINEKUAI_API_KEY"], env["MINEKUAI_SERVER_ID"])
    try:
        state = api.state()
    except SystemExit as exc:
        print(f"{NG} 鉴权/请求失败：{exc}")
        return False
    except Exception as exc:  # noqa: BLE001 - 网络层异常统一报出来
        print(f"{NG} 请求异常：{type(exc).__name__}: {exc}")
        return False

    print(f"{OK} 实例状态 state={state}")

    try:
        log = api.log()
    except SystemExit as exc:
        print(f"{NG} 读日志失败：{exc}")
        return False
    except Exception as exc:  # noqa: BLE001
        print(f"{NG} 读日志异常：{type(exc).__name__}: {exc}")
        return False

    lines = log.splitlines()
    print(f"{OK} 日志可读：{len(lines)} 行，{len(log)} 字符")
    if lines:
        print(f"       末行：{lines[-1][:160]}")
    if dt.DONE_MARKER in log:
        print(f"{OK} 日志里出现就绪标记 {dt.DONE_MARKER!r}")
    else:
        print(f"{SK} 当前日志里没有 {dt.DONE_MARKER!r}（实例可能还没启动完）")
    return True


def check_sftp(env: dict[str, str]) -> bool:
    print("\n== SFTP ==")
    if not check_env_present(env, REQUIRED_SFTP, "SFTP 配置"):
        return False

    try:
        import paramiko
    except ImportError:
        print(f"{NG} 缺少 paramiko，请执行：python -m pip install paramiko")
        return False

    host = env["MINEKUAI_SFTP_HOST"]
    port = int(env["MINEKUAI_SFTP_PORT"])
    user = env["MINEKUAI_SFTP_USER"]
    plugins_path = env.get("MINEKUAI_PLUGINS_PATH", "plugins").strip("/") or "plugins"
    print(f"       {user}@{host}:{port} → {plugins_path}/")

    transport = paramiko.Transport((host, port))
    transport.banner_timeout = 30
    try:
        transport.connect(username=user, password=env["MINEKUAI_SFTP_PASSWORD"])
    except paramiko.AuthenticationException:
        print(f"{NG} SFTP 认证失败：用户名或密码错误")
        transport.close()
        return False
    except Exception as exc:  # noqa: BLE001
        print(f"{NG} SFTP 连接失败：{type(exc).__name__}: {exc}")
        transport.close()
        return False

    print(f"{OK} SFTP 登录成功")
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            try:
                entries = sftp.listdir(plugins_path)
            except IOError:
                print(f"{SK} 远程 {plugins_path}/ 不存在（首次部署时脚本会自动创建）")
                entries = []
            jars = sorted(name for name in entries if name.endswith(".jar"))
            print(f"{OK} {plugins_path}/ 可列出：{len(entries)} 个条目，其中 {len(jars)} 个 jar")
            for name in jars[:20]:
                print(f"       {name}")
            if len(jars) > 20:
                print(f"       …还有 {len(jars) - 20} 个")
        finally:
            sftp.close()
    finally:
        transport.close()
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="只读校验 minekuai 测试服的 API 与 SFTP 凭据")
    parser.add_argument("--api", action="store_true", help="只查 API")
    parser.add_argument("--sftp", action="store_true", help="只查 SFTP")
    args = parser.parse_args()

    only_api = args.api and not args.sftp
    only_sftp = args.sftp and not args.api

    print("== 凭据文件 ==")
    for path in dt.ENV_FILES:
        state = "存在" if path.is_file() else "不存在"
        print(f"       {state}：{path.relative_to(dt.REPO_ROOT)}")

    env = dt.load_env(dt.ENV_FILES)

    results: list[bool] = []
    if not only_sftp:
        results.append(check_api(env))
    if not only_api:
        results.append(check_sftp(env))

    print()
    if all(results):
        print("结论：凭据可用，可以跑 scripts/deploy_paper.py")
        return
    print("结论：有检查未通过，按上面提示补配置后重跑本脚本")
    sys.exit(1)


if __name__ == "__main__":
    main()
