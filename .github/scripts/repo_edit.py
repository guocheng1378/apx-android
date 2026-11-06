#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

约定：无参执行，脚本内 EDITS 自包含；每步 git diff 非空才提交。
"""
import sys, json, os, re, difflib

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

def find_file(name):
    for p in (WORKSPACE, BASE):
        for x in (name, os.path.basename(name)):
            if os.path.exists(os.path.join(p, x)):
                return os.path.join(p, x)
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
    return "".join(c[1] for c in list(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True), lineterm=""))[-4000:])

def find_anchor(name, content):
    hits = []
    for m in re.finditer(rf"//\s*#\s*{{re.escape(name)}}\s*(?:\{{[^}]*\}})?\s*(?:@\d+)?", content):
        hits.append((m.start(), m.end(), m.group(0)))
    if not hits:
        return None
    if len(hits) == 1:
        return hits[0]
    return max(hits, key=lambda h: h[1] - h[0])

def do_replacements(text, edits):
    for old, new in edits:
        text = text.replace(old, new)
    return text

def commit(msg, files):
    for f in files:
        r = req("GET", f"repos/{{OWNER}}/{{REPO}}/git/blobs/{{f['sha']}}")
        write_file(f["path"], r["content"].decode("utf-8"))
    parent = req("GET", f"repos/{{OWNER}}/{{REPO}}/git/refs/heads/{{HEAD}}").get("object", {}).get("sha")
    tree = req("POST", f"repos/{{OWNER}}/{{REPO}}/git/trees", json={"tree": files, "base_tree": parent}).get("sha")
    req("POST", f"repos/{{OWNER}}/{{REPO}}/git/commits", json={"message": msg, "tree": tree, "parents": [parent]})
    for f in files:
        req("POST", f"repos/{{OWNER}}/{{REPO}}/git/blobs/{{f['sha']}}/comments", json={"message": "edited by repo-edit"})

def step(path, anchor, edit, files_out):
    content = read_file(path)
    anchor_found = find_anchor(anchor, content)
    if not anchor_found:
        print(f"ANCHOR_NOT_FOUND:{path}:{anchor}")
        return False
    new_content = do_replacements(content, edit)
    if new_content == content:
        print(f"NO_CHANGE:{path}:{anchor}")
        return False
    files_out.append({"path": path, "sha": None})
    return True

def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    target, anchor_name, edit_index = sys.argv[1], sys.argv[2], int(sys.argv[3])
    path = find_file(target)
    if not path:
        print(f"FILE_NOT_FOUND:{target}")
        sys.exit(1)
    content = read_file(path)
    anchor = find_anchor(anchor_name, content)
    if not anchor:
        print(f"ANCHOR_NOT_FOUND:{target}:{anchor_name}")
        sys.exit(1)
    edit = edits[edit_index]
    new_content = do_replacements(content, edit)
    diff = diff_content(content, new_content)
    print(json.dumps({"path": path, "anchor": anchor_name, "diff": diff}, ensure_ascii=False))
    return 0

edits = {}

files_out = []
for (path, anchor), edit in edits.items():
    if step(path, anchor, edit, files_out):
        print(f"OK:{path}:{anchor}")
    else:
        print(f"SKIP:{path}:{anchor}")

if files_out:
    commit("batch 2: 修正方案 — 补 kStreamCamera、导出 cameraView 单例、panel_win32 接 s6 feed", files_out)
    print(f"COMMITTED:{len(files_out)} files")
else:
    print("NO_CHANGES")

sys.exit(0)
