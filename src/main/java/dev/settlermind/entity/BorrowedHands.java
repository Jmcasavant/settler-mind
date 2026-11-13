package dev.settlermind.entity;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lends a settler a Fabric API {@link FakePlayer} for the duration of one interaction
 * that the game (or another mod) only accepts from a player, such as breaking a block
 * with correct tool, enchantment and loot-table behaviour.
 *
 * <p>The fake player has its own identity derived from the settler, NOT the owner's,
 * so it never inherits the owner's claim rights. Everything it ends up holding is
 * moved back to the settler afterwards (some mods insert drops straight into the
 * player's inventory), so nothing is lost or duplicated.
 */
public final class BorrowedHands {
    private BorrowedHands() {}

    public static GameProfile profileFor(SettlerEntity settler) {
        UUID id = UUID.nameUUIDFromBytes(("settlermind:" + settler.getUuid()).getBytes(StandardCharsets.UTF_8));
        String name = ("sm_" + settler.getSettlerName()).replaceAll("[^A-Za-z0-9_]", "_");
        if (name.length() > 16) name = name.substring(0, 16);
        return new GameProfile(id, name);
    }

    /** Positions a fake player on the settler, holding the settler's main-hand item. */
    public static FakePlayer acquire(SettlerEntity settler) {
        ServerWorld world = (ServerWorld) settler.getWorld();
        FakePlayer fake = FakePlayer.get(world, profileFor(settler));
        clear(fake); // defensive: instances are cached and shared per profile
        fake.refreshPositionAndAngles(settler.getX(), settler.getY(), settler.getZ(), settler.getYaw(), settler.getPitch());
        fake.setOnGround(settler.isOnGround());
        fake.setStackInHand(Hand.MAIN_HAND, settler.getMainHandStack());
        return fake;
    }

    /** Returns the (possibly damaged or broken) tool and anything the fake picked up. */
    public static void release(SettlerEntity settler, FakePlayer fake) {
        settler.setStackInHand(Hand.MAIN_HAND, fake.getMainHandStack());
        fake.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        PlayerInventory inv = fake.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.removeStack(i);
            if (stack.isEmpty()) continue;
            ItemStack rest = settler.getInventory().addStack(stack);
            if (!rest.isEmpty()) {
                ItemScatterer.spawn(settler.getWorld(), settler.getX(), settler.getY(), settler.getZ(), rest);
            }
        }
    }

    /** Use a fake player for one operation; always released, even on exceptions. */
    public static <T> T with(SettlerEntity settler, Function<FakePlayer, T> action) {
        FakePlayer fake = acquire(settler);
        try {
            return action.apply(fake);
        } finally {
            release(settler, fake);
        }
    }

    private static void clear(FakePlayer fake) {
        PlayerInventory inv = fake.getInventory();
        for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
    }
}
