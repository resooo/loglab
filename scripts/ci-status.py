#!/usr/bin/env python3
"""
查询 GitHub Actions run 状态与各步骤结果。

沙箱网络间歇性超时（RemoteDisconnected / SSL handshake timeout），
因此所有请求都带重试 —— 单次失败不代表 run 有问题。

用法：
    python3 scripts/ci-status.py [run_id]
不给 run_id 时取最近一次 run。
"""
import json
import re
import sys
import time
import urllib.request

REPO = "resooo/loglab"
HOST = "https://api.github.com"


def token() -> str:
    raw = open("/root/.git-credentials", encoding="utf-8").read().strip()
    return re.search(r"https://[^:]+:([^@]+)@", raw).group(1)


def api(path: str, tries: int = 5):
    """带重试的 GET；沙箱网络不稳，单次失败就重试。"""
    headers = {
        "Authorization": "Bearer " + token(),
        "User-Agent": "taixu",
        "Accept": "application/vnd.github+json",
        "Connection": "close",  # 避免复用被中断的 keep-alive 连接
    }
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(HOST + path, headers=headers)
            return json.load(urllib.request.urlopen(req, timeout=30))
        except Exception as e:
            last = e
            time.sleep(2 + i * 2)
    raise RuntimeError(f"连续 {tries} 次请求失败: {type(last).__name__}: {last}")


def main() -> int:
    rid = sys.argv[1] if len(sys.argv) > 1 else None
    if rid is None:
        runs = api(f"/repos/{REPO}/actions/runs?per_page=1")["workflow_runs"]
        if not runs:
            print("没有 workflow run")
            return 1
        rid = runs[0]["id"]

    r = api(f"/repos/{REPO}/actions/runs/{rid}")
    print(f"run #{r['run_number']}  sha={r['head_sha'][:7]}  "
          f"{r['status']} / {r['conclusion']}")
    print(f"  {r['html_url']}")

    for j in api(f"/repos/{REPO}/actions/runs/{rid}/jobs")["jobs"]:
        print(f"\nJOB {j['name']}: {j['status']} / {j['conclusion']}")
        for s in j["steps"]:
            mark = {"success": "OK  ", "skipped": "--  "}.get(
                s["conclusion"], s["status"][:4].ljust(4))
            print(f"   [{mark}] {s['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
