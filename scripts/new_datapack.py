#!/usr/bin/env python3
"""从 templates/datapack 生成一个新的数据包目录（datapacks/<id>/）。

用法：
  python scripts/new_datapack.py --id my-pack --name "我的数据包"
  python scripts/new_datapack.py --id my-pack --pack-format 121
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE_DIR = REPO_ROOT / "templates" / "datapack"
DATAPACKS_DIR = REPO_ROOT / "datapacks"
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_.-]*$")
TEXT_SUFFIXES = {".mcmeta", ".mcfunction", ".json", ".md"}


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


def copy_template(dest: pathlib.Path, values: dict[str, str]) -> None:
    for src in sorted(TEMPLATE_DIR.rglob("*")):
        if not src.is_file():
            continue
        relative = src.relative_to(TEMPLATE_DIR).as_posix()
        relative = relative.replace("__NAMESPACE__", values["NAMESPACE"])
        target = dest / pathlib.Path(relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix in TEXT_SUFFIXES:
            content = replace_tokens(src.read_text(encoding="utf-8"), values)
            target.write_text(content, encoding="utf-8", newline="\n")
        else:
            target.write_bytes(src.read_bytes())


def main() -> None:
    parser = argparse.ArgumentParser(description="从模板创建新的数据包")
    parser.add_argument("--id", required=True, help="数据包目录名 / 命名空间，如 my-pack")
    parser.add_argument("--name", help="显示名称，默认与 id 相同")
    parser.add_argument(
        "--pack-format",
        type=int,
        default=None,
        help="数据包格式版本，默认读取 gradle.properties 的 datapack_format",
    )
    args = parser.parse_args()

    namespace = args.id.strip()
    if not ID_PATTERN.match(namespace):
        sys.exit("id 只能包含小写字母、数字、-、_ 和 .，且以字母开头")

    dest = DATAPACKS_DIR / namespace
    if dest.exists():
        sys.exit(f"目录已存在：{dest}")

    pack_format = args.pack_format
    if pack_format is None:
        raw = read_property("datapack_format")
        if raw is None or not raw.isdigit():
            sys.exit("gradle.properties 缺少有效的 datapack_format，请用 --pack-format 指定")
        pack_format = int(raw)

    name = args.name or namespace
    values = {
        "NAMESPACE": namespace,
        "NAME": name,
        "PACK_FORMAT": str(pack_format),
    }
    copy_template(dest, values)

    print(f"已创建数据包：{dest.relative_to(REPO_ROOT)}")
    print("下一步：")
    print("  .\\gradlew :datapacks:buildDatapacks   # 产出 datapacks/build/libs/" + namespace + ".zip")
    print("  .\\gradlew :datapacks:syncToRun        # 同步到 run/world/datapacks")
    print("  .\\gradlew :dev:runServer              # 启动开发服（会自动同步数据包）")


if __name__ == "__main__":
    main()
