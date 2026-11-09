#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Veyra 上游更新巡检（只读，零第三方依赖）。

每天由定时任务调用，做三件事：
  1. 查上游 gybeyond1/Veyra 的 main 是否有新提交；
  2. 有更新时，拉取「基线版本 -> 上游最新」以及「上次检查 -> 本次」的提交与文件差异；
  3. 与本地定制文件清单交叉比对，生成中文报告，供后续判断是否需要合并。

用法：
    python scripts/upstream-watch.py               检查更新，有更新才出报告
    python scripts/upstream-watch.py --json        机器可读输出（定时任务用）
    python scripts/upstream-watch.py --force       即使无更新也出一份全量报告
    python scripts/upstream-watch.py --since v0.28.12   指定基线 tag

产出（docs/upstream-watch/）：
    state.json          上次检查到的上游 HEAD 与基线信息
    YYYY-MM-DD.md       当日巡检报告

硬约束：只读 git 与 GitHub API，不 fetch、不合并、不提交、不推送、不改任何业务代码。
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OUT_DIR = REPO / "docs" / "upstream-watch"
STATE_FILE = OUT_DIR / "state.json"
UPSTREAM = "gybeyond1/Veyra"
API = "https://api.github.com"
FALLBACK_BASELINE_TAG = "v0.28.12"
HEADERS = {
    "User-Agent": "binguo-upstream-watch/1.0",
    "Accept": "application/vnd.github+json",
    # compare 响应会到几百 KB，压缩后能显著降低被中途截断的概率
    "Accept-Encoding": "gzip",
}

# 我方深度定制过的文件：上游若也动了，合并时必须人工核对，切勿直接覆盖
PROTECTED_EXACT = {
    "app/src/main/assets/app.js",
    "app/src/main/assets/app.css",
    "app/src/main/assets/app.html",
    "app/src/main/assets/appearance.js",
    "app/src/main/assets/hot-update-public.pem",
    "app/src/main/assets/alipay-qr.png",
    "app/src/main/java/com/veyra/gybeyond/MainActivity.java",
    "app/src/main/java/com/veyra/gybeyond/PlayerActivity.java",
    "app/src/main/java/com/veyra/gybeyond/HongguoRepository.java",
    "app/src/main/java/com/veyra/gybeyond/HongguoAccount.java",
    "app/src/main/java/com/veyra/gybeyond/SecureStore.java",
    "app/src/main/java/com/veyra/gybeyond/UpdateManager.java",
    "app/src/main/java/com/veyra/gybeyond/HotUpdateManager.java",
    "app/src/main/java/com/veyra/gybeyond/VideoPlaybackCache.java",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "README.md",
    "build-mine.ps1",
    "publish-update.ps1",
    "gradle/wrapper/gradle-wrapper.properties",
    "server/hongguo-app.js",
    "server/account-check.js",
    "scripts/brand-patch.py",
    "scripts/patch-remote.py",
    "scripts/sign-hot-update.py",
}
PROTECTED_PREFIXES = (
    "app/src/main/res/",
    "third_party/hongguo/",
)
HIGH_VALUE_PREFIXES = (
    "app/src/main/java/",
    "app/src/main/assets/",
    "server/",
    "third_party/",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
)
LOW_VALUE_PREFIXES = (
    ".github/",
    "gradle/",
    "desktop/",
    "scripts/",
    ".gitignore",
)


def ver_key(tag: str) -> tuple:
    nums = re.findall(r"\d+", tag or "")
    return tuple(int(n) for n in nums) if nums else (0,)


def is_protected(path: str) -> bool:
    return path in PROTECTED_EXACT or path.startswith(PROTECTED_PREFIXES)


def value_rank(path: str) -> int:
    if path.startswith(LOW_VALUE_PREFIXES):
        return 0
    if path.startswith(HIGH_VALUE_PREFIXES):
        return 2
    return 1


def api_get(path: str, tries: int = 4):
    url = API + path
    last = "unknown error"
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=45) as resp:
                raw = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            hint = "（未认证 API 配额可能已用完）" if exc.code in (403, 429) else ""
            last = f"HTTP {exc.code} {path} {hint}".strip()
            if exc.code in (403, 404, 429):
                break
        except Exception as exc:  # noqa: BLE001 - 网络错误统一转成人话
            last = f"{type(exc).__name__}: {exc} @ {path}"
        if attempt < tries - 1:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(last)


def git(*args: str) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=str(REPO),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败: {proc.stderr.strip()}")
    return proc.stdout.strip()


def local_state() -> dict:
    """读取本地仓库的基线提交、我方改动文件、当前版本号。"""
    head = git("rev-parse", "HEAD")
    base_sha, base_tag = None, FALLBACK_BASELINE_TAG
    for line in git("log", "--format=%H%x09%s", "HEAD").splitlines():
        sha, _, subject = line.partition("\t")
        match = re.match(r"baseline:\s*(v?[\d.]+)\s+upstream snapshot", subject.strip(), re.I)
        if match:
            base_sha, base_tag = sha, match.group(1)
            break
    if not base_sha:
        raise RuntimeError("本地历史里找不到 baseline 提交，无法定位分叉基线")

    ours = {}
    for line in git("diff", "--name-status", f"{base_sha}..HEAD").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            ours[parts[-1].strip()] = parts[0].strip()

    version = ""
    try:
        gradle = (REPO / "app" / "build.gradle").read_text(encoding="utf-8")
        match = re.search(r'versionName\s+"([^"]+)"', gradle)
        if match:
            version = match.group(1)
    except OSError:
        pass

    return {
        "head": head,
        "baseline_commit": base_sha,
        "baseline_tag": base_tag,
        "our_version": version,
        "ours": ours,
    }


def upstream_state(baseline_tag: str) -> dict:
    head_commit = api_get(f"/repos/{UPSTREAM}/commits?sha=main&per_page=1")[0]
    tags = api_get(f"/repos/{UPSTREAM}/tags?per_page=100")
    tag_names = sorted({t["name"] for t in tags}, key=ver_key)
    latest_tag = tag_names[-1] if tag_names else ""
    full = api_get(f"/repos/{UPSTREAM}/compare/{baseline_tag}...{head_commit['sha']}")
    releases = api_get(f"/repos/{UPSTREAM}/releases?per_page=10")
    recent_releases = [
        {
            "tag": r.get("tag_name", ""),
            "published_at": r.get("published_at", ""),
            "body": (r.get("body") or "").strip(),
        }
        for r in releases
        if ver_key(r.get("tag_name", "")) >= ver_key(baseline_tag)
    ][:8]
    return {
        "head_sha": head_commit["sha"],
        "head_date": head_commit["commit"]["author"]["date"],
        "head_message": head_commit["commit"]["message"].splitlines()[0],
        "latest_tag": latest_tag,
        "full": full,
        "releases": recent_releases,
        "html_url": head_commit.get("html_url", ""),
    }


def local_time(iso: str) -> str:
    if not iso:
        return "-"
    try:
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone()
        return dt.strftime("%Y-%m-%d %H:%M")
    except ValueError:
        return iso


def summarize_files(files: list, ours: dict) -> dict:
    items = []
    for f in files:
        path = f.get("filename", "")
        prev = f.get("previous_filename")
        status = f.get("status", "modified")
        own = ours.get(path) or (ours.get(prev) if prev else None)
        items.append({
            "path": path,
            "status": status,
            "own": own,
            "protected": is_protected(path),
            "rank": value_rank(path),
            "additions": f.get("additions", 0),
            "deletions": f.get("deletions", 0),
            "patch": f.get("patch", "") or "",
        })
    sort_key = lambda x: (-x["rank"], x["path"])  # noqa: E731
    overlap = sorted([x for x in items if x["own"]], key=sort_key)
    added = sorted([x for x in items if not x["own"] and x["status"] == "added"], key=sort_key)
    removed = sorted([x for x in items if not x["own"] and x["status"] == "removed"], key=sort_key)
    clean = sorted(
        [x for x in items if not x["own"] and x["status"] not in ("added", "removed")],
        key=sort_key,
    )
    return {"all": items, "overlap": overlap, "added": added, "removed": removed, "clean": clean}


def commit_rows(commits: list) -> list:
    return [
        {
            "sha": c.get("sha", "")[:10],
            "date": local_time(c.get("commit", {}).get("author", {}).get("date", "")),
            "message": c.get("commit", {}).get("message", "").splitlines()[0][:120],
        }
        for c in commits
    ]


def md_table(items: list) -> str:
    if not items:
        return "（无）\n"
    lines = ["| 文件 | 上游状态 | +/- | 备注 |", "| --- | --- | --- | --- |"]
    for x in items:
        if x["own"]:
            flag = f"我方也改过（{x['own']}），🔴 必须人工合并"
            if x["protected"]:
                flag += "；且在定制保护清单内，勿整文件覆盖"
        elif x["protected"]:
            flag = "位于我方定制目录，合并前确认依赖关系"
        else:
            flag = "—"
        lines.append(
            f"| `{x['path']}` | {x['status']} | +{x['additions']}/-{x['deletions']} | {flag or '—'} |"
        )
    return "\n".join(lines) + "\n"


def patch_section(items: list, max_lines: int, max_files: int) -> str:
    picked = [x for x in items if x["patch"]][:max_files]
    if not picked:
        return "（无可用 diff）\n"
    out = []
    for x in picked:
        body = "\n".join(x["patch"].splitlines()[:max_lines])
        if len(x["patch"].splitlines()) > max_lines:
            body += "\n... （已截断，完整 diff 见 GitHub compare）"
        out.append(f"### `{x['path']}`\n\n```diff\n{body}\n```\n")
    return "\n".join(out)


def render_report(loc: dict, up: dict, inc: dict | None, base_tag: str, forced: bool) -> str:
    now = datetime.now().astimezone()
    full_files = summarize_files(up["full"].get("files", []), loc["ours"])
    inc_files = summarize_files(inc.get("files", []), loc["ours"]) if inc else None
    scope = inc_files or full_files
    full_commits = commit_rows(up["full"].get("commits", []))
    # 首次运行（没有上次检查点）时，"本次" 就等于自基线以来的全量
    inc_commits = commit_rows(inc.get("commits", [])) if inc else full_commits

    lines = [
        f"# Veyra 上游更新巡检 · {now:%Y-%m-%d}",
        "",
        f"> 生成时间：{now:%Y-%m-%d %H:%M:%S %z}　|　脚本：`scripts/upstream-watch.py`　|　模式：{'手动全量' if forced else '每日自动'}",
        "",
        "## 结论速览",
        "",
        f"- **上游**：`{up['latest_tag'] or '未打 tag'}`　HEAD `{up['head_sha'][:10]}`（{local_time(up['head_date'])}）— {up['head_message']}",
        f"- **我方**：`{loc['our_version']}`　HEAD `{loc['head'][:10]}`，基线 `{base_tag}`（`{loc['baseline_commit'][:10]}`）",
        f"- **本次新增**：{len(inc_commits)} 个提交 / {len(scope['all'])} 个文件变动",
        f"- **自基线累计未合并**：{len(full_commits)} 个提交 / {len(full_files['all'])} 个文件变动",
        f"- **撞上我方定制**：本次 {len(scope['overlap'])} 个、累计 {len(full_files['overlap'])} 个（🔴 需人工合并）",
        f"- **我方未动、可直接合并**：本次 {len(scope['clean'])} 个、累计 {len(full_files['clean'])} 个（🟢 风险低）",
        f"- 上游变更文件与我方定制文件总数：上游 {len(full_files['all'])} / 我方 {len(loc['ours'])}",
        "",
        "> 合并结论由每日定时任务在文末「合并建议」章节补充；本脚本只负责采集事实，不做决策、不改代码。",
        "",
        "## 本次新增提交",
        "",
    ]
    if inc_commits:
        lines += ["| SHA | 时间 | 说明 |", "| --- | --- | --- |"]
        lines += [f"| `{c['sha']}` | {c['date']} | {c['message']} |" for c in inc_commits]
        if not inc:
            lines.append("")
            lines.append("> 首次巡检，本次范围即自基线以来的全部提交。")
    else:
        lines.append("（上游无新增提交）")
    lines += ["", "## Release 说明（基线与之后）", ""]
    if up["releases"]:
        for r in up["releases"][:3]:
            body = r["body"].replace("\r", "")[:450]
            lines += [f"**{r['tag']}**（{local_time(r['published_at'])}）", "", "```text", body, "```", ""]
        rest = [r["tag"] for r in up["releases"][3:]]
        if rest:
            lines += ["更早版本仅列标签：" + "、".join(f"`{t}`" for t in rest), ""]
    else:
        lines.append("（无）")

    lines += [
        "",
        "## 变更文件交叉比对（本次范围）",
        "",
        "### 🔴 上游动了、我方也动过 —— 必须人工合并",
        "",
        md_table(scope["overlap"]),
        "### 🟢 上游动了、我方未动 —— 可直接合并",
        "",
        md_table(scope["clean"]),
        "### 🆕 上游新增文件",
        "",
        md_table(scope["added"]),
        "### ⚠️ 上游删除文件",
        "",
        md_table(scope["removed"]),
        "## 自基线累计未合并清单",
        "",
        "| 文件 | 上游状态 | 我方状态 | 备注 |",
        "| --- | --- | --- | --- |",
    ]
    for x in sorted(full_files["all"], key=lambda i: (-i["rank"], i["path"])):
        mark = "🔴 重叠" if x["own"] else ("⚠️ 定制目录" if x["protected"] else "🟢")
        lines.append(
            f"| `{x['path']}` | {x['status']} | {x['own'] or '—'} | {mark} |"
        )
    lines += [
        "",
        "## 累计提交清单",
        "",
        "| SHA | 时间 | 说明 |",
        "| --- | --- | --- |",
    ]
    lines += [f"| `{c['sha']}` | {c['date']} | {c['message']} |" for c in full_commits]
    lines += [
        "",
        "## 关键 diff 摘录（本次范围）",
        "",
        "### 重叠文件（合并前重点看）",
        "",
        patch_section(scope["overlap"], max_lines=25, max_files=8),
        "### 高价值新改动（前 6 个文件）",
        "",
        patch_section([x for x in scope["clean"] if x["rank"] == 2], max_lines=10, max_files=6),
        "## 我方定制保护清单",
        "",
        "上游合并时以下路径必须保留彬果自己的实现，不能整文件覆盖：",
        "",
    ]
    lines += [f"- `{p}`" for p in sorted(PROTECTED_EXACT)]
    lines += [f"- `{p}*`（品牌图标 / 红果账号核心）" for p in PROTECTED_PREFIXES]
    lines += [
        "",
        "重点：品牌改名与图标、自建更新源与热更密钥、红果账号同步（passport/aid=8662/read_history）、",
        "隐藏黄果入口、1.5 倍速、关于页与赞助信息、版本号 `0.28.14xx` 命名规则。",
        "",
        "---",
        "",
        "本报告为只读分析产物：未 fetch、未合并、未提交、未推送，也未修改任何业务代码。",
        "",
    ]
    return "\n".join(lines)


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def unique_report_path() -> Path:
    day = datetime.now().strftime("%Y-%m-%d")
    candidate = OUT_DIR / f"{day}.md"
    n = 2
    while candidate.exists():
        candidate = OUT_DIR / f"{day}-{n}.md"
        n += 1
    return candidate


def emit(payload: dict, as_json: bool) -> None:
    if as_json:
        print(json.dumps(payload, ensure_ascii=False))
        return
    if payload["status"] == "no_update":
        print(f"上游无更新：{payload['tag']} ({payload['head'][:10]})，我方 {payload['our_version']}")
    elif payload["status"] == "updated":
        print(
            f"发现上游更新：{payload['tag']} ({payload['head'][:10]})，"
            f"新增 {payload['new_commits']} 提交 / {payload['new_files']} 文件，"
            f"其中 {payload['overlap_count']} 个与我方定制重叠"
        )
        print(f"报告：{payload['report']}")
    else:
        print(f"检查失败：{payload.get('error')}")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description="Veyra 上游更新巡检")
    parser.add_argument("--json", action="store_true", help="只输出机器可读 JSON")
    parser.add_argument("--force", action="store_true", help="无更新也生成全量报告")
    parser.add_argument("--since", default="", help="覆盖基线 tag，如 v0.28.12")
    args = parser.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    state = load_state()

    try:
        loc = local_state()
        baseline = args.since or loc["baseline_tag"]
        up = upstream_state(baseline)
    except Exception as exc:  # noqa: BLE001
        emit({"status": "error", "error": str(exc)}, args.json)
        return 2

    changed = not state or state.get("last_head_sha") != up["head_sha"]
    if not changed and not args.force:
        state.update({
            "last_check": datetime.now().astimezone().isoformat(timespec="seconds"),
            "checks": int(state.get("checks", 0)) + 1,
        })
        save_state(state)
        emit({
            "status": "no_update",
            "head": up["head_sha"],
            "tag": up["latest_tag"],
            "our_version": loc["our_version"],
            "baseline": baseline,
            "checked_at": state["last_check"],
        }, args.json)
        return 0

    inc = None
    # --force 是手动全量复盘：忽略上次检查点，报告直接按「基线 -> 最新」呈现
    if not args.force and state.get("last_head_sha") and state["last_head_sha"] != up["head_sha"]:
        try:
            inc = api_get(
                f"/repos/{UPSTREAM}/compare/{state['last_head_sha']}...{up['head_sha']}"
            )
        except RuntimeError:
            inc = None  # 上游 force push 等异常情况，退回全量视角

    report = render_report(loc, up, inc, baseline, args.force)
    path = unique_report_path()
    path.write_text(report, encoding="utf-8")

    scope = summarize_files(
        (inc or up["full"]).get("files", []), loc["ours"]
    )
    full_scope = summarize_files(up["full"].get("files", []), loc["ours"])
    now_iso = datetime.now().astimezone().isoformat(timespec="seconds")
    save_state({
        "last_head_sha": up["head_sha"],
        "last_tag": up["latest_tag"],
        "last_check": now_iso,
        "last_report": path.name,
        "baseline_tag": baseline,
        "baseline_commit": loc["baseline_commit"],
        "checks": int(state.get("checks", 0)) + 1,
        "updates": int(state.get("updates", 0)) + (1 if changed else 0),
    })

    emit({
        "status": "updated",
        "head": up["head_sha"],
        "tag": up["latest_tag"],
        "head_message": up["head_message"],
        "our_version": loc["our_version"],
        "our_head": loc["head"],
        "baseline": baseline,
        "report": str(path),
        "new_commits": len((inc or up["full"]).get("commits", [])),
        "new_files": len(scope["all"]),
        "overlap_count": len(scope["overlap"]),
        "clean_count": len(scope["clean"]),
        "added_count": len(scope["added"]),
        "removed_count": len(scope["removed"]),
        "total_unmerged_commits": len(up["full"].get("commits", [])),
        "total_unmerged_files": len(full_scope["all"]),
        "total_overlap": len(full_scope["overlap"]),
        "overlap_files": [x["path"] for x in scope["overlap"]],
        "clean_high_value": [x["path"] for x in scope["clean"] if x["rank"] == 2],
        "release_tags": [r["tag"] for r in up["releases"]],
        "checked_at": now_iso,
    }, args.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
