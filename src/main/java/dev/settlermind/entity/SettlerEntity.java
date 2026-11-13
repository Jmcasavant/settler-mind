package dev.settlermind.entity;

import dev.settlermind.mind.Decision;
import dev.settlermind.skill.GoToSkill;
import dev.settlermind.skill.MineBlockSkill;
import dev.settlermind.skill.Outcome;
import dev.settlermind.skill.WaitSkill;
import dev.settlermind.skill.Skill;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The settler's body: a pathfinding mob rendered as a player, with its own inventory,
 * hunger, owner and a single active {@link Skill}. All logic runs on the server thread.
 *
 * <p>Layering, from most to least urgent: reflexes (eat, flee) run every tick and can
 * pre-empt; the active skill runs if no reflex is busy; with nothing to do the settler
 * follows its owner or stays put.
 */
public class SettlerEntity extends PathAwareEntity {
    public enum Mode { FOLLOW, STAY }

    private static final int INVENTORY_SIZE = 27;
    private static final int EAT_TICKS = 32;

    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE) {
        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            // Closes the screen if the settler dies or walks away (prevents item duplication).
            return SettlerEntity.this.isAlive() && player.squaredDistanceTo(SettlerEntity.this) < 64;
        }
    };

    private @Nullable UUID ownerUuid;
    private String settlerName = "Settler";
    private Mode mode = Mode.FOLLOW;
    private boolean paused;

    private int foodLevel = 20;
    private float exhaustion;
    private int needsTimer;
    private int eatingTicks;

    private @Nullable Skill activeSkill;
    private @Nullable Outcome lastOutcome;

    /** Steps chosen by the mind, executed in order. */
    private final Deque<Decision.Step> plan = new ArrayDeque<>();
    private @Nullable Decision.Step currentStep;
    /** Player to follow in FOLLOW mode; null means the owner. */
    private @Nullable String followTarget;
    private long lastHurtReport;

    /** Installed by the mind host; the body works without one. */
    public static SettlerEvents events = SettlerEvents.NONE;

    public SettlerEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
        setCanPickUpLoot(false); // we collect items ourselves, into the real inventory
        for (EquipmentSlot slot : EquipmentSlot.values()) setEquipmentDropChance(slot, 2.0f);
        if (getNavigation() instanceof MobNavigation nav) {
            nav.setCanPathThroughDoors(true);
            nav.setCanSwim(true);
        }
    }

    public static DefaultAttributeContainer.Builder createSettlerAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        goalSelector.add(9, new LookAroundGoal(this));
    }

    // ------------------------------------------------------------------ brain

    @Override
    protected void mobTick() {
        super.mobTick();
        tickNeeds();
        if (paused) return;
        if (tickReflexes()) return;

        if (activeSkill != null) {
            Outcome outcome = activeSkill.tick(this);
            if (outcome != null) {
                lastOutcome = outcome;
                activeSkill = null;
                finishStep(outcome);
            }
            return;
        }
        if (!plan.isEmpty()) {
            startStep(plan.pollFirst());
            return;
        }
        if (mode == Mode.FOLLOW) followOwner();
    }

    /** @return true if a reflex is using the body this tick. */
    private boolean tickReflexes() {
        if (eatingTicks > 0) {
            if (--eatingTicks % 4 == 0) playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.5f, 1.0f);
            if (eatingTicks == 0) finishEating();
            return true;
        }
        boolean starving = foodLevel <= 6;
        if (foodLevel <= 14 && findFoodSlot(starving) >= 0) {
            eatingTicks = EAT_TICKS;
            getNavigation().stop();
            return true;
        }
        return false;
    }

    private void tickNeeds() {
        exhaustion += 0.0005f + (getNavigation().isIdle() ? 0 : 0.001f);
        if (exhaustion >= 4.0f) {
            exhaustion -= 4.0f;
            foodLevel = Math.max(0, foodLevel - 1);
        }
        if (++needsTimer >= 80) {
            needsTimer = 0;
            if (foodLevel >= 18 && getHealth() < getMaxHealth()) {
                heal(1.0f);
                exhaustion += 1.5f;
            } else if (foodLevel == 0 && getHealth() > 1.0f) {
                damage(getDamageSources().starve(), 1.0f);
            }
        }
    }

    private void followOwner() {
        PlayerEntity owner = followTarget == null ? getOwnerPlayer() : findPlayer(followTarget);
        if (owner == null || owner.getWorld() != getWorld()) return;
        double d = squaredDistanceTo(owner);
        if (d > 9.0 && (getNavigation().isIdle() || age % 20 == 0)) {
            getNavigation().startMovingTo(owner, d > 64 ? 1.2 : 1.0);
        } else if (d < 4.0) {
            getNavigation().stop();
        }
    }

    // ------------------------------------------------------------------ plans from the mind

    /** Replace the current plan with steps chosen by the mind. */
    public void setPlan(List<Decision.Step> steps) {
        if (activeSkill != null) {
            Outcome cancelled = activeSkill.cancel(this);
            activeSkill = null;
            lastOutcome = cancelled;
        }
        currentStep = null;
        plan.clear();
        plan.addAll(steps);
    }

    private void startStep(Decision.Step step) {
        currentStep = step;
        switch (step.kind()) {
            case "mine" -> activeSkill = new MineBlockSkill(new BlockPos(step.x(), step.y(), step.z()));
            case "go_to" -> activeSkill = new GoToSkill(new BlockPos(step.x(), step.y(), step.z()));
            case "wait" -> activeSkill = new WaitSkill(step.seconds() == null ? 5 : step.seconds());
            case "follow" -> {
                PlayerEntity target = step.target() == null ? null : findPlayer(step.target());
                if (target == null) {
                    finishStep(Outcome.fail("target_missing", "can't see " + step.target()));
                } else {
                    followTarget = isOwner(target) ? null : target.getGameProfile().getName();
                    mode = Mode.FOLLOW;
                    finishStep(Outcome.ok("following " + target.getGameProfile().getName()));
                }
            }
            case "stay" -> {
                mode = Mode.STAY;
                getNavigation().stop();
                finishStep(Outcome.ok("staying"));
            }
            default -> finishStep(Outcome.fail("unknown_step", step.kind()));
        }
    }

    private void finishStep(Outcome outcome) {
        Decision.Step step = currentStep;
        currentStep = null;
        lastOutcome = outcome;
        if (step == null) return;
        boolean failed = outcome.status() != Outcome.Status.SUCCEEDED;
        if (failed) plan.clear(); // later steps depended on this one
        events.onStepFinished(this, step, outcome, plan.isEmpty());
    }

    private @Nullable PlayerEntity findPlayer(String name) {
        for (PlayerEntity p : getWorld().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name) && p.squaredDistanceTo(this) < 48 * 48) return p;
        }
        return null;
    }

    /** True when the body has nothing to do: the mind may decide how to spend the time. */
    public boolean isIdleForMind() {
        return !paused && activeSkill == null && plan.isEmpty() && eatingTicks == 0;
    }

    public String describeActivity() {
        if (paused) return "paused by your owner";
        if (eatingTicks > 0) return "eating";
        if (activeSkill != null) return activeSkill.name() + " (" + activeSkill.phase() + ")";
        if (mode == Mode.FOLLOW) return "following " + (followTarget == null ? "your owner" : followTarget);
        return "standing around";
    }

    public List<Decision.Step> getPlan() { return List.copyOf(plan); }

    // ------------------------------------------------------------------ skills

    public void startSkill(Skill skill) {
        cancelSkill();
        activeSkill = skill;
    }

    public void cancelSkill() {
        plan.clear();
        currentStep = null;
        if (activeSkill != null) {
            lastOutcome = activeSkill.cancel(this);
            activeSkill = null;
        }
    }

    public @Nullable Skill getActiveSkill() { return activeSkill; }
    public @Nullable Outcome getLastOutcome() { return lastOutcome; }

    // ------------------------------------------------------------------ food

    /** @return slot of an edible item; food with side effects only when starving. */
    private int findFoodSlot(boolean starving) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            FoodComponent food = stack.getItem().getFoodComponent();
            if (food == null) continue;
            if (!food.getStatusEffects().isEmpty() && !starving) continue;
            return i;
        }
        return -1;
    }

    private void finishEating() {
        int slot = findFoodSlot(foodLevel <= 6);
        if (slot < 0) return; // food was taken out mid-meal: no effect, no free food
        ItemStack stack = inventory.getStack(slot);
        FoodComponent food = stack.getItem().getFoodComponent();
        if (food == null) return;
        foodLevel = Math.min(20, foodLevel + food.getHunger());
        stack.decrement(1);
        inventory.markDirty();
        playSound(SoundEvents.ENTITY_PLAYER_BURP, 0.5f, 1.0f);
    }

    public void addExhaustion(float amount) { exhaustion += amount; }
    public int getFoodLevel() { return foodLevel; }
    public void setFoodLevel(int level) { foodLevel = Math.max(0, Math.min(20, level)); }
    public boolean isEating() { return eatingTicks > 0; }

    // ------------------------------------------------------------------ inventory

    public SimpleInventory getInventory() { return inventory; }

    public Map<Item, Integer> inventoryCounts() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack hand = getMainHandStack();
        if (!hand.isEmpty()) counts.merge(hand.getItem(), hand.getCount(), Integer::sum);
        return counts;
    }

    /** Swap the best tool for this block from the inventory into the main hand. */
    public void equipBestToolFor(BlockState state) {
        int best = -1;
        float bestSpeed = getMainHandStack().getMiningSpeedMultiplier(state);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(state);
            boolean upgrade = speed > bestSpeed
                    || (state.isToolRequired() && s.isSuitableFor(state) && !getMainHandStack().isSuitableFor(state));
            if (upgrade) {
                best = i;
                bestSpeed = speed;
            }
        }
        if (best >= 0) {
            ItemStack tool = inventory.getStack(best);
            inventory.setStack(best, getMainHandStack());
            setStackInHand(Hand.MAIN_HAND, tool);
        }
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (!getWorld().isClient && isAlive() && age % 2 == 0) collectNearbyItems();
    }

    private void collectNearbyItems() {
        for (ItemEntity item : getWorld().getEntitiesByClass(ItemEntity.class,
                getBoundingBox().expand(1.0, 0.5, 1.0), e -> e.isAlive() && !e.cannotPickup())) {
            ItemStack stack = item.getStack();
            int before = stack.getCount();
            ItemStack rest = inventory.addStack(stack.copy());
            int taken = before - rest.getCount();
            if (taken <= 0) continue;
            sendPickup(item, taken);
            if (rest.isEmpty()) item.discard();
            else item.setStack(rest);
        }
    }

    @Override
    protected void dropInventory() {
        super.dropInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.removeStack(i);
            if (!stack.isEmpty()) ItemScatterer.spawn(getWorld(), getX(), getY(), getZ(), stack);
        }
    }

    /** Drop everything carried and remove the settler without loss. */
    public void dismiss() {
        events.onDismissed(this);
        cancelSkill();
        dropInventory();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = getEquippedStack(slot);
            if (!stack.isEmpty()) {
                ItemScatterer.spawn(getWorld(), getX(), getY(), getZ(), stack);
                equipStack(slot, ItemStack.EMPTY);
            }
        }
        discard();
    }

    // ------------------------------------------------------------------ owner & interaction

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (getWorld().isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!isOwner(player)) return ActionResult.PASS;
        if (player.isSneaking()) {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, inventory),
                    getDisplayName()));
            return ActionResult.SUCCESS;
        }
        ItemStack held = player.getStackInHand(hand);
        if (!held.isEmpty()) {
            ItemStack rest = inventory.addStack(held.copy());
            player.setStackInHand(hand, rest);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    public @Nullable PlayerEntity getOwnerPlayer() {
        return ownerUuid == null ? null : getWorld().getPlayerByUuid(ownerUuid);
    }

    public void setOwner(ServerPlayerEntity owner) { ownerUuid = owner.getUuid(); }
    public @Nullable UUID getOwnerUuid() { return ownerUuid; }

    public String getSettlerName() { return settlerName; }

    public void setSettlerName(String name) {
        settlerName = name;
        setCustomName(Text.literal(name));
        setCustomNameVisible(true);
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; this.followTarget = null; getNavigation().stop(); }
    public boolean isPaused() { return paused; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            cancelSkill();
            getNavigation().stop();
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Owners can't accidentally hurt their settlers.
        if (source.getAttacker() instanceof PlayerEntity p && isOwner(p)) return false;
        boolean hurt = super.damage(source, amount);
        if (hurt && !getWorld().isClient && age - lastHurtReport > 100) {
            lastHurtReport = age;
            String by = source.getAttacker() instanceof PlayerEntity p ? p.getGameProfile().getName()
                    : source.getAttacker() != null ? source.getAttacker().getType().getName().getString()
                    : source.getName();
            events.onHurt(this, by, amount);
        }
        return hurt;
    }

    @Override public boolean cannotDespawn() { return true; }
    @Override public boolean canImmediatelyDespawn(double distanceSquared) { return false; }
    @Override protected boolean isDisallowedInPeaceful() { return false; }

    // ------------------------------------------------------------------ persistence

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        NbtCompound hk = new NbtCompound();
        hk.putInt("Version", 1);
        if (ownerUuid != null) hk.putUuid("Owner", ownerUuid);
        hk.putString("Name", settlerName);
        hk.putString("Mode", mode.name());
        hk.putBoolean("Paused", paused);
        hk.putInt("Food", foodLevel);
        hk.putFloat("Exhaustion", exhaustion);
        hk.put("Inventory", inventory.toNbtList());
        // A skill in progress cannot be resumed safely after a restart: record it so the
        // settler re-observes instead of assuming it finished.
        if (activeSkill != null) hk.putString("InterruptedSkill", activeSkill.name());
        nbt.put("SettlerMind", hk);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        NbtCompound hk = nbt.getCompound("SettlerMind");
        ownerUuid = hk.containsUuid("Owner") ? hk.getUuid("Owner") : null;
        setSettlerName(hk.contains("Name") ? hk.getString("Name") : "Settler");
        try {
            mode = Mode.valueOf(hk.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.FOLLOW;
        }
        paused = hk.getBoolean("Paused");
        foodLevel = hk.contains("Food") ? hk.getInt("Food") : 20;
        exhaustion = hk.getFloat("Exhaustion");
        inventory.clear();
        inventory.readNbtList(hk.getList("Inventory", NbtElement.COMPOUND_TYPE));
        if (hk.contains("InterruptedSkill")) {
            lastOutcome = new Outcome(Outcome.Status.UNCERTAIN, "interrupted_by_restart", hk.getString("InterruptedSkill"));
        }
    }

    @Override
    public boolean canTarget(LivingEntity target) { return false; }
}
