package dev.hearthkin.ai;

import com.google.gson.Gson;
import dev.hearthkin.Hearthkin;
import dev.hearthkin.mind.Mind;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.PersistentState;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minds and chat-consent choices, stored in the world save
 * ({@code data/hearthkin_minds.dat}) so backups and rollbacks keep them consistent with
 * the world. Minds are serialised as JSON strings to keep the mind package engine-free.
 */
public final class MindStore extends PersistentState {
    static final String ID = "hearthkin_minds";
    private static final Gson GSON = new Gson();

    final Map<UUID, Mind> minds = new LinkedHashMap<>();
    final Set<UUID> chatConsent = new HashSet<>();

    public static MindStore fromNbt(NbtCompound nbt) {
        MindStore s = new MindStore();
        NbtCompound m = nbt.getCompound("Minds");
        for (String key : m.getKeys()) {
            try {
                Mind.Saved saved = GSON.fromJson(m.getString(key), Mind.Saved.class);
                s.minds.put(UUID.fromString(key), Mind.load(saved));
            } catch (RuntimeException e) {
                // A corrupt mind must not take the world down; the settler starts fresh.
                Hearthkin.LOG.error("Could not load mind {}; it will be recreated", key, e);
            }
        }
        for (String key : nbt.getCompound("Consent").getKeys()) {
            try {
                s.chatConsent.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) { }
        }
        return s;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound m = new NbtCompound();
        minds.forEach((id, mind) -> m.putString(id.toString(), GSON.toJson(mind.save())));
        nbt.put("Minds", m);
        NbtCompound c = new NbtCompound();
        chatConsent.forEach(id -> c.putBoolean(id.toString(), true));
        nbt.put("Consent", c);
        nbt.putInt("Version", 1);
        return nbt;
    }

    /** Minds change constantly; always save them with the world. */
    @Override
    public boolean isDirty() {
        return true;
    }
}
