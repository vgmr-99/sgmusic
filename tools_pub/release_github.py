#!/usr/bin/env python3
"""从 Windows 凭据管理器读取 GitHub 凭据并调用 GitHub API。

令牌只在进程内使用，不会写入磁盘或打印到输出。
"""
import ctypes
import ctypes.wintypes as wintypes
import json
import os
import sys
import urllib.request
import urllib.error

REPO = "vgmr-99/sgmusic"
API = "https://api.github.com"

CREDENTIAL_TYPE_GENERIC = 1


class CredENTIALW(ctypes.Structure):
    _fields_ = [
        ("Flags", wintypes.DWORD),
        ("Type", wintypes.DWORD),
        ("TargetName", wintypes.LPWSTR),
        ("Comment", wintypes.LPWSTR),
        ("LastWritten", wintypes.FILETIME),
        ("CredentialBlobSize", wintypes.DWORD),
        ("CredentialBlob", ctypes.POINTER(ctypes.c_char)),
        ("Persist", wintypes.DWORD),
        ("AttributeCount", wintypes.DWORD),
        ("Attributes", ctypes.c_void_p),
        ("TargetAlias", wintypes.LPWSTR),
        ("UserName", wintypes.LPWSTR),
    ]


def read_credential(target):
    advapi = ctypes.windll.advapi32
    out = ctypes.POINTER(CredENTIALW)()
    if not advapi.CredReadW(target, CREDENTIAL_TYPE_GENERIC, 0, ctypes.byref(out)):
        return None
    try:
        blob = ctypes.string_at(out.contents.CredentialBlob, out.contents.CredentialBlobSize)
        # gh CLI 存的凭据是 ASCII 明文，git 存的则是 UTF-16LE
        if blob.isascii():
            secret = blob.decode("utf-8").rstrip("\x00")
        else:
            secret = blob.decode("utf-16-le").rstrip("\x00")
        return out.contents.UserName, secret
    finally:
        advapi.CredFree(out)


def find_token():
    for target in (
        "GitHub - https://api.github.com/vgmr-99",
        "git:https://github.com",
    ):
        found = read_credential(target)
        if found and found[1]:
            return found
    raise SystemExit("未找到可用的 GitHub 凭据")


def api(path, method="GET", data=None, upload=None, content_type=None):
    username, token = find_token()
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "Timbre-Release-Script",
    }
    body = data
    if upload is not None:
        headers["Content-Type"] = content_type
        body = upload
    req = urllib.request.Request(
        path if path.startswith("https://") else f"{API}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "ignore")[:400]
        raise SystemExit(f"GitHub API {exc.code} {method} {path}: {detail}")
    except urllib.error.URLError as exc:
        raise SystemExit(f"网络错误 {method} {path}: {exc}")


def cmd_status():
    for item in api(f"/repos/{REPO}/releases?per_page=5"):
        assets = [f"{a['name']}({a['size']})" for a in item.get("assets", [])]
        print(
            f"{item['tag_name']:<10} draft={item['draft']} prerelease={item['prerelease']} "
            f"assets={assets}"
        )


def cmd_create(tag, name, body_file, draft="true"):
    body = open(body_file, encoding="utf-8").read() if body_file and os.path.exists(body_file) else ""
    payload = {
        "tag_name": tag,
        "name": name,
        "body": body,
        "draft": draft.lower() == "true",
        "prerelease": False,
    }
    result = api(
        f"/repos/{REPO}/releases",
        method="POST",
        data=json.dumps(payload).encode("utf-8"),
    )
    print("created:", result.get("id"), result.get("html_url"))


def find_release_id(tag):
    """按 tag 找 release id；draft release 只能用列表接口查，tags 接口会 404。"""
    for item in api(f"/repos/{REPO}/releases?per_page=100"):
        if item.get("tag_name") == tag:
            return item["id"]
    raise SystemExit(f"找不到 tag 为 {tag} 的 release")


def cmd_upload(tag, path):
    name = os.path.basename(path)
    with open(path, "rb") as handle:
        payload = handle.read()
    release_id = find_release_id(tag)
    release = api(f"/repos/{REPO}/releases/{release_id}")
    # 上传必须直连 uploads.github.com，走 api.github.com 会被 307 重定向打断
    upload_url = release["upload_url"].split("{")[0]
    result = api(
        f"{upload_url}?name={name}",
        method="POST",
        upload=payload,
        content_type="application/vnd.android.package-archive",
    )
    print("uploaded:", result.get("name"), result.get("size"))


def cmd_delete_assets(tag):
    """删除指定 tag 下所有资产（用于替换已上传的包）。"""
    release_id = find_release_id(tag)
    release = api(f"/repos/{REPO}/releases/{release_id}")
    for asset in release.get("assets", []):
        api(f"/repos/{REPO}/releases/assets/{asset['id']}", method="DELETE")
        print("deleted:", asset.get("name"))


def cmd_publish(tag):
    release_id = find_release_id(tag)
    release = api(f"/repos/{REPO}/releases/{release_id}")
    api(
        f"/repos/{REPO}/releases/{release_id}",
        method="PATCH",
        data=json.dumps({"draft": False, "prerelease": False}).encode("utf-8"),
    )
    print("published:", release.get("html_url"))


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    if action == "status":
        cmd_status()
    elif action == "create":
        cmd_create(*sys.argv[2:])
    elif action == "upload":
        cmd_upload(*sys.argv[2:])
    elif action == "delete-assets":
        cmd_delete_assets(sys.argv[2])
    elif action == "publish":
        cmd_publish(sys.argv[2])
    else:
        raise SystemExit("usage: release_github.py status|create|upload|publish ...")
