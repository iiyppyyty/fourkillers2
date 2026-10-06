package com.fourkillers.mixin;

import com.fourkillers.ai.MobAi;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LookAtEntityGoal.class)
public abstract class LookAtEntityGoalMixin {
    @Shadow
    @Final
    protected MobEntity mob;

    @Inject(method = "canStart", at = @At("HEAD"), cancellable = true)
    private void fourkillers$noFreeLook(CallbackInfoReturnable<Boolean> cir) {
        if (MobAi.isAffected(this.mob) && !MobAi.isAlert(this.mob)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldContinue", at = @At("HEAD"), cancellable = true)
    private void fourkillers$stopFreeLook(CallbackInfoReturnable<Boolean> cir) {
        if (MobAi.isAffected(this.mob) && !MobAi.isAlert(this.mob)) {
            cir.setReturnValue(false);
        }
    }
}
