package net.luckperms.api.util;

public class Tristate {
    private final boolean value;

    public Tristate(boolean value) {
        this.value = value;
    }

    public boolean asBoolean() {
        return value;
    }
}
