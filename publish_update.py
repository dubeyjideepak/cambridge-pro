#!/usr/bin/env python3
"""
CamBridge Pro — One-Click GitHub Release & In-App Auto-Update Publisher
Usage:
  ./publish_update.py [versionName] [changelog]
Example:
  ./publish_update.py 1.0.1 "Added 48kHz audio clock sync & RTMPS support"
"""

import sys
import os
import json
import re
import urllib.parse
import urllib.request
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.join(PROJECT_DIR, "app")
APK_PATH = os.path.join(APP_DIR, "build", "outputs", "apk", "debug", "app-debug.apk")
VERSION_JSON = os.path.join(PROJECT_DIR, "version.json")
BUILD_GRADLE = os.path.join(APP_DIR, "build.gradle.kts")
CREDENTIALS_FILE = os.path.expanduser("~/.git-credentials")

def get_github_token():
    if not os.path.exists(CREDENTIALS_FILE):
        print("Error: ~/.git-credentials not found")
        sys.exit(1)
    with open(CREDENTIALS_FILE) as f:
        for line in f:
            if "github.com" in line:
                u = urllib.parse.urlparse(line.strip())
                return u.password
    print("Error: No GitHub token found in ~/.git-credentials")
    sys.exit(1)

def run(cmd):
    print(f"➜ {cmd}")
    res = subprocess.run(cmd, shell=True, cwd=PROJECT_DIR)
    if res.returncode != 0:
        print(f"Command failed with code {res.returncode}")
        sys.exit(res.returncode)

def main():
    token = get_github_token()

    # Read current version.json
    with open(VERSION_JSON, "r") as f:
        vdata = json.load(f)

    current_code = vdata.get("versionCode", 1)
    current_name = vdata.get("versionName", "1.0.0")

    new_code = current_code + 1
    new_name = sys.argv[1] if len(sys.argv) > 1 else f"1.0.{new_code - 1}"
    changelog = sys.argv[2] if len(sys.argv) > 2 else "Performance improvements and bug fixes"

    print(f"\n📦 Preparing CamBridge Pro Release v{new_name} (code: {new_code})...\n")

    # Update build.gradle.kts
    with open(BUILD_GRADLE, "r") as f:
        content = f.read()
    content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {new_code}', content)
    content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_name}"', content)
    with open(BUILD_GRADLE, "w") as f:
        f.write(content)

    # Build APK
    print("🔨 Compiling APK via Gradle...")
    run("./gradlew assembleDebug")

    if not os.path.exists(APK_PATH):
        print("Error: APK not found after build")
        sys.exit(1)

    import shutil
    local_root_apk = "/Users/deepakdubey/Downloads/tealogy/CamBridgePro.apk"
    shutil.copy2(APK_PATH, local_root_apk)
    print(f"📁 Updated local copy at {local_root_apk}")

    tag = f"v{new_name}"
    apk_url = f"https://github.com/dubeyjideepak/cambridge-pro/releases/download/{tag}/CamBridgePro.apk"

    # Update version.json
    vdata["versionCode"] = new_code
    vdata["versionName"] = new_name
    vdata["apkUrl"] = apk_url
    vdata["changelog"] = changelog
    with open(VERSION_JSON, "w") as f:
        json.dump(vdata, f, indent=2)

    # Commit and push to Git
    print("\n🚀 Pushing code & version.json to GitHub...")
    run("git add .")
    run(f'git commit -m "Release v{new_name}: {changelog}"')
    run("git push origin main")

    # Create GitHub Release via API
    print(f"\n🏷️ Creating GitHub Release {tag}...")
    release_payload = json.dumps({
        "tag_name": tag,
        "name": f"CamBridge Pro v{new_name}",
        "body": changelog,
        "draft": False,
        "prerelease": False
    }).encode("utf-8")

    req = urllib.request.Request(
        "https://api.github.com/repos/dubeyjideepak/cambridge-pro/releases",
        data=release_payload,
        headers={
            "Authorization": f"token {token}",
            "User-Agent": "CamBridge-Publisher",
            "Content-Type": "application/json"
        }
    )

    try:
        with urllib.request.urlopen(req) as resp:
            release_data = json.loads(resp.read().decode())
            upload_url_template = release_data["upload_url"]
            upload_url = upload_url_template.split("{")[0] + "?name=CamBridgePro.apk"
    except urllib.error.HTTPError as e:
        print(f"Release creation failed: {e.code} {e.read().decode()}")
        sys.exit(1)

    # Upload APK asset
    print(f"⬆️ Uploading CamBridgePro.apk ({os.path.getsize(APK_PATH) // (1024*1024)} MB) to GitHub Releases...")
    with open(APK_PATH, "rb") as f:
        apk_bytes = f.read()

    upload_req = urllib.request.Request(
        upload_url,
        data=apk_bytes,
        headers={
            "Authorization": f"token {token}",
            "User-Agent": "CamBridge-Publisher",
            "Content-Type": "application/vnd.android.package-archive"
        }
    )

    try:
        with urllib.request.urlopen(upload_req) as resp:
            print("✅ APK successfully attached to GitHub Release!")
    except urllib.error.HTTPError as e:
        print(f"Asset upload failed: {e.code} {e.read().decode()}")
        sys.exit(1)

    print(f"\n🎉 DONE! Release published at: https://github.com/dubeyjideepak/cambridge-pro/releases/tag/{tag}")
    print("📲 Any phone with CamBridge Pro installed will now automatically detect this update!")

if __name__ == "__main__":
    main()
