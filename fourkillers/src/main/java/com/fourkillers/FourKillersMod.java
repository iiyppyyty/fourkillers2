package com.fourkillers;

import com.fourkillers.ai.MobAi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

public class FourKillersMod implements ModInitializer {
    public static final String MOD_ID = "fourkillers";

    @Override
    public void onInitialize() {
        // Главный цикл ИИ: раз в несколько тиков обновляем "внимание" мобов
        ServerTickEvents.END_WORLD_TICK.register(MobAi::tick);

        // Шум от ударов и ломания блоков
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient) {
                MobAi.addNoise(player.getUuid(), 14.0, world.getTime() + 10);
            }
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!world.isClient) {
                MobAi.addNoise(player.getUuid(), 10.0, world.getTime() + 10);
            }
        });

        // /fkdebug включает и выключает частицы состояний над мобами
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("fkdebug").executes(ctx -> {
                MobAi.debugParticles = !MobAi.debugParticles;
                String s = MobAi.debugParticles ? "включены" : "выключены";
                ctx.getSource().sendFeedback(() -> Text.literal("Частицы состояний мобов " + s), false);
                return 1;
            })));
    }
}
