"""commands: /cgc check on a chest, /cgc overflow, and disabled-worlds.

The commands take coordinates from the console (/cgc check <x> <y> <z>), which is
how these scenarios read their output over RCON.
"""
import re

from arena import SERVER, expect, scenario
from suite_options import set_options


def say(c, command):
    return re.sub(r"§.", "", c.r.cmd(command))


def check_at(c, dx, dz):
    x, y, z = c.abs(dx, dz)
    return say(c, f"cgc check {x} {y} {z}")


def has(reply, text):
    return text in reply, f"says '{text}'" + ("" if text in reply else f"; got: {reply.strip()}")


@scenario("check-labels", "commands", 30, "/cgc check on a chest explains each sign line: a group, one item, and a line that matches nothing.")
def _(c):
    c.chest("wool", 10, 10)
    c.sign(10, 10, ["[Wool]", "[String]", "[frobnitz]"])
    c.chest("horse", 14, 10)
    c.sign(14, 10, "[Horse]")
    return lambda: [has(check_at(c, 10, 10), "[Wool] = group wool (64 items)"),
                    has(check_at(c, 10, 10), "[String] = the item string"),
                    has(check_at(c, 10, 10), "[frobnitz] matches no group or item"),
                    has(check_at(c, 14, 10), 'items with "horse" in their name')]


@scenario("check-contents", "commands", 30, "/cgc check on unlabelled chests: shared group, a stray item, empty, blocked, and a copper chest.")
def _(c):
    c.chest("logs", 6, 10, [("oak_log", 4), ("birch_log", 2)])
    c.chest("mixed", 9, 10, [("oak_log", 4), ("dirt", 1)])
    c.chest("empty", 12, 10)
    c.chest("blocked", 15, 10, [("oak_log", 1)])
    c.block(15, 10, "stone", dy=1)
    c.chest("copper", 18, 10, block="waxed_copper_chest")
    return lambda: [has(check_at(c, 6, 10), "plus anything in group logs"),
                    has(check_at(c, 9, 10), "share no group"),
                    has(check_at(c, 12, 10), "No labels and empty"),
                    has(check_at(c, 15, 10), "can't open it"),
                    has(check_at(c, 18, 10), "Copper chest"),
                    has(check_at(c, 3, 3), "No container")]


@scenario("overflow-report", "commands", 30, "/cgc overflow sorts overflow contents into: no chest (with a sign label), chest full, and golems will move it.")
def _(c):
    c.chest("overflow", 10, 10, [("kelp", 10), ("oak_log", 5), ("granite", 3)])
    c.sign(10, 10, "[Overflow]")
    c.chest("stone", 14, 10)
    c.sign(14, 10, "[stone]")
    c.chest("logs", 6, 10, [("oak_log", 64)] * 27)
    c.sign(6, 10, "[logs]")
    x, y, z = c.abs(10, 10)

    def check():
        reply = say(c, f"cgc overflow {x} {y} {z}")
        no_home = reply.split("No chest for these")[-1].split("Their chest is full")[0]
        full = reply.split("Their chest is full")[-1].split("Have a chest with room")[0]
        moving = reply.split("Have a chest with room")[-1]
        return [has(reply, "hold 18 items (3 kinds)"),
                ("[ocean]" in no_home and "kelp x10" in no_home, f"kelp listed under [ocean] with no chest: {no_home.strip()}"),
                ("[logs]" in full and "oak log x5" in full, f"oak logs listed as chest full: {full.strip()}"),
                ("granite x3" in moving, f"granite listed as golems will move it: {moving.strip()}")]
    return check


@scenario("disabled-world", "commands", 150, "disabled-worlds lists this world: golems act as in vanilla (nearer empty chest gets the birch logs) and /cgc check says it's off.", alone=True)
def _(c):
    world = next(l.split("=", 1)[1].strip() for l in open(SERVER + "/server.properties") if l.startswith("level-name="))
    set_options(c, disabled_worlds=f"[{world}]")
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "birch_log", 16), expect(c, "logs", "birch_log", 0),
                    has(check_at(c, 16, 10), "off in this world")]


@scenario("check-odd-targets", "commands", 30, "/cgc check: a barrel is explained as ignored, a trapped chest's label works, bad coordinates and world names get a clear error.")
def _(c):
    c.chest("barrel", 6, 10, block="barrel")
    c.chest("trapped", 10, 10, block="trapped_chest")
    c.sign(10, 10, "[logs]")
    return lambda: [has(check_at(c, 6, 10), "they ignore this"),
                    has(check_at(c, 10, 10), "[logs] = group logs"),
                    has(say(c, "cgc check 1 2 nowhere"), "whole-number"),
                    has(say(c, "cgc check 0 100 0 no_such_world"), "No world named 'no_such_world'"),
                    has(say(c, "cgc check 2000000 100 2000000"), "isn't loaded")]


@scenario("overflow-report-unusable", "commands", 30, "/cgc overflow: an item whose only chest has a block on top is listed as 'can't be used', not as needing a chest.")
def _(c):
    c.chest("overflow", 10, 10, [("birch_log", 7)])
    c.sign(10, 10, "[Overflow]")
    c.chest("logs", 14, 10)
    c.sign(14, 10, "[logs]")
    c.block(14, 10, "stone", dy=1)
    x, y, z = c.abs(10, 10)

    def check():
        reply = say(c, f"cgc overflow {x} {y} {z}")
        part = reply.split("can't be used by golems")[-1]
        return [has(reply, "can't be used by golems"),
                ("a block on top" in part and "birch log x7" in part, f"birch logs listed under the blocked [logs] chest: {part.strip()}"),
                ("No chest for these" not in reply, "not listed as needing a chest")]
    return check


@scenario("help", "commands", 30, "/cgc help lists every command (console forms with coordinates); /cgc help <command> explains one; an unknown topic says so.")
def _(c):
    listing = say(c, "cgc help")
    one = say(c, "cgc help overflow")
    bad = say(c, "cgc help frob")
    return lambda: [has(listing, "/cgc check <x> <y> <z> [world]"),
                    has(listing, "/cgc overflow <x> <y> <z> [world]"),
                    has(listing, "/cgc reload"),
                    has(listing, "/cgc golems [<x> <y> <z> [world]] - Where the copper golems are"),
                    has(listing, "/cgc stats - How much golems have sorted"),
                    has(listing, "/cgc help"),
                    has(one, "idle golems will move it"),
                    has(one, "From the console, give the position"),
                    has(bad, "No command named 'frob'")]


@scenario("check-odd-chests", "commands", 30, "/cgc check flags a waterlogged chest and a locked one; a check far above the world says there's no container; [#] on a sign is listed as matching nothing.")
def _(c):
    x, y, z = c.abs(6, 10)
    c.run(f"setblock {x} {y} {z} minecraft:chest[facing=south,waterlogged=true]")
    c.chests["wet"] = [(x, y, z)]
    lx, ly, lz = c.abs(9, 10)
    c.run(f'setblock {lx} {ly} {lz} minecraft:chest[facing=south]{{lock:{{items:"minecraft:diamond"}}}}')
    c.chests["locked"] = [(lx, ly, lz)]
    c.chest("junk", 12, 10)
    c.sign(12, 10, "[#]")
    return lambda: [has(check_at(c, 6, 10), "waterlogged"),
                    has(check_at(c, 9, 10), "locked"),
                    has(check_at(c, 12, 10), "[#] matches no group or item"),
                    has(say(c, f"cgc check {x} 9999 {z}"), "No container")]


# ---- update check: a stand-in for GitHub's release list, served from this machine

def fake_releases(releases):
    """Serve a GitHub-style release list on 127.0.0.1; returns its URL. releases: (tag, draft, prerelease)."""
    import json
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    body = json.dumps([{"tag_name": tag, "draft": draft, "prerelease": pre,
                        "html_url": f"https://example.invalid/releases/{tag}"}
                       for tag, draft, pre in releases]).encode()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_address[1]}/releases"


def version_check(c, url):
    """Point the update check at url, ask for /cgc version (starts a check), and return a check
    function that asks again once the result has had time to arrive."""
    set_options(c, update_check="true", update_check_url=f'"{url}"')
    first = say(c, "cgc version")
    if "Checking GitHub" not in first:
        raise RuntimeError(f"/cgc version didn't start a check: {first}")
    return lambda: say(c, "cgc version") if c.elapsed() > 3 else ""


@scenario("update-available", "commands", 60, "/cgc version finds a newer release (skipping drafts and pre-releases), logs it once, and notes one that needs a newer Minecraft.", alone=True)
def _(c):
    url = fake_releases([("v1.0.0-paper26.3", False, False), ("v9.9.0-paper26.3", False, False),
                         ("v50.0.0-paper26.3", True, False), ("v60.0.0-paper26.3", False, True),
                         ("v99.0.0-paper99.0", False, False), ("nightly", False, False)])
    again = version_check(c, url)
    def check():
        reply = again()
        log = "\n".join(c.log_lines())
        return [has(reply, "Version 9.9.0 is available"),
                has(reply, "Version 99.0.0 is out for Minecraft 99.0"),
                (log.count("Copper Golem Clerk 9.9.0 is available") == 1, "logged 9.9.0 once"),
                ("50.0.0" not in log and "60.0.0" not in log, "draft and pre-release ignored")]
    return check


@scenario("update-none", "commands", 60, "/cgc version with only older releases out: no update is offered or logged.", alone=True)
def _(c):
    url = fake_releases([("v1.0.0-paper26.3", False, False), ("v2.0.0-paper26.3", False, False)])
    again = version_check(c, url)
    def check():
        reply = again()
        log = "\n".join(c.log_lines())
        return [(c.elapsed() > 5 and "is available" not in reply and "Not checked yet" not in reply
                 and "failed" not in reply, f"no update offered; got: {reply.strip()}"),
                ("is available" not in log, "nothing logged")]
    return check


@scenario("update-unreachable", "commands", 60, "/cgc version when the release list can't be reached: says the check failed, nothing else breaks.", alone=True,
          allow_errors=("Update check failed",))
def _(c):
    again = version_check(c, "http://127.0.0.1:9/releases")  # port 9: nothing listens
    return lambda: [has(again(), "The last check failed")]
