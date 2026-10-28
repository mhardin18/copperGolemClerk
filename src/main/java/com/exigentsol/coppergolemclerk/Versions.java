package com.exigentsol.coppergolemclerk;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Release tags ("v2.3.0-paper26.3") and comparing versions, for the update check. No server needed. */
final class Versions {

    private static final Pattern TAG = Pattern.compile("v?(\\d+(?:\\.\\d+)*)-paper(\\d+(?:\\.\\d+)*)");

    /** A published release: plugin version, the Minecraft (Paper) version it was built for, its page. */
    record Release(String version, String minecraft, String url) {
    }

    /**
     * What the update check found.
     *
     * @param update newer than the installed version and built for this server's Minecraft version
     *               (or an older one); null if none
     * @param needsNewerServer an even newer release that needs a newer Minecraft version; null if none
     */
    record Result(Release update, Release needsNewerServer) {
    }

    private Versions() {
    }

    /** The release a tag names, or null if the tag isn't "v<version>-paper<minecraft>". */
    static Release parse(String tag, String url) {
        Matcher m = TAG.matcher(tag == null ? "" : tag.trim());
        return m.matches() ? new Release(m.group(1), m.group(2), url) : null;
    }

    /** Compares dotted versions number by number: 2.10.0 > 2.9.1, 2.3 == 2.3.0. Text after the numbers is ignored. */
    static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        Matcher m = Pattern.compile("^\\d+(?:\\.\\d+)*").matcher(version == null ? "" : version.trim());
        if (!m.find()) {
            return new int[0];
        }
        String[] parts = m.group().split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = Integer.MAX_VALUE; // absurdly long number: treat as huge
            }
        }
        return out;
    }

    /** The newest release this server can use that is newer than what's installed, and any newer one it can't. */
    static Result choose(List<Release> releases, String installed, String serverMinecraft) {
        Release update = null;
        Release newer = null;
        for (Release r : releases) {
            if (compare(r.version(), installed) <= 0) {
                continue;
            }
            if (compare(r.minecraft(), serverMinecraft) <= 0) {
                if (update == null || compare(r.version(), update.version()) > 0) {
                    update = r;
                }
            } else if (newer == null || compare(r.version(), newer.version()) > 0) {
                newer = r;
            }
        }
        if (newer != null && update != null && compare(newer.version(), update.version()) <= 0) {
            newer = null; // the usable update is at least as new
        }
        return new Result(update, newer);
    }
}
