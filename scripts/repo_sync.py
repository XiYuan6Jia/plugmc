#!/usr/bin/env python3
"""改动前后与 GitHub 仓库做同步检查 / 推送。

用途（按用户约束设计的固定流程）：

  改动前：  python scripts/repo_sync.py check
            —— 拉取远端，报告另一位开发者有没有新提交、动了哪些文件，
               这些文件与本机未提交改动有没有重叠（重叠就是冲突风险）。

  改动后：  python scripts/repo_sync.py push -m "提交说明"
            —— 提交本机改动、推送、并回读验证本地与远端完全一致。
               推送成功后才更新 .local/last_sync.json 里的同步水位。

设计约束：
  * 只做 git 操作，不碰服务器、不碰线上数据。
  * check 从不修改工作区（不 pull、不 merge、不 stash），只读。
  * push 在发现落后远端时默认拒绝执行，要求先手动处理，避免把别人的提交覆盖掉。
  * 所有输出不包含任何凭据。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
SYNC_MARKER = REPO_ROOT / ".local" / "last_sync.json"
UPSTREAM = "origin/main"


# ---------------------------------------------------------------- git 基础

def git(*args: str, check: bool = True) -> str:
    """跑一条 git 命令，返回 stdout（已 strip）。"""
    result = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and result.returncode != 0:
        print(result.stdout.strip())
        sys.exit(f"git {' '.join(args)} 失败（exit {result.returncode}）")
    return result.stdout.strip()


def self_email() -> str:
    email = git("config", "user.email", check=False)
    return email.strip().lower()


def read_marker() -> str | None:
    """上次推送成功后记录的远端 sha。"""
    if not SYNC_MARKER.exists():
        return None
    try:
        data = json.loads(SYNC_MARKER.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    sha = (data or {}).get("last_synced_sha")
    return sha if isinstance(sha, str) and sha else None


def write_marker(sha: str) -> None:
    SYNC_MARKER.parent.mkdir(parents=True, exist_ok=True)
    SYNC_MARKER.write_text(
        json.dumps({"last_synced_sha": sha}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


# ---------------------------------------------------------------- 状态收集

def fetch() -> None:
    print("==> git fetch --prune origin")
    git("fetch", "--prune", "origin")


def divergence() -> tuple[int, int]:
    """(本地领先数, 远端领先数)。"""
    out = git("rev-list", "--left-right", "--count", f"main...{UPSTREAM}")
    ahead, behind = out.split("\t")
    return int(ahead), int(behind)


def dirty_paths() -> list[str]:
    out = git("status", "--porcelain")
    paths = []
    for line in out.splitlines():
        if len(line) < 4:
            continue
        paths.append(line[3:].strip().strip('"'))
    return paths


def commits_since(sha: str | None) -> list[dict]:
    """列出自 sha 之后的提交（sha 为 None 时取最近 30 条）。"""
    fmt = "--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s"
    date = "--date=format:%Y-%m-%d %H:%M"
    if sha:
        out = git("log", fmt, date, f"{sha}..{UPSTREAM}")
    else:
        out = git("log", fmt, date, "-n", "30", UPSTREAM)
    if not out:
        return []
    items = []
    for line in out.splitlines():
        parts = line.split("\x1f")
        if len(parts) != 5:
            continue
        items.append(
            {
                "sha": parts[0],
                "author": parts[1],
                "email": parts[2],
                "date": parts[3],
                "subject": parts[4],
            }
        )
    return items


def changed_files(base_sha: str | None) -> list[tuple[str, str]]:
    """(状态, 路径) 列表；状态取 A/M/D/R。base_sha 为 None 时返回空。"""
    if not base_sha:
        return []
    out = git("diff", "--name-status", f"{base_sha}..{UPSTREAM}", check=False)
    rows = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            status = parts[0][:1]
            path = parts[-1] if status == "R" else parts[1]
            rows.append((status, path))
    return rows


# ---------------------------------------------------------------- check

def cmd_check(args: argparse.Namespace) -> int:
    fetch()

    ahead, behind = divergence()
    sha = read_marker()
    dirty = dirty_paths()
    self_mail = self_email()

    print()
    print("=" * 68)
    print("仓库同步检查")
    print("=" * 68)
    print(f"  仓库          {REPO_ROOT}")
    print(f"  当前分支      {git('rev-parse', '--abbrev-ref', 'HEAD')}")
    print(f"  本地 HEAD     {git('rev-parse', '--short', 'HEAD')}")
    print(f"  远端          {UPSTREAM} @ {git('rev-parse', '--short', UPSTREAM)}")
    print(f"  本机身份      {git('config', 'user.name', check=False)} <{self_mail}>")
    print(f"  同步水位      {sha or '（无记录，按最近 30 条远端提交比对）'}")
    print(f"  本地领先      {ahead} 个提交待推送")
    print(f"  远端领先      {behind} 个提交待拉取")
    print(f"  未提交改动    {len(dirty)} 个文件" + ("（工作区干净）" if not dirty else ""))

    commits = commits_since(sha)
    others = [c for c in commits if c["email"].strip().lower() != self_mail]
    mine = [c for c in commits if c["email"].strip().lower() == self_mail]

    # 计算他人改动的文件：优先用同步水位，无水位时用与远端的共同祖先
    base = sha
    if not base:
        mb = git("merge-base", "main", UPSTREAM, check=False)
        base = mb or None
    files = changed_files(base)

    print()
    print("-" * 68)
    print(f"远端新增提交：{len(commits)} 个（其中他人 {len(others)} / 本人 {len(mine)}）")
    if not sha and len(commits) >= 30:
        print("  ⚠ 未记录同步水位，仅比对最近 30 个提交；更早的改动未列出")
    print("-" * 68)

    if others:
        # 按作者归并
        by_author: dict[str, list[dict]] = {}
        for c in others:
            by_author.setdefault(f"{c['author']} <{c['email']}>", []).append(c)
        for who, items in by_author.items():
            print(f"\n  ◆ {who}（{len(items)} 个提交）")
            for c in items:
                print(f"    {c['sha'][:7]}  {c['date']}  {c['subject']}")
    elif commits:
        print("\n  远端新增提交全部来自本人，无他人改动。")
    else:
        print("\n  水位之后远端没有新提交。")

    if others:
        print()
        print("-" * 68)
        print(f"他人改动的文件：{len(files)} 个")
        print("-" * 68)
        label = {"A": "新增", "M": "修改", "D": "删除", "R": "重命名"}
        for status, path in files:
            print(f"    [{label.get(status, status)}] {path}")

        # 冲突风险：他人改的文件 == 本机未提交改的文件
        overlap = sorted(set(p for _, p in files) & set(dirty))
        print()
        if overlap:
            print("  ⚠ 冲突风险：以下文件他人改过，本机也有未提交改动")
            for p in overlap:
                print(f"      {p}")
            print("    处理方式：先 git stash 或先提交本机改动，再 git merge --ff-only origin/main，")
            print("    然后 git stash pop 逐个核对。不要直接 overwrite。")
        else:
            print("  ✓ 他人改动的文件与本机未提交改动无重叠，可以安全 fast-forward。")

    print()
    print("-" * 68)
    print("结论")
    print("-" * 68)
    blocking = False
    if behind > 0:
        blocking = True
        print(f"  ✗ 远端领先 {behind} 个提交 —— 动手前必须先同步：")
        if dirty:
            print("       git stash push -u -m 'wip'")
            print(f"       git merge --ff-only {UPSTREAM}")
            print("       git stash pop")
        else:
            print(f"       git merge --ff-only {UPSTREAM}")
    if ahead > 0:
        print(f"  • 本地有 {ahead} 个提交未推送 —— 改完后跑 push 子命令")
    if not blocking and ahead == 0:
        print("  ✓ 本地与远端一致，可以开始改动")
    if dirty:
        print(f"  • 工作区有 {len(dirty)} 个文件未提交（不算错误，push 时会一起提交）")

    return 1 if blocking else 0


# ---------------------------------------------------------------- push

def cmd_push(args: argparse.Namespace) -> int:
    fetch()
    ahead, behind = divergence()

    print()
    print("=" * 68)
    print("提交并推送")
    print("=" * 68)

    if behind > 0:
        print(f"  ✗ 远端领先 {behind} 个提交，拒绝直接提交推送。")
        print(f"    请先 git merge --ff-only {UPSTREAM} 并检查他人改动，再重跑本命令。")
        return 1

    dirty = dirty_paths()
    if not dirty and ahead == 0:
        print("  ✓ 没有需要提交的改动，本地与远端一致。")
        return 0

    if dirty:
        print(f"==> 暂存 {len(dirty)} 个文件")
        git("add", "-A")
        for p in dirty:
            print(f"    {p}")

        message = args.message
        if not message:
            print("\n未提供 -m 提交说明。下列改动将被提交：")
            for p in dirty:
                print(f"    {p}")
            return 2

        print(f"\n==> git commit -m {message!r}")
        git("commit", "-m", message)

    head = git("rev-parse", "HEAD")
    short = head[:7]

    print(f"==> git push origin main（{short}）")
    push = subprocess.run(
        ["git", "push", "origin", "main"],
        cwd=REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    print(push.stdout.strip())
    if push.returncode != 0:
        return 1

    # 回读验证：必须真的 0 0
    fetch()
    ahead2, behind2 = divergence()
    remote_sha = git("rev-parse", UPSTREAM)
    print()
    print("==> 回读验证")
    print(f"    本地 HEAD        {short}")
    print(f"    远端 origin/main {remote_sha[:7]}")
    print(f"    领先 / 落后      {ahead2} / {behind2}")

    if ahead2 == 0 and behind2 == 0 and remote_sha.startswith(head[:12]):
        write_marker(remote_sha)
        print(f"\n  ✓ 已同步，同步水位更新为 {remote_sha[:7]}")
        return 0

    print("\n  ✗ 推送后状态不符（远端未指向本地 HEAD），请检查。")
    return 1


# ---------------------------------------------------------------- 入口

def main() -> None:
    # 控制台默认是 GBK，脚本里的 ✓ 会直接抛 UnicodeEncodeError，而不是打出来乱码
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(
        description="改动前检查远端更新、改动后提交推送",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="只读检查：远端有无他人新提交、有无冲突风险")
    p_check.set_defaults(func=cmd_check)

    p_push = sub.add_parser("push", help="提交本机改动并推送，回读验证")
    p_push.add_argument("-m", "--message", help="提交说明（缺省时只列改动不提交）")
    p_push.set_defaults(func=cmd_push)

    args = parser.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
