package com.puffcompanion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffOutfit;
import com.puffcompanion.story.PuffStoryManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.function.BiConsumer;

/**
 * Debug commands (permission level 2), acting on the nearest Puff owned by the caller within 64 blocks:
 * <pre>
 * /puff debug stage &lt;1-4&gt;
 * /puff debug call &lt;brother|sister&gt;
 * /puff debug affection &lt;0-100&gt;
 * </pre>
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PuffCommands {
    private static final double SEARCH_RANGE = 64.0;
    private static final SimpleCommandExceptionType NO_PUFF =
            new SimpleCommandExceptionType(Component.translatable("commands.puffcompanion.no_puff"));

    private PuffCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("puff")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("stage")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> apply(ctx, "stage", IntegerArgumentType.getInteger(ctx, "value"),
                                                (puff, v) -> puff.setGrowthStage(v)))))
                        .then(Commands.literal("affection")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> apply(ctx, "affection", IntegerArgumentType.getInteger(ctx, "value"),
                                                (puff, v) -> puff.setAffection(v)))))
                        .then(Commands.literal("hunger")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> apply(ctx, "hunger", IntegerArgumentType.getInteger(ctx, "value"),
                                                (puff, v) -> puff.setPuffHunger(v)))))
                        .then(Commands.literal("mana")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 300))
                                        .executes(ctx -> apply(ctx, "mana", IntegerArgumentType.getInteger(ctx, "value"),
                                                (puff, v) -> puff.setSpellMana(v)))))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(com.puffcompanion.entity.PuffWorkMode.values())
                                                        .map(com.puffcompanion.entity.PuffWorkMode::id), b))
                                        .executes(ctx -> {
                                            String raw = StringArgumentType.getString(ctx, "mode");
                                            var m = com.puffcompanion.entity.PuffWorkMode.byId(raw);
                                            if (m == null) {
                                                throw new SimpleCommandExceptionType(Component.translatable(
                                                        "commands.puffcompanion.debug.mode.invalid", raw)).create();
                                            }
                                            PuffEntity puff = findPuff(ctx.getSource());
                                            puff.setWorkMode(m);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "commands.puffcompanion.debug.mode", puff.getDisplayName(),
                                                    Component.translatable(m.langKey())), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("outfit")
                                .then(Commands.argument("outfit", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(PuffOutfit.values()).map(PuffOutfit::id), b))
                                        .executes(PuffCommands::setOutfit)))
                        .then(Commands.literal("story")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(PuffStoryManager.keys(), b))
                                        .executes(PuffCommands::playStory)))
                        .then(Commands.literal("unlock_all").executes(PuffCommands::unlockAll))
                        .then(Commands.literal("revive").executes(PuffCommands::reviveOwn))
                        .then(Commands.literal("curiotest")
                                .executes(ctx -> curioTest(ctx, "enigmaticlegacy:blazing_core"))
                                .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> curioTest(ctx, net.minecraft.commands.arguments.ResourceLocationArgument
                                                .getId(ctx, "item").toString()))))
                        .then(Commands.literal("curio_slot")
                                .executes(PuffCommands::listCurioSlots)
                                .then(Commands.argument("slotId", StringArgumentType.word())
                                        .suggests((ctx, sb) -> {
                                            try {
                                                return SharedSuggestionProvider.suggest(com.puffcompanion.compat.CuriosCompat
                                                        .slotSizes(findPuff(ctx.getSource())).stream()
                                                        .map(com.puffcompanion.compat.CuriosCompat.SlotGroup::id), sb);
                                            } catch (CommandSyntaxException e) {
                                                return sb.buildFuture();
                                            }
                                        })
                                        .then(Commands.argument("delta", IntegerArgumentType.integer(-64, 64))
                                                .executes(PuffCommands::changeCurioSlot))))
                        .then(Commands.literal("story_done")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new String[]{
                                                PuffEntity.STORY_PROLOGUE, PuffEntity.STORY_CH1_DOC_ID,
                                                PuffEntity.STORY_SIDE1_DOC_ID, PuffEntity.STORY_CHAPTER1,
                                                PuffEntity.STORY_SIDE1}, b))
                                        .executes(PuffCommands::markStoryDone)))
                        .then(Commands.literal("call")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{PuffEntity.CALL_BROTHER, PuffEntity.CALL_SISTER}, b))
                                        .executes(PuffCommands::setCall)))));
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, String field, int value,
                             BiConsumer<PuffEntity, Integer> setter) throws CommandSyntaxException {
        PuffEntity puff = findPuff(ctx.getSource());
        setter.accept(puff, value);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug." + field,
                puff.getDisplayName(), value), false);
        return value;
    }

    /** Debug: play a story right now (also replays completed ones, without rewards). */
    private static int playStory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        String key = PuffStoryManager.resolveKey(id);
        if (key == null) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.puffcompanion.debug.story.unknown",
                    id, String.join(", ", PuffStoryManager.keys()))).create();
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PuffEntity puff = findPuff(ctx.getSource());
        PuffStoryManager.start(player, puff, key);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.story",
                puff.getDisplayName(), key), false);
        return 1;
    }

    /** Debug: unlock every outfit and mark all 8 stories completed (memories become replayable). */
    /** Debug: revive every fallen Puff owned by the command source at their feet (consumes the graveyard record). */
    private static int reviveOwn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.puffcompanion.world.PuffGraveyard yard = com.puffcompanion.world.PuffGraveyard.get(ctx.getSource().getServer());
        int n = 0;
        for (java.util.UUID id : yard.deadIds()) {
            net.minecraft.nbt.CompoundTag snap = yard.peek(id);
            if (snap == null || !snap.hasUUID("Owner") || !snap.getUUID("Owner").equals(player.getUUID())) continue;
            if (com.puffcompanion.entity.PuffRevival.reviveFromGraveyard(player.serverLevel(), id, player.position()) != null) n++;
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Revived " + count + " Puff(s)"), false);
        return count;
    }

    /** Debug: lists Puff's Curios slot types with count (and expander bonus). */
    private static int listCurioSlots(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PuffEntity puff = findPuff(ctx.getSource());
        if (!com.puffcompanion.compat.CuriosCompat.isLoaded()) {
            ctx.getSource().sendFailure(Component.literal("Curios is not installed"));
            return 0;
        }
        for (com.puffcompanion.compat.CuriosCompat.SlotGroup g : com.puffcompanion.compat.CuriosCompat.slotSizes(puff)) {
            int bonus = puff.getCurioSlotBonus(g.id());
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.curio_slot.entry",
                    Component.translatable("curios.identifier." + g.id()), g.id(), g.count(), bonus), false);
        }
        return 1;
    }

    /** Debug: changes the expander bonus of one slot type (never below the player's base size). */
    private static int changeCurioSlot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PuffEntity puff = findPuff(ctx.getSource());
        String type = StringArgumentType.getString(ctx, "slotId");
        int delta = IntegerArgumentType.getInteger(ctx, "delta");
        if (!com.puffcompanion.compat.CuriosCompat.isPlayerSlotType(puff, type)) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.puffcompanion.debug.curio_slot.unknown", type)).create();
        }
        int now = com.puffcompanion.compat.CuriosCompat.addSlotBonus(puff, type, delta);
        int bonus = puff.getCurioSlotBonus(type);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.curio_slot",
                Component.translatable("curios.identifier." + type), now, bonus), true);
        return 1;
    }

    /** Debug: accessory bridge self-test on the nearest Puff (needs Curios). */
    private static int curioTest(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        PuffEntity puff = findPuff(ctx.getSource());
        if (!com.puffcompanion.compat.CuriosCompat.isLoaded()) {
            ctx.getSource().sendFailure(Component.literal("Curios is not installed"));
            return 0;
        }
        com.puffcompanion.compat.curios.CurioSelfTest.run(puff, itemId, c -> ctx.getSource().sendSuccess(() -> c, false));
        return 1;
    }

    private static int unlockAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PuffEntity puff = findPuff(ctx.getSource());
        for (PuffOutfit outfit : PuffOutfit.values()) puff.unlockOutfit(outfit);
        for (com.puffcompanion.entity.PuffStories.Entry e : com.puffcompanion.entity.PuffStories.ORDER) {
            puff.markStoryCompleted(e.id());
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.unlock_all",
                puff.getDisplayName()), false);
        return 1;
    }

    /** Debug: mark any story id as completed (unlocks dependent topics such as chapter1 / side1). */
    private static int markStoryDone(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        PuffEntity puff = findPuff(ctx.getSource());
        // Accept a script file key too, and store the script's real id
        String key = PuffStoryManager.resolveKey(id);
        var story = key != null ? PuffStoryManager.get(key) : null;
        String storyId = story != null ? story.id() : id;
        puff.markStoryCompleted(storyId);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.story_done",
                storyId, puff.getDisplayName()), false);
        return 1;
    }

    private static int setCall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!PuffEntity.CALL_BROTHER.equals(mode) && !PuffEntity.CALL_SISTER.equals(mode)) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.puffcompanion.debug.call.invalid", mode)).create();
        }
        PuffEntity puff = findPuff(ctx.getSource());
        puff.setCallMode(mode);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.call",
                puff.getDisplayName(), Component.translatable("puff.call." + mode)), false);
        return 1;
    }

    /** Debug: unlocks the outfit if needed and wears it. */
    private static int setOutfit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "outfit");
        PuffOutfit outfit = PuffOutfit.byId(id);
        if (outfit == null) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.puffcompanion.debug.outfit.invalid", id)).create();
        }
        PuffEntity puff = findPuff(ctx.getSource());
        puff.unlockOutfit(outfit);
        puff.setCurrentOutfit(outfit.id());
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.puffcompanion.debug.outfit",
                puff.getDisplayName(), Component.translatable(outfit.nameKey())), false);
        return 1;
    }

    private static PuffEntity findPuff(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return player.level().getEntitiesOfClass(PuffEntity.class, player.getBoundingBox().inflate(SEARCH_RANGE),
                        p -> p.isAlive() && p.isOwnedBy(player))
                .stream()
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(player)))
                .orElseThrow(NO_PUFF::create);
    }
}
