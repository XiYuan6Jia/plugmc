#!/usr/bin/env python3
"""本地起一个 Paper 开发服（run-paper/），并把 plugins/ 下已构建的插件装进去。

核心 jar 从 PaperMC 官方接口解析并缓存在 .local/thirdparty/，不随仓库分发。

用法：
  python scripts/run_paper.py                 # 装好插件后前台启动（Ctrl+C 停止）
  python scripts/run_paper.py --sync-only     # 只同步插件 jar，不启动
  python scripts/run_paper.py --build         # 先构建 plugins/ 下全部插件
  python scripts/run_paper.py --memory 4G --java "C:/jdks/jdk-25/bin/java.exe"
  python scripts/run_paper.py -- --port 25566 # -- 之后的参数原样传给服务端
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys

import requests

import deploy_test as dt

REQUIRED_JAVA = 25
FILL_API = "https://fill.papermc.io/v3"
CACHE_DIR = dt.REPO_ROOT / ".local" / "thirdparty"
RUN_DIR = dt.REPO_ROOT / "run-paper"
PLUGINS_DIR = dt.REPO_ROOT / "plugins"
DATAPACKS_DIR = dt.REPO_ROOT / "datapacks"

SERVER_PROPERTIES = """# 本地 Paper 开发服（scripts/run_paper.py 生成，可自由修改）
level-name=world
motd=PlugMC Paper Dev
online-mode=false
spawn-protection=0
view-distance=8
"""


def resolve_build(minecraft_version: str, build: int | None) -> dict:
    """向 PaperMC 接口查询目标版本的构建信息。"""
    url = f"{FILL_API}/projects/paper/versions/{minecraft_version}/builds"
    print(f"==> 查询 Paper {minecraft_version} 构建列表")
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    builds = [item for item in resp.json() if item.get("downloads")]
    if not builds:
        sys.exit(f"Paper 没有可用于 {minecraft_version} 的构建")

    if build is not None:
        for item in builds:
            if item["id"] == build:
                return item
        sys.exit(f"Paper {minecraft_version} 没有构建号 {build}")

    stable = [item for item in builds if item.get("channel") == "STABLE"] or builds
    return max(stable, key=lambda item: item["id"])


def ensure_server_jar(minecraft_version: str, build: dict) -> pathlib.Path:
    download = build["downloads"]["server:default"]
    target = CACHE_DIR / download["name"]
    if target.is_file() and target.stat().st_size == download.get("size", 0):
        print(f"==> 复用缓存 {target.name}")
        return target

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"==> 下载 {download['name']}（{download.get('size', 0) / 1024 / 1024:.1f} MB）")
    with requests.get(download["url"], timeout=300, stream=True) as resp:
        resp.raise_for_status()
        with target.open("wb") as handle:
            for chunk in resp.iter_content(chunk_size=1 << 20):
                handle.write(chunk)
    print(f"    已保存 {target}")
    return target


def built_jars(selected: list[str] | None) -> list[pathlib.Path]:
    jars: list[pathlib.Path] = []
    if not PLUGINS_DIR.is_dir():
        return jars
    for project in sorted(PLUGINS_DIR.iterdir()):
        if not project.is_dir():
            continue
        if selected and project.name not in selected:
            continue
        libs = project / "build" / "libs"
        if not libs.is_dir():
            continue
        for jar in sorted(libs.glob(f"{project.name}-*.jar")):
            if jar.name.endswith(("-sources.jar", "-dev.jar")):
                continue
            jars.append(jar)
    return jars


def sync_plugins(run_dir: pathlib.Path, selected: list[str] | None) -> None:
    target = run_dir / "plugins"
    target.mkdir(parents=True, exist_ok=True)
    jars = built_jars(selected)
    if not jars:
        print("==> 没有找到已构建的插件 jar（先执行 .\\gradlew build）")
        return

    wanted = {jar.name for jar in jars}
    for stale in target.glob("*.jar"):
        if stale.name not in wanted:
            stale.unlink()

    for jar in jars:
        shutil.copy2(jar, target / jar.name)
    print("==> 已同步插件：" + "、".join(jar.name for jar in jars))


def prepare_run_dir(run_dir: pathlib.Path) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    eula = run_dir / "eula.txt"
    if not eula.is_file():
        eula.write_text("eula=true\n", encoding="utf-8")
    properties = run_dir / "server.properties"
    if not properties.is_file():
        properties.write_text(SERVER_PROPERTIES, encoding="utf-8")


def sync_datapacks(run_dir: pathlib.Path) -> None:
    """把 datapacks/<id>/ 复制进开发世界的 datapacks 目录。"""
    if not DATAPACKS_DIR.is_dir():
        return
    packs = [
        path
        for path in sorted(DATAPACKS_DIR.iterdir())
        if path.is_dir() and (path / "pack.mcmeta").is_file()
    ]
    if not packs:
        return

    target = run_dir / "world" / "datapacks"
    target.mkdir(parents=True, exist_ok=True)
    for pack in packs:
        destination = target / pack.name
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(pack, destination)
    print("==> 已同步数据包：" + "、".join(pack.name for pack in packs))


def java_major(java: str) -> int | None:
    try:
        result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=60)
    except OSError:
        return None
    match = re.search(r'version "(\d+)', (result.stderr or "") + (result.stdout or ""))
    return int(match.group(1)) if match else None


def candidate_javas() -> list[str]:
    suffix = "java.exe" if sys.platform == "win32" else "java"
    candidates: list[str] = []
    for home in (os.environ.get("PLUGMC_JAVA_HOME"), os.environ.get("JAVA_HOME")):
        if home:
            candidates.append(str(pathlib.Path(home) / "bin" / suffix))

    discovered = shutil.which("java")
    if discovered:
        candidates.append(discovered)

    # 本机 JDK 通常落在 ~/.jdks（IDEA 下载）或 /usr/lib/jvm
    for root in (pathlib.Path.home() / ".jdks", pathlib.Path("/usr/lib/jvm")):
        if root.is_dir():
            candidates.extend(str(path / "bin" / suffix) for path in sorted(root.iterdir(), reverse=True))
    return candidates


def detect_java(override: str | None) -> str:
    """挑一个 Paper 26.x 能用的 Java（需要 25+）。"""
    if override:
        if (major := java_major(override)) is not None and major < REQUIRED_JAVA:
            sys.exit(f"{override} 是 Java {major}，Paper {REQUIRED_JAVA}+ 需要 Java {REQUIRED_JAVA} 及以上")
        return override

    for candidate in candidate_javas():
        if not pathlib.Path(candidate).is_file():
            continue
        major = java_major(candidate)
        if major is not None and major >= REQUIRED_JAVA:
            print(f"==> 使用 Java {major}：{candidate}")
            return candidate

    sys.exit(
        f"没有找到 Java {REQUIRED_JAVA}+，请安装或通过 --java 指定可执行文件"
        f"（当前 PATH 上的 java 可能是旧版本）"
    )


def build_plugins() -> None:
    gradlew = dt.REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    tasks = [
        f":plugins:{path.name}:build"
        for path in sorted(PLUGINS_DIR.iterdir())
        if path.is_dir() and (path / "build.gradle.kts").is_file()
    ]
    if not tasks:
        return
    print(f"==> 构建插件 ({' '.join(tasks)})")
    subprocess.run([str(gradlew), *tasks, "--console=plain"], cwd=dt.REPO_ROOT, check=True)


def main() -> None:
    properties = dt.read_gradle_properties()
    default_version = properties.get("paper_minecraft_version", "26.3")
    default_build = properties.get("paper_build")
    default_build = int(default_build) if default_build and default_build.isdigit() else None

    parser = argparse.ArgumentParser(description="本地启动 Paper 开发服并装入 plugins/ 下的插件")
    parser.add_argument("--version", default=default_version, help=f"Minecraft 版本（默认 {default_version}）")
    parser.add_argument("--build", type=int, default=default_build, help="Paper 构建号，默认取最新稳定版")
    parser.add_argument("--memory", default="2G", help="服务端最大堆，如 2G / 4G（默认 2G）")
    parser.add_argument("--java", help="java 可执行文件路径，默认取 JAVA_HOME 或 PATH")
    parser.add_argument("--build-plugins", action="store_true", help="启动前先构建 plugins/ 下全部插件")
    parser.add_argument("--plugin", action="append", dest="selected", metavar="ID", help="只同步指定插件")
    parser.add_argument(
        "--dir",
        default=str(RUN_DIR),
        help="服务端运行目录（默认 run-paper/；并行调试时可换一个目录避免抢世界锁）",
    )
    parser.add_argument("--sync-only", action="store_true", help="只同步插件并准备目录，不启动服务端")
    known, extra = parser.parse_known_args()
    if extra and extra[0] == "--":
        extra = extra[1:]

    if known.build_plugins:
        build_plugins()

    build = resolve_build(known.version, known.build)
    print(f"    Paper {known.version} build {build['id']}（{build.get('channel', 'unknown')}）")
    jar = ensure_server_jar(known.version, build)

    run_dir = pathlib.Path(known.dir).expanduser().resolve()
    prepare_run_dir(run_dir)
    sync_plugins(run_dir, known.selected)
    sync_datapacks(run_dir)
    if known.sync_only:
        print(f"==> 目录已就绪：{run_dir}")
        return

    java = detect_java(known.java)
    command = [
        java,
        "-Xms512M",
        f"-Xmx{known.memory}",
        "-jar",
        str(jar),
        "--nogui",
        *extra,
    ]
    print(f"==> 启动：{' '.join(command)}")
    print(f"    工作目录：{run_dir}（输入 stop 或 Ctrl+C 停止）")
    process = subprocess.Popen(command, cwd=run_dir)
    try:
        process.wait()
    except KeyboardInterrupt:
        print("\n==> 收到 Ctrl+C，正在关闭服务端")
        process.wait(timeout=120)
    sys.exit(process.returncode or 0)


if __name__ == "__main__":
    main()
