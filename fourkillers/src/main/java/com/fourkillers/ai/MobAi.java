package com.fourkillers.ai;

import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Глава 1А: зрение, слух и состояния обычных мобов.
 * Состояния: CALM -> SUSPICIOUS -> SEARCHING -> ALERT.
 */
public final class MobAi {
    private MobAi() {}

    // ===== НАСТРОЙКИ (можно крутить) =====
    public static boolean debugParticles = true;      // частицы над мобами
    public static final double VIEW_RANGE = 22.0;     // дальность зрения, блоков
    public static final double FOV_DEGREES = 120.0;   // угол обзора
    public static final double MAX_RANGE = 40.0;      // мобы дальше от игрока не обрабатываются
    public static final int CHECK_INTERVAL = 5;       // проверка раз в 5 тиков
    public static final int ALERT_MEMORY = 100;       // 5 сек помнит игрока после потери из виду
    public static final int SEARCH_TICKS = 200;       // 10 сек ищет
    public static final float BACKSTAB_MULT = 3.0f;   // множитель урона по незамеченному мобу
    public static final double HEAR_SPRINT = 14.0;    // слышно бег с этого расстояния
    public static final double HEAR_WALK = 6.0;       // слышно ходьбу
    // Шаг осторожности (присед) почти не слышен

    private static final double COS_HALF_FOV = Math.cos(Math.toRadians(FOV_DEGREES / 2.0));

    public static boolean inBackstab = false;

    public enum State { CALM, SUSPICIOUS, SEARCHING, ALERT }

    private static final class Awareness {
        State state = State.CALM;
        double meter = 0.0;          // шкала подозрения 0..1
        UUID targetId;
        Vec3d lastKnown;
        long lastSeen;
        long searchUntil;
    }

    private static final class Noise {
        final double radius;
        final long until;
        Noise(double radius, long until) { this.radius = radius; this.until = until; }
    }

    private static final Map<MobEntity, Awareness> DATA = new WeakHashMap<>();
    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Boolean> MOVING = new HashMap<>();
    private static final Map<UUID, Noise> NOISE = new HashMap<>();

    // ===== Публичное API =====

    public static boolean isAffected(MobEntity mob) {
        return (mob instanceof ZombieEntity && !(mob instanceof ZombifiedPiglinEntity))
                || mob instanceof AbstractSkeletonEntity
                || mob instanceof CreeperEntity
                || mob instanceof SpiderEntity
                || mob instanceof WitchEntity;
    }

    public static boolean isAlert(MobEntity mob) {
        Awareness a = DATA.get(mob);
        return a != null && a.state == State.ALERT;
    }

    public static void addNoise(UUID player, double radius, long until) {
        Noise old = NOISE.get(player);
        if (old == null || old.until < until || old.radius < radius) {
            NOISE.put(player, new Noise(radius, until));
        }
    }

    /** Игрок ударил моба. Возвращает true, если моб до этого игрока не замечал. */
    public static boolean registerHit(MobEntity mob, ServerPlayerEntity player) {
        Awareness a = DATA.computeIfAbsent(mob, m -> new Awareness());
        boolean unnoticed = !(a.state == State.ALERT && player.getUuid().equals(a.targetId));
        enterAlert(a, mob, player, mob.getWorld().getTime());
        return unnoticed;
    }

    // ===== Главный цикл =====

    public static void tick(ServerWorld world) {
        long now = world.getTime();
        if (now % CHECK_INTERVAL != 0) return;

        List<ServerPlayerEntity> players = world.getPlayers(
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative());
        if (players.isEmpty()) return;

        // Двигается ли игрок (по смещению с прошлой проверки)
        for (ServerPlayerEntity p : players) {
            Vec3d pos = p.getPos();
            Vec3d last = LAST_POS.put(p.getUuid(), pos);
            boolean moving = last != null && Math.hypot(pos.x - last.x, pos.z - last.z) > 0.3;
            MOVING.put(p.getUuid(), moving);
        }

        Set<MobEntity> mobs = new HashSet<>();
        for (ServerPlayerEntity p : players) {
            mobs.addAll(world.getEntitiesByClass(MobEntity.class,
                    p.getBoundingBox().expand(MAX_RANGE), MobAi::isAffected));
        }
        for (MobEntity mob : mobs) {
            if (mob.isAlive()) update(world, mob, players, now);
        }
    }

    private static void update(ServerWorld world, MobEntity mob, List<ServerPlayerEntity> players, long now) {
        Awareness a = DATA.computeIfAbsent(mob, m -> new Awareness());

        ServerPlayerEntity seenBy = null;
        ServerPlayerEntity heardBy = null;
        double seenDist = Double.MAX_VALUE;
        double heardDist = Double.MAX_VALUE;

        for (ServerPlayerEntity p : players) {
            double dist = mob.distanceTo(p);
            if (dist > MAX_RANGE) continue;
            if (canSee(world, mob, p, dist)) {
                if (dist < seenDist) { seenBy = p; seenDist = dist; }
            } else if (canHear(mob, p, dist, now)) {
                if (dist < heardDist) { heardBy = p; heardDist = dist; }
            }
        }

        // --- шкала подозрения ---
        if (seenBy != null) {
            double closeness = MathHelper.clamp(1.0 - seenDist / VIEW_RANGE, 0.0, 1.0);
            a.meter += 0.08 + 0.35 * closeness;
            a.lastKnown = seenBy.getPos();
            a.lastSeen = now;
            a.targetId = seenBy.getUuid();
        } else if (heardBy != null) {
            if (a.state != State.ALERT) {
                a.meter = Math.min(a.meter + 0.14, 0.95); // на слух не атакуют, только идут проверить
                a.lastKnown = heardBy.getPos();
                a.targetId = heardBy.getUuid();
            }
        } else if (a.state == State.CALM || a.state == State.SUSPICIOUS) {
            a.meter -= 0.03;
        }
        a.meter = MathHelper.clamp(a.meter, 0.0, 1.0);

        // --- состояния ---
        switch (a.state) {
            case ALERT -> {
                ServerPlayerEntity t = findPlayer(players, a.targetId);
                if (t == null || now - a.lastSeen > ALERT_MEMORY) {
                    mob.setTarget(null);
                    if (a.lastKnown != null) {
                        a.state = State.SEARCHING;
                        a.meter = 0.7;
                        a.searchUntil = now + SEARCH_TICKS;
                    } else {
                        a.state = State.CALM;
                        a.meter = 0.0;
                    }
                } else if (mob.getTarget() != t) {
                    mob.setTarget(t);
                }
            }
            case SEARCHING -> {
                if (a.meter >= 1.0 && seenBy != null) {
                    enterAlert(a, mob, seenBy, now);
                } else if (a.lastKnown == null || now > a.searchUntil) {
                    a.state = State.CALM;
                    a.meter = 0.1;
                } else if (now % 20 == 0 || mob.getNavigation().isIdle()) {
                    Vec3d t = a.lastKnown;
                    mob.getNavigation().startMovingTo(t.x, t.y, t.z, 1.1);
                }
            }
            default -> { // CALM и SUSPICIOUS
                if (a.meter >= 1.0 && seenBy != null) {
                    enterAlert(a, mob, seenBy, now);
                } else if (seenBy == null && heardBy != null && a.meter >= 0.3) {
                    a.state = State.SEARCHING;
                    a.searchUntil = now + SEARCH_TICKS;
                } else if (a.state == State.CALM && a.meter >= 0.3) {
                    a.state = State.SUSPICIOUS;
                } else if (a.state == State.SUSPICIOUS && a.meter < 0.15) {
                    a.state = State.CALM;
                }
                if (a.state == State.SUSPICIOUS && a.lastKnown != null) {
                    mob.getNavigation().stop();
                    mob.getLookControl().lookAt(a.lastKnown.x, a.lastKnown.y + 1.0, a.lastKnown.z);
                }
            }
        }

        if (debugParticles && a.state != State.CALM) {
            ParticleEffect fx = switch (a.state) {
                case SUSPICIOUS -> ParticleTypes.SMOKE;
                case SEARCHING -> ParticleTypes.END_ROD;
                default -> ParticleTypes.ANGRY_VILLAGER;
            };
            world.spawnParticles(fx, mob.getX(), mob.getEyeY() + 0.6, mob.getZ(), 1, 0.1, 0.0, 0.1, 0.0);
        }
    }

    // ===== Зрение и слух =====

    private static boolean canSee(ServerWorld world, MobEntity mob, ServerPlayerEntity p, double dist) {
        double range = VIEW_RANGE;
        if (p.isSneaking()) range *= 0.5;
        if (p.isInvisible()) range *= 0.4;
        if (world.getLightLevel(p.getBlockPos()) < 7) range *= 0.75;
        if (dist > range) return false;

        // угол обзора (по горизонтали)
        double yaw = Math.toRadians(mob.getHeadYaw());
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        double dx = p.getX() - mob.getX();
        double dz = p.getZ() - mob.getZ();
        double hd = Math.hypot(dx, dz);
        if (hd > 0.001) {
            double dot = (lookX * dx + lookZ * dz) / hd;
            if (dot < COS_HALF_FOV) return false;
        }
        return mob.canSee(p); // луч до игрока без стен
    }

    private static boolean canHear(MobEntity mob, ServerPlayerEntity p, double dist, long now) {
        double r = 0.0;
        if (p.isSneaking()) {
            r = 0.0;
        } else if (p.isSprinting()) {
            r = HEAR_SPRINT;
        } else if (MOVING.getOrDefault(p.getUuid(), false)) {
            r = HEAR_WALK;
        }
        Noise n = NOISE.get(p.getUuid());
        if (n != null) {
            if (n.until >= now) r = Math.max(r, n.radius);
            else NOISE.remove(p.getUuid());
        }
        if (r <= 0.0) return false;
        if (!mob.canSee(p)) r *= 0.6; // через стены хуже слышно
        return dist <= r;
    }

    // ===== Вспомогательное =====

    private static void enterAlert(Awareness a, MobEntity mob, ServerPlayerEntity p, long now) {
        a.state = State.ALERT;
        a.meter = 1.0;
        a.targetId = p.getUuid();
        a.lastSeen = now;
        a.lastKnown = p.getPos();
        mob.setTarget(p);
    }

    private static ServerPlayerEntity findPlayer(List<ServerPlayerEntity> players, UUID id) {
        if (id == null) return null;
        for (ServerPlayerEntity p : players) {
            if (p.getUuid().equals(id)) return p;
        }
        return null;
    }
}
