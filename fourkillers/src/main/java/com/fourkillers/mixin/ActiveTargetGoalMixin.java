package com.fourkillers.mixin;

import com.fourkillers.ai.MobAi;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ванильные мобы сами выбирают игрока целью, едва он окажется рядом.
 * Блокируем это: цель выдаёт только наш ИИ, когда моб реально заметил игрока.
 */
@Mixin(ActiveTargetGoal.class)
public abstract class ActiveTargetGoalMixin {
    @Shadow
    protected Class<?> targetClass;

    @Shadow
    protected MobEntity mob;

    @Inject(method = "canStart", at = @At("HEAD"), cancellable = true)
    private void fourkillers$onlyIfAlert(CallbackInfoReturnable<Boolean> cir) {
        if (this.targetClass == PlayerEntity.class
                && MobAi.isAffected(this.mob)
                && !MobAi.isAlert(this.mob)) {
            cir.setReturnValue(false);
        }
    }
}
