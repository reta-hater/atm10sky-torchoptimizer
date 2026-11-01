package dev.torchskip;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Lets vanilla's natural spawner give up early on spawn attempts that Torchmaster's Mega Torch would
 * deny anyway.
 *
 * Torchmaster only blocks at MobSpawnEvent.PositionCheck, which fires AFTER vanilla has already done
 * the expensive work for the attempt (biome lookup, spawn-list lookup, validity checks) and even
 * created the mob. This class asks Torchmaster the same question up front, so the mixin can skip the
 * attempt.
 *
 * Torchmaster is reached through method handles, so this mod does not need Torchmaster to compile. If
 * Torchmaster is missing or its API changes, the hooks log a warning and do nothing.
 */
@Mod(TorchSkip.MOD_ID)
public class TorchSkip {
    public static final String MOD_ID = "torchskip";
    private static final Logger LOG = LogUtils.getLogger();

    /** Run with -Dtorchskip.stats=true to log how many attempts are skipped (every 60 s). */
    private static final boolean STATS = Boolean.getBoolean("torchskip.stats");

    /**
     * Set to false to disable the whole-category shortcut and only use the exact per-entity-type
     * check. That is slower, but it never skips anything Torchmaster wouldn't deny.
     */
    private static final boolean SKIP_WHOLE_MONSTER_CATEGORY = true;

    // Bound to Torchmaster at server start. Server thread only: natural spawning runs there.
    private static MethodHandle getRegistryForLevel;   // (Level) -> Optional<IBlockingLightManager>
    private static MethodHandle shouldBlockEntityType; // (manager, EntityType, Level, Vec3, MobSpawnType) -> boolean
    private static boolean ready = false;
    private static boolean monstersFullyBlocked = false;

    private static final Map<ServerLevel, Object> MANAGERS = new WeakHashMap<>();

    private static long skippedCategory, passedCategory, skippedType, passedType;

    public TorchSkip() {
        NeoForge.EVENT_BUS.addListener(TorchSkip::onServerStarted);
        NeoForge.EVENT_BUS.addListener(TorchSkip::onServerStopped);
        if (STATS) NeoForge.EVENT_BUS.addListener(TorchSkip::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        // Torchmaster builds its filter lists on level load; by ServerStarted they are final.
        bind();
        if (ready) {
            LOG.info("[torchskip] hooked into Torchmaster; whole-monster-category shortcut {}",
                    monstersFullyBlocked ? "ENABLED" : "disabled (Mega Torch filter does not cover every MONSTER entity type)");
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        MANAGERS.clear();
        ready = false;
        monstersFullyBlocked = false;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 1200 != 0) return;
        LOG.info("[torchskip] last 60s: category skipped={} passed={} | type skipped={} passed={}",
                skippedCategory, passedCategory, skippedType, passedType);
        skippedCategory = passedCategory = skippedType = passedType = 0;
    }

    private static void bind() {
        ready = false;
        monstersFullyBlocked = false;
        try {
            Class<?> torchmaster = Class.forName("net.xalcon.torchmaster.Torchmaster");
            Class<?> manager = Class.forName("net.xalcon.torchmaster.logic.entityblocking.IBlockingLightManager");
            Class<?> filterList = Class.forName("net.xalcon.torchmaster.EntityFilterList");
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            getRegistryForLevel = lookup.findStatic(torchmaster, "getRegistryForLevel",
                    MethodType.methodType(Optional.class, Level.class));
            shouldBlockEntityType = lookup.findVirtual(manager, "shouldBlockEntityType",
                    MethodType.methodType(boolean.class, EntityType.class, Level.class, Vec3.class, MobSpawnType.class));

            MethodHandle megaFilter = lookup.findStaticGetter(torchmaster, "MegaTorchFilterRegistry", filterList);
            MethodHandle contains = lookup.findVirtual(filterList, "containsEntity",
                    MethodType.methodType(boolean.class, ResourceLocation.class));
            Object filter = megaFilter.invoke();

            monstersFullyBlocked = SKIP_WHOLE_MONSTER_CATEGORY && computeMonstersFullyBlocked(contains, filter);
            ready = true;
        } catch (Throwable t) {
            LOG.warn("[torchskip] could not hook into Torchmaster ({}); spawn skipping is disabled", t.toString());
        }
    }

    /** True if every entity type vanilla classifies as MONSTER is on the Mega Torch block list. */
    private static boolean computeMonstersFullyBlocked(MethodHandle contains, Object filter) throws Throwable {
        int monsters = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() != MobCategory.MONSTER) continue;
            monsters++;
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!(boolean) contains.invoke(filter, key)) return false;
        }
        return monsters > 0;
    }

    private static Object manager(ServerLevel level) throws Throwable {
        Object mgr = MANAGERS.get(level);
        if (mgr == null) {
            // Torchmaster.getRegistryForLevel builds a string and does a SavedData lookup on every
            // call, so resolve it once per level and reuse it.
            Optional<?> found = (Optional<?>) getRegistryForLevel.invoke((Level) level);
            mgr = found.orElse(null);
            if (mgr != null) MANAGERS.put(level, mgr);
        }
        return mgr;
    }

    private static boolean ask(ServerLevel level, EntityType<?> type, BlockPos pos) {
        if (!ready) return false;
        try {
            Object mgr = manager(level);
            if (mgr == null) return false;
            // Same position vanilla gives the mob before PositionCheck: block centre in x/z, block y.
            Vec3 where = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            return (boolean) shouldBlockEntityType.invoke(mgr, type, (Level) level, where, MobSpawnType.NATURAL);
        } catch (Throwable t) {
            ready = false; // stop asking after the first failure
            LOG.error("[torchskip] Torchmaster check failed; disabling spawn skipping", t);
            return false;
        }
    }

    /** Exact check: would a Mega Torch deny a natural spawn of this entity type at this position? */
    public static boolean isBlockedAt(ServerLevel level, EntityType<?> type, BlockPos pos) {
        boolean blocked = ask(level, type, pos);
        if (STATS) { if (blocked) skippedType++; else passedType++; }
        return blocked;
    }

    /**
     * Shortcut: every possible monster here is blocked, so skip choosing a spawner entry at all.
     * Only true when the Mega Torch filter covers all MONSTER types (checked at server start).
     */
    public static boolean isMonsterCategoryBlocked(ServerLevel level, MobCategory category, BlockPos pos) {
        if (!monstersFullyBlocked || category != MobCategory.MONSTER) return false;
        // Zombie is a stand-in: it is on the Mega Torch list (verified at start), and the check only
        // depends on the torch position and radius.
        boolean blocked = ask(level, EntityType.ZOMBIE, pos);
        if (STATS) { if (blocked) skippedCategory++; else passedCategory++; }
        return blocked;
    }
}
