#!/usr/bin/env python3
"""守护 (watchdog): keep a debloat list disabled on an Android TV between reboots and OTA updates.

Some TV firmware re-enables disabled packages after an OTA upgrade or a factory-style reset of
preloads, so a clean Home and a quiet system creep back toward the stock, ad-filled state. This tool
connects to the TV over the LAN (the same ADB-over-TCP path as tools/tv.py and tools/disable.sh),
loads a debloat file, and re-disables any package from that list that is currently enabled again.

It is *only* a re-applier, not a debloater: it never disables a package that is not already in the
list, and it inherits the same safety guards as tools/disable.sh:

  * CORE_RE / CORE_OK  -- never touch core Android or TV-middleware packages (a boot loop risk).
  * Home guard        -- never leave the TV with no app that can answer the HOME key.

Usage:
    TV_HOST=192.168.1.50 tools/guard.py examples/xiaomi-mi-tv-4a/debloat.txt          # one pass
    TV_HOST=192.168.1.50 tools/guard.py --loop --interval 3600 examples/.../debloat.txt # every hour
    TV_HOST=192.168.1.50 tools/guard.py --dry-run examples/.../debloat.txt             # report only

Requires the tools/venv from the README (python3 -m venv tools/venv && pip install -r tools/requirements.txt);
guard.py reuses that venv's Python and its adb_shell, keys and connection exactly like disable.sh.
"""
import argparse
import os
import re
import subprocess
import sys
import time

# --- locate the venv Python and tv.py the same way disable.sh does -------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VENV_PY = os.path.join(SCRIPT_DIR, "venv", "bin", "python")
if not os.path.exists(VENV_PY):
    VENV_PY = os.path.join(SCRIPT_DIR, "venv", "Scripts", "python.exe")
TV_PY = os.path.join(SCRIPT_DIR, "tv.py")

# --- safety constants, kept identical to tools/disable.sh / tools/recovery.py --------------------
# A list that disables one of these can stop Android from booting. No flag overrides this here.
CORE_RE = re.compile(
    r'^(android|android\.ext\.(services|shared)|com\.android\.sdksandbox|com\.android\.location\.fused|'
    r'com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|'
    r'com\.android\.tv\.settings|com\.(google\.)?android\.(permissioncontroller|packageinstaller|'
    r'ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|'
    r'com\.android\.providers\..*|com\.cltv\.mal|com\.mediatek\.(tis|tv\.service|tv\.agent))$'
)
CORE_OK = re.compile(r'^com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)$')

# Launchers the project treats as a usable Home; the last resort if the TV cannot say who answers HOME.
KNOWN_LAUNCHERS = (
    "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher", "com.mitv.tvhome",
    "com.gathin.tvhome",  # 吾爱电视 launcher (formerly CleanYourTV), the usual replacement Home
)
HOME_QUERY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
CLAIM_QUERY = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
PKG_RE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')

RUNNING = True  # cleared by the SIGINT/SIGTERM handler for a clean shutdown


def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def tv_run(command, timeout=120.0):
    """Run one shell command on the TV through tools/tv.py; return its stdout text or raise."""
    if not os.path.exists(VENV_PY):
        sys.exit(
            "tools/venv not found. Run first:\n"
            "  python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt\n"
            "  (or tools\\venv\\Scripts\\python.exe on Windows)"
        )
    if "TV_HOST" not in os.environ:
        sys.exit("Set TV_HOST to your TV's IP address, e.g.  TV_HOST=192.168.1.50 tools/guard.py ...")
    proc = subprocess.run(
        [VENV_PY, "-I", TV_PY, command],
        capture_output=True, text=True, timeout=timeout,
        env=os.environ,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"tv.py failed (rc={proc.returncode}) for: {command}\n{proc.stderr.strip()}"
        )
    return proc.stdout


def parse_list(path):
    """Return the list of package names from a debloat file (same rules as disable.sh)."""
    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as fh:
            raw = fh.read()
    except OSError as e:
        sys.exit(f"cannot read the list: {path} ({e})")
    pkgs = []
    for line in raw.splitlines():
        line = line.replace("\r", "")
        line = re.sub(r"[ \t]+#.*$", "", line)   # strip trailing comment
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name = line.split()[0]
        if not PKG_RE.match(name):
            sys.exit(f"refusing {path}: not a package name: {name!r}")
        pkgs.append(name)
    if not pkgs:
        sys.exit(f"refusing {path}: no package names in it (every line is a comment or blank).")
    return pkgs


def get_disabled_set():
    out = tv_run("cmd package list packages -d")
    return {ln.split(":", 1)[1] for ln in out.splitlines() if ln.startswith("package:")}


def get_all_packages():
    out = tv_run("cmd package list packages")
    return {ln.split(":", 1)[1] for ln in out.splitlines() if ln.startswith("package:")}


def get_enabled_home_packages():
    """Packages that declare a HOME activity AND are currently enabled (disabled ones cannot answer)."""
    out = tv_run(CLAIM_QUERY)
    home = set()
    for ln in out.splitlines():
        ln = ln.strip()
        if not ln or "/" not in ln:
            continue
        pkg = ln.split("/", 1)[0].strip()
        if PKG_RE.match(pkg):
            home.add(pkg)
    all_pkgs = get_all_packages()
    disabled = get_disabled_set()
    return {p for p in home if p in all_pkgs and p not in disabled}


def guard_once(list_path, dry_run, force_home):
    pkgs = parse_list(list_path)
    disabled = get_disabled_set()
    enabled_home = get_enabled_home_packages()

    already = [p for p in pkgs if p in disabled]
    to_check = [p for p in pkgs if p not in disabled]

    log(f"list has {len(pkgs)} packages; {len(already)} already disabled, "
        f"{len(to_check)} enabled on the TV.")

    acted, skipped = [], []
    for p in to_check:
        # 1) never touch core Android / TV-middleware packages
        if CORE_RE.match(p) and not CORE_OK.match(p):
            log(f"SKIP {p}: core system package (boot-loop risk).")
            skipped.append(p)
            continue
        # 2) Home guard: do not leave the TV with no app that answers HOME
        if p in enabled_home and not force_home:
            if len(enabled_home) <= 1:
                log(f"SKIP {p}: it is the only enabled HOME-capable app; disabling it would leave "
                    f"nothing to start when HOME is pressed. Set FORCE_HOME=1 to override.")
                skipped.append(p)
                continue
        # 3) re-disable
        if dry_run:
            log(f"WOULD DISABLE {p} (dry-run).")
            acted.append(p)
            continue
        try:
            tv_run(f"pm disable-user --user 0 {p}", timeout=60.0)
            log(f"DISABLED {p} (was re-enabled).")
            acted.append(p)
        except RuntimeError as e:
            log(f"FAILED {p}: {e}")
            skipped.append(p)

    if dry_run:
        log(f"dry-run complete: {len(acted)} would be disabled, {len(skipped)} skipped.")
    else:
        log(f"pass complete: {len(acted)} re-disabled, {len(skipped)} skipped.")
    return acted, skipped


def main():
    ap = argparse.ArgumentParser(
        description="守护: re-disable debloat-list packages that firmware re-enabled (OTA/reset).")
    ap.add_argument("list", help="path to a debloat.txt list (same format as disable.sh)")
    ap.add_argument("--loop", action="store_true", help="repeat forever instead of one pass")
    ap.add_argument("--interval", type=int, default=3600,
                    help="seconds between passes in --loop mode (default 3600)")
    ap.add_argument("--dry-run", action="store_true",
                    help="report what would be re-disabled without changing the TV")
    ap.add_argument("--once", action="store_true",
                    help="explicitly run a single pass (default when --loop is not given)")
    args = ap.parse_args()

    force_home = os.environ.get("FORCE_HOME") == "1"

    if args.loop:
        log(f"守护 started for {args.list}; checking every {args.interval}s "
            f"(Ctrl-C to stop). dry_run={args.dry_run}")
        while RUNNING:
            try:
                guard_once(args.list, args.dry_run, force_home)
            except RuntimeError as e:
                log(f"connection/command error, will retry next pass: {e}")
            except subprocess.TimeoutExpired:
                log("tv.py timed out, will retry next pass.")
            if not RUNNING:
                break
            # sleep in small steps so Ctrl-C is responsive
            for _ in range(max(1, args.interval // 5)):
                if not RUNNING:
                    break
                time.sleep(5)
        log("守护 stopped.")
    else:
        try:
            guard_once(args.list, args.dry_run, force_home)
        except RuntimeError as e:
            log(f"aborting: {e}")
            sys.exit(1)


if __name__ == "__main__":
    # clean shutdown on Ctrl-C / termination
    import signal

    def _stop(signum, frame):
        global RUNNING
        RUNNING = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    main()
