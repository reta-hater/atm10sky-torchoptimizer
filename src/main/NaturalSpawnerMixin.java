package dev.torchskip.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.torchskip.TorchSkip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    /**
     * Step 1 (biggest saving): vanilla picks a spawner entry per group with a biome lookup. If a Mega
     * Torch covers this spot and blocks every monster, return "nothing can spawn here". Vanilla already
     * handles an empty result by abandoning that group, so this is the same path as a biome with no
     * monsters.
     */
    @WrapOperation(
            method = "spawnCategoryForPosition",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;getRandomSpawnMobAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;"
            )
    )
    private static Optional<MobSpawnSettings.SpawnerData> torchskip$skipBlockedCategory(
            ServerLevel level, StructureManager structures, ChunkGenerator generator,
            MobCategory category, RandomSource random, BlockPos pos,
            Operation<Optional<MobSpawnSettings.SpawnerData>> original) {
        if (TorchSkip.isMonsterCategoryBlocked(level, category, pos)) {
            return Optional.empty();
        }
        return original.call(level, structures, generator, category, random, pos);
    }

    /**
     * Step 2 (exact fallback): for the chosen entity type, skip the second biome lookup and the
     * validity checks if a Mega Torch would deny that exact type here anyway.
     */
    @WrapOperation(
            method = "spawnCategoryForPosition",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;isValidSpawnPostitionForType(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/MobSpawnSettings$SpawnerData;Lnet/minecraft/core/BlockPos$MutableBlockPos;D)Z"
            )
    )
    private static boolean torchskip$skipBlockedType(
            ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator,
            MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double distanceSqr,
            Operation<Boolean> original) {
        if (TorchSkip.isBlockedAt(level, data.type, pos)) {
            return false;
        }
        return original.call(level, category, structures, generator, data, pos, distanceSqr);
    }
}
