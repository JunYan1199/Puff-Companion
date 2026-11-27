package com.puffcompanion.entity;

import com.puffcompanion.network.PuffBubblePacket;
import com.puffcompanion.network.PuffInteractionPacket;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PuffEntity extends TamableAnimal implements GeoEntity {
    // ---- Synced state (client needs these for outfit / UI / {call} rendering) ----
    private static final EntityDataAccessor<Integer> AFFECTION =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> GROWTH_STAGE =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> CALL_MODE =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> CURRENT_OUTFIT =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> PUFF_HUNGER =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    /** Bits 0-4: unlocked outfits ({@link PuffOutfit#bit()}); bits 8+: story flags. */
    private static final EntityDataAccessor<Integer> UNLOCKS =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    /** Bit i = {@link PuffStories#ORDER}[i] completed (lets the client list replayable memories). */
    private static final EntityDataAccessor<Integer> STORIES_DONE =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    /** Comma-separated story choice/route flags synced to the client for story tree & AI memory. */
    private static final EntityDataAccessor<String> SYNCED_STORY_FLAGS =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> OWNER_GUARD =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> RESTING =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SPELL_MANA =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SPELL_CAST_ENABLED =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> WORK_MODE =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<ItemStack> CAST_DISPLAY_STACK =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> HOME_MODE =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<java.util.Optional<net.minecraft.core.BlockPos>> HOME_POS =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Boolean> SHOW_HELD_ITEMS =
            SynchedEntityData.defineId(PuffEntity.class, EntityDataSerializers.BOOLEAN);

    public static final int HOME_RADIUS = 16;

    public static final String TAG_OWNER_UUID = "OwnerUUID";
    public static final String TAG_AFFECTION = "Affection";
    public static final String TAG_GROWTH_STAGE = "GrowthStage";
    public static final String TAG_CALL_MODE = "CallMode";
    public static final String TAG_CURRENT_OUTFIT = "CurrentOutfit";
    public static final String TAG_PUFF_HUNGER = "PuffHunger";
    public static final String TAG_COMPLETED_STORIES = "CompletedStories";
    public static final String TAG_UNLOCKED_OUTFITS = "UnlockedOutfits";
    public static final String TAG_RESTING = "Resting";
    public static final String TAG_PET_DAY = "PetAffectionDay";
    public static final String TAG_PET_GAINED = "PetAffectionGained";
    public static final String TAG_GIFT_DAY = "GiftAffectionDay";
    public static final String TAG_GIFT_GAINED = "GiftAffectionGained";

    public static final String CALL_BROTHER = "brother";
    public static final String CALL_SISTER = "sister";

    // Story ids (the Galgame system will mark these) and their synced flag bits
    public static final String STORY_CHAPTER1 = "chapter1";
    public static final String STORY_SIDE1 = "side1";
    public static final String STORY_PROLOGUE = "puff_story_prologue";
    public static final String STORY_CH1_DOC_ID = "puff_story_ch1_hoard";
    public static final String STORY_SIDE1_DOC_ID = "puff_side_cat_panic";
    public static final String TAG_STORY_FLAGS = "StoryFlags";
    public static final int FLAG_STORY_CHAPTER1 = 1 << 8;
    public static final int FLAG_STORY_SIDE1 = 1 << 9;

    // ---- Animations ----
    public static final String ACTION_CONTROLLER = "action";
    public static final String ANIM_ALERT = "action_alert";
    public static final String ANIM_SHY = "action_shy";
    public static final String ANIM_SWING = "swing_hand";
    public static final String ANIM_SPELLCAST = "action_spellcast";
    public static final String ANIM_ALERT_SNIFF = "action_alert_sniff";
    public static final String ANIM_CAT_PANIC = "action_cat_panic";
    public static final String ANIM_CHECK_CUFF = "action_check_cuff";
    public static final String ANIM_COMMAND_RATS = "action_command_rats";
    public static final String ANIM_GROUND_REST = "action_ground_rest";
    public static final String ANIM_HOARD_PICKUP = "action_hoard_pickup";
    public static final String ANIM_SHOULDER_SIT = "action_shoulder_sit";
    public static final String ANIM_VOW_SHY = "action_vow_shy";
    public static final String ANIM_EAT_CHEESE = "action_eat_cheese";
    public static final String ANIM_BOW = "action_bow";
    public static final String ANIM_SHOUT = "action_shout";
    public static final String ANIM_STRETCH = "action_stretch";
    public static final String ANIM_WAVE = "action_wave";

    private static final int HUNGER_TICKS_PER_POINT = 600;     // -1 hunger per 30 s while active
    private static final int PAYDAY_WINDOW_TICKS = 30 * 20;
    private static final int PAYDAY_BONUS = 2;
    public static final String TAG_HOARD = "HoardInventory";
    public static final String TAG_DAILY_EVENTS = "DailyEvents";
    public static final String TAG_LAST_DIMENSION = "LastDimension";

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation SIT = RawAnimation.begin().thenLoop("sit");
    private static final RawAnimation REST = RawAnimation.begin().thenLoop("sleep");
    private static final RawAnimation ALERT = RawAnimation.begin().thenPlay(ANIM_ALERT);
    private static final RawAnimation SHY = RawAnimation.begin().thenPlay(ANIM_SHY);
    private static final RawAnimation SWING = RawAnimation.begin().thenPlay(ANIM_SWING);
    private static final RawAnimation SPELLCAST = RawAnimation.begin().thenPlay(ANIM_SPELLCAST);
    private static final RawAnimation ALERT_SNIFF = RawAnimation.begin().thenPlay(ANIM_ALERT_SNIFF);
    private static final RawAnimation CAT_PANIC = RawAnimation.begin().thenPlay(ANIM_CAT_PANIC);
    private static final RawAnimation CHECK_CUFF = RawAnimation.begin().thenPlay(ANIM_CHECK_CUFF);
    private static final RawAnimation COMMAND_RATS = RawAnimation.begin().thenPlay(ANIM_COMMAND_RATS);
    private static final RawAnimation GROUND_REST = RawAnimation.begin().thenPlay(ANIM_GROUND_REST);
    private static final RawAnimation HOARD_PICKUP = RawAnimation.begin().thenPlay(ANIM_HOARD_PICKUP);
    private static final RawAnimation SHOULDER_SIT = RawAnimation.begin().thenPlay(ANIM_SHOULDER_SIT);
    private static final RawAnimation VOW_SHY = RawAnimation.begin().thenPlay(ANIM_VOW_SHY);
    private static final RawAnimation EAT_CHEESE = RawAnimation.begin().thenPlay(ANIM_EAT_CHEESE);
    private static final RawAnimation BOW = RawAnimation.begin().thenPlay(ANIM_BOW);
    private static final RawAnimation SHOUT = RawAnimation.begin().thenPlay(ANIM_SHOUT);
    private static final RawAnimation STRETCH = RawAnimation.begin().thenPlay(ANIM_STRETCH);
    private static final RawAnimation WAVE = RawAnimation.begin().thenPlay(ANIM_WAVE);

    /** Horizontal blocks-per-tick above which the run cycle is used instead of walk. */
    private static final double RUN_SPEED_THRESHOLD = 0.17;
    /** Owner distance (squared) beyond which Puff hurries to catch up. */
    private static final double CATCH_UP_DIST_SQR = 7.0 * 7.0;
    /** Max distance for menu actions (server-side validation). */
    public static final double INTERACT_RANGE = 8.0;

    // ---- Anti-spam caps ----
    private static final int PET_AFFECTION_GAIN = 2;
    private static final int PET_AFFECTION_DAILY_CAP = 10;
    public static final int GIFT_AFFECTION_DAILY_CAP = 20;
    private static final int PANIC_TICKS = 60;

    /** Render scale while sitting on the owner's shoulder (chibi size). */
    public static final float SHOULDER_SCALE = 0.42F;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** Server-side only; story flags derived from it are mirrored into {@link #UNLOCKS}. */
    private final Set<String> completedStories = new LinkedHashSet<>();
    private final Set<String> storyFlags = new LinkedHashSet<>();
    private long petAffectionDay = -1;
    private int petAffectionGained;
    private long giftAffectionDay = -1;
    private int giftAffectionGained;

    /** Secret nest: 9 slots, persisted, dropped on death, closable only within reach. */
    private final net.minecraft.world.SimpleContainer hoard = new net.minecraft.world.SimpleContainer(PuffHoard.SIZE) {
        @Override
        public boolean stillValid(Player player) {
            return PuffEntity.this.isAlive() && (PuffEntity.this.getVehicle() == player
                    || PuffEntity.this.distanceToSqr(player) <= INTERACT_RANGE * INTERACT_RANGE);
        }
    };
    /** Rats-style training (rare candy / gene treats). */
    private final PuffProgression progression = new PuffProgression();
    /** Curios accessories (stored even without Curios installed). */
    private final PuffCurioStore curioStore = new PuffCurioStore();
    /** Curio attribute modifiers currently applied (transient, recomputed). */
    private final Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> appliedCurioModifiers = new HashMap<>();
    /** Modifier id -> curio stack that granted it (for the attribute panel's source icons). */
    private final Map<UUID, ItemStack> curioModifierSources = new HashMap<>();
    @Nullable private PuffFakePlayer fakePlayer;
    /**
     * The proxy player's persistent data (mods' "already got the starter book" flags etc.), saved with Puff so a
     * re-created proxy (new session, dimension change, spirit bell) is not treated as a brand-new player.
     */
    private CompoundTag proxyPersistentData = new CompoundTag();
    /** Curios slot type -> extra slots bought with the star accessory expander (saved; survives revival). */
    private final Map<String, Integer> curioSlotBonus = new java.util.TreeMap<>();
    /** Permanent Curios slot modifiers accessories gave her proxy player (saved; restored onto a new proxy). */
    private CompoundTag curioSlotState = new CompoundTag();
    /** Slot type -> ticks the slot count has been below what her store holds (grace before handing items back). */
    private final Map<String, Integer> curioShrinkTicks = new HashMap<>();
    /** Stage 6: rat squad, work area, storage, blueprint, Lootr state. */
    private final PuffSquad squad = new PuffSquad();
    private final Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> proxyAttributeModifiers = new HashMap<>();
    /**
     * Ticks the proxy keeps being ticked after Puff took off her last accessory / armor piece, so Curios and
     * the item code see the removal (onUnequip, modifiers taken back) instead of freezing the last state.
     */
    public int proxyLinger;

    /** Last in-game day each once-per-day event fired (dusk / night). */
    private final Map<String, Long> dailyEvents = new HashMap<>();
    /** Dimension id Puff was last ticking in; a change triggers the arrival line. */
    @Nullable private String lastDimension;

    // ---- Transient server state ----
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> spellCooldowns = new HashMap<>();
    private long lastAutoBubbleTime = Long.MIN_VALUE / 2;
    private int panicTicks;
    private int hungerTicker;
    private int castDisplayTicks;
    private long lastSwingAnimTick = Long.MIN_VALUE;
    /** Game time until which a pat / cheese gift pays the rats' "wages" (+2 affection). */
    private long paydayUntil = Long.MIN_VALUE;
    @Nullable private Vec3 ownerStillPos;
    private long ownerStillSince;
    private long lastOwnerNearTime = Long.MIN_VALUE / 2;

    // ---- Transient client state (speech bubble) ----
    @Nullable private String bubbleText;
    private int bubbleStartTick;
    private int bubbleEndTick;
    @Nullable private String dialogueExpression;
    @Nullable private String dialogueEar;
    @Nullable private String tempExpression;
    @Nullable private String tempEar;
    private int tempExpressionEndTick;
    @Nullable private String lastBiomeId;

    public PuffEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        this.setTame(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, STAGE_BASE_DAMAGE);
    }

    // ---- Growth-stage combat stats (gentle curve) ----
    private static final double STAGE_BASE_HEALTH = 20.0D;
    private static final double STAGE_HEALTH_STEP = 6.0D;     // 20 / 26 / 32 / 38
    private static final double STAGE_BASE_DAMAGE = 3.0D;
    private static final double STAGE_DAMAGE_STEP = 1.5D;     // 3 / 4.5 / 6 / 7.5
    /** Puff will not fight on an empty stomach. */
    public static final int FIGHT_HUNGER_MIN = 15;

    /** Applies max health / attack damage for the current growth stage (server side). */
    private void applyStageStats() {
        int step = Mth.clamp(this.getGrowthStage(), 1, 4) - 1;
        var health = this.getAttribute(Attributes.MAX_HEALTH);
        var damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (health != null) health.setBaseValue(STAGE_BASE_HEALTH + step * STAGE_HEALTH_STEP);
        if (damage != null) damage.setBaseValue(STAGE_BASE_DAMAGE + step * STAGE_DAMAGE_STEP);
        if (this.getHealth() > this.getMaxHealth()) this.setHealth(this.getMaxHealth());
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(AFFECTION, 10);
        this.entityData.define(GROWTH_STAGE, 1);
        this.entityData.define(CALL_MODE, CALL_BROTHER);
        this.entityData.define(CURRENT_OUTFIT, PuffOutfit.SWIM.id());
        this.entityData.define(PUFF_HUNGER, 100);
        this.entityData.define(UNLOCKS, PuffOutfit.defaultMask());
        this.entityData.define(RESTING, false);
        this.entityData.define(STORIES_DONE, 0);
        this.entityData.define(SYNCED_STORY_FLAGS, "");
        this.entityData.define(SPELL_MANA, 100);
        this.entityData.define(SPELL_CAST_ENABLED, true);
        this.entityData.define(OWNER_GUARD, true);
        this.entityData.define(WORK_MODE, PuffWorkMode.FOLLOW_GUARD.id());
        this.entityData.define(CAST_DISPLAY_STACK, ItemStack.EMPTY);
        this.entityData.define(HOME_MODE, false);
        this.entityData.define(HOME_POS, java.util.Optional.empty());
        this.entityData.define(SHOW_HELD_ITEMS, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(1, new HideBehindOwnerGoal(this));
        this.goalSelector.addGoal(2, new PuffSpellCastGoal(this));  // stage 7: Goety / Iron's Spells (yields to melee/ranged when cooling)
        this.goalSelector.addGoal(2, new PuffRangedAttackGoal(this)); // bow / crossbow / trident
        this.goalSelector.addGoal(2, new PuffMeleeGoal(this));
        this.goalSelector.addGoal(2, new PuffLootrGoal(this));   // stage 6: her own Lootr share (soft dep)
        this.goalSelector.addGoal(3, new PuffSelfWorkGoal(this)); // self farm / lumber / fish / ranch
        // Follow owner whenever Home mode is off (independent of active work mode)
        this.goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.0D, 4.0F, 2.0F, false) {
            @Override
            public boolean canUse() {
                return !PuffEntity.this.isHomeMode() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PuffEntity.this.isHomeMode() && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(4, new PuffReturnHomeGoal(this));
        this.goalSelector.addGoal(5, new PuffHoardGoal(this));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        // Guard the owner (both goals already skip while ordered to sit)
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        this.targetSelector.addGoal(4, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                this, net.minecraft.world.entity.monster.Monster.class, 10, true, false,
                mob -> this.getWorkMode().isAggressiveCombat() && this.canFight()
                        && !(mob instanceof net.minecraft.world.entity.monster.Creeper)
                        && !(mob instanceof net.minecraft.world.entity.monster.Ghast)));
    }

    /** True when Puff is free and fed enough to fight. */
    public boolean canFight() {
        return this.isTame() && !this.isOrderedToSit() && !this.isResting() && !this.isPassenger()
                && this.getPuffHunger() > FIGHT_HUNGER_MIN;
    }

    /** Same rules as wolves: never creepers / ghasts, never the owner's other pets or PvP-protected players. */
    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        if (target instanceof net.minecraft.world.entity.monster.Creeper
                || target instanceof net.minecraft.world.entity.monster.Ghast) return false;
        if (target instanceof TamableAnimal pet && pet.isTame() && pet.getOwner() == owner) return false;
        if (target instanceof Player p && owner instanceof Player o && !o.canHarmPlayer(p)) return false;
        return !(target instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse && horse.isTamed());
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        net.minecraft.world.damagesource.DamageSource bridged = this.level().isClientSide ? null
                : com.puffcompanion.compat.CuriosCompat.attackSource(this);
        boolean hit = bridged != null ? this.hurtTargetAs(target, bridged) : super.doHurtTarget(target);
        if (hit) this.playSwingAnim();
        return hit;
    }

    /** Plays the swing animation on the server with a 12-tick debounce so Puff never spams swings. */
    public void playSwingAnim() {
        if (this.level().isClientSide) return;
        this.keepHeldItemsVisible(60);
        long now = this.level().getGameTime();
        if (now - this.lastSwingAnimTick < 12L) return;
        this.lastSwingAnimTick = now;
        this.triggerAnim(ACTION_CONTROLLER, ANIM_SWING);
    }

    /**
     * Vanilla {@code Mob#doHurtTarget} with a custom damage source (accessory bridge: caused by Puff's proxy
     * player, delivered by Puff). Afterwards the target's revenge / AI target is pointed back at Puff, so mobs
     * fight her, not the invisible proxy.
     */
    private boolean hurtTargetAs(net.minecraft.world.entity.Entity target, net.minecraft.world.damagesource.DamageSource source) {
        float damage = (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        float knockback = (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK);
        if (target instanceof LivingEntity living) {
            damage += net.minecraft.world.item.enchantment.EnchantmentHelper.getDamageBonus(this.getMainHandItem(), living.getMobType());
            knockback += net.minecraft.world.item.enchantment.EnchantmentHelper.getKnockbackBonus(this);
        }
        float crit = com.puffcompanion.compat.CuriosCompat.bridgeMeleePre(this, target);
        if (crit < 0) return false;   // an accessory cancelled the attack
        damage *= crit;
        int fire = net.minecraft.world.item.enchantment.EnchantmentHelper.getFireAspect(this);
        if (fire > 0) target.setSecondsOnFire(fire * 4);
        boolean hit = target.hurt(source, damage);
        com.puffcompanion.compat.CuriosCompat.flushProxy(this);
        if (target instanceof LivingEntity living) {
            living.setLastHurtByMob(this);
            if (living instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == source.getEntity()) mob.setTarget(this);
        }
        if (hit) {
            if (knockback > 0 && target instanceof LivingEntity living) {
                float rad = this.getYRot() * ((float) Math.PI / 180F);
                living.knockback(knockback * 0.5F, net.minecraft.util.Mth.sin(rad), -net.minecraft.util.Mth.cos(rad));
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D));
            }
            this.doEnchantDamageEffects(this, target);
            this.setLastHurtMob(target);
        }
        return hit;
    }

    /** Hurt lines: owner/players (friendly fire) vs monsters. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        // Owner friendly-fire guard (also covers the owner's arrows / tridents: getEntity() is the shooter)
        if (this.isOwnerGuard() && source.getEntity() instanceof Player p && this.isOwnedBy(p)) {
            return false;
        }
        if (!this.level().isClientSide && this.isTame() && com.puffcompanion.compat.CuriosCompat.isLoaded()) {
            amount = com.puffcompanion.compat.CuriosCompat.bridgeIncomingDamage(this, source, amount);
            if (amount < 0) return false;   // an accessory blocked the hit
        }
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && this.isAlive() && this.cooldownReady(PuffEventTriggers.EVENT_HURT)) {
            net.minecraft.world.entity.Entity attacker = source.getEntity();
            String key = attacker instanceof Player ? "puff.on_hurt.player"
                    : attacker instanceof LivingEntity ? "puff.on_hurt.mob" : null;
            if (key != null) {
                this.startCooldown(PuffEventTriggers.EVENT_HURT, PuffEventTriggers.CD_HURT);
                this.say(key, 2, 70, false, "");
            }
        }
        return hurt;
    }

    /**
     * A tamed Puff leaves a spirit cheese core instead of loose items: the full snapshot (nest, gear, curios,
     * stats, stories...) goes to the graveyard, so nothing else is dropped.
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        // Revive / totem accessories get the first word (replayed on her proxy player)
        if (!this.level().isClientSide && this.isTame() && com.puffcompanion.compat.CuriosCompat.bridgeDeath(this, source)) {
            this.dead = false;
            return;
        }
        if (!this.level().isClientSide && this.isTame() && !this.isRemoved() && this.level() instanceof ServerLevel sl) {
            com.puffcompanion.world.PuffGraveyard.get(sl.getServer()).record(this.getUUID(), PuffRevival.snapshot(this));
            ItemStack core = com.puffcompanion.item.CheeseCoreItem.create(this);
            if (this.getY() < sl.getMinBuildHeight() + 2 && this.getOwner() instanceof ServerPlayer owner) {
                PuffRevival.give(owner, core);   // void death: straight to the owner
            } else {
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                        sl, this.getX(), Math.max(this.getY(), sl.getMinBuildHeight() + 2) + 0.3, this.getZ(), core);
                drop.setUnlimitedLifetime();
                drop.setInvulnerable(true);
                sl.addFreshEntity(drop);
            }
            if (this.getOwner() instanceof Player owner) {
                com.puffcompanion.item.RatSpiritBellItem.markDead(owner, this.getUUID(), true);
                owner.displayClientMessage(Component.translatable("message.puffcompanion.core_dropped"), false);
            }
            // Everything now lives in the snapshot: empty the live inventories so nothing is duplicated
            this.hoard.clearContent();
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
            this.curioStore.clear();
        }
        super.die(source);
    }

    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        for (int i = 0; i < this.hoard.getContainerSize(); i++) {
            ItemStack stack = this.hoard.removeItemNoUpdate(i);
            if (!stack.isEmpty()) this.spawnAtLocation(stack);
        }
    }

    /** Melee attack that only runs while Puff is able to fight. */
    static class PuffMeleeGoal extends net.minecraft.world.entity.ai.goal.MeleeAttackGoal {
        private final PuffEntity puff;

        PuffMeleeGoal(PuffEntity puff) {
            super(puff, 1.3D, true);
            this.puff = puff;
        }

        @Override
        public boolean canUse() {
            return this.puff.canFight() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return this.puff.canFight() && super.canContinueToUse();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            if (this.panicTicks > 0) this.panicTicks--;
            if (this.castDisplayTicks > 0 && --this.castDisplayTicks == 0) {
                this.entityData.set(CAST_DISPLAY_STACK, ItemStack.EMPTY);
            }
            if (this.heldItemDisplayTicks > 0) this.heldItemDisplayTicks--;
            boolean activeHeld = this.heldItemDisplayTicks > 0
                    || this.castDisplayTicks > 0
                    || this.isAggressive()
                    || (this.getTarget() != null && this.getTarget().isAlive());
            if (this.entityData.get(SHOW_HELD_ITEMS) != activeHeld) {
                this.entityData.set(SHOW_HELD_ITEMS, activeHeld);
            }
            // Owner sneaks -> hop off the shoulder
            if (this.getVehicle() instanceof Player rider && rider.isShiftKeyDown()) {
                this.dismountFromShoulder(rider, true);
            }
            this.tickHunger();
            if (com.puffcompanion.story.PuffStoryManager.isInStorySession(this)) {
                this.getNavigation().stop();
                LivingEntity storyOwner = this.getOwner();
                if (storyOwner != null && storyOwner.level() == this.level()) {
                    this.getLookControl().setLookAt(storyOwner, 30.0F, 30.0F);
                }
            }
            if (this.tickCount % 20 == 0 && this.getHealth() < this.getMaxHealth() && this.progression.regenPerSecond() > 0) {
                this.heal((float) this.progression.regenPerSecond());
            }
            if (com.puffcompanion.compat.CuriosCompat.isLoaded()) {
                com.puffcompanion.compat.CuriosCompat.tick(this);   // accessories + armor through the proxy
            } else {
                PuffProxyBridge.tickArmorOnly(this);
            }
            this.checkDimensionArrival();
            if (this.isTame()) com.puffcompanion.item.RsDepositCharmItem.tickPuff(this);   // stage 6: RS deposit charm
            if (this.getOwner() != null && this.getOwner().level() == this.level()
                    && this.distanceToSqr(this.getOwner()) <= 16.0 * 16.0) {
                this.lastOwnerNearTime = this.level().getGameTime();
            }
            if (this.tickCount % PuffEventTriggers.CHECK_INTERVAL == 0) {
                PuffEventTriggers.tick(this);
            }
            // Prologue plays once Puff has an owner nearby (first bind, or next login if it was interrupted).
            // Wait ~2 s after spawning/loading so the owner's client already tracks this entity.
            if (this.tickCount > 40 && this.tickCount % 20 == 0 && this.isTame()) {
                com.puffcompanion.story.PuffStoryManager.autoTrigger(this);
            }
        } else if (this.bubbleText != null && this.tickCount >= this.bubbleEndTick) {
            this.bubbleText = null;
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity owner = this.getOwner();
        if (owner != null && !this.isOrderedToSit() && !this.getNavigation().isDone()) {
            // Hurry (run animation) when the owner gets far ahead (not in Home mode) or when fleeing a cat, otherwise walk
            boolean hurry = this.panicTicks > 0 || (!this.isHomeMode() && this.distanceToSqr(owner) > CATCH_UP_DIST_SQR);
            this.getNavigation().setSpeedModifier(hurry ? 1.7D : 1.0D);
        }
    }

    // ---------------- Shoulder riding ----------------

    /** Sit on the owner's right shoulder instead of the vanilla "on top of the head" seat. */
    @Override
    public void rideTick() {
        super.rideTick();
        if (this.getVehicle() instanceof Player player) {
            float yaw = player.yBodyRot * Mth.DEG_TO_RAD;
            double rx = -Mth.cos(yaw), rz = -Mth.sin(yaw);   // player's right-hand side
            this.setPos(player.getX() + rx * 0.42, player.getY() + player.getBbHeight() * 0.72, player.getZ() + rz * 0.42);
            this.setYRot(player.yBodyRot);
            this.yBodyRot = player.yBodyRot;
            this.setYHeadRot(player.getYHeadRot());
            this.fallDistance = 0.0F;
        }
    }

    /** Not targetable while on the owner's shoulder, so she never blocks the crosshair. */
    @Override
    public boolean isPickable() {
        return !(this.getVehicle() instanceof Player) && super.isPickable();
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        // Riding the owner: no suffocation / cramming damage from the owner's surroundings
        if (this.getVehicle() instanceof Player
                && (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
                || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING))) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    private void climbOnShoulder(Player player) {
        this.setResting(false);
        this.setOrderedToSit(false);
        this.setInSittingPose(false);
        this.getNavigation().stop();
        this.startRiding(player, true);
        syncPassengersToSelf(player);
    }

    /**
     * Vanilla only broadcasts a player's passenger list to OTHER players tracking them, never to the
     * player themself, so the owner's client would not know Puff is on their shoulder. Send it explicitly.
     */
    private static void syncPassengersToSelf(Player player) {
        if (player instanceof ServerPlayer sp) {
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(sp));
        }
    }

    private void dismountFromShoulder(Player player, boolean speak) {
        this.stopRiding();
        syncPassengersToSelf(player);
        Vec3 look = player.getLookAngle().multiply(1.0, 0.0, 1.0);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
        Vec3 spot = player.position().add(look.normalize().scale(1.2));
        this.moveTo(spot.x, player.getY(), spot.z, player.getYRot() + 180.0F, 0.0F);
        if (speak) this.say("puff.action.dismount", 1, 70, false, "");
    }

    // ---------------- Interaction ----------------

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!this.isTame()) {
            if (!stack.isEmpty()) return InteractionResult.PASS;
            if (!this.level().isClientSide) {
                // Test convenience (spawn egg / summon): an unowned Puff binds to whoever greets her first
                this.bindOwner(player);
                player.displayClientMessage(Component.translatable("message.puffcompanion.bound", this.getDisplayName()), true);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!this.isOwnedBy(player)) {
            return InteractionResult.PASS;
        }
        if (stack.getItem() instanceof com.puffcompanion.item.CurioSlotExpanderItem) {
            if (!this.level().isClientSide && player instanceof ServerPlayer sp) {
                com.puffcompanion.item.CurioSlotExpanderItem.openFor(sp, this);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (stack.getItem() instanceof com.puffcompanion.item.RatSpiritBellItem) {
            return InteractionResult.PASS;   // let the bell store her
        }
        if (PuffProgression.isTrainingItem(stack)) {
            if (!this.level().isClientSide) this.feedTraining(player, stack);
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (player.isShiftKeyDown() && stack.isEmpty()) {
            if (!this.level().isClientSide) this.pet(player);       // quick head pat (stage 2A)
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (this.level().isClientSide) {
            // Empty hand or holding an item: open the interaction menu
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.puffcompanion.client.ClientHooks.openInteractionScreen(this));
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    /**
     * Server-side handler for menu buttons. The packet handler has already checked: sender is the owner,
     * Puff is alive and within {@link #INTERACT_RANGE} (or riding the sender).
     */
    /** Executes a natural-language action command parsed from free AI chat. */
    private void executeAiCommand(ServerPlayer player, String rawCmd) {
        if (rawCmd == null || rawCmd.isBlank()) return;
        String cmd = rawCmd.trim().toLowerCase(java.util.Locale.ROOT);
        if (cmd.startsWith("outfit:")) {
            String outfitId = cmd.substring("outfit:".length()).trim();
            PuffOutfit outfit = PuffOutfit.byId(outfitId);
            if (outfit != null && this.isOutfitUnlocked(outfit)) {
                this.setCurrentOutfit(outfit.id());
            }
            return;
        }
        switch (cmd) {
            case "lumber", "farm", "fish", "ranch", "follow", "patrol", "spell", "ranged" -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                this.setResting(false);
                this.setOrderedToSit(false);
                this.setInSittingPose(false);
                PuffWorkMode mode = PuffWorkMode.byId(cmd);
                if (mode != null) {
                    if (mode == PuffWorkMode.FOLLOW_GUARD) this.setHomeMode(false, null);
                    this.setWorkMode(mode);
                    player.displayClientMessage(
                            Component.literal("✦ ").append(this.getDisplayName()).append(" → ")
                                    .append(Component.translatable(mode.langKey())), true);
                }
            }
            case "sit", "wait" -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                this.setResting(false);
                this.setOrderedToSit(true);
                this.setInSittingPose(true);
            }
            case "rest", "sleep" -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                this.setResting(true);
                this.setOrderedToSit(true);
                this.setInSittingPose(true);
            }
            case "shoulder" -> {
                if (this.getVehicle() == player) {
                    this.dismountFromShoulder(player, false);
                } else if (player.getPassengers().isEmpty()) {
                    this.setResting(false);
                    this.setOrderedToSit(false);
                    this.setInSittingPose(false);
                    this.startRiding(player, true);
                }
            }
            case "home" -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                this.setResting(false);
                this.setOrderedToSit(false);
                this.setInSittingPose(false);
                net.minecraft.core.BlockPos home = this.blockPosition();
                this.setHomeMode(true, home);
                player.displayClientMessage(Component.translatable("message.puffcompanion.home.on",
                        home.getX(), home.getY(), home.getZ()), true);
            }
            default -> {}
        }
    }

    public void handleInteraction(ServerPlayer player, PuffInteractionPacket.Action action, String arg) {
        switch (action) {
            case AI_COMMAND -> this.executeAiCommand(player, arg);
            case TOPIC -> {
                PuffTopic topic = PuffTopic.byId(arg);
                if (topic != null && topic.isUnlocked(this)) {
                    if (topic == PuffTopic.SCENE) {
                        PuffSceneDialogue.speakAboutSurroundings(this, player);
                    } else {
                        String expr = switch (topic) {
                            case CAT -> PuffExpression.PANIC_FEAR;
                            case FUTURE -> PuffExpression.OVERHEAT_SHY;
                            case RATS -> PuffExpression.BEAM;
                            default -> PuffExpression.SMILE;
                        };
                        String anim = topic == PuffTopic.CAT ? ANIM_CAT_PANIC
                                : (topic == PuffTopic.RATS ? ANIM_COMMAND_RATS : ANIM_WAVE);
                        this.say(topic.lineBaseKey(), 1, 220, false, anim, expr, PuffExpression.defaultEarFor(expr));
                    }
                }
            }
            case GIFT -> this.receiveGift(player);
            case TOGGLE_FOLLOW -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                // Waiting/resting -> follow; following (or on the shoulder) -> wait here
                boolean wait = !this.isOrderedToSit();
                this.setResting(false);
                this.setOrderedToSit(wait);
                this.setInSittingPose(wait);
                this.getNavigation().stop();
                this.say(wait ? "puff.action.stay" : "puff.action.follow", 2, 80, false,
                        wait ? ANIM_BOW : ANIM_WAVE,
                        wait ? PuffExpression.CLOSED_SMILE : PuffExpression.BEAM,
                        wait ? PuffExpression.EAR_CURIOUS : PuffExpression.EAR_ALERT);
            }
            case TOGGLE_REST -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                if (this.isResting()) {
                    this.setResting(false);
                    this.setOrderedToSit(false);
                    this.setInSittingPose(false);
                    this.say("puff.action.stand", 1, 70, false, ANIM_STRETCH,
                            PuffExpression.YAWN, PuffExpression.EAR_CURIOUS);
                } else {
                    this.setResting(true);
                    this.setOrderedToSit(true);
                    this.getNavigation().stop();
                    this.say("puff.action.lie", 2, 90, false, ANIM_GROUND_REST,
                            PuffExpression.SLEEP_DROOL, PuffExpression.EAR_FOLDED);
                }
            }
            case SHOULDER -> {
                if (this.getVehicle() == player) {
                    this.dismountFromShoulder(player, true);
                } else if (!this.isPassenger() && !player.isPassenger()) {
                    this.climbOnShoulder(player);
                    this.say("puff.action.ride", 2, 90, false, ANIM_SHOULDER_SIT,
                            PuffExpression.BEAM, PuffExpression.EAR_ALERT);
                }
            }
            case PET -> this.pet(player);
            case HOARD -> this.openMaidPanel(player);
            case TOGGLE_GUARD -> this.setOwnerGuard(!this.isOwnerGuard());
            case TOGGLE_SPELL -> this.setSpellCastEnabled(!this.isSpellCastEnabled());
            case CYCLE_MODE -> {
                PuffWorkMode next = "prev".equals(arg) ? this.getWorkMode().prev() : this.getWorkMode().next();
                this.setWorkMode(next);
            }
            case TOGGLE_HOME -> {
                if (this.getVehicle() == player) this.dismountFromShoulder(player, false);
                boolean enable = !this.isHomeMode();
                if (enable) {
                    this.setResting(false);
                    this.setOrderedToSit(false);
                    this.setInSittingPose(false);
                    net.minecraft.core.BlockPos home = this.blockPosition();
                    this.setHomeMode(true, home);
                    player.displayClientMessage(Component.translatable("message.puffcompanion.home.on",
                            home.getX(), home.getY(), home.getZ()), true);
                    this.say("puff.action.home_on", 1, 80, false, "");
                } else {
                    this.setHomeMode(false, null);
                    player.displayClientMessage(Component.translatable("message.puffcompanion.home.off"), true);
                    this.say("puff.action.home_off", 1, 80, false, "");
                }
            }
            case TOGGLE_LOOTR -> {
                this.squad.autoLootr = !this.squad.autoLootr;
                this.squad.lootrTarget = null;
            }
            case PROPOSE -> this.propose(player);
            case STORY_REPLAY -> {
                String key = com.puffcompanion.story.PuffStoryManager.resolveKey(arg);
                var story = key != null ? com.puffcompanion.story.PuffStoryManager.get(key) : null;
                if (story != null && this.hasCompletedStory(story.id())) {
                    com.puffcompanion.story.PuffStoryManager.start(player, this, key);
                }
            }
            case OUTFIT -> {
                PuffOutfit outfit = PuffOutfit.byId(arg);
                if (outfit != null && this.isOutfitUnlocked(outfit) && !outfit.id().equals(this.getCurrentOutfit())) {
                    this.setCurrentOutfit(outfit.id());
                    this.say(outfit.lineBaseKey(), 1, 100, false, "");
                }
            }
            case CALL_MODE -> {
                this.setCallMode(CALL_SISTER.equals(this.getCallMode()) ? CALL_BROTHER : CALL_SISTER);
            }
        }
    }

    /** Takes one item from the owner's main hand and reacts according to its gift tier. */
    private void receiveGift(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        // Giving a proposal token when Puff is ready starts the finale instead of a normal gift
        if (com.puffcompanion.story.PuffStoryManager.isProposalToken(stack) && this.getGrowthStage() == 3
                && this.getAffection() >= 100 && !this.hasCompletedStory(PuffStories.FINALE.id())) {
            this.propose(player);
            return;
        }
        if (stack.isEmpty()) return;                       // server-side check: really holding something
        PuffGifts.Tier tier = PuffGifts.classify(stack);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        long day = this.level().getDayTime() / 24000L;
        if (day != this.giftAffectionDay) {
            this.giftAffectionDay = day;
            this.giftAffectionGained = 0;
        }
        int gain = Math.min(tier.affection, GIFT_AFFECTION_DAILY_CAP - this.giftAffectionGained);
        if (gain > 0) {
            this.giftAffectionGained += gain;
            this.setAffection(this.getAffection() + gain);
        } else if (tier.affection > 0) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.gift_capped",
                    GIFT_AFFECTION_DAILY_CAP), true);
        }
        if (tier.hunger > 0) this.setPuffHunger(this.getPuffHunger() + tier.hunger);
        if (tier.hearts > 0 && this.level() instanceof ServerLevel level) {
            PuffEventTriggers.hearts(level, this, tier.hearts);
        }
        if (tier == PuffGifts.Tier.BEST) this.consumePaydayBonus(player);
        String giftAnim = tier == PuffGifts.Tier.BEST ? ANIM_EAT_CHEESE
                : (tier == PuffGifts.Tier.GOOD ? ANIM_HOARD_PICKUP : ANIM_CHECK_CUFF);
        String giftExpr = tier == PuffGifts.Tier.BEST ? PuffExpression.CHEESE_DROOL
                : (tier == PuffGifts.Tier.GOOD ? PuffExpression.STARRY : PuffExpression.SMILE);
        this.say(tier.langBase, tier.variants, 110, false, giftAnim, giftExpr, PuffExpression.defaultEarFor(giftExpr));
    }

    /** Head pat (Shift + empty-hand right click, or the menu button): shy freeze. */
    void pet(Player player) {
        if (!this.cooldownReady(PuffEventTriggers.EVENT_PET)) return;
        this.startCooldown(PuffEventTriggers.EVENT_PET, PuffEventTriggers.CD_PET);

        long day = this.level().getDayTime() / 24000L;
        if (day != this.petAffectionDay) {
            this.petAffectionDay = day;
            this.petAffectionGained = 0;
        }
        if (this.petAffectionGained < PET_AFFECTION_DAILY_CAP) {
            int gain = Math.min(PET_AFFECTION_GAIN, PET_AFFECTION_DAILY_CAP - this.petAffectionGained);
            this.petAffectionGained += gain;
            this.setAffection(this.getAffection() + gain);
        }
        if (this.level() instanceof ServerLevel level) {
            PuffEventTriggers.hearts(level, this, 7);
        }
        this.getNavigation().stop();
        this.getLookControl().setLookAt(player, 30.0F, 30.0F);
        int stage = Mth.clamp(this.getGrowthStage(), 1, 4);
        this.consumePaydayBonus(player);
        String petExpr = stage >= 3 ? PuffExpression.BLISS_BOGGLE : PuffExpression.OVERHEAT_SHY;
        this.say(PuffEventTriggers.EVENT_PET + ".stage" + stage, 3, 90, false,
                stage >= 4 ? ANIM_VOW_SHY : ANIM_SHY, petExpr, PuffExpression.EAR_FOLDED);
    }

    /** Tames this Puff to the given player (sets owner + tamed flag) and makes her persistent. */
    public void bindOwner(Player player) {
        this.tame(player);
        this.setOrderedToSit(false);
        this.setInSittingPose(false);
        this.setPersistenceRequired();
    }

    // ---------------- Speech (server side) ----------------

    /**
     * Picks a random variant {@code baseKey.0 .. baseKey.(variants-1)} and sends it as a bubble to every
     * client tracking this Puff. The owner's client additionally prints it to chat when {@code toChat}.
     */
    public void say(String baseKey, int variants, int durationTicks, boolean toChat, String animation) {
        this.say(baseKey, variants, durationTicks, toChat, animation, "", "");
    }

    public void say(String baseKey, int variants, int durationTicks, boolean toChat,
                    String animation, String expression, String ear) {
        if (this.level().isClientSide || com.puffcompanion.story.PuffStoryManager.isInStorySession(this)) return;
        LivingEntity owner = this.getOwner();
        String ownerName = owner != null ? owner.getName().getString() : "";
        String key = baseKey + "." + this.random.nextInt(Math.max(1, variants));
        this.lastAutoBubbleTime = this.level().getGameTime();
        PuffNetwork.sendToTracking(this, new PuffBubblePacket(
                this.getId(), key, durationTicks, toChat, animation, ownerName, expression, ear));
    }

    /** Sends an exact translation key (without appending a random index) with expression/ear/anim. */
    public void sayExact(String exactLangKey, int durationTicks, boolean toChat,
                         String animation, String expression, String ear) {
        if (this.level().isClientSide || com.puffcompanion.story.PuffStoryManager.isInStorySession(this)) return;
        LivingEntity owner = this.getOwner();
        String ownerName = owner != null ? owner.getName().getString() : "";
        this.lastAutoBubbleTime = this.level().getGameTime();
        PuffNetwork.sendToTracking(this, new PuffBubblePacket(
                this.getId(), exactLangKey, durationTicks, toChat, animation, ownerName, expression, ear));
    }

    @Nullable
    public String getLastBiomeId() { return this.lastBiomeId; }

    public void setLastBiomeId(@Nullable String biomeId) { this.lastBiomeId = biomeId; }

    public boolean isCooldownReady(String event) { return this.cooldownReady(event); }

    public void markCooldown(String event, int ticks) { this.startCooldown(event, ticks); }

    public boolean isGlobalBubbleReady() { return this.globalGapPassed(); }

    boolean cooldownReady(String event) {
        return this.level().getGameTime() >= this.cooldowns.getOrDefault(event, Long.MIN_VALUE);
    }

    void startCooldown(String event, int ticks) {
        this.cooldowns.put(event, this.level().getGameTime() + ticks);
    }

    boolean globalGapPassed() {
        return this.level().getGameTime() - this.lastAutoBubbleTime >= PuffEventTriggers.GLOBAL_GAP;
    }

    void startPanic() {
        if (!this.isPassenger()) this.panicTicks = PANIC_TICKS;
    }

    public boolean isPanicking() {
        return this.panicTicks > 0;
    }

    // ---------------- Stage 3: survival / Rats systems (server side) ----------------

    /** Hunger drops 1 point per 30 s while active, half as fast while sitting, resting or on the shoulder. */
    private void tickHunger() {
        boolean idle = this.isOrderedToSit() || this.isResting() || this.isPassenger();
        this.hungerTicker += idle ? 1 : 2;
        if (this.hungerTicker >= HUNGER_TICKS_PER_POINT * 2) {
            this.hungerTicker = 0;
            if (this.getPuffHunger() > 0) this.setPuffHunger(this.getPuffHunger() - 1);
        }
        if (this.tickCount % 20 == 0 && this.getSpellMana() < this.getMaxSpellMana()) {
            int regen = this.getPuffHunger() > FIGHT_HUNGER_MIN ? (6 + 2 * this.getGrowthStage()) : 2;
            this.setSpellMana(this.getSpellMana() + regen);
        }
    }

    /** Says the arrival line once whenever Puff starts ticking in a different dimension. */
    private void checkDimensionArrival() {
        String dim = this.level().dimension().location().toString();
        if (dim.equals(this.lastDimension)) return;
        boolean first = this.lastDimension == null;
        this.lastDimension = dim;
        if (!first) this.onDimensionEntered();
    }

    void onDimensionEntered() {
        String key = PuffEventTriggers.dimensionLine(this.level().dimension());
        if (key != null) {
            this.addStoryFlag(PuffStories.FLAG_VISITED_DANGER_DIM);   // chapter 3 condition
            this.say(key, 1, 140, true, PuffEntity.ANIM_ALERT);
        }
    }

    /** Following (not told to stay) and was near the owner within the last 10 s. */
    public boolean willFollowAcrossPortal() {
        return this.isTame() && !this.isOrderedToSit() && !this.isResting()
                && this.level().getGameTime() - this.lastOwnerNearTime <= 200;
    }

    /** Moves Puff into the owner's new dimension, right next to the owner. */
    public void followOwnerToDimension(ServerPlayer owner, ServerLevel to) {
        if (this.isPassenger()) this.stopRiding();
        Vec3 target = owner.position();
        float yaw = owner.getYRot();
        this.changeDimension(to, new net.minecraftforge.common.util.ITeleporter() {
            @Override
            public net.minecraft.world.level.portal.PortalInfo getPortalInfo(net.minecraft.world.entity.Entity entity,
                    ServerLevel destWorld, java.util.function.Function<ServerLevel, net.minecraft.world.level.portal.PortalInfo> defaultPortalInfo) {
                return new net.minecraft.world.level.portal.PortalInfo(target, Vec3.ZERO, yaw, 0.0F);
            }

            @Override
            public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceWorld, ServerLevel destWorld) {
                return false;
            }
        });
    }

    /** Rats payday bubble fired: for the next 30 s a pat or cheese gift pays an extra +2 affection. */
    void openPaydayWindow(long now) {
        this.paydayUntil = now + PAYDAY_WINDOW_TICKS;
    }

    /** Consumes the payday bonus if it is still open. */
    private boolean consumePaydayBonus(Player player) {
        if (this.level().getGameTime() > this.paydayUntil) return false;
        this.paydayUntil = Long.MIN_VALUE;
        this.setAffection(this.getAffection() + PAYDAY_BONUS);
        player.displayClientMessage(Component.translatable("message.puffcompanion.payday_bonus", PAYDAY_BONUS), true);
        return true;
    }

    /** True (and remembered) the first time {@code event} is claimed on {@code day}. */
    boolean claimDailyEvent(String event, long day) {
        Long last = this.dailyEvents.get(event);
        if (last != null && last == day) return false;
        this.dailyEvents.put(event, day);
        return true;
    }

    /** Tracks how long the owner has stood (almost) still. */
    void trackOwnerStillness(Player owner, long now) {
        Vec3 pos = owner.position();
        if (this.ownerStillPos == null || this.ownerStillPos.distanceToSqr(pos) > 0.3 * 0.3) {
            this.ownerStillPos = pos;
            this.ownerStillSince = now;
        }
    }

    long ownerStillFor(long now) {
        return this.ownerStillPos == null ? 0 : now - this.ownerStillSince;
    }

    public PuffProgression getProgression() { return this.progression; }
    public PuffCurioStore getCurioStore() { return this.curioStore; }
    public Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> getAppliedCurioModifiers() {
        return this.appliedCurioModifiers;
    }
    public Map<UUID, ItemStack> getCurioModifierSources() { return this.curioModifierSources; }
    public PuffSquad getSquad() { return this.squad; }
    public Map<String, Integer> getCurioSlotBonus() { return this.curioSlotBonus; }
    /** Attribute modifiers accessory / armor code put on her proxy player and that were passed on to her. */
    public Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> getProxyAttributeModifiers() {
        return this.proxyAttributeModifiers;
    }
    public int getCurioSlotBonus(String type) { return this.curioSlotBonus.getOrDefault(type, 0); }
    /** Changes the expander bonus of one slot type (never below 0). Returns the new bonus. */
    public int addCurioSlotBonus(String type, int delta) {
        int v = Math.max(0, Math.min(64, this.getCurioSlotBonus(type) + delta));
        if (v == 0) this.curioSlotBonus.remove(type);
        else this.curioSlotBonus.put(type, v);
        return v;
    }
    public CompoundTag getCurioSlotState() { return this.curioSlotState; }
    public void setCurioSlotState(CompoundTag tag) { this.curioSlotState = tag; }
    public Map<String, Integer> getCurioShrinkTicks() { return this.curioShrinkTicks; }

    /** Puts a stack into the secret nest; whatever does not fit drops at her feet. Never deletes items. */
    public void stashOrDrop(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack rest = this.hoard.addItem(stack);
        if (!rest.isEmpty() && !this.level().isClientSide) this.spawnAtLocation(rest);
    }
    public boolean isOwnerGuard() { return this.entityData.get(OWNER_GUARD); }
    public void setOwnerGuard(boolean on) { this.entityData.set(OWNER_GUARD, on); }

    public int getMaxSpellMana() {
        return switch (Mth.clamp(this.getGrowthStage(), 1, 4)) {
            case 2 -> 150;
            case 3 -> 220;
            case 4 -> 300;
            default -> 100;
        };
    }

    public int getSpellMana() { return this.entityData.get(SPELL_MANA); }
    public void setSpellMana(int mana) { this.entityData.set(SPELL_MANA, Mth.clamp(mana, 0, this.getMaxSpellMana())); }
    public void consumeSpellMana(int cost) { this.setSpellMana(this.getSpellMana() - Math.max(0, cost)); }

    public boolean isSpellCastEnabled() { return this.entityData.get(SPELL_CAST_ENABLED); }
    public void setSpellCastEnabled(boolean on) { this.entityData.set(SPELL_CAST_ENABLED, on); }

    public boolean isSpellOnCooldown(String key, long now) {
        Long until = this.spellCooldowns.get(key);
        return until != null && now < until;
    }

    public void setSpellCooldown(String key, long readyTick) {
        this.spellCooldowns.put(key, readyTick);
    }

    public PuffWorkMode getWorkMode() {
        PuffWorkMode mode = PuffWorkMode.byId(this.entityData.get(WORK_MODE));
        return mode != null ? mode : PuffWorkMode.FOLLOW_GUARD;
    }

    public void setWorkMode(PuffWorkMode mode) {
        this.entityData.set(WORK_MODE, mode != null ? mode.id() : PuffWorkMode.FOLLOW_GUARD.id());
    }

    public ItemStack getCastDisplayStack() {
        return this.entityData.get(CAST_DISPLAY_STACK);
    }

    public void showCastDisplayStack(ItemStack stack, int durationTicks) {
        if (this.level().isClientSide || stack.isEmpty()) return;
        this.entityData.set(CAST_DISPLAY_STACK, stack.copyWithCount(1));
        this.castDisplayTicks = Math.max(durationTicks, 10);
        this.keepHeldItemsVisible(Math.max(durationTicks, 40));
    }

    private int heldItemDisplayTicks;

    public boolean shouldShowHeldItems() {
        return this.entityData.get(SHOW_HELD_ITEMS);
    }

    public void keepHeldItemsVisible(int ticks) {
        if (this.level().isClientSide) return;
        this.heldItemDisplayTicks = Math.max(this.heldItemDisplayTicks, ticks);
        if (!this.entityData.get(SHOW_HELD_ITEMS)) {
            this.entityData.set(SHOW_HELD_ITEMS, true);
        }
    }

    public boolean isHomeMode() {
        return this.entityData.get(HOME_MODE);
    }

    @Nullable
    public net.minecraft.core.BlockPos getHomePos() {
        return this.entityData.get(HOME_POS).orElse(null);
    }

    public void setHomeMode(boolean on, @Nullable net.minecraft.core.BlockPos pos) {
        if (on) {
            net.minecraft.core.BlockPos home = pos != null ? pos : this.blockPosition();
            this.entityData.set(HOME_POS, java.util.Optional.of(home));
            this.entityData.set(HOME_MODE, true);
            this.setResting(false);
            this.setOrderedToSit(false);
            this.setInSittingPose(false);
            this.restrictTo(home, HOME_RADIUS);
        } else {
            this.entityData.set(HOME_MODE, false);
            this.clearRestriction();
        }
    }

    /** Server: the proxy player for player-only integrations (recreated after a dimension change). */
    @Nullable
    public PuffFakePlayer getFakePlayer() {
        if (!(this.level() instanceof ServerLevel)) return null;
        if (this.fakePlayer == null || this.fakePlayer.level() != this.level()) {
            try {
                this.fakePlayer = PuffFakePlayer.create(this);
                this.fakePlayer.getPersistentData().merge(this.proxyPersistentData);
            } catch (Throwable t) {
                return null;
            }
        }
        return this.fakePlayer;
    }

    /** Rats rare candy (levels) or gene treat (gene + wider cap). Consumes one unless creative. */
    private void feedTraining(Player player, ItemStack stack) {
        int candy = PuffProgression.candyLevels(stack);
        boolean ok;
        if (candy > 0) {
            int gained = this.progression.eatCandy(candy);
            ok = gained > 0;
            player.displayClientMessage(ok
                    ? Component.translatable("message.puffcompanion.train.level", this.progression.level(), this.progression.levelCap())
                    : Component.translatable("message.puffcompanion.train.level_cap", this.progression.levelCap()), true);
        } else {
            PuffProgression.Gene gene = PuffProgression.treatGene(stack);
            float value = gene == null ? -1 : this.progression.eatTreat(gene);
            ok = value > 0;
            player.displayClientMessage(ok
                    ? Component.translatable("message.puffcompanion.train.gene",
                    Component.translatable("puff.gene." + gene.key()), String.format(java.util.Locale.ROOT, "%.2f", value),
                    this.progression.levelCap())
                    : Component.translatable("message.puffcompanion.train.gene_max"), true);
        }
        if (!ok) return;
        if (!player.getAbilities().instabuild) stack.shrink(1);
        this.progression.apply(this);
        if (this.level() instanceof ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getY() + 1.2, this.getZ(),
                    12, 0.4, 0.5, 0.4, 0.02);
            sl.sendParticles(PuffRevival.GOLD_DUST, this.getX(), this.getY() + 1.4, this.getZ(), 10, 0.4, 0.4, 0.4, 0.02);
        }
        this.say("puff.train", 3, 90, false, ANIM_SHY);
    }

    public net.minecraft.world.SimpleContainer getHoard() {
        return this.hoard;
    }

    /** Called by {@link PuffHoardGoal} after tucking an item away. */
    void onHoardPickup() {
        if (this.cooldownReady(PuffEventTriggers.EVENT_HOARD)) {
            this.startCooldown(PuffEventTriggers.EVENT_HOARD, PuffEventTriggers.CD_HOARD);
            this.say(PuffEventTriggers.EVENT_HOARD, 3, 90, false, "");
        }
    }

    /** Opens the 9-slot nest as a chest UI for the owner; Puff grumbles shyly. */
    private void openMaidPanel(ServerPlayer player) {
        var layout = com.puffcompanion.compat.CuriosCompat.layoutFor(this);
        this.curioStore.ensureLayout(layout);
        net.minecraftforge.network.NetworkHooks.openScreen(player, new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new com.puffcompanion.menu.PuffMaidMenu(id, inv, this, layout),
                        Component.translatable("container.puffcompanion.hoard")),
                buf -> com.puffcompanion.menu.PuffMaidMenu.writeOpenData(buf, this.getId(), layout));
        this.say("puff.hoard.open", 2, 90, false, ANIM_SHY);
    }

    /** Proposal from the menu button or by gifting a token. */
    private void propose(ServerPlayer player) {
        var result = com.puffcompanion.story.PuffStoryManager.tryPropose(player, this);
        if (result != com.puffcompanion.story.PuffStoryManager.ProposeResult.STARTED) {
            player.displayClientMessage(com.puffcompanion.story.PuffStoryManager.proposeMessage(result), true);
        }
    }

    /** Equipment changed from the maid panel: player-given gear always drops on death, never vanishes. */
    public void setPanelEquipment(net.minecraft.world.entity.EquipmentSlot slot, ItemStack stack) {
        this.setItemSlot(slot, stack);
        this.setGuaranteedDrop(slot);
    }

    // ---------------- Speech (client side) ----------------

    public void showBubble(String text, int durationTicks) {
        this.bubbleText = text;
        this.bubbleStartTick = this.tickCount;
        this.bubbleEndTick = this.tickCount + Math.max(20, durationTicks);
    }

    public void clearBubble() {
        this.bubbleText = null;
        this.bubbleEndTick = 0;
    }

    /** Smoothly turns Puff's head and body toward {@code target} on the client during dialogue/interaction. */
    public void faceEntityClient(@Nullable net.minecraft.world.entity.Entity target) {
        if (target == null || target == this || this.getVehicle() == target) return;
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double dy = target.getEyeY() - this.getEyeY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4D) return;
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float targetPitch = Mth.clamp((float) (-(Mth.atan2(dy, horiz) * (180.0D / Math.PI))), -35.0F, 35.0F);

        float nextHead = Mth.rotLerp(0.35F, this.yHeadRot, targetYaw);
        float nextBody = Mth.rotLerp(0.22F, this.yBodyRot, targetYaw);
        float nextPitch = Mth.lerp(0.35F, this.getXRot(), targetPitch);
        this.yHeadRot = nextHead;
        this.yHeadRotO = nextHead;
        this.yBodyRot = nextBody;
        this.yBodyRotO = nextBody;
        this.setYRot(nextBody);
        this.yRotO = nextBody;
        this.setXRot(nextPitch);
        this.xRotO = nextPitch;
    }

    public void setDialogueExpression(@Nullable String expression, @Nullable String ear) {
        this.dialogueExpression = expression;
        this.dialogueEar = ear;
    }

    public void clearDialogueExpression() {
        this.dialogueExpression = null;
        this.dialogueEar = null;
    }

    public void setTempExpression(@Nullable String expression, @Nullable String ear, int durationTicks) {
        this.tempExpression = expression;
        this.tempEar = ear;
        this.tempExpressionEndTick = this.tickCount + Math.max(20, durationTicks);
    }

    /** Resolves which of the 24 expressions to render on the head mesh this frame. */
    public String resolveRenderExpression() {
        if (this.dialogueExpression != null) return this.dialogueExpression;
        if (this.tempExpression != null && this.tickCount < this.tempExpressionEndTick) {
            return this.tempExpression;
        }
        if (this.isResting()) return PuffExpression.SLEEP_DROOL;
        if (this.isPanicking()) return PuffExpression.PANIC_FEAR;
        if (this.hurtTime > 0) return PuffExpression.TEARY;
        if (this.getPuffHunger() <= PuffEventTriggers.HUNGRY_THRESHOLD) return PuffExpression.HUNGRY_DRAINED;
        int blinkPhase = (this.tickCount + this.getId() * 17) % 100;
        if (blinkPhase >= 96) return PuffExpression.BLINK;
        return this.getAffection() >= 70 ? PuffExpression.SMILE : PuffExpression.NEUTRAL;
    }

    /** Resolves which of the 6 ear mood shapes to render on the ear mesh this frame. */
    public String resolveRenderEar() {
        if (this.dialogueEar != null) return this.dialogueEar;
        if (this.tempEar != null && this.tickCount < this.tempExpressionEndTick) {
            return this.tempEar;
        }
        if (this.isResting()) return PuffExpression.EAR_FOLDED;
        if (this.isPanicking()) return PuffExpression.EAR_AIRPLANE;
        if (this.hurtTime > 0) return PuffExpression.EAR_DROOPING;
        if (this.getPuffHunger() <= PuffEventTriggers.HUNGRY_THRESHOLD) return PuffExpression.EAR_DROOPING;
        if (this.getTarget() != null) return PuffExpression.EAR_ALERT;
        return PuffExpression.EAR_REST;
    }

    @Nullable
    public String getBubbleText() {
        return this.bubbleText;
    }

    /** Fade in over 4 ticks, out over the last 8. */
    public float getBubbleAlpha(float partialTick) {
        if (this.bubbleText == null) return 0.0F;
        float t = this.tickCount + partialTick;
        float in = (t - this.bubbleStartTick) / 4.0F;
        float out = (this.bubbleEndTick - t) / 8.0F;
        return Mth.clamp(Math.min(in, out), 0.0F, 1.0F);
    }

    // ---------------- Persistence ----------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); // TamableAnimal writes "Owner" + "Sitting"
        UUID owner = this.getOwnerUUID();
        if (owner != null) {
            tag.putUUID(TAG_OWNER_UUID, owner);
        }
        tag.putInt(TAG_AFFECTION, this.getAffection());
        tag.putInt(TAG_GROWTH_STAGE, this.getGrowthStage());
        tag.putString(TAG_CALL_MODE, this.getCallMode());
        tag.putString(TAG_CURRENT_OUTFIT, this.getCurrentOutfit());
        tag.putInt(TAG_PUFF_HUNGER, this.getPuffHunger());
        ListTag stories = new ListTag();
        for (String id : this.completedStories) {
            stories.add(StringTag.valueOf(id));
        }
        tag.put(TAG_COMPLETED_STORIES, stories);
        ListTag flags = new ListTag();
        for (String f : this.storyFlags) {
            flags.add(StringTag.valueOf(f));
        }
        tag.put(TAG_STORY_FLAGS, flags);
        ListTag outfits = new ListTag();
        for (PuffOutfit o : PuffOutfit.values()) {
            if (this.isOutfitUnlocked(o)) outfits.add(StringTag.valueOf(o.id()));
        }
        tag.put(TAG_UNLOCKED_OUTFITS, outfits);
        tag.putBoolean(TAG_RESTING, this.isResting());
        tag.putLong(TAG_PET_DAY, this.petAffectionDay);
        tag.putInt(TAG_PET_GAINED, this.petAffectionGained);
        tag.putLong(TAG_GIFT_DAY, this.giftAffectionDay);
        tag.putInt(TAG_GIFT_GAINED, this.giftAffectionGained);
        tag.put(TAG_HOARD, this.hoard.createTag());
        tag.put("Progression", this.progression.save());
        tag.put("PuffCurios", this.curioStore.save());
        CompoundTag bonus = new CompoundTag();
        this.curioSlotBonus.forEach(bonus::putInt);
        tag.put("CurioSlotBonus", bonus);
        tag.put("CurioSlotState", this.curioSlotState.copy());
        tag.put("Squad", this.squad.save());
        if (this.fakePlayer != null) this.proxyPersistentData = this.fakePlayer.getPersistentData().copy();
        tag.put("ProxyData", this.proxyPersistentData.copy());
        tag.putBoolean("OwnerFriendlyFireGuard", this.isOwnerGuard());
        tag.putInt("SpellMana", this.getSpellMana());
        tag.putBoolean("SpellCastEnabled", this.isSpellCastEnabled());
        tag.putString("WorkMode", this.getWorkMode().id());
        tag.putBoolean("HomeMode", this.isHomeMode());
        net.minecraft.core.BlockPos home = this.getHomePos();
        if (home != null) tag.putLong("HomePos", home.asLong());
        CompoundTag daily = new CompoundTag();
        this.dailyEvents.forEach(daily::putLong);
        tag.put(TAG_DAILY_EVENTS, daily);
        if (this.lastDimension != null) tag.putString(TAG_LAST_DIMENSION, this.lastDimension);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Fallback in case "Owner" is missing but our explicit field exists
        if (this.getOwnerUUID() == null && tag.hasUUID(TAG_OWNER_UUID)) {
            this.setOwnerUUID(tag.getUUID(TAG_OWNER_UUID));
            this.setTame(true);
        }
        if (tag.contains(TAG_AFFECTION, Tag.TAG_INT)) this.setAffection(tag.getInt(TAG_AFFECTION));
        if (tag.contains(TAG_GROWTH_STAGE, Tag.TAG_INT)) this.setGrowthStage(tag.getInt(TAG_GROWTH_STAGE));
        if (tag.contains(TAG_CALL_MODE, Tag.TAG_STRING)) this.setCallMode(tag.getString(TAG_CALL_MODE));
        if (tag.contains(TAG_PUFF_HUNGER, Tag.TAG_INT)) this.setPuffHunger(tag.getInt(TAG_PUFF_HUNGER));

        int mask = PuffOutfit.defaultMask();
        if (tag.contains(TAG_UNLOCKED_OUTFITS, Tag.TAG_LIST)) {
            ListTag outfits = tag.getList(TAG_UNLOCKED_OUTFITS, Tag.TAG_STRING);
            for (int i = 0; i < outfits.size(); i++) {
                PuffOutfit o = PuffOutfit.byId(outfits.getString(i));
                if (o != null) mask |= o.bit();
            }
        }
        this.completedStories.clear();
        int doneMask = 0;
        ListTag stories = tag.getList(TAG_COMPLETED_STORIES, Tag.TAG_STRING);
        for (int i = 0; i < stories.size(); i++) {
            this.completedStories.add(stories.getString(i));
            mask |= storyFlag(stories.getString(i));
            doneMask |= storyBit(stories.getString(i));
        }
        this.entityData.set(UNLOCKS, mask);
        this.entityData.set(STORIES_DONE, doneMask);
        this.storyFlags.clear();
        ListTag flags = tag.getList(TAG_STORY_FLAGS, Tag.TAG_STRING);
        for (int i = 0; i < flags.size(); i++) {
            this.storyFlags.add(flags.getString(i));
        }
        this.syncStoryFlags();
        // Outfit after unlocks so a saved (unlocked) outfit is accepted
        if (tag.contains(TAG_CURRENT_OUTFIT, Tag.TAG_STRING)) this.setCurrentOutfit(tag.getString(TAG_CURRENT_OUTFIT));
        this.setResting(tag.getBoolean(TAG_RESTING));

        if (tag.contains(TAG_PET_DAY, Tag.TAG_LONG)) this.petAffectionDay = tag.getLong(TAG_PET_DAY);
        if (tag.contains(TAG_PET_GAINED, Tag.TAG_INT)) this.petAffectionGained = tag.getInt(TAG_PET_GAINED);
        if (tag.contains(TAG_GIFT_DAY, Tag.TAG_LONG)) this.giftAffectionDay = tag.getLong(TAG_GIFT_DAY);
        if (tag.contains(TAG_GIFT_GAINED, Tag.TAG_INT)) this.giftAffectionGained = tag.getInt(TAG_GIFT_GAINED);
        if (tag.contains(TAG_HOARD, Tag.TAG_LIST)) this.hoard.fromTag(tag.getList(TAG_HOARD, Tag.TAG_COMPOUND));
        if (tag.contains("Progression", Tag.TAG_COMPOUND)) this.progression.load(tag.getCompound("Progression"));
        this.progression.apply(this);
        // Growth bonuses are transient, so vanilla clamped the saved health before they existed: restore it
        if (tag.contains("Health", Tag.TAG_ANY_NUMERIC)) this.setHealth(tag.getFloat("Health"));
        if (tag.contains("PuffCurios", Tag.TAG_COMPOUND)) this.curioStore.load(tag.getCompound("PuffCurios"));
        this.curioSlotBonus.clear();
        CompoundTag bonus = tag.getCompound("CurioSlotBonus");
        for (String k : bonus.getAllKeys()) if (bonus.getInt(k) > 0) this.curioSlotBonus.put(k, bonus.getInt(k));
        this.curioSlotState = tag.getCompound("CurioSlotState").copy();
        if (tag.contains("Squad", Tag.TAG_COMPOUND)) this.squad.load(tag.getCompound("Squad"));
        this.proxyPersistentData = tag.getCompound("ProxyData").copy();
        this.setOwnerGuard(!tag.contains("OwnerFriendlyFireGuard") || tag.getBoolean("OwnerFriendlyFireGuard"));
        if (tag.contains("SpellMana", Tag.TAG_INT)) this.setSpellMana(tag.getInt("SpellMana"));
        this.setSpellCastEnabled(!tag.contains("SpellCastEnabled") || tag.getBoolean("SpellCastEnabled"));
        if (tag.contains("WorkMode", Tag.TAG_STRING)) {
            PuffWorkMode m = PuffWorkMode.byId(tag.getString("WorkMode"));
            if (m != null) this.setWorkMode(m);
        }
        net.minecraft.core.BlockPos savedHome = tag.contains("HomePos", Tag.TAG_LONG)
                ? net.minecraft.core.BlockPos.of(tag.getLong("HomePos")) : null;
        if (tag.getBoolean("HomeMode") && savedHome != null) {
            this.setHomeMode(true, savedHome);
        } else {
            this.entityData.set(HOME_MODE, false);
            this.entityData.set(HOME_POS, java.util.Optional.ofNullable(savedHome));
            this.clearRestriction();
        }
        this.dailyEvents.clear();
        CompoundTag daily = tag.getCompound(TAG_DAILY_EVENTS);
        for (String k : daily.getAllKeys()) this.dailyEvents.put(k, daily.getLong(k));
        if (tag.contains(TAG_LAST_DIMENSION, Tag.TAG_STRING)) this.lastDimension = tag.getString(TAG_LAST_DIMENSION);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // ---------------- Getters / setters ----------------

    public int getAffection() { return this.entityData.get(AFFECTION); }
    public void setAffection(int value) { this.entityData.set(AFFECTION, Mth.clamp(value, 0, 100)); }

    public int getGrowthStage() { return this.entityData.get(GROWTH_STAGE); }
    public void setGrowthStage(int value) {
        this.entityData.set(GROWTH_STAGE, Mth.clamp(value, 1, 4));
        if (!this.level().isClientSide) this.applyStageStats();
    }

    public String getCallMode() { return this.entityData.get(CALL_MODE); }
    public void setCallMode(String mode) {
        this.entityData.set(CALL_MODE, CALL_SISTER.equals(mode) ? CALL_SISTER : CALL_BROTHER);
    }

    public String getCurrentOutfit() { return this.entityData.get(CURRENT_OUTFIT); }
    /** Only accepts known and unlocked outfits. */
    public void setCurrentOutfit(String outfit) {
        PuffOutfit o = PuffOutfit.byId(outfit);
        if (o != null && this.isOutfitUnlocked(o)) this.entityData.set(CURRENT_OUTFIT, o.id());
    }

    public int getPuffHunger() { return this.entityData.get(PUFF_HUNGER); }
    public void setPuffHunger(int value) { this.entityData.set(PUFF_HUNGER, Mth.clamp(value, 0, 100)); }

    public boolean isResting() { return this.entityData.get(RESTING); }
    public void setResting(boolean resting) { this.entityData.set(RESTING, resting); }

    public int getUnlockMask() { return this.entityData.get(UNLOCKS); }
    public boolean isOutfitUnlocked(PuffOutfit outfit) { return (this.getUnlockMask() & outfit.bit()) != 0; }
    public void unlockOutfit(PuffOutfit outfit) { this.entityData.set(UNLOCKS, this.getUnlockMask() | outfit.bit()); }
    public boolean hasStoryFlag(int flag) { return (this.getUnlockMask() & flag) != 0; }

    public Set<String> getCompletedStories() { return Collections.unmodifiableSet(this.completedStories); }
    public boolean markStoryCompleted(String id) {
        boolean added = this.completedStories.add(id);
        int flag = storyFlag(id);
        if (flag != 0) this.entityData.set(UNLOCKS, this.getUnlockMask() | flag);
        this.entityData.set(STORIES_DONE, this.entityData.get(STORIES_DONE) | storyBit(id));
        return added;
    }

    /** Client-safe: was story {@code PuffStories.ORDER[index]} completed? */
    public boolean isStoryDone(int index) {
        return (this.entityData.get(STORIES_DONE) & (1 << index)) != 0;
    }

    private static int storyBit(String id) {
        int i = PuffStories.indexOf(id);
        return i < 0 ? 0 : 1 << i;
    }

    public boolean hasCompletedStory(String id) { return this.completedStories.contains(id); }

    /** Small story memories (e.g. "impression_tsukkomi") that later dialogue can react to. */
    public Set<String> getStoryFlags() { return Collections.unmodifiableSet(this.storyFlags); }
    public void addStoryFlag(String flag) {
        if (flag == null || flag.isBlank()) return;
        if (this.storyFlags.add(flag)) {
            this.syncStoryFlags();
        }
    }

    private void syncStoryFlags() {
        if (this.storyFlags.isEmpty()) {
            this.entityData.set(SYNCED_STORY_FLAGS, "");
            return;
        }
        this.entityData.set(SYNCED_STORY_FLAGS, String.join(",", this.storyFlags));
    }

    /** Client-safe set of recorded story choice & route flags. */
    public Set<String> getSyncedStoryFlags() {
        if (!this.level().isClientSide) return Collections.unmodifiableSet(this.storyFlags);
        String raw = this.entityData.get(SYNCED_STORY_FLAGS);
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Client-safe count of completed side stories (excluding post-marriage epilogue). */
    public int getCompletedSideCount() {
        int n = 0;
        for (PuffStories.Entry e : java.util.List.of(
                PuffStories.SIDE1, PuffStories.SIDE2,
                PuffStories.SIDE3_STARLIGHT, PuffStories.SIDE4_CHEESE_FEAST, PuffStories.SIDE5_RAT_PARADE)) {
            int idx = PuffStories.indexOf(e.id());
            if (idx >= 0 && this.isStoryDone(idx)) n++;
        }
        return n;
    }

    /** Client-safe Adventure route tendency score (drives Adventure / Queen / True endings). */
    public int getAdventureRouteScore() {
        Set<String> flags = this.getSyncedStoryFlags();
        int score = 0;
        for (String f : flags) {
            if (f.startsWith("route_adv") || f.startsWith("ending_adv") || f.startsWith("mod_boss_")
                    || PuffStories.FLAG_VISITED_DANGER_DIM.equals(f)) {
                score++;
            }
        }
        if (this.isStoryDone(PuffStories.indexOf(PuffStories.SIDE3_STARLIGHT.id()))) score++;
        if (this.isStoryDone(PuffStories.indexOf(PuffStories.SIDE5_RAT_PARADE.id()))) score++;
        return score;
    }

    /** Client-safe Hearth/Cozy route tendency score (drives Hearth / True endings). */
    public int getHearthRouteScore() {
        Set<String> flags = this.getSyncedStoryFlags();
        int score = 0;
        for (String f : flags) {
            if (f.startsWith("route_hearth") || f.startsWith("ending_hearth")) {
                score++;
            }
        }
        if (this.isStoryDone(PuffStories.indexOf(PuffStories.SIDE2.id()))) score++;
        if (this.isStoryDone(PuffStories.indexOf(PuffStories.SIDE4_CHEESE_FEAST.id()))) score++;
        if (this.isHomeMode()) score++;
        return score;
    }

    /** Client-safe Bond/True route score (side stories + deep-bond choices + crossovers). */
    public int getBondRouteScore() {
        Set<String> flags = this.getSyncedStoryFlags();
        int score = this.getCompletedSideCount();
        for (String f : flags) {
            if (f.startsWith("route_true")) score++;
        }
        for (PuffStories.Entry e : PuffStories.ORDER) {
            if (e.category() == PuffStories.Category.CROSSOVER) {
                int idx = PuffStories.indexOf(e.id());
                if (idx >= 0 && this.isStoryDone(idx)) score++;
            }
        }
        return score;
    }

    /** Short ids from stage 2B and the official ids from the story doc both unlock the same topics. */
    private static int storyFlag(String storyId) {
        if (STORY_CHAPTER1.equals(storyId) || STORY_CH1_DOC_ID.equals(storyId)) return FLAG_STORY_CHAPTER1;
        if (STORY_SIDE1.equals(storyId) || STORY_SIDE1_DOC_ID.equals(storyId)) return FLAG_STORY_SIDE1;
        return 0;
    }

    // ---------------- Animal boilerplate ----------------

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canMate(net.minecraft.world.entity.animal.Animal other) {
        return false;
    }

    // ---------------- GeckoLib ----------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, state -> {
            if (this.isResting()) {
                return state.setAndContinue(REST);
            }
            if (this.isInSittingPose() || this.isPassenger()) {
                return state.setAndContinue(SIT);
            }
            if (state.isMoving()) {
                double dx = this.getX() - this.xo;
                double dz = this.getZ() - this.zo;
                double speed = Math.sqrt(dx * dx + dz * dz);
                return state.setAndContinue(speed > RUN_SPEED_THRESHOLD ? RUN : WALK);
            }
            return state.setAndContinue(IDLE);
        }));
        // One-shot reactions layered on top; idle otherwise (STOP), triggered from the bubble packet
        controllers.add(new AnimationController<>(this, ACTION_CONTROLLER, 3, state -> PlayState.STOP)
                .triggerableAnim(ANIM_ALERT, ALERT)
                .triggerableAnim(ANIM_SHY, SHY)
                .triggerableAnim(ANIM_SWING, SWING)
                .triggerableAnim(ANIM_SPELLCAST, SPELLCAST)
                .triggerableAnim(ANIM_ALERT_SNIFF, ALERT_SNIFF)
                .triggerableAnim(ANIM_CAT_PANIC, CAT_PANIC)
                .triggerableAnim(ANIM_CHECK_CUFF, CHECK_CUFF)
                .triggerableAnim(ANIM_COMMAND_RATS, COMMAND_RATS)
                .triggerableAnim(ANIM_GROUND_REST, GROUND_REST)
                .triggerableAnim(ANIM_HOARD_PICKUP, HOARD_PICKUP)
                .triggerableAnim(ANIM_SHOULDER_SIT, SHOULDER_SIT)
                .triggerableAnim(ANIM_VOW_SHY, VOW_SHY)
                .triggerableAnim(ANIM_EAT_CHEESE, EAT_CHEESE)
                .triggerableAnim(ANIM_BOW, BOW)
                .triggerableAnim(ANIM_SHOUT, SHOUT)
                .triggerableAnim(ANIM_STRETCH, STRETCH)
                .triggerableAnim(ANIM_WAVE, WAVE));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ---------------- Goals ----------------

    /** While panicking (cat nearby) run to a spot ~1.5 blocks behind the owner. */
    static class HideBehindOwnerGoal extends Goal {
        private final PuffEntity puff;
        private int repath;

        HideBehindOwnerGoal(PuffEntity puff) {
            this.puff = puff;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.puff.isPanicking() && !this.puff.isOrderedToSit() && !this.puff.isPassenger()
                    && this.puff.getOwner() != null;
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void tick() {
            LivingEntity owner = this.puff.getOwner();
            if (owner == null) return;
            this.puff.getLookControl().setLookAt(owner, 30.0F, 30.0F);
            if (--this.repath > 0) return;
            this.repath = 5;
            Vec3 look = owner.getLookAngle().multiply(1.0, 0.0, 1.0);
            if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
            Vec3 behind = owner.position().subtract(look.normalize().scale(1.5));
            this.puff.getNavigation().moveTo(behind.x, behind.y, behind.z, 1.7D);
        }
    }

    /** When Home mode is enabled, keeps Puff within {@link #HOME_RADIUS} blocks of her home anchor. */
    static class PuffReturnHomeGoal extends Goal {
        private static final double RETURN_DIST_SQR = HOME_RADIUS * HOME_RADIUS;
        private static final double TELEPORT_DIST_SQR = 32.0 * 32.0;

        private final PuffEntity puff;
        private int repath;

        PuffReturnHomeGoal(PuffEntity puff) {
            this.puff = puff;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!this.puff.isHomeMode() || this.puff.isOrderedToSit() || this.puff.isResting() || this.puff.isPassenger()) {
                return false;
            }
            if (this.puff.getTarget() != null) return false;
            net.minecraft.core.BlockPos home = this.puff.getHomePos();
            return home != null && this.puff.blockPosition().distSqr(home) > RETURN_DIST_SQR;
        }

        @Override
        public boolean canContinueToUse() {
            if (!this.puff.isHomeMode() || this.puff.isOrderedToSit() || this.puff.isResting() || this.puff.isPassenger()) {
                return false;
            }
            if (this.puff.getTarget() != null) return false;
            net.minecraft.core.BlockPos home = this.puff.getHomePos();
            return home != null && this.puff.blockPosition().distSqr(home) > 9.0;
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void tick() {
            net.minecraft.core.BlockPos home = this.puff.getHomePos();
            if (home == null) return;
            double d2 = this.puff.blockPosition().distSqr(home);
            if (d2 > TELEPORT_DIST_SQR) {
                this.puff.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, this.puff.getYRot(), this.puff.getXRot());
                this.puff.getNavigation().stop();
                return;
            }
            if (--this.repath > 0) return;
            this.repath = 10;
            this.puff.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.15D);
        }
    }
}
