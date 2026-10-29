"""labels: status labels over golems (status-labels).

Labels normally appear only near a player holding bamboo; the hidden setting
status-labels-everywhere labels every loaded golem so these scenarios can read the text
(data get entity on the text display above each golem).
"""
from arena import Y, Trigger, _count, scenario
from suite_modes import LUCK, VANILLA
from suite_options import set_options


def golem_at(c, dx, dz, extra=""):
    x, y, z = c.abs(dx, dz)
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{NoAI:1b,active_effects:[{LUCK}]{extra}}}")


def label_text(c, dx, dz, path="text"):
    """The label's text (path "text.extra[1].text" for its second line: the game shortens nested text to "...")."""
    x, y, z = c.abs(dx, dz)
    return c.r.cmd(f"data get entity @e[type=minecraft:text_display,x={x + 0.5},y={y + 1.8},z={z + 0.5},"
                   f"distance=..1.5,limit=1,sort=nearest] {path}")


def labels(c):
    return _count(c.r.cmd(f"execute if entity {c.selector('text_display')}"))


@scenario("labels-say-what-golems-do", "labels", 45, "Status labels: a golem carrying logs says so, an empty one is idle, a vanilla-mode one says vanilla mode, a weathered one says its copper is weathered.", alone=True)
def _(c):
    set_options(c, status_labels_everywhere="true")
    golem_at(c, 4, 10, ',equipment:{mainhand:{id:"minecraft:birch_log",count:5}}')
    golem_at(c, 8, 10)
    golem_at(c, 12, 10, "," + VANILLA)
    golem_at(c, 16, 10, ',weather_state:"weathered"')

    def check():
        a, b, v = (label_text(c, dx, 10) for dx in (4, 8, 12))
        w = label_text(c, 16, 10, "text.extra[1].text")
        return [("carrying 5 birch log" in a, f"carrier: {a[-80:]!r}"), ("idle" in b, f"empty: {b[-60:]!r}"),
                ("vanilla mode" in v, f"vanilla: {v[-60:]!r}"), ("weathered copper" in w, f"weathered: {w[-120:]!r}")]
    return check


@scenario("labels-off", "labels", 40, "status-labels: false removes the labels.", alone=True)
def _(c):
    set_options(c, status_labels_everywhere="true")
    golem_at(c, 4, 10)
    off = Trigger()

    def check():
        off.fire_when(lambda: labels(c) >= 1, lambda: set_options(c, status_labels="false"))
        if off.at is None:
            return [(False, "waiting for a label to appear")]
        return [(off.since() > 3, "waited 3s"), (labels(c) == 0, f"{labels(c)} labels left (want 0)")]
    return check


@scenario("labels-gone-with-golem", "labels", 40, "A labelled golem that's killed takes its label with it.", alone=True)
def _(c):
    set_options(c, status_labels_everywhere="true")
    golem_at(c, 4, 10)
    killed = Trigger()

    def check():
        killed.fire_when(lambda: labels(c) >= 1, lambda: c.r.cmd(f"kill {c.selector('copper_golem')}"))
        if killed.at is None:
            return [(False, "waiting for a label to appear")]
        return [(killed.since() > 2, "waited 2s"), (labels(c) == 0, f"{labels(c)} labels left (want 0)")]
    return check


@scenario("labels-hidden-normally", "labels", 30, "With no player holding bamboo (and the test setting off), no labels exist.")
def _(c):
    golem_at(c, 4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        return [(t.since() > 5, "waited 5s"), (labels(c) == 0, f"{labels(c)} labels (want 0)")]
    return check
