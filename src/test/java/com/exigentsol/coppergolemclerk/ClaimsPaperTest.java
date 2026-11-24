package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Claims: GriefPrevention (a fake, read by reflection like the real one) and LuckPerms
 * (a fake API under net.luckperms.api), including every way their API can be missing or fail.
 */
class ClaimsPaperTest extends PaperTest {

    /** ClaimPermission, as GriefPrevention names it. */
    public enum Perm { Access, Container, Build }

    /** A claim with the whole API Claims uses. */
    public static class RichClaim extends Claim {
        public RichClaim parent;
        public Long id = 1L;
        public int area = 100;
        /** What allowContainers answers: null = allowed. */
        public String refusal;
        public final Set<UUID> containerTrust = new HashSet<>();
        public boolean failing;

        public RichClaim(UUID owner) {
            super(owner);
        }

        @Override
        public UUID getOwnerID() {
            if (failing) {
                throw new IllegalStateException("owner");
            }
            return super.getOwnerID();
        }

        public Long getID() {
            if (failing) {
                throw new IllegalStateException("id");
            }
            return id;
        }

        public int getArea() {
            if (failing) {
                throw new IllegalStateException("area");
            }
            return area;
        }

        public String allowContainers(Player player) {
            if (failing) {
                throw new IllegalStateException("containers");
            }
            return refusal;
        }

        public Object checkPermission(UUID player, Perm permission, Event event) {
            if (failing) {
                throw new IllegalStateException("permission");
            }
            assertEquals(Perm.Container, permission);
            return containerTrust.contains(player) ? null : "no container trust";
        }
    }

    /** ClaimPermission without a Container entry. */
    public enum OddPerm { Access, Build }

    public static class OddClaim extends Claim {
        public OddClaim(UUID owner) {
            super(owner);
        }

        public Object checkPermission(UUID player, OddPerm permission, Event event) {
            return null;
        }
    }

    /** A GriefPrevention that has no static instance field. */
    public static class NoInstanceGP extends JavaPlugin {
    }

    /** A GriefPrevention whose instance is never set. */
    public static class NullInstanceGP extends JavaPlugin {
        public static NullInstanceGP instance;
        public Object dataStore;
    }

    /** A GriefPrevention whose data store can't look up claims. */
    public static class NoLookupGP extends JavaPlugin {
        public static NoLookupGP instance;
        public Object dataStore = new Object();

        @Override
        public void onEnable() {
            instance = this;
        }
    }

    public static class FakeLuckPerms extends JavaPlugin {
    }

    private final List<String> warnings = new ArrayList<>();
    private Claims claims;
    private Location inside;
    private Location outside;

    @BeforeEach
    void setUp() {
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        claims = new Claims(plugin);
        inside = new Location(world, 5, FLOOR, 5);
        outside = new Location(world, 50, FLOOR, 50);
    }

    @AfterEach
    void resetLuckPerms() {
        LuckPermsProvider.users = id -> CompletableFuture.completedFuture(null);
    }

    private GriefPrevention gp() {
        return MockBukkit.loadWith(GriefPrevention.class,
                new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
    }

    /** A claim over x < 20; elsewhere is unclaimed. */
    private void claim(GriefPrevention gp, Claim claim) {
        gp.dataStore.claims = l -> l.getBlockX() < 20 ? claim : null;
    }

    // ---- without GriefPrevention

    @Test
    void withoutGriefPreventionNothingIsClaimed() {
        assertFalse(claims.present());
        assertFalse(claims.claimed(inside));
        assertNull(claims.claimKeyAt(inside));
        assertNull(claims.claimInfoAt(inside));
        assertNull(claims.ownerAt(inside));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), inside));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aDisabledGriefPreventionCountsAsAbsent() {
        gp();
        server.getPluginManager().disablePlugin(server.getPluginManager().getPlugin("GriefPrevention"));
        assertFalse(claims.present());
    }

    @Test
    void aNullLocationHasNoClaim() {
        claim(gp(), new Claim(UUID.randomUUID()));
        assertNull(claims.claimInfoAt(null));
        assertNull(claims.ownerAt(null));
        assertFalse(claims.claimed(null));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), null));
    }

    // ---- owners

    @Test
    void ownerAndOwnershipFollowTheClaim() {
        UUID owner = UUID.randomUUID();
        claim(gp(), new Claim(owner));
        assertTrue(claims.present());
        assertTrue(claims.claimed(inside));
        assertFalse(claims.claimed(outside));
        assertEquals(owner, claims.ownerAt(inside));
        assertNull(claims.ownerAt(outside));
        PlayerMock mine = server.addPlayer();
        PlayerMock other = server.addPlayer();
        claim(GriefPrevention.instance, new Claim(mine.getUniqueId()));
        assertTrue(claims.ownsClaimAt(mine, inside));
        assertFalse(claims.ownsClaimAt(other, inside));
        assertFalse(claims.ownsClaimAt(mine, outside), "no claim, no owner");
    }

    @Test
    void anAdminClaimHasNoOwner() {
        claim(gp(), new Claim(null));
        assertTrue(claims.claimed(inside));
        assertNull(claims.ownerAt(inside));
        PlayerMock player = server.addPlayer();
        assertFalse(claims.ownsClaimAt(player, inside));
        assertNull(claims.claimInfoAt(inside).owner());
    }

    @Test
    void anOwnerThatCantBeReadReadsAsNobody() {
        RichClaim claim = new RichClaim(UUID.randomUUID());
        claim.failing = true;
        claim(gp(), claim);
        assertNull(claims.ownerAt(inside));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), inside));
    }

    @Test
    void aFailingClaimLookupMeansNoClaim() {
        GriefPrevention gp = gp();
        gp.dataStore.claims = l -> {
            throw new IllegalStateException("store");
        };
        assertTrue(claims.present());
        assertFalse(claims.claimed(inside));
        assertNull(claims.claimInfoAt(inside));
    }

    // ---- claim keys and sizes

    @Test
    void aClaimWithoutIdOrAreaStillGetsAKeyAndTheOwner() {
        UUID owner = UUID.randomUUID();
        claim(gp(), new Claim(owner));
        Claims.ClaimInfo info = claims.claimInfoAt(inside);
        assertTrue(info.key().startsWith("claim:@"), info.key());
        assertEquals(owner, info.owner());
        assertEquals(0, info.area(), "unknown area reads as 0");
        assertEquals(info.key(), claims.claimKeyAt(inside), "the same claim has the same key");
        assertNull(claims.claimKeyAt(outside));
    }

    @Test
    void aClaimKeyIsItsIdAndAreaIsRead() {
        UUID owner = UUID.randomUUID();
        RichClaim claim = new RichClaim(owner);
        claim.id = 42L;
        claim.area = 2500;
        claim(gp(), claim);
        Claims.ClaimInfo info = claims.claimInfoAt(inside);
        assertEquals(new Claims.ClaimInfo("claim:42", owner, 2500), info);
    }

    @Test
    void aSubdivisionCountsAsItsTopLevelClaim() {
        UUID owner = UUID.randomUUID();
        RichClaim top = new RichClaim(owner);
        top.id = 7L;
        top.area = 900;
        RichClaim middle = new RichClaim(owner);
        middle.id = 8L;
        middle.parent = top;
        RichClaim sub = new RichClaim(owner);
        sub.id = 9L;
        sub.area = 10;
        sub.parent = middle;
        claim(gp(), sub);
        assertEquals(new Claims.ClaimInfo("claim:7", owner, 900), claims.claimInfoAt(inside));
        assertEquals("claim:7", claims.claimKeyAt(inside));
    }

    @Test
    void aClaimThatIsItsOwnParentStopsAtTheDepthLimit() {
        RichClaim loop = new RichClaim(UUID.randomUUID());
        loop.id = 3L;
        loop.parent = loop;
        claim(gp(), loop);
        assertEquals("claim:3", claims.claimKeyAt(inside));
    }

    @Test
    void aClaimWithNullIdGetsAnIdentityKey() {
        RichClaim claim = new RichClaim(UUID.randomUUID());
        claim.id = null;
        claim(gp(), claim);
        assertTrue(claims.claimKeyAt(inside).startsWith("claim:@"), claims.claimKeyAt(inside));
    }

    @Test
    void aClaimWhoseDetailsFailStillGivesAKey() {
        RichClaim claim = new RichClaim(UUID.randomUUID());
        claim.failing = true;
        claim(gp(), claim);
        Claims.ClaimInfo info = claims.claimInfoAt(inside);
        assertTrue(info.key().startsWith("claim:@"), info.key());
        assertNull(info.owner());
        assertEquals(0, info.area());
    }

    // ---- may a player open a container

    @Test
    void unclaimedContainersCanBeOpenedAndLockedOnesCannot() {
        claim(gp(), new Claim(UUID.randomUUID()));
        PlayerMock player = server.addPlayer();
        Block free = chest(50, 50);
        assertTrue(claims.mayOpen(player, free));
        assertTrue(claims.mayOpen(player, free.getState(false)));
        Chest state = (Chest) free.getState();
        state.setLock("secret");
        state.update();
        assertTrue(state.isLocked(), "the test server keeps the lock");
        assertFalse(claims.mayOpen(player, state), "locked containers are never theirs to open");
    }

    @Test
    void aClaimedContainerFollowsAllowContainers() {
        RichClaim claim = new RichClaim(UUID.randomUUID());
        claim(gp(), claim);
        PlayerMock player = server.addPlayer();
        Block inClaim = chest(5, 5);
        assertTrue(claims.mayOpen(player, inClaim), "null = allowed");
        claim.refusal = "no container trust";
        assertFalse(claims.mayOpen(player, inClaim));
        assertTrue(claims.mayOpen(player, chest(50, 50)), "outside the claim");
    }

    @Test
    void aClaimThatCantSayIsNotOpenable() {
        claim(gp(), new Claim(UUID.randomUUID())); // no allowContainers method
        PlayerMock player = server.addPlayer();
        assertFalse(claims.mayOpen(player, chest(5, 5)));
        RichClaim failing = new RichClaim(UUID.randomUUID());
        failing.failing = true;
        claim(GriefPrevention.instance, failing);
        assertFalse(claims.mayOpen(player, chest(6, 6)));
    }

    // ---- container trust of players who may be offline

    @Test
    void ownersAndTrustedPlayersHaveContainerTrust() {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        RichClaim claim = new RichClaim(owner);
        claim.containerTrust.add(friend);
        claim(gp(), claim);
        assertTrue(claims.hasContainerTrust(owner, inside), "the owner needs no trust");
        assertTrue(claims.hasContainerTrust(friend, inside));
        assertTrue(claims.hasContainerTrust(friend, inside), "the lookup is cached the second time");
        assertFalse(claims.hasContainerTrust(stranger, inside));
        assertFalse(claims.hasContainerTrust(friend, outside), "no claim");
        assertFalse(claims.hasContainerTrust(null, inside));
    }

    @Test
    void aClaimWithoutACheckPermissionMethodGivesNoTrust() {
        claim(gp(), new Claim(UUID.randomUUID()));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), inside));
    }

    @Test
    void aPermissionEnumWithoutContainerGivesNoTrust() {
        claim(gp(), new OddClaim(UUID.randomUUID()));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), inside));
    }

    @Test
    void aFailingPermissionCheckGivesNoTrust() {
        RichClaim claim = new RichClaim(UUID.randomUUID());
        claim.failing = true; // the owner can't be read either, so the owner shortcut doesn't apply
        UUID friend = UUID.randomUUID();
        claim.containerTrust.add(friend);
        claim(gp(), claim);
        assertFalse(claims.hasContainerTrust(friend, inside));
    }

    // ---- GriefPrevention that can't be read

    private void assertBroken() {
        assertTrue(claims.present(), "installed, so claims apply");
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Couldn't read GriefPrevention claims")), warnings.toString());
        assertTrue(claims.claimed(inside), "can't tell, so everything counts as claimed");
        assertNull(claims.claimInfoAt(inside));
        assertNull(claims.ownerAt(inside));
        assertFalse(claims.hasContainerTrust(UUID.randomUUID(), inside));
        assertFalse(claims.mayOpen(server.addPlayer(), chest(50, 50)), "can't tell whose it is: not shown");
    }

    @Test
    void aGriefPreventionWithoutInstanceIsReportedAndEverythingIsHidden() {
        MockBukkit.loadWith(NoInstanceGP.class, new PluginDescriptionFile("GriefPrevention", "1", NoInstanceGP.class.getName()));
        assertBroken();
        assertEquals(1, warnings.stream().filter(w -> w.startsWith("Couldn't read")).count(), "warned once");
    }

    @Test
    void aGriefPreventionWithANullInstanceIsReported() {
        MockBukkit.loadWith(NullInstanceGP.class, new PluginDescriptionFile("GriefPrevention", "1", NullInstanceGP.class.getName()));
        assertBroken();
    }

    @Test
    void aGriefPreventionWithoutAClaimLookupIsReported() {
        MockBukkit.loadWith(NoLookupGP.class, new PluginDescriptionFile("GriefPrevention", "1", NoLookupGP.class.getName()));
        assertBroken();
    }

    @Test
    void resetLooksAgainSoALateGriefPreventionIsFound() {
        assertFalse(claims.present());
        claim(gp(), new Claim(UUID.randomUUID()));
        assertFalse(claims.present(), "not looked at again by itself");
        claims.reset();
        assertTrue(claims.present());
        assertTrue(claims.claimed(inside));
    }

    // ---- permissions of players who may be offline

    private Boolean ask(UUID player, String node) {
        List<Boolean> answers = new ArrayList<>();
        claims.hasPermission(player, node, answers::add);
        server.getScheduler().performTicks(2);
        assertTrue(answers.size() <= 1, "answered once: " + answers);
        return answers.isEmpty() ? null : answers.getFirst();
    }

    private void luckPerms() {
        MockBukkit.loadWith(FakeLuckPerms.class, new PluginDescriptionFile("LuckPerms", "1", FakeLuckPerms.class.getName()));
    }

    @Test
    void anOnlinePlayerIsAskedDirectly() {
        PlayerMock player = server.addPlayer();
        assertEquals(false, ask(player.getUniqueId(), "coppergolemclerk.alerts"));
        player.addAttachment(plugin, "coppergolemclerk.alerts", true);
        assertEquals(true, ask(player.getUniqueId(), "coppergolemclerk.alerts"));
    }

    @Test
    void anOfflinePlayerWithoutLuckPermsHasNoPermission() {
        assertEquals(false, ask(UUID.randomUUID(), "coppergolemclerk.alerts"));
    }

    @Test
    void aDisabledLuckPermsIsNotAsked() {
        luckPerms();
        server.getPluginManager().disablePlugin(server.getPluginManager().getPlugin("LuckPerms"));
        LuckPermsProvider.users = id -> {
            throw new AssertionError("not asked");
        };
        assertEquals(false, ask(UUID.randomUUID(), "x.y"));
    }

    @Test
    void anOfflinePlayersPermissionsComeFromLuckPermsOnTheMainThread() {
        luckPerms();
        UUID id = UUID.randomUUID();
        LuckPermsProvider.users = who -> CompletableFuture.completedFuture(new User(Set.of("coppergolemclerk.alerts")));
        assertEquals(true, ask(id, "coppergolemclerk.alerts"));
        assertEquals(false, ask(id, "coppergolemclerk.admin"));

        List<Boolean> answers = new ArrayList<>();
        claims.hasPermission(id, "coppergolemclerk.alerts", answers::add);
        assertTrue(answers.isEmpty(), "the answer waits for the next tick");
        server.getScheduler().performTicks(1);
        assertEquals(List.of(true), answers);
    }

    @Test
    void anAsynchronousLuckPermsAnswerIsHandedBackAfterItArrives() {
        luckPerms();
        CompletableFuture<User> pending = new CompletableFuture<>();
        LuckPermsProvider.users = who -> pending;
        List<Boolean> answers = new ArrayList<>();
        claims.hasPermission(UUID.randomUUID(), "x.y", answers::add);
        server.getScheduler().performTicks(5);
        assertTrue(answers.isEmpty());
        pending.complete(new User(Set.of("x.y")));
        server.getScheduler().performTicks(1);
        assertEquals(List.of(true), answers);
    }

    @Test
    void luckPermsFailuresMeanNo() {
        luckPerms();
        LuckPermsProvider.users = who -> CompletableFuture.completedFuture(null);
        assertEquals(false, ask(UUID.randomUUID(), "x.y"), "unknown user");
        LuckPermsProvider.users = who -> CompletableFuture.failedFuture(new IllegalStateException("db down"));
        assertEquals(false, ask(UUID.randomUUID(), "x.y"), "load failed");
        User broken = new User(Set.of("x.y"));
        broken.broken = true;
        LuckPermsProvider.users = who -> CompletableFuture.completedFuture(broken);
        assertEquals(false, ask(UUID.randomUUID(), "x.y"), "permission data unreadable");
        LuckPermsProvider.users = who -> {
            throw new IllegalStateException("not ready");
        };
        assertEquals(false, ask(UUID.randomUUID(), "x.y"), "API call failed");
    }

    @Test
    void aLuckPermsAnswerAfterThePluginIsDisabledIsDropped() {
        luckPerms();
        CompletableFuture<User> pending = new CompletableFuture<>();
        LuckPermsProvider.users = who -> pending;
        List<Boolean> answers = new ArrayList<>();
        claims.hasPermission(UUID.randomUUID(), "x.y", answers::add);
        server.getPluginManager().disablePlugin(plugin);
        pending.complete(new User(Set.of("x.y")));
        server.getScheduler().performTicks(2);
        assertTrue(answers.isEmpty(), answers.toString());
        assertNotNull(plugin);
    }

    @Test
    void anOfflinePlayerWhoseLuckPermsClassesAreMissingHasNoPermission() {
        // A plugin named LuckPerms whose class loader has no LuckPerms API: the same as an unusable install.
        MockBukkit.loadWith(ForeignLoaderPlugin.class, new PluginDescriptionFile("LuckPerms", "1", ForeignLoaderPlugin.class.getName()));
        assertEquals(false, ask(UUID.randomUUID(), "x.y"));
    }

    /** Stands in for a LuckPerms plugin; the API classes are found through the test class loader anyway. */
    public static class ForeignLoaderPlugin extends JavaPlugin {
    }

    @Test
    void mayOpenIgnoresMaterialsThatArentContainersByUsingTheStateGiven() {
        PlayerMock player = server.addPlayer();
        Block stone = world.getBlockAt(1, FLOOR, 1);
        stone.setType(Material.STONE);
        assertTrue(claims.mayOpen(player, stone), "no claims, not locked");
    }
}
