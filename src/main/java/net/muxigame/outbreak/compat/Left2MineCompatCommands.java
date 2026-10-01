package net.muxigame.outbreak.compat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.outbreak.OutbreakGame;
import net.muxigame.outbreak.map.OutbreakMap;

/**
 * Clean-room compatibility for the command surface documented by Left 2 Mine.
 * No Left 2 Mine code or assets are used here.
 */
public final class Left2MineCompatCommands {
    private Left2MineCompatCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, OutbreakGame game) {
        dispatcher.register(Commands.literal("left2mine")
            .then(Commands.literal("start")
                .executes(context -> legacyStart(game, context.getSource().getPlayerOrException(), null, null))
                .then(Commands.argument("first", StringArgumentType.word())
                    .executes(context -> legacyStart(
                        game, context.getSource().getPlayerOrException(),
                        StringArgumentType.getString(context, "first"), null
                    ))
                    .then(Commands.argument("second", StringArgumentType.word())
                        .executes(context -> legacyStart(
                            game, context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "first"),
                            StringArgumentType.getString(context, "second")
                        )))))
            .then(Commands.literal("stop").executes(context -> {
                game.stop(context.getSource().getPlayerOrException(), "Left 2 Mine 兼容命令停止");
                return 1;
            }))
            .then(Commands.literal("win").executes(context -> {
                game.win(context.getSource().getPlayerOrException());
                return 1;
            }))
            .then(Commands.literal("panicstart")
                .executes(context -> panic(game, context.getSource().getPlayerOrException(), -1, 0))
                .then(Commands.argument("waves", IntegerArgumentType.integer(-1))
                    .executes(context -> panic(
                        game, context.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(context, "waves"), 0
                    ))
                    .then(Commands.argument("delay", IntegerArgumentType.integer(0))
                        .executes(context -> panic(
                            game, context.getSource().getPlayerOrException(),
                            IntegerArgumentType.getInteger(context, "waves"),
                            IntegerArgumentType.getInteger(context, "delay")
                        )))))
            .then(Commands.literal("panicstop").executes(context -> {
                game.panicStop(context.getSource().getPlayerOrException());
                return 1;
            }))
            .then(Commands.literal("director")
                .then(Commands.argument("action", StringArgumentType.word())
                    .executes(context -> {
                        game.legacyDirector(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "action"),
                            null
                        );
                        return 1;
                    })
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .executes(context -> {
                            game.legacyDirector(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "action"),
                                StringArgumentType.getString(context, "kind")
                            );
                            return 1;
                        }))))
            .then(Commands.literal("clearsurvival").executes(context -> 1)));
    }

    private static int legacyStart(OutbreakGame game, ServerPlayer player, String first, String second) {
        boolean survival = first != null && first.equalsIgnoreCase("survival");
        String difficulty = survival ? second : first;
        game.start(
            player,
            game.defaultMapId(),
            survival ? OutbreakMap.Mode.SURVIVAL : OutbreakMap.Mode.CAMPAIGN,
            OutbreakGame.parseDifficulty(difficulty)
        );
        return 1;
    }

    private static int panic(OutbreakGame game, ServerPlayer player, int waves, int delay) {
        game.panicStart(player, waves, delay);
        return 1;
    }
}
