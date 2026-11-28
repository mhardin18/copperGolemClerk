"""books: sign labels that name an enchantment ([Mending], [Efficiency bks]) send enchanted books to their own chests.

Layout: copper chest at (2,10), golem at (4,10), the labelled chests further along +x.
"""
from arena import scenario
from suite_tidy import book_slots, put_book


@scenario("book-labels", "books", 150, "Books in a copper chest go by enchantment: Mending (and a Protection + Mending book) to [Mending], Efficiency to [Efficiency bks], Protection to [books].")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    for slot, book in enumerate([dict(protection=4), dict(mending=1), dict(efficiency=5), dict(protection=1, mending=1)]):
        put_book(c, 2, 10, slot, **book)
    c.chest("mending", 10, 8)
    c.sign(10, 8, "[Mending]")
    c.chest("efficiency", 10, 12)
    c.sign(10, 12, "[Efficiency bks]")
    c.chest("books", 14, 10)
    c.sign(14, 10, "[books]")
    c.golem(4, 10)

    def check():
        mending, eff, books = book_slots(c, "mending"), book_slots(c, "efficiency"), book_slots(c, "books")
        return [(sorted(mending) == ["mending 1", "mending 1 + protection 1"], f"[Mending]: {mending}"),
                (eff == ["efficiency 5"], f"[Efficiency bks]: {eff}"),
                (books == ["protection 4"], f"[books]: {books}"),
                (book_slots(c, "copper") == [], "copper chest emptied")]
    return check


@scenario("book-homeless-doesnt-block", "books", 200, "A Smite book with no chest is returned to the copper chest, and the Mending book behind it still goes to [Mending].")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    put_book(c, 2, 10, 0, smite=5)
    put_book(c, 2, 10, 1, mending=1)
    c.chest("mending", 12, 10)
    c.sign(12, 10, "[Mending]")
    c.golem(4, 10)

    def check():
        return [(book_slots(c, "mending") == ["mending 1"], f"[Mending]: {book_slots(c, 'mending')}"),
                (book_slots(c, "copper") == ["smite 5"], f"copper chest: {book_slots(c, 'copper')}")]
    return check


@scenario("book-list", "books", 150, "A [Books: Silk, / Aqua, Fire] list over two sign lines takes Silk Touch, Aqua Affinity and Fire Protection books; a Power book goes to [books].")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    for slot, book in enumerate([dict(silk_touch=1), dict(power=5), dict(aqua_affinity=1), dict(fire_protection=4)]):
        put_book(c, 2, 10, slot, **book)
    c.chest("list", 10, 10)
    c.sign(10, 10, ["[Books: Silk,", "Aqua, Fire]"])
    c.chest("books", 14, 10)
    c.sign(14, 10, "[books]")
    c.golem(4, 10)

    def check():
        listed, books = book_slots(c, "list"), book_slots(c, "books")
        return [(sorted(listed) == ["aqua_affinity 1", "fire_protection 4", "silk_touch 1"], f"[Books: ...]: {listed}"),
                (books == ["power 5"], f"[books]: {books}")]
    return check


@scenario("book-ranges", "books", 150, "[Bk: A-E] takes Aqua Affinity and Efficiency, [Bk: F*, So*-U] takes Fortune and Thorns; Mending goes to [books].")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    for slot, book in enumerate([dict(fortune=3), dict(aqua_affinity=1), dict(mending=1), dict(thorns=3), dict(efficiency=5)]):
        put_book(c, 2, 10, slot, **book)
    c.chest("a-e", 10, 8)
    c.sign(10, 8, "[Bk: A-E]")
    c.chest("f-u", 10, 12)
    c.sign(10, 12, "[Bk: F*, So*-U]")
    c.chest("books", 14, 10)
    c.sign(14, 10, "[books]")
    c.golem(4, 10)

    def check():
        ae, fu, books = book_slots(c, "a-e"), book_slots(c, "f-u"), book_slots(c, "books")
        return [(sorted(ae) == ["aqua_affinity 1", "efficiency 5"], f"[Bk: A-E]: {ae}"),
                (sorted(fu) == ["fortune 3", "thorns 3"], f"[Bk: F*, So*-U]: {fu}"),
                (books == ["mending 1"], f"[books]: {books}")]
    return check
