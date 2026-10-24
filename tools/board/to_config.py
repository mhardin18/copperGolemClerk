"""Turn the sorting board's saved layout into config.yml groups.

Usage: python3 -I to_config.py <scratch dir> <layout.json> <out groups.yml>

Each category becomes one group. Entries are chosen greedily: vanilla item
tags, name patterns and @properties are used only when every survival item
they match is in that category (so no item lands in two groups by accident);
whatever is left is listed by name. The result is checked to reproduce the
board exactly.
"""
import fnmatch
import json
import os
import re
import sys

S, layout_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
tagdir = os.path.join(S, "tags/data/minecraft/tags/item")
comp = os.path.join(S, "datagen/out/reports/minecraft/components/item")
C = {f[:-5].upper(): json.load(open(os.path.join(comp, f)))["components"] for f in os.listdir(comp)}
ALL = set(C)

raw = json.load(open(layout_path))
board = raw.get("data", raw)
cats = board["categories"]
assign = board["assign"]
survival = set(assign)  # every survival item is assigned

# Group names: short, sign-friendly ([ores], [light source], [Rail] ...).
NAMES = {
    "light": "light-sources", "ingots-gems": "ingots-and-gems", "utility": "utility-blocks",
    "dirt-sand": "dirt-and-sand", "saplings-leaves": "saplings-and-leaves", "other-building": "building",
    "potions-brewing": "brewing", "books-enchanting": "books", "maps-navigation": "maps",
    "ice-snow": "ice-and-snow", "concrete-terracotta": "concrete-and-terracotta",
    "animal-gear": "animal-gear", "rare": "rare-items",
}
def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") or "group"
def group_name(c):
    if c["id"].startswith("c-"):
        return slug(c["name"])
    return NAMES.get(c["id"], c["id"])

# ---- candidate rules
def tag_members(name, seen=None):
    p = os.path.join(tagdir, name + ".json")
    out = set()
    for v in json.load(open(p))["values"]:
        v = v if isinstance(v, str) else v["id"]
        out |= tag_members(v[1:].split(":")[-1]) if v.startswith("#") else {v.split(":")[-1].upper()}
    return out

# Only tags that describe what the items ARE (not mob food, AI or crafting rules),
# so a future game update can't quietly add unrelated items to a group.
DESCRIPTIVE_TAGS = """logs planks wool wool_carpets wool_slabs wool_stairs wooden_slabs wooden_stairs
wooden_fences fence_gates wooden_doors wooden_trapdoors wooden_buttons wooden_pressure_plates saplings
leaves flowers small_flowers mushrooms banners beds signs hanging_signs skulls decorated_pot_sherds rails
buttons stone_buttons candles lanterns copper copper_chests chains moss_blocks sand dirt terracotta
glazed_terracotta stone_bricks stone_crafting_materials slabs stairs walls concrete_slabs concrete_stairs
coal_ores iron_ores gold_ores copper_ores diamond_ores emerald_ores lapis_ores redstone_ores
trim_templates boats chest_boats bundles shulker_boxes harnesses swords axes pickaxes shovels hoes
spears arrows head_armor chest_armor leg_armor foot_armor dyes fishes coals music_discs""".split()
cands = {}  # entry -> set of ALL items it matches
for t in DESCRIPTIVE_TAGS:
    if os.path.exists(os.path.join(tagdir, t + ".json")):
        cands["#minecraft:" + t] = tag_members(t)
cands["@edible"] = {i for i in ALL if "minecraft:food" in C[i]}
cands["@record"] = {i for i in ALL if "minecraft:jukebox_playable" in C[i]}
pats = set()
for i in survival:
    w = i.split("_")
    for k in range(1, min(3, len(w) - 1) + 1):
        pats.add("*_" + "_".join(w[-k:]))
        pats.add("_".join(w[:k]) + "_*")
    for x in w:
        if len(x) >= 4:
            pats.add("*" + x + "*")
for p in pats:
    cands[p] = {i for i in ALL if fnmatch.fnmatchcase(i, p)}

def readable(e):  # tie-break: tags, then patterns, then shorter
    return (0 if e.startswith("#") else 1 if e.startswith("@") else 2, len(e))

groups = {}
for c in cats:
    members = {i for i in survival if assign[i] == c["id"]}
    if not members:
        continue
    ok = [e for e, m in cands.items()
          if (m & survival) and (m & survival) <= members and len(m & survival) >= 3]
    chosen, covered = [], set()
    while True:
        best, gain = None, 0
        for e in ok:
            g = len((cands[e] & members) - covered)
            if g > gain or (g == gain and g and readable(e) < readable(best)):
                best, gain = e, g
        if gain < 3:
            break
        chosen.append(best)
        covered |= cands[best] & members
    # drop entries made redundant by later picks
    for e in list(chosen):
        rest = set().union(*[cands[x] & members for x in chosen if x != e]) if len(chosen) > 1 else set()
        if (cands[e] & members) <= rest:
            chosen.remove(e)
    covered = set().union(*[cands[x] & members for x in chosen]) if chosen else set()
    exact = sorted(members - covered)
    groups[group_name(c)] = (c["name"], sorted(chosen, key=readable), exact, members)

# ---- verify: every survival item in exactly its own group
def entry_items(e):
    return cands[e] if e in cands else {e}
problems = []
for i in survival:
    hit = [g for g, (_, ents, ex, _) in groups.items() if i in ex or any(i in entry_items(e) for e in ents)]
    want = group_name(next(c for c in cats if c["id"] == assign[i]))
    if hit != [want]:
        problems.append((i, want, hit))
if len(set(groups)) != len(groups):
    problems.append(("duplicate group names", None, None))

lines = ["groups:"]
for g, (title, ents, ex, members) in groups.items():
    lines.append(f"  # {title} ({len(members)} items)")
    lines.append(f"  {g}:")
    for e in ents + ex:
        lines.append(f'    - "{e}"' if e[0] in "#@*" or "*" in e else f"    - {e}")
open(out_path, "w").write("\n".join(lines) + "\n")
n_ent = sum(len(v[1]) + len(v[2]) for v in groups.values())
print(f"{len(groups)} groups, {n_ent} entries for {len(survival)} items; problems: {len(problems)}")
for p in problems[:20]:
    print("  ", p)
