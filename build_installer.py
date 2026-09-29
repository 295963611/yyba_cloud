#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YybaCloudServer Windows packaging script.

Usage:
    python build_installer.py                 # exe installer (needs WiX 3.x)
    python build_installer.py --type msi      # msi installer (needs WiX 3.x)
    python build_installer.py --type app-image  # portable folder, no WiX needed
"""

import argparse
import os
import shutil
import subprocess
import sys

# ---------------- project constants ----------------
APP_NAME = "yyba网盘"
ROOT = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(ROOT, "yyba_cloud_server")
LAUNCHER_DIR = os.path.join(SERVER_DIR, "server-launcher")
MVNW = os.path.join(SERVER_DIR, "mvnw.cmd")
ICON = os.path.join(SERVER_DIR, "packaging", "windows", "app.ico")
VERSION = "1.0.0"
JAR_NAME = "server-launcher-1.0.0-SNAPSHOT.jar"
DIST_DIR = os.path.join(SERVER_DIR, "dist")
INPUT_DIR = os.path.join(LAUNCHER_DIR, "target", "jpackage-input")


# ---------------- pretty output ----------------
def info(msg):
    print(f"\033[36m{msg}\033[0m")


def ok(msg):
    print(f"\033[32m{msg}\033[0m")


def fail(msg):
    print(f"\033[31m{msg}\033[0m", file=sys.stderr)


def run(cmd, **kwargs):
    """Run a command, stream output, raise on failure."""
    print("  >", " ".join(str(c) for c in cmd))
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        raise RuntimeError(f"command failed (exit={result.returncode})")
    return result


# ---------------- step 0: kill stale processes ----------------
def kill_stale_processes():
    """Stop any running YybaCloudServer.exe so clean/delete won't fail on locked files."""
    info("=== 0/4 Closing stale processes ===")
    for proc in ("YybaCloudServer.exe", f"{APP_NAME}.exe"):
        try:
            subprocess.run(
                ["taskkill", "/F", "/IM", proc],
                capture_output=True,
            )
        except FileNotFoundError:
            pass
    import time
    time.sleep(1)
    ok("done")


# ---------------- step 1: maven build ----------------
def maven_build(skip_tests):
    info("=== 1/4 Building fat jar ===")
    extra = ["-Dmaven.test.skip=true"] if skip_tests else []
    try:
        run([MVNW, "clean", "package", "-pl", "server-launcher", "-am"] + extra, cwd=SERVER_DIR)
    except RuntimeError:
        # Windows 下 target 目录常被 IDE/终端/杀软占用导致 clean 失败，
        # 退化为不带 clean 的增量打包（产物同样由 shade 插件重新生成）。
        info("clean failed (target locked?), fallback to 'package' without clean")
        run([MVNW, "package", "-pl", "server-launcher", "-am"] + extra, cwd=SERVER_DIR)

    jar_path = os.path.join(LAUNCHER_DIR, "target", JAR_NAME)
    if not os.path.isfile(jar_path):
        raise RuntimeError(f"jar not found: {jar_path}")
    ok(f"fat jar: {jar_path}")

    # prepare jpackage input dir
    if os.path.isdir(INPUT_DIR):
        shutil.rmtree(INPUT_DIR, ignore_errors=True)
    os.makedirs(INPUT_DIR, exist_ok=True)
    shutil.copy2(jar_path, INPUT_DIR)


# ---------------- step 2: locate jpackage ----------------
def find_jpackage():
    info("=== 2/4 Locating jpackage ===")
    found = shutil.which("jpackage")
    if not found:
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidate = os.path.join(java_home, "bin", "jpackage.exe")
            if os.path.isfile(candidate):
                found = candidate
    if not found:
        raise RuntimeError("jpackage not found; use JDK 17+ and set JAVA_HOME")
    ok(f"jpackage: {found}")
    return found


# ---------------- step 3: locate WiX ----------------
def find_wix(pkg_type):
    info("=== 3/4 Checking WiX toolchain ===")
    if pkg_type == "app-image":
        ok("app-image needs no WiX, skipped")
        return None

    candidates = [
        os.path.join(os.environ.get("ProgramFiles(x86)", ""), "WiX Toolset v3.11", "bin"),
        os.path.join(os.environ.get("ProgramFiles", ""), "WiX Toolset v3.11", "bin"),
        os.path.join(os.environ.get("ProgramFiles(x86)", ""), "WiX Toolset v3.10", "bin"),
        os.path.join(os.environ.get("ProgramFiles", ""), "WiX Toolset v3.10", "bin"),
    ]
    wix_dir = None
    for cand in candidates:
        if os.path.isfile(os.path.join(cand, "candle.exe")) and \
           os.path.isfile(os.path.join(cand, "light.exe")):
            wix_dir = cand
            break
    if not wix_dir:
        candle = shutil.which("candle.exe")
        if candle:
            wix_dir = os.path.dirname(candle)

    if not wix_dir:
        fail("ERROR: WiX Toolset not found.")
        print("  jpackage needs WiX 3.x to build exe/msi installers.")
        print("  Download: https://github.com/wixtoolset/wix3/releases (use wix311.exe)")
        print("  Or run: python build_installer.py --type app-image")
        sys.exit(1)

    ok(f"WiX: {wix_dir}")
    return wix_dir


# ---------------- step 4: jpackage ----------------
def run_jpackage(jpackage, pkg_type, wix_dir):
    info(f"=== 4/4 jpackage -> {pkg_type} ===")
    os.makedirs(DIST_DIR, exist_ok=True)

    args = [
        jpackage,
        "--type", pkg_type,
        "--name", APP_NAME,
        "--app-version", VERSION,
        "--vendor", "yyba",
        "--description", "Yyba Cloud Server",
        "--input", INPUT_DIR,
        "--main-jar", JAR_NAME,
        "--dest", DIST_DIR,
        "--icon", ICON,
        "--java-options", "-Djava.awt.headless=false",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dsun.jnu.encoding=UTF-8",
    ]

    if pkg_type != "app-image":
        args += [
            "--win-menu",
            "--win-menu-group", "YybaCloud",
            "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-per-user-install",
        "--win-dir-chooser",
        ]

    env = os.environ.copy()
    if wix_dir:
        env["PATH"] = wix_dir + os.pathsep + env.get("PATH", "")

    run(args, env=env)


def report(pkg_type):
    ext = "exe" if pkg_type == "app-image" else pkg_type
    products = []
    for name in os.listdir(DIST_DIR):
        if pkg_type == "app-image":
            full = os.path.join(DIST_DIR, name)
            if os.path.isdir(full) and (name.startswith("YybaCloudServer") or name.startswith(APP_NAME)):
                products.append(full)
        elif name.lower().endswith(f".{ext}"):
            products.append(os.path.join(DIST_DIR, name))

    ok("\n========================================")
    ok("Build completed")
    for p in products:
        if os.path.isdir(p):
            exe = os.path.join(p, f"{APP_NAME}.exe")
            print(f"  Portable dir : {p}")
            print(f"  Launch exe   : {exe}")
        else:
            size_mb = os.path.getsize(p) / (1024 * 1024)
            print(f"  Installer    : {p}")
            print(f"  Size         : {size_mb:.2f} MB")
    ok("========================================\n")


def main():
    parser = argparse.ArgumentParser(description="Package YybaCloudServer on Windows")
    parser.add_argument("--type", choices=["exe", "msi", "app-image"], default="exe",
                        help="package type (default: exe)")
    parser.add_argument("--skip-tests", action="store_true", default=True,
                        help="skip test compilation (default: true)")
    parser.add_argument("--with-tests", dest="skip_tests", action="store_false",
                        help="run tests during build")
    args = parser.parse_args()

    try:
        kill_stale_processes()
        maven_build(args.skip_tests)
        jpackage = find_jpackage()
        wix_dir = find_wix(args.type)
        run_jpackage(jpackage, args.type, wix_dir)
        report(args.type)
    except RuntimeError as e:
        fail(f"\nBUILD FAILED: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
