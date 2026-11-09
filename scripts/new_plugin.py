#!/usr/bin/env python3
"""从模板生成一个新的插件工程。

  --type paper（默认）  templates/paper-plugin  -> plugins/<id>/   Paper 插件
  --type fabric        templates/fabric-plugin -> mods/<id>/      Fabric 模组（历史）

用法：
  python scripts/new_plugin.py --id my-plugin --name "我的插件"
  python scripts/new_plugin.py --id my-mod --type fabric --package com.example.mymod
"""

from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATES = {
    "paper": (REPO_ROOT / "templates" / "paper-plugin", REPO_ROOT / "plugins"),
    "fabric": (REPO_ROOT / "templates" / "fabric-plugin", REPO_ROOT / "mods"),
}
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_-]*$")
PACKAGE_PATTERN = re.compile(r"^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*$")
DEFAULT_AUTHOR = "XiYuan6Jia"
TEXT_SUFFIXES = {".java", ".json", ".yml", ".yaml", ".kts", ".mcmeta", ".mcfunction", ".md", ".properties"}


def pascal_case(mod_id: str) -> str:
    parts = [part for part in re.split(r"[-_]+", mod_id) if part]
    return "".join(part[:1].upper() + part[1:] for part in parts)


def default_package(mod_id: str) -> str:
    return "com.xiyuan.plugmc." + re.sub(r"[-_]", "", mod_id)


def read_property(key: str) -> str | None:
    path = REPO_ROOT / "gradle.properties"
    if not path.is_file():
        return None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name.strip() == key:
            return value.strip()
    return None


def replace_tokens(text: str, values: dict[str, str]) -> str:
    for key, value in values.items():
        text = text.replace(f"__{key}__", value)
    return text


def copy_template(template_dir: pathlib.Path, dest: pathlib.Path, values: dict[str, str]) -> None:
    package_path = values["PACKAGE"].replace(".", "/")
    for src in sorted(template_dir.rglob("*")):
        if not src.is_file():
            continue
        relative = src.relative_to(template_dir).as_posix()
        relative = relative.replace("__PACKAGE_PATH__", package_path)
        relative = relative.replace("__MAIN_CLASS__", values["MAIN_CLASS"])
        target = dest / pathlib.Path(relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix in TEXT_SUFFIXES:
            content = replace_tokens(src.read_text(encoding="utf-8"), values)
            target.write_text(content, encoding="utf-8", newline="\n")
        else:
            shutil.copy2(src, target)


def main() -> None:
    parser = argparse.ArgumentParser(description="从模板创建新的插件工程")
    parser.add_argument("--id", required=True, help="插件 id / 目录名，如 my-plugin")
    parser.add_argument(
        "--type",
        choices=sorted(TEMPLATES),
        default="paper",
        help="插件类型：paper（默认）或 fabric（历史）",
    )
    parser.add_argument("--name", help="显示名称，默认与 id 相同")
    parser.add_argument("--package", help="Java 包名，默认 com.xiyuan.plugmc.<id>")
    parser.add_argument("--class", dest="main_class", help="入口类名，默认由 id 生成")
    parser.add_argument("--version", help="初始版本号，默认读取 gradle.properties 的 version")
    parser.add_argument("--author", default=DEFAULT_AUTHOR, help=f"作者（默认 {DEFAULT_AUTHOR}）")
    parser.add_argument("--description", help="插件描述")
    args = parser.parse_args()

    template_dir, plugins_root = TEMPLATES[args.type]

    mod_id = args.id.strip()
    if not ID_PATTERN.match(mod_id):
        sys.exit("id 只能包含小写字母、数字、- 和 _，且以字母开头")
    package = args.package or default_package(mod_id)
    if not PACKAGE_PATTERN.match(package):
        sys.exit(f"包名不合法：{package}")

    dest = plugins_root / mod_id
    if dest.exists():
        sys.exit(f"目录已存在：{dest}")

    name = args.name or mod_id
    suffix = "Paper" if args.type == "paper" else "Fabric"
    values = {
        "MOD_ID": mod_id,
        "MOD_NAME": name,
        "PACKAGE": package,
        "MAIN_CLASS": args.main_class or pascal_case(mod_id),
        "VERSION": args.version or read_property("version") or "0.1.0",
        "AUTHOR": args.author,
        "DESCRIPTION": args.description or f"{name}（基于 {suffix}）。",
    }
    copy_template(template_dir, dest, values)

    gradle_path = f":{plugins_root.name}:{mod_id}"
    print(f"已创建插件工程：{dest.relative_to(REPO_ROOT)}")
    print("下一步：")
    print(f"  .\\gradlew {gradle_path}:build")
    if args.type == "paper":
        print("  python scripts/run_paper.py            # 本地起 Paper 开发服（自动装入已构建插件）")
        print("  python scripts/deploy_paper.py         # 构建并部署到麦块联机测试服")
    else:
        print(f"  .\\gradlew :mods:{mod_id}:runServer     # 只跑该插件")
        print("  .\\gradlew :dev:runServer               # 全部插件联调")


if __name__ == "__main__":
    main()
