package com.exigentsol.coppergolemclerk;

import java.util.List;
import java.util.function.Predicate;

/**
 * Reads the optional radius of /cgc find and /cgc golems (and the console's position that goes
 * with them) from the words a player typed. No Bukkit here, so unit tests can run it.
 */
final class RadiusArgs {

    private RadiusArgs() {
    }

    /** What /cgc find was asked: the item words, a position (x, y, z; world may be null) and a radius, each null if not given. */
    record Find(String item, Integer x, Integer y, Integer z, String world, Integer radius) {
        boolean hasPosition() {
            return x != null;
        }
    }

    /** What /cgc golems was asked. */
    record Golems(boolean all, Integer x, Integer y, Integer z, String world, Integer radius) {
        boolean hasPosition() {
            return x != null;
        }
    }

    static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int radius(String s) {
        if (!isInt(s) || Integer.parseInt(s) < 1) {
            throw new IllegalArgumentException("The radius must be a whole number of 1 or more.");
        }
        return Integer.parseInt(s);
    }

    /**
     * The words after "/cgc find". Forms: {@code <item> [radius]} and
     * {@code <item> <x> <y> <z> [world] [radius]}. The item may be several words and may end in a
     * number ("music disc 13"): if the whole text names one exact item, that's the item and there is
     * no radius; otherwise a trailing positive whole number is the radius.
     *
     * @param exactItem true when this text names exactly one item
     */
    static Find parseFind(List<String> words, Predicate<String> exactItem) {
        int n = words.size();
        // <item> <x> <y> <z> <world> <radius>
        if (n >= 6 && ints(words, n - 5, 3) && !isInt(words.get(n - 2))) {
            return at(words, n - 5, words.get(n - 2), radius(words.get(n - 1)));
        }
        // <item> <x> <y> <z> <world>
        if (n >= 5 && ints(words, n - 4, 3) && !isInt(words.get(n - 1))) {
            return at(words, n - 4, words.get(n - 1), null);
        }
        if (n >= 4 && ints(words, n - 3, 3)) {
            // Four numbers in a row: "<item> x y z radius", unless the item itself ends in a number
            // ("music disc 13 x y z").
            if (n >= 5 && isInt(words.get(n - 4)) && !exactItem.test(String.join(" ", words.subList(0, n - 3)))) {
                return at(words, n - 4, null, radius(words.get(n - 1)));
            }
            return at(words, n - 3, null, null);
        }
        String text = String.join(" ", words);
        if (n >= 2 && isInt(words.get(n - 1)) && !exactItem.test(text) && Integer.parseInt(words.get(n - 1)) >= 1) {
            return new Find(String.join(" ", words.subList(0, n - 1)), null, null, null, null,
                    Integer.parseInt(words.get(n - 1)));
        }
        return new Find(text, null, null, null, null, null);
    }

    /** The position starts at words[from] (x y z); the item is everything before it. */
    private static Find at(List<String> words, int from, String world, Integer radius) {
        return new Find(String.join(" ", words.subList(0, from)), Integer.parseInt(words.get(from)),
                Integer.parseInt(words.get(from + 1)), Integer.parseInt(words.get(from + 2)), world, radius);
    }

    private static boolean ints(List<String> words, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (!isInt(words.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** The words after "/cgc golems": nothing, all, {@code <radius>}, or {@code <x> <y> <z> [world] [radius]}. */
    static Golems parseGolems(List<String> rest) {
        int n = rest.size();
        if (n == 0) {
            return new Golems(false, null, null, null, null, null);
        }
        if (n == 1) {
            if (rest.getFirst().equalsIgnoreCase("all")) {
                return new Golems(true, null, null, null, null, null);
            }
            if (isInt(rest.getFirst())) {
                return new Golems(false, null, null, null, null, radius(rest.getFirst()));
            }
        }
        if (n < 3 || n > 5) {
            throw new IllegalArgumentException(
                    "Give a whole position: <x> <y> <z> [world] [radius], a radius, or 'all'.");
        }
        if (!ints(rest, 0, 3)) {
            throw new IllegalArgumentException("Give whole-number coordinates: <x> <y> <z> [world] [radius]");
        }
        String world = null;
        Integer radius = null;
        if (n == 5) {
            world = rest.get(3);
            radius = radius(rest.get(4));
        } else if (n == 4 && isInt(rest.get(3))) {
            radius = radius(rest.get(3));
        } else if (n == 4) {
            world = rest.get(3);
        }
        return new Golems(false, Integer.parseInt(rest.get(0)), Integer.parseInt(rest.get(1)),
                Integer.parseInt(rest.get(2)), world, radius);
    }

    /** The radius to use: what was asked, held to find-max-radius unless the sender is unlimited. Null if none asked. */
    static Integer limit(Integer asked, int max, boolean unlimited) {
        if (asked == null || unlimited) {
            return asked;
        }
        return Math.min(asked, max);
    }
}
