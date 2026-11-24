package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The plugin's first start: the old name's settings are carried over and brought up to date, and the old plugin is complained about. */
class StartupPaperTest {

    /** Stands in for the plugin under its old name. */
    public static class OldNamePlugin extends JavaPlugin {
    }

    private ServerMock server;
    private final List<String> log = new ArrayList<>();
    private Handler handler;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger.getLogger("").addHandler(handler); // the plugin's logger isn't made until it loads
    }

    @AfterEach
    void stop() {
        java.util.logging.Logger.getLogger("").removeHandler(handler);
        MockBukkit.unmock();
    }

    private OldNamePlugin installOld() {
        return MockBukkit.loadWith(OldNamePlugin.class, new PluginDescriptionFile("SmartCopperGolems", "1", OldNamePlugin.class.getName()));
    }

    @Test
    void theOldPluginBeingInstalledToo() {
        installOld();
        CopperGolemClerk plugin = MockBukkit.load(CopperGolemClerk.class);
        assertTrue(plugin.isEnabled(), "it still runs");
        assertTrue(log.contains("SEVERE SmartCopperGolems (this plugin's old name) is also installed. Delete its jar from plugins/"
                + " and restart, or golems are handled twice."), log.toString());
    }

    @Test
    void withoutTheOldPluginNothingIsSaid() {
        CopperGolemClerk plugin = MockBukkit.load(CopperGolemClerk.class);
        assertTrue(plugin.isEnabled());
        assertFalse(log.stream().anyMatch(l -> l.contains("old name")), log.toString());
    }

    @Test
    void theOldNamesSettingsAreCopiedAndThenBroughtUpToDate() throws Exception {
        OldNamePlugin old = installOld();
        File oldFolder = new File(old.getDataFolder().getParentFile(), "SmartCopperGolems"); // MockBukkit numbers its own folders
        assertTrue(oldFolder.mkdirs() || oldFolder.isDirectory());
        String oldConfig = "carry-amount: 7\nmatch-mode: any\n";
        Files.writeString(new File(oldFolder, "config.yml").toPath(), oldConfig);
        CopperGolemClerk plugin = MockBukkit.load(CopperGolemClerk.class);

        assertEquals(7, plugin.carryAmount(), "the old value is used");
        assertFalse(plugin.matchModeAll());
        assertTrue(log.contains("INFO Copied your settings from plugins/SmartCopperGolems/config.yml (the plugin's old name)."
                + " You can delete the plugins/SmartCopperGolems/ folder."), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("INFO Added ") && l.contains(" new settings to config.yml")), log.toString());
        String now = Files.readString(new File(plugin.getDataFolder(), "config.yml").toPath());
        assertTrue(now.contains("carry-amount: 7") && now.contains("match-mode: any"), "values are unchanged");
        assertTrue(now.contains("find-radius: 32"), "new settings are added");
        String version = plugin.getPluginMeta().getVersion();
        assertEquals(oldConfig, Files.readString(new File(plugin.getDataFolder(), "config-before-" + version + ".yml").toPath()));
        assertTrue(new File(oldFolder, "config.yml").isFile(), "the old folder is left for the owner to delete");
    }
}
