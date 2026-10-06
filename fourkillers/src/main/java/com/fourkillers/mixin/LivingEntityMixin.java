package com.fourkillers.mixin;

import com.fourkillers.ai.MobAi;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Удар по мобу, который вас не заметил, наносит больше урона (удар со спины). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void fourkillers$backstab(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (MobAi.inBackstab || self.getWorld().isClient) return;
        if (!(self instanceof MobEntity mob) || !MobAi.isAffected(mob)) return;
        if (!(source.getAttacker() instanceof ServerPlayerEntity player)) return;

        boolean unnoticed = MobAi.registerHit(mob, player);
        if (unnoticed && amount > 0.0f) {
            MobAi.inBackstab = true;
            try {
                boolean result = self.damage(source, amount * MobAi.BACKSTAB_MULT);
                cir.setReturnValue(result);
            } finally {
                MobAi.inBackstab = false;
            }
            if (self.getWorld() instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.CRIT, self.getX(), self.getBodyY(0.5), self.getZ(),
                        12, 0.3, 0.4, 0.3, 0.2);
            }
            player.sendMessage(Text.literal("Удар со спины! x" + (int) MobAi.BACKSTAB_MULT), true);
        }
    }
}
