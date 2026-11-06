#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。把小而精确的改动交给 Actions 云端执行。"""
import sys, json, os, re

BASE = os.path.dirname(os.path.abspath(__file__))
WORKSPACE = os.environ.get("GITHUB_WORKSPACE", BASE)
API = os.environ["GITHUB_API_URL"].rstrip("/")
HEAD = os.environ["GITHUB_REF_NAME"].strip("refs/heads/")
OWNER = os.environ["GITHUB_REPOSITORY_OWNER"]
REPO = os.environ["GITHUB_REPOSITORY"].split("/")[1]
TOKEN = os.environ["GH_TOKEN"]


def req(method, path, data=None):
    url = f"{API}/{OWNER}/{REPO}/{path}"
    h = {"Accept": "application/vnd.github.v3+json", "Authorization": f"token {TOKEN}"}
    r = __import__("requests").requests.request(method, url, headers=h, json=data)
    r.raise_for_status()
    return r.json() if data else r.json()


def find_file(base, name, max_attempts=3):
    for i in range(max_attempts):
        p = os.path.join(base, name)
        if os.path.exists(p):
            return p
        p = os.path.join(base, os.path.basename(name))
        if os.path.exists(p):
            return p
        try:
            r = req("GET", f"search/code?q={name}&per_page=10")
            for it in r.get("items", []):
                p = os.path.join(WORKSPACE, it["path"])
                if os.path.exists(p):
                    return p
        except Exception:
            pass
    return None


def read_file(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write_file(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def diff_content(old, new):
    return "".join(c[1] for c in list(__import__("difflib").difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True), lineterm=""))[-4000:])


def find_anchor(name, content):
    hits = []
    for m in re.finditer(rf"//\s*#\s*{re.escape(name)}\s*(?:\{[^}]*\})?\s*(?:@\d+)?", content):
        hits.append((m.start(), m.end(), m.group(0)))
    if not hits:
        return None
    if len(hits) == 1:
        return hits[0]
    best = max(hits, key=lambda h: h[1] - h[0])
    return best


def anchor_spans(text, anchor):
    lo, hi = anchor[0], anchor[1]
    spans = [(lo, text[lo:hi])] if lo >= 0 else [(0, text)]
    return [((lo, hi), sp) for sp in spans]


def do_replacements(text, edits):
    for (old, new) in edits:
        old = old.encode("utf-8", "surrogateescape").decode("utf-8")
        new = new.encode("utf-8", "surrogateescape").decode("utf-8")
        text = text.replace(old, new)
    return text


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    target, anchor_name, edit_index = sys.argv[1], sys.argv[2], int(sys.argv[3])
    path = find_file(WORKSPACE, target)
    if not path:
        print(f"File not found: {target}")
        sys.exit(1)
    content = read_file(path)
    anchor = find_anchor(anchor_name, content)
    if not anchor:
        print(f"Anchor not found: {anchor_name}")
        sys.exit(1)
    edit = edits[edit_index]
    new_content = do_replacements(content, edit)
    diff = diff_content(content, new_content)
    print(json.dumps({"path": path, "anchor": anchor_name, "diff": diff}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
