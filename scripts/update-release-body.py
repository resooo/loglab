#!/usr/bin/env python3
"""
把 RELEASE_NOTES_<tag>.md 的内容更新到对应 GitHub Release 的正文。

用途：CI 工作流此前漏配 body_path，导致 Release 页面正文空白。
修好工作流后，这里先给已发布的版本补上正文，无需重跑构建。

用法：python3 scripts/update-release-body.py <tag> [notes_file]
"""
import json
import re
import sys
import urllib.request

REPO = "resooo/loglab"


def main() -> int:
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.9.6"
    notes = sys.argv[2] if len(sys.argv) > 2 else f"RELEASE_NOTES_{tag}.md"

    raw = open("/root/.git-credentials", encoding="utf-8").read().strip()
    tok = re.search(r"https://[^:]+:([^@]+)@", raw).group(1)
    headers = {
        "Authorization": "Bearer " + tok,
        "User-Agent": "taixu",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    }

    body = open(notes, encoding="utf-8").read()
    # 用列表接口按 tag_name 查找，而不是 /releases/tags/<tag>：
    # 后者在刚才实测中返回 404（疑似该 tag 被删除过再重建，
    # 导致按 tag 的查询索引未及时恢复），而列表接口能正常取到。
    rel = None
    for page in (1, 2):
        url = f"https://api.github.com/repos/{REPO}/releases?per_page=30&page={page}"
        items = json.load(urllib.request.urlopen(
            urllib.request.Request(url, headers=headers), timeout=30))
        if not items:
            break
        rel = next((r for r in items if r["tag_name"] == tag), None)
        if rel:
            break
    if rel is None:
        print(f"❌ 未找到 tag 为 {tag} 的 Release")
        return 1

    print(f"找到 Release {rel['tag_name']} (id={rel['id']})")
    print(f"  当前正文长度: {len(rel.get('body') or '')}")
    print(f"  待写入长度:   {len(body)}  (来源 {notes})")

    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases/{rel['id']}",
        data=json.dumps({"body": body}).encode(),
        headers=headers,
        method="PATCH",
    )
    out = json.load(urllib.request.urlopen(req, timeout=40))
    print(f"✅ 更新完成，正文长度: {len(out['body'])}")
    print(f"   {out['html_url']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
