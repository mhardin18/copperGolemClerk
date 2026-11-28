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
