"""options: each config switch actually changes what golems do.

Each scenario switches settings in config.yml and reloads; the runner puts the
original config back after every scenario that runs on its own.
"""
import re

from arena import CONFIG, expect, scenario


def set_options(c, **options):
    text = open(CONFIG).read()
    for key, value in options.items():
        key = key.replace("_", "-")
        text, n = re.subn(rf"(?m)^{key}: .*$", f"{key}: {value}", text)
        if n != 1:
            raise RuntimeError(f"config has no single '{key}:' line")
    with open(CONFIG, "w") as f:
        f.write(text)
    reply = re.sub(r"§.", "", c.r.cmd("coppergolemclerk reload"))
    if "reloaded" not in reply:
        raise RuntimeError(f"reload failed: {reply}")


@scenario("option-allow-empty-off", "options", 420, "allow-empty-chests: false: an item with no home never goes into the empty chest; it is returned instead.", alone=True)
def _(c):
    set_options(c, allow_empty_chests="false")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("empty", 8, 10)
    c.golem(4, 10)
    seen = []

    def check():
        if c.holding("sculk_shrieker"):
            seen.append(True)
        return [(bool(seen), "golem picked the item up"), expect(c, "empty", "*", 0),
                expect(c, "copper", "sculk_shrieker", 1), (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]
    return check


@scenario("option-prefer-matching-off", "options", 120, "prefer-matching-chests: false: vanilla-style, the nearer empty chest gets the birch logs.", alone=True)
def _(c):
    set_options(c, prefer_matching_chests="false")
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "birch_log", 16), expect(c, "logs", "birch_log", 0)]


@scenario("option-frames-off", "options", 150, "item-frame-labels: false: a framed chest is an ordinary empty chest; the stone pickaxe goes to the tools chest.", alone=True)
def _(c):
    set_options(c, item_frame_labels="false")
    c.chest("copper", 2, 10, [("stone_pickaxe", 1)], block="waxed_copper_chest")
    c.chest("framed", 6, 10)
    c.frame(6, 10, "stone_pickaxe")
    c.chest("tools", 16, 10, [("wooden_axe", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "tools", "stone_pickaxe", 1), expect(c, "framed", "*", 0)]


@scenario("option-signs-off", "options", 150, "sign-labels: false: [Rail] and [Overflow] signs mean nothing; rails go to the chest that already holds rails.", alone=True)
def _(c):
    set_options(c, sign_labels="false")
    c.chest("copper", 2, 10, [("powered_rail", 8)], block="waxed_copper_chest")
    c.chest("signed", 6, 10, [("dirt", 1)])
    c.sign(6, 10, "[Rail]")
    c.chest("overflow", 9, 10, [("dirt", 1)])
    c.sign(9, 10, "[Overflow]")
    c.chest("rails", 16, 10, [("rail", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "rails", "powered_rail", 8), expect(c, "signed", "powered_rail", 0),
                    expect(c, "overflow", "powered_rail", 0)]


@scenario("option-match-mode-any", "options", 120, "match-mode: any: a log chest with a stray dirt block still counts as a log chest.", alone=True)
def _(c):
    set_options(c, match_mode="any")
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("mixed", 14, 10, [("oak_log", 1), ("dirt", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "mixed", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("option-fetch-off", "options", 45, "fetch-from-overflow: false: an idle golem leaves the overflow chest alone.", alone=True)
def _(c):
    set_options(c, fetch_from_overflow="false")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    return lambda: [(c.elapsed() >= 35, f"watched {c.elapsed():.0f}s of 35s"), expect(c, "overflow", "granite", 16)]


@scenario("option-overflow-after-empty", "options", 120, "overflow-before-empty: false: an item with no home uses the empty chest before [Overflow].", alone=True)
def _(c):
    set_options(c, overflow_before_empty="false")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 6, 10)
    c.sign(6, 10, "[Overflow]")
    c.chest("empty", 14, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "sculk_shrieker", 1), expect(c, "overflow", "*", 0)]


@scenario("option-return-off", "options", 200, "return-unsortable-items: false: an item with no home stays with the golem past the 120s return mark.", alone=True)
def _(c):
    set_options(c, return_unsortable_items="false")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    return lambda: [(c.elapsed() >= 150, f"watched {c.elapsed():.0f}s of 150s"),
                    (c.holding("sculk_shrieker") == 1, f"golem holding {c.holding('sculk_shrieker')} shrieker (want 1)"),
                    expect(c, "copper", "sculk_shrieker", 0)]


@scenario("option-prefer-off-labels-enforced", "options", 150, "prefer-matching-chests: false: a golem reaching a nearer [stone] chest with birch logs is refused there; the logs go to the log chest.", alone=True)
def _(c):
    set_options(c, prefer_matching_chests="false")
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("stone", 6, 10)
    c.sign(6, 10, "[stone]")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)

    def refused():
        return any("labelled for" in l and "BIRCH_LOG" in l and c.in_cell(l) for l in c.log_lines())
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "stone", "birch_log", 0),
                    (refused(), "the [stone] chest refused the logs ('labelled for' in the log)")]


@scenario("option-short-timeout", "options", 120, "matching-chest-timeout-seconds: 5: a no-home item is returned within about 20 active seconds, not 120.", alone=True)
def _(c):
    set_options(c, matching_chest_timeout_seconds="5")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    picked = []

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if in_chest == 0 and holding and not picked:
            picked.append(c.elapsed())
        returned = bool(picked) and in_chest == 1 and holding == 0
        took = c.elapsed() - picked[0] if picked else None
        return [(bool(picked), "golem picked the item up"), (returned, "item returned to the copper chest"),
                (took is not None and took < 45, f"returned {took and round(took)}s after pickup (want under 45)")]
    return check


@scenario("option-one-fetcher", "options", 240, "fetchers-per-overflow-chest: 1: four idle golems, four fetchable items, never more than one fetching at once.", alone=True)
def _(c):
    from suite_edge import fetch_log
    set_options(c, fetchers_per_overflow_chest="1")
    items = [("birch_log", "oak_log", "logs"), ("granite", "andesite", "stone"),
             ("orange_wool", "white_wool", "wool"), ("blue_dye", "red_dye", "dyes")]
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 10, 2, [(fetch, 16) for fetch, _, _ in items])
    c.sign(10, 2, "[Overflow]")
    for n, (fetch, seed, name) in enumerate(items):
        c.chest(name, 6 + 3 * n, 18, [(seed, 1)])
    for n in range(4):
        c.golem(4 + 2 * n, 10)

    def peak():
        active, top = set(), 0
        for _, kind, item, _ in fetch_log(c):
            active.add(item) if kind == "start" else active.discard(item)
            top = max(top, len(active))
        return top

    return lambda: [(peak() <= 1, f"at most {peak()} fetching at once (want 1)")] + \
                   [expect(c, name, fetch, 16) for fetch, _, name in items]


@scenario("option-homeless-retry", "options", 300, "homeless-retry-minutes: 1: a returned no-home item is picked up again about a minute later.", alone=True)
def _(c):
    set_options(c, homeless_retry_minutes="1", matching_chest_timeout_seconds="5")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    state = {"phase": 0, "returned_at": None}

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if state["phase"] == 0 and holding:
            state["phase"] = 1  # first pickup
        elif state["phase"] == 1 and in_chest == 1 and not holding:
            state["phase"], state["returned_at"] = 2, c.elapsed()  # returned
        elif state["phase"] == 2 and holding:
            state["phase"] = 3  # picked up again
            state["again_after"] = c.elapsed() - state["returned_at"]
        again = state.get("again_after")
        return [(state["phase"] >= 2, "item was returned"), (state["phase"] == 3, "item was picked up again"),
                (again is not None and 45 <= again <= 150, f"picked up again {again and round(again)}s after the return (want ~60)")]
    return check


@scenario("long-search-not-reset", "options", 960, "matching-chest-timeout-seconds: 180: a no-home item is returned after 720 active seconds, even though that is past the 10-minute mark where stale searches are cleaned up. ~13 minutes.", alone=True, manual=True)
def _(c):
    set_options(c, matching_chest_timeout_seconds="180")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)  # no home, no empty chest
    c.golem(4, 10)
    picked = []

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if in_chest == 0 and holding and not picked:
            picked.append(c.elapsed())
        returned = bool(picked) and in_chest == 1 and holding == 0
        took = c.elapsed() - picked[0] if picked else 0
        return [(bool(picked), "golem picked the item up"),
                (returned, f"item returned to the copper chest ({took:.0f}s after pickup; it would be ~1300s if the search were reset)"),
                (not returned or took >= 650, f"returned after {took:.0f}s (want about 720, not early)")]
    return check


@scenario("option-avoid-trapdoors-off", "options", 420, "avoid-trapdoors: false: vanilla paths, so the golem either falls through the open trapdoors or gets stuck at them and returns the logs; it never delivers (shows trapdoor-holes tests something).", alone=True)
def _(c):
    from suite_setup import OPEN_TRAPDOOR, fell_in_holes, trapdoor_holes, trapdoor_scene
    set_options(c, avoid_trapdoors="false")
    trapdoor_holes(c, OPEN_TRAPDOOR)
    trapdoor_scene(c)
    def check():
        returned = any("returned BIRCH_LOG" in line for line in c.log_lines())
        return [(fell_in_holes(c) or returned, "the golem fell through a trapdoor, or got stuck and returned the logs"),
                expect(c, "logs", "birch_log", 0)]
    return check
