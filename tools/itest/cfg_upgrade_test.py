"""Manual in-game test of the config upgrade at startup (needs server restarts, so it isn't a
run.py scenario). Only for pluginDev: it restarts mcd once per case, about 5 minutes apart
(mcd allows 3 restarts in 10 minutes), and puts config.yml back after each.

    python3 -I tools/itest/cfg_upgrade_test.py

Cases: Windows line endings with settings missing; a YAML error (file left untouched); a
read-only config.yml (warning, defaults apply); an old SmartCopperGolems config carried over
and then brought up to date.
"""
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import arena  # noqa: E402

DIR = os.path.join(arena.SERVER, "plugins", "CopperGolemClerk")
CONFIG = os.path.join(DIR, "config.yml")
OLD_DIR = os.path.join(arena.SERVER, "plugins", "SmartCopperGolems")
LOG = os.path.join(arena.SERVER, "logs", "latest.log")
SPACING = 300


def restart():
    started = time.strftime("%H:%M:%S")
    subprocess.run(["sudo", "-n", "systemctl", "restart", "mcd"], check=True)
    for _ in range(120):
        time.sleep(3)
        try:
            lines = open(LOG).read().splitlines()
        except OSError:
            continue
        done = [l for l in lines if "Done (" in l and l[1:9] >= started]
        if done:
            return [l for l in lines if "CopperGolemClerk" in l]
    raise RuntimeError("pluginDev didn't come back")


def without(text, *keys):
    for key in keys:
        text = re.sub(rf"(?m)(^#.*\n)*^{re.escape(key)}:.*\n", "", text, count=1)
    return text


def backups():
    for f in os.listdir(DIR):
        if f.startswith("config-before-"):
            os.remove(os.path.join(DIR, f))


def case_crlf(original):
    text = without(original, "carry-amount", "tidy-chests").replace("\n", "\r\n")
    with open(CONFIG, "w", newline="") as f:
        f.write(text)
    log = restart()
    after = open(CONFIG, newline="").read()
    added = [l for l in log if "Added 2 new settings" in l]
    return [(bool(added), "log says 2 settings were added"),
            ("\r\n" in after and "\n" not in after.replace("\r\n", ""), "file still uses Windows line endings throughout"),
            ("carry-amount: 16" in after and "tidy-chests: true" in after, "both settings are back"),
            ("debug: true" in after, "debug: true kept")]


def case_yaml_error(original):
    text = original.replace("debug: true", "debug: true\n  this is: [not valid", 1)
    with open(CONFIG, "w") as f:
        f.write(text)
    log = restart()
    after = open(CONFIG).read()
    return [(after == text, "file left exactly as it was"),
            (not any("Added" in l or "Updated the explanation" in l for l in log), "nothing added or updated"),
            (os.listdir(DIR).count("config-before-3.0.0.yml") == 0, "no backup written")]


def case_read_only(original):
    os.remove(CONFIG)
    with open(CONFIG, "w") as f:
        f.write(without(original, "carry-amount"))
    os.chmod(CONFIG, 0o444)
    log = restart()
    after = open(CONFIG).read()
    os.chmod(CONFIG, 0o664)
    warned = [l for l in log if "Couldn't add new settings" in l or "Couldn't" in l]
    return [(bool(warned), f"warned: {warned[0][:160] if warned else 'no warning'}"),
            ("carry-amount" not in after, "file unchanged (no carry-amount added)"),
            (any("Enabling CopperGolemClerk" in l for l in log), "plugin still enabled")]


def case_old_name(original):
    os.remove(CONFIG)
    log = restart()
    after = open(CONFIG).read() if os.path.exists(CONFIG) else ""
    copied = [l for l in log if "Copied your settings from plugins/SmartCopperGolems" in l]
    added = [l for l in log if "new setting" in l and "Added" in l]
    return [(bool(copied), "copied from plugins/SmartCopperGolems"), (bool(added), "then brought up to date (settings added)"),
            ("furnace-feeding:" in after and "overflow-fetch-min:" in after, "3.0.0 settings are in the new file")]


def main():
    original = open(CONFIG).read()
    results = []
    try:
        for i, case in enumerate((case_crlf, case_yaml_error, case_read_only, case_old_name)):
            if i:
                time.sleep(SPACING)
            try:
                checks = case(original)
            except Exception as e:
                checks = [(False, f"{type(e).__name__}: {e}")]
            finally:
                if os.path.exists(CONFIG):
                    os.chmod(CONFIG, 0o664) if os.stat(CONFIG).st_uid == os.getuid() else None
                    os.remove(CONFIG)
                with open(CONFIG, "w") as f:
                    f.write(original)
                backups()
            ok = all(c for c, _ in checks)
            results.append(ok)
            print(f"{'PASS' if ok else 'FAIL'}  {case.__name__}", flush=True)
            for c, text in checks:
                print(f"        {'ok ' if c else 'BAD'} {text}", flush=True)
    finally:
        with open(CONFIG, "w") as f:
            f.write(original)
        backups()
        subprocess.run([sys.executable, "-I", "-c", "import sys; sys.path.insert(0, %r); from rcon import from_properties; "
                        "print(from_properties(%r).cmd('coppergolemclerk reload'))" % (HERE, os.path.join(arena.SERVER, "server.properties"))])
    print(f"{sum(results)}/{len(results)} passed", flush=True)
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
