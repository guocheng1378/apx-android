#!/usr/bin/env python3
"""
程序化获取 GitHub Actions self-hosted runner 注册 token。
解决 self-hosted runner token 只有 ~1 小时有效、容器重启频繁会失效的问题。

用法：
  python3 refresh_token.py --owner Leonxlnx --repo allperiph --pat ghp_xxx

返回 stdout：新 token
返回码：0 成功，非 0 失败

依赖：GitHub PAT（Personal Access Token）需有 repo scope。
在 NAS 上可以做一个 cron：
  */30 * * * * python3 /path/to/refresh_token.py ... > /path/to/APXPC_RUNNER_TOKEN.env
然后 docker-compose 用 env_file 挂进来。
"""
import argparse, sys, urllib.request, urllib.error, json


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--owner", required=True)
    p.add_argument("--repo", required=True)
    p.add_argument("--pat", required=True,
                   help="GitHub Personal Access Token，需 repo scope")
    args = p.parse_args()

    url = f"https://api.github.com/repos/{args.owner}/{args.repo}/actions/runners/registration-token"
    req = urllib.request.Request(
        url, data=b"",
        headers={
            "Authorization": f"Bearer {args.pat}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "apxpc-selfhosted-refresher",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print(f"❌ HTTP {e.code}: {e.read().decode()}", file=sys.stderr)
        return 1

    token = data.get("token", "")
    expires = data.get("expires_at", "")
    if not token:
        print("❌ GitHub 未返回 token", file=sys.stderr)
        return 1

    print(token)
    print(f"# expires_at={expires}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
