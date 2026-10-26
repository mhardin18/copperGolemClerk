"""config: bad config files. The plugin must warn, never crash, and never silently stop sorting.

Each scenario writes a variant of the real config.yml, reloads, checks the reply and
the log, then restores the original and reloads again. (The runner also restores the
original if anything goes wrong.)
"""
import re
import time

from arena import CONFIG, scenario

ORIGINAL = {}


def original():
    if "text" not in ORIGINAL:
        ORIGINAL["text"] = open(CONFIG).read()
    return ORIGINAL["text"]


def restore(c):
    with open(CONFIG, "w") as f:
        f.write(original())
    return c.r.cmd("coppergolemclerk reload")


def try_config(c, text):
    """Write a config, reload, restore. Returns (reply to the bad config, plugin log lines)."""
    original()
    start = c.log.size()
    try:
        with open(CONFIG, "w") as f:
            f.write(text)
        reply = re.sub(r"§.", "", c.r.cmd("coppergolemclerk reload"))
        time.sleep(1.5)  # the server writes its log file asynchronously
        lines = [l for l in c.log.since(start) if "CopperGolemClerk" in l or "Exception" in l]
    finally:
        restored = re.sub(r"§.", "", restore(c))
    return reply, lines, restored


def groups_count(reply):
    m = re.search(r"\((\d+) groups\)", reply)
    return int(m.group(1)) if m else None


def logged(lines, text):
    return any(text in l for l in lines)


@scenario("bad-group-entries", "config", 30, "Unknown tags, items and properties are reported by name; the rest of the config still loads.", alone=True)
def _(c):
    bad = ('  broken:\n    - "#minecraft:not_a_tag"\n    - NOT_AN_ITEM\n    - "@nonsense"\n'
           '  nothing:\n    - "*ZZZ_NO_SUCH*"\n')
    text = original().replace("\ngroups:\n", "\ngroups:\n" + bad, 1)
    reply, lines, restored = try_config(c, text)
    want = groups_count(restored)
    return lambda: [
        (groups_count(reply) == (want or 0) + 2, f"reply: {reply.strip()} (want {(want or 0) + 2} groups)"),
        (logged(lines, "unknown item tag #minecraft:not_a_tag"), "warned about the unknown tag"),
        (logged(lines, "unknown item NOT_AN_ITEM"), "warned about the unknown item"),
        (logged(lines, "unknown property @nonsense"), "warned about the unknown property"),
        (logged(lines, "'nothing' matches no items"), "warned that a group matches nothing"),
        (groups_count(restored) == want, f"restored: {restored.strip()}")]


@scenario("bad-match-mode", "config", 30, "An invalid match-mode is reported and 'all' is used.", alone=True)
def _(c):
    text = re.sub(r"(?m)^match-mode: .*$", "match-mode: sometimes", original())
    reply, lines, restored = try_config(c, text)
    return lambda: [(logged(lines, "match-mode must be 'all' or 'any'"), "warned about match-mode"),
                    (logged(lines, "match-mode: all"), "fell back to 'all'"),
                    ("reloaded" in reply, f"reply: {reply.strip()}")]


@scenario("bad-numbers", "config", 30, "Zero, negative and non-numeric timings load without errors (clamped to safe minimums).", alone=True)
def _(c):
    text = original()
    for key, value in (("matching-chest-timeout-seconds", "-5"), ("fetchers-per-overflow-chest", "0"),
                       ("homeless-retry-minutes", "abc")):
        text = re.sub(rf"(?m)^{key}: .*$", f"{key}: {value}", text)
    reply, lines, restored = try_config(c, text)
    return lambda: [("reloaded" in reply, f"reply: {reply.strip()}"),
                    (not logged(lines, "Exception"), "no exception in the log")]


@scenario("missing-groups", "config", 30, "A config with no groups section is reported, and loads with 0 groups.", alone=True)
def _(c):
    text = original()[:original().index("\ngroups:\n")] + "\n"
    reply, lines, restored = try_config(c, text)
    return lambda: [(logged(lines, "No 'groups' section"), "warned about the missing groups section"),
                    (groups_count(reply) == 0, f"reply: {reply.strip()}")]


@scenario("yaml-syntax-error", "config", 30, "A YAML typo keeps the previous settings and tells you the line, instead of silently loading 0 groups.",
          alone=True, allow_errors=("config.yml could not be read", "Cannot load plugins/CopperGolemClerk/config.yml"))
def _(c):
    # An unclosed flow list is a hard YAML error (an unclosed quote isn't: quoted strings may span lines).
    text = original().rstrip("\n") + "\n  broken: [unclosed\n"
    reply, lines, restored = try_config(c, text)
    return lambda: [("could not be read" in reply, f"reply: {reply.strip()}"),
                    ("line " in reply, "reply names the line with the problem"),
                    (logged(lines, "Keeping the previous settings"), "log says the previous settings were kept"),
                    (groups_count(restored) and groups_count(restored) > 0, f"restored: {restored.strip()}")]


@scenario("minimal-config", "config", 30, "A config with only a groups section (no other settings) loads with every setting at its default.", alone=True)
def _(c):
    text = original()[original().index("\ngroups:\n") + 1:]
    reply, lines, restored = try_config(c, text)
    return lambda: [(groups_count(reply) == groups_count(restored), f"reply: {reply.strip()}"),
                    (not logged(lines, "Exception"), "no exception in the log")]


@scenario("console-commands", "config", 30, "From the console: /cgc check explains it's for players, an unknown subcommand and bare /cgc show the help list.", alone=True)
def _(c):
    check = re.sub(r"§.", "", c.r.cmd("coppergolemclerk check"))
    usage = re.sub(r"§.", "", c.r.cmd("coppergolemclerk frobnicate"))
    alias = re.sub(r"§.", "", c.r.cmd("cgc"))
    return lambda: [("Only players" in check, f"/cgc check -> {check.strip()}"),
                    ("Unknown command 'frobnicate'" in usage and "/cgc reload" in usage, f"/cgc frobnicate -> {usage.strip()}"),
                    ("Copper Golem Clerk commands" in alias, f"/cgc -> {alias.strip()}")]


@scenario("config-name-warnings", "config", 30, "A group named 'overflow' and a misspelled disabled world are warned about.", alone=True)
def _(c):
    text = original().replace("\ngroups:\n", "\ngroups:\n  overflow:\n    - DIRT\n", 1)
    text = re.sub(r"(?m)^disabled-worlds: .*$", "disabled-worlds: [no_such_world_xyz]", text)
    reply, lines, restored = try_config(c, text)
    return lambda: [(logged(lines, "Group 'overflow' can't be used on signs"), "warned about the 'overflow' group"),
                    (logged(lines, "no world named 'no_such_world_xyz'"), "warned about the unknown world"),
                    ("reloaded" in restored, f"restored: {restored.strip()}")]
