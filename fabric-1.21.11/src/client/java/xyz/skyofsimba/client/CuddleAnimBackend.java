package xyz.skyofsimba.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.core.CuddleAnimClip;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddlePlacement;
import xyz.skyofsimba.core.CuddlePose;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 1.21.11 的动作播放后端：没有可用的动画库，用 common 的采样器直接算姿势。
 *
 * <p>锁定表以<b>实体 id</b> 为键（渲染状态里只有 id，没有 UUID）。姿势按
 * {@code (世界时间 - 开始时间) × speed} 采样，渲染时的 {@code tickDelta} 由调用方补上。
 * 结束包里 {@code cancelled=false} 时不会保留动画对象，所以两种结束方式都只是移除锁定表项。
 */
public final class CuddleAnimBackend {

    /** 一条锁定：某一方在播哪段动画。 */
    public record Lock(UUID senderId, UUID receiverId, int partnerId, CuddleAnimDef def, boolean senderSide,
                       double startTick) {}

    private static final Map<Integer, Lock> LOCKS = new HashMap<>();

    private CuddleAnimBackend() {}

    /** 开始贴贴：两边各登记一条（锁定的两人看到同一段动画的两半）。 */
    public static void start(PlayerEntity sender, PlayerEntity receiver, CuddleAnimDef def) {
        double now = now();
        LOCKS.put(sender.getId(), new Lock(sender.getUuid(), receiver.getUuid(), receiver.getId(), def, true, now));
        LOCKS.put(receiver.getId(), new Lock(sender.getUuid(), receiver.getUuid(), sender.getId(), def, false, now));

        ClientPlayerEntity local = MinecraftClient.getInstance().player;
        if (local != null && (local.getId() == sender.getId() || local.getId() == receiver.getId())) {
            CuddleCameraHandler.onCuddleStart();
        }
    }

    /** 结束贴贴：移除两边的锁定。 */
    public static void stop(PlayerEntity sender, PlayerEntity receiver) {
        LOCKS.remove(sender.getId());
        LOCKS.remove(receiver.getId());

        ClientPlayerEntity local = MinecraftClient.getInstance().player;
        if (local != null && (local.getUuid().equals(sender.getUuid()) || local.getUuid().equals(receiver.getUuid()))) {
            CuddleCameraHandler.onCuddleEnd();
        }
    }

    /** 掉线/退出世界时清空。 */
    public static void clear() {
        LOCKS.clear();
        CuddleCameraHandler.reset();
    }

    /** 每 tick 的兜底：服务端结束包丢失时，最多锁 {@link CuddleConstants#CLIENT_LOCK_FALLBACK_TICKS}。 */
    public static void tick() {
        if (LOCKS.isEmpty()) {
            return;
        }
        double now = now();
        Iterator<Map.Entry<Integer, Lock>> iterator = LOCKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Lock lock = iterator.next().getValue();
            if (now - lock.startTick() > CuddleConstants.CLIENT_LOCK_FALLBACK_TICKS) {
                iterator.remove();
                CuddleCameraHandler.onCuddleEnd();
            }
        }
    }

    public static boolean isLocked(int entityId) {
        return LOCKS.containsKey(entityId);
    }

    @Nullable
    public static Lock lockOf(int entityId) {
        return LOCKS.get(entityId);
    }

    /** 该玩家当前朝向对面那人的 yaw（度）；没锁定时返回 null。 */
    @Nullable
    public static Float targetYaw(int entityId) {
        Lock lock = LOCKS.get(entityId);
        Entity self = entity(entityId);
        Entity partner = entity(lock == null ? -1 : lock.partnerId());
        if (self == null || partner == null) {
            return null;
        }
        return CuddlePlacement.yawTo(
                new xyz.skyofsimba.core.Vec3d(self.getX(), self.getY(), self.getZ()),
                new xyz.skyofsimba.core.Vec3d(partner.getX(), partner.getY(), partner.getZ()));
    }

    /** 采样该玩家这一刻的姿势；没锁定或动画缺失时返回 null。 */
    @Nullable
    public static CuddlePose pose(int entityId, float tickDelta) {
        Lock lock = LOCKS.get(entityId);
        if (lock == null) {
            return null;
        }
        CuddleAnimClip clip = clip(lock);
        if (clip == null) {
            return null;
        }
        return clip.sample(animationTick(lock, tickDelta));
    }

    /** body 位移在世界空间的水平分量（给第三人称镜头用）。 */
    public static Vec3d bodyOffset(int entityId, float tickDelta) {
        Lock lock = LOCKS.get(entityId);
        if (lock == null) {
            return Vec3d.ZERO;
        }
        CuddlePose pose = pose(entityId, tickDelta);
        Float yaw = targetYaw(entityId);
        if (pose == null || yaw == null) {
            return Vec3d.ZERO;
        }
        xyz.skyofsimba.core.Vec3d offset =
                CuddlePlacement.worldBodyOffset(pose.body().x(), pose.body().z(), yaw);
        return new Vec3d(offset.x(), offset.y(), offset.z());
    }

    @Nullable
    private static CuddleAnimClip clip(Lock lock) {
        String animationId = lock.senderSide() ? lock.def().senderId() : lock.def().receiverId();
        CuddleAnimClip clip = CuddleAnimRegistry.clip(animationId);
        if (clip == null && !CuddleConstants.DEFAULT_ANIM_ID.equals(lock.def().id())) {
            // 本端缺这段动画：退回默认动作，别停住
            CuddleAnimDef fallback = CuddleAnimRegistry.get(CuddleConstants.DEFAULT_ANIM_ID);
            if (fallback != null) {
                clip = CuddleAnimRegistry.clip(lock.senderSide() ? fallback.senderId() : fallback.receiverId());
            }
        }
        return clip;
    }

    private static float animationTick(Lock lock, float tickDelta) {
        double elapsed = now() - lock.startTick() + tickDelta;
        return (float) (Math.max(0.0D, elapsed) * lock.def().speed());
    }

    private static double now() {
        ClientWorld world = MinecraftClient.getInstance().world;
        return world == null ? 0.0D : world.getTime();
    }

    @Nullable
    private static Entity entity(int entityId) {
        ClientWorld world = MinecraftClient.getInstance().world;
        return world == null || entityId < 0 ? null : world.getEntityById(entityId);
    }
}

