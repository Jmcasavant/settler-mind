package dev.hearthkin.protection;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Decides whether a settler may change a block. Fails closed: if a known claims mod
 * is installed and we have no verified adapter for it, every mutation is refused.
 * This is deliberate until the Open Parties and Claims adapter is written and tested
 * against a clone of the real pack.
 */
public final class ProtectionGuard {
    /** Mod ids of claim/protection mods we must not silently ignore. */
    private static final List<String> KNOWN_CLAIM_MODS = List.of(
            "openpartiesandclaims", "ftbchunks", "flan", "goml", "cadmus");

    private ProtectionGuard() {}

    /** @return null if allowed, otherwise a failure code. */
    public static @Nullable String checkModify(ServerWorld world, ServerPlayerEntity actor, BlockPos pos) {
        String claimMod = unsupportedClaimMod();
        if (claimMod != null) return "claims_adapter_missing:" + claimMod;
        if (!world.isChunkLoaded(pos)) return "chunk_unloaded";
        // World border and vanilla spawn protection.
        if (!world.canPlayerModifyAt(actor, pos)) return "protected";
        return null;
    }

    /** @return the first installed claims mod we cannot yet check, or null. */
    public static @Nullable String unsupportedClaimMod() {
        for (String id : KNOWN_CLAIM_MODS) {
            if (FabricLoader.getInstance().isModLoaded(id)) return id;
        }
        return null;
    }
}
