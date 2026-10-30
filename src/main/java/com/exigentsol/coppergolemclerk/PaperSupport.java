package com.exigentsol.coppergolemclerk;

import java.util.List;

/** Checks that this Paper build has the golem events the plugin is built on. */
final class PaperSupport {
    /** The oldest Paper 26.3 build that has every class in {@link #REQUIRED}. */
    static final int MIN_BUILD = 190;

    /** ItemTransportingEntitySortEvent was added in Paper 26.3 build 190. */
    static final List<String> REQUIRED = List.of(
            "io.papermc.paper.event.entity.ItemTransportingEntitySortEvent",
            "io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent");

    private PaperSupport() {
    }

    /** The first of {@code classNames} that can't be loaded, or null if all can. */
    static String firstMissing(List<String> classNames, ClassLoader loader) {
        for (String name : classNames) {
            try {
                Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                return name;
            }
        }
        return null;
    }

    /** The message logged when this Paper build is too old. */
    static String tooOldMessage(String missingClass, String serverVersion) {
        String simple = missingClass.substring(missingClass.lastIndexOf('.') + 1);
        return "Copper Golem Clerk needs Paper 26.3 build " + MIN_BUILD + " or newer, and this server is " + serverVersion
                + " (it has no " + simple + "). Update Paper; the plugin is disabled until then.";
    }
}
