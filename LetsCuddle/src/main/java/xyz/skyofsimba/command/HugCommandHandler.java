package xyz.skyofsimba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.animation.HugAnimationEnum;
import xyz.skyofsimba.network.HugNetwork;
import xyz.skyofsimba.network.HugRenderPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端状态机：请求 → 接受/拒绝 → 120 tick 锁定 → 解锁。
 *
 * <p>与原实现的差异全部是 port-plan 8.1 的 PORT-FIX：
 * <ul>
 *   <li>K1/K2：删掉 {@code ScheduledExecutorService}，请求带 {@code expireAt}，在 ServerTickEvent 里扫（不再有跨线程访问与永不关闭的线程池）；</li>
 *   <li>K3：「已接受」消息移到站位校验之后，站不住时只会收到「无法站立」；</li>
 *   <li>K5：两人 XZ 完全重合时落点返回 null，走「无法站立」分支而不是 NaN；</li>
 *   <li>K9：掉线立刻清理并广播结束包（客户端另有 200 tick 兜底）；</li>
 *   <li>K11：去掉恒真的 {@code active} 字段，结束/掉线统一走 {@link #broadcastHugEnd}；</li>
 *   <li>K13：校验请求的接收者确实是执行命令的人。</li>
 * </ul>
 *
 * <p>KEEP（明确不改）：落点方向（K4）、1.3 格间距、120 tick、60 秒、64 格广播、有序 Pair 键（K12）、状态不持久化（K17）。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HugCommandHandler {

    /** 请求 60 秒后过期。 */
    private static final long REQUEST_TIMEOUT_TICKS = 20L * 60L;
    /** 拥抱锁定 6 秒。 */
    private static final int ANIM_TICKS = 20 * 6;
    private static final double MAX_DISTANCE = 5.0D;
    private static final double NEARBY_RADIUS = 64.0D;
    private static final double STAND_OFFSET = 1.3D;
    private static final double STAND_HALF_WIDTH = 0.3D;
    private static final Random RANDOM = new Random();

    private static final Map<Pair<UUID, UUID>, HugRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Pair<UUID, UUID>, HugStatus> HUGS = new ConcurrentHashMap<>();

    private HugCommandHandler() {}

    // ------------------------------------------------------------------ 状态对象

    /**
     * @param senderId   发起者
     * @param receiverId 接受者
     * @param expireAt   过期时刻（服务端 tick 计数，PORT-FIX K1/K2）
     */
    public record HugRequest(UUID senderId, UUID receiverId, long expireAt) {}

    private static final class HugStatus {
        final UUID senderId;
        final UUID receiverId;
        final Vec3 senderPos;
        final Vec3 receiverPos;
        final List<ServerPlayer> nearbyPlayers;
        int ticksRemaining;

        HugStatus(UUID senderId, UUID receiverId, Vec3 senderPos, Vec3 receiverPos,
                  int ticksRemaining, List<ServerPlayer> nearbyPlayers) {
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.senderPos = senderPos;
            this.receiverPos = receiverPos;
            this.ticksRemaining = ticksRemaining;
            this.nearbyPlayers = nearbyPlayers;
        }
    }

    // ------------------------------------------------------------------ 命令

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("letscuddle")
                .then(Commands.literal("acceptRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getServer().getPlayerList()
                                            .getPlayerByName(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayer receiver = ctx.getSource().getPlayerOrException();
                                    if (sender == null) {
                                        ctx.getSource().sendFailure(red("letscuddle.message.sendernotfound"));
                                        return 0;
                                    }
                                    handleHugAcceptance(sender, receiver);
                                    return 1;
                                })))
                .then(Commands.literal("rejectRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getServer().getPlayerList()
                                            .getPlayerByName(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayer receiver = ctx.getSource().getPlayerOrException();
                                    if (sender == null) {
                                        ctx.getSource().sendFailure(red("letscuddle.message.sendernotfound"));
                                        return 0;
                                    }
                                    handleHugRejection(sender, receiver);
                                    return 1;
                                }))));
    }

    // ------------------------------------------------------------------ 发起 / 接受 / 拒绝

    public static boolean startHugRequest(ServerPlayer sender, ServerPlayer receiver) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }

        Pair<UUID, UUID> key = key(sender, receiver);
        if (REQUESTS.containsKey(key)) {
            sender.sendSystemMessage(red("letscuddle.message.have_request"));
            return false;
        }
        REQUESTS.put(key, new HugRequest(sender.getUUID(), receiver.getUUID(),
                server.getTickCount() + REQUEST_TIMEOUT_TICKS));
        return true;
    }

    private static void handleHugAcceptance(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        HugRequest request = REQUESTS.get(key);

        if (request == null || !request.receiverId().equals(receiver.getUUID())) {   // PORT-FIX K13
            receiver.sendSystemMessage(red("letscuddle.message.invalid_request"));
            return;
        }
        if (HUGS.containsKey(key)) {
            sender.sendSystemMessage(red2("letscuddle.message.hug_in_progress_sender", receiver));
            receiver.sendSystemMessage(red2("letscuddle.message.hug_in_progress_receiver", sender));
            return;
        }
        if (sender.level() != receiver.level()) {
            receiver.sendSystemMessage(red2("letscuddle.message.invalid_level", sender));
            return;
        }
        if (receiver.distanceTo(sender) > MAX_DISTANCE) {
            receiver.sendSystemMessage(red2("letscuddle.message.too_far", sender));
            return;
        }

        Vec3 stand = computeStandPosition(sender, receiver);
        if (stand == null || !isPositionSafe(stand, sender.level())) {               // PORT-FIX K5
            receiver.sendSystemMessage(red2("letscuddle.message.unsafe_position", sender));
            return;
        }

        // PORT-FIX K3：先校验、后播「已接受」，避免「接受了却站不住」的双消息
        sender.sendSystemMessage(green2("letscuddle.message.sender_accepted", receiver));
        receiver.sendSystemMessage(green2("letscuddle.message.receiver_accepted", sender));

        receiver.teleportTo(stand.x, stand.y, stand.z);
        receiver.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(sender.getX(), sender.getEyeY(), sender.getZ()));

        broadcastHugStart(sender, receiver);
        REQUESTS.remove(key);
    }

    private static void handleHugRejection(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        HugRequest request = REQUESTS.get(key);

        if (request != null && request.receiverId().equals(receiver.getUUID())) {
            sender.sendSystemMessage(red2("letscuddle.message.sender_rejected", receiver));
            receiver.sendSystemMessage(red2("letscuddle.message.receiver_rejected", sender));
            REQUESTS.remove(key);
        } else {
            receiver.sendSystemMessage(red("letscuddle.message.invalid_request"));
        }
    }

    // ------------------------------------------------------------------ 落点与安全检查

    /**
     * 原式：{@code R' = C + 1.3 * normalise(R - C)}，即把接受者摆到<b>发起者另一侧</b> 1.3 格处
     * （语义按原实现保留，KEEP K4）。XZ 完全重合时返回 null（PORT-FIX K5）。
     */
    private static Vec3 computeStandPosition(ServerPlayer sender, ServerPlayer receiver) {
        Vec3 delta = sender.position().subtract(receiver.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-4D) {
            return null;
        }

        double offsetX = delta.x / horizontal * STAND_OFFSET;
        double offsetZ = delta.z / horizontal * STAND_OFFSET;
        return new Vec3(sender.getX() + offsetX, sender.getY(), sender.getZ() + offsetZ);
    }

    private static boolean isPositionSafe(Vec3 pos, Level level) {
        return canStand(pos, level) && canPass(pos, level);
    }

    /** 脚下要有碰撞（可站立）。 */
    private static boolean canStand(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - STAND_HALF_WIDTH, pos.y - 0.1D, pos.z - STAND_HALF_WIDTH,
                pos.x + STAND_HALF_WIDTH, pos.y, pos.z + STAND_HALF_WIDTH);
        return level.getBlockCollisions(null, box).iterator().hasNext();
    }

    /** 身体高度内不能有碰撞（可通过）。 */
    private static boolean canPass(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - STAND_HALF_WIDTH, pos.y + 0.1D, pos.z - STAND_HALF_WIDTH,
                pos.x + STAND_HALF_WIDTH, pos.y + 1.7D, pos.z + STAND_HALF_WIDTH);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    // ------------------------------------------------------------------ 广播与状态

    private static void broadcastHugStart(ServerPlayer sender, ServerPlayer receiver) {
        List<ServerPlayer> nearby = getNearbyPlayers(sender);
        HUGS.put(key(sender, receiver), new HugStatus(
                sender.getUUID(), receiver.getUUID(),
                sender.position(), receiver.position(),
                ANIM_TICKS, nearby));

        // 整批只抽一次，所有接收者拿到同一个动画号（原仓库 93f9487 的教训）
        int animation = RANDOM.nextInt(HugAnimationEnum.values().length);
        HugRenderPayload payload = new HugRenderPayload(
                sender.getUUID().toString(), receiver.getUUID().toString(), false, animation);
        for (ServerPlayer player : nearby) {
            HugNetwork.sendTo(player, payload);
        }
    }

    private static void broadcastHugEnd(HugStatus status) {
        HugRenderPayload payload = new HugRenderPayload(
                status.senderId.toString(), status.receiverId.toString(), true, 0);
        for (ServerPlayer player : status.nearbyPlayers) {
            if (player == null || player.hasDisconnected()) {
                continue;
            }
            HugNetwork.sendTo(player, payload);
        }
    }

    private static List<ServerPlayer> getNearbyPlayers(ServerPlayer sender) {
        List<ServerPlayer> players = new ArrayList<>();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return players;
        }

        Level level = sender.level();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            if (player.distanceTo(sender) < NEARBY_RADIUS) {
                players.add(player);
            }
        }
        return players;
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {      // 只在 tick 末做一次
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        expireRequests(server);
        tickHugs(server);
    }

    /** PORT-FIX K1/K2：过期扫描搬到主线程 tick，替换原来的 60 秒调度线程。 */
    private static void expireRequests(MinecraftServer server) {
        if (REQUESTS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<Pair<UUID, UUID>, HugRequest>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            HugRequest request = it.next().getValue();
            if (now < request.expireAt()) {
                continue;
            }
            it.remove();

            ServerPlayer sender = server.getPlayerList().getPlayer(request.senderId());
            ServerPlayer receiver = server.getPlayerList().getPlayer(request.receiverId());
            if (sender == null || receiver == null) {
                continue;      // 有人不在线就不打扰
            }
            sender.sendSystemMessage(red2("letscuddle.message.request_expired", receiver));
            receiver.sendSystemMessage(red2("letscuddle.message.request_expired", sender));
        }
    }

    /** 每 tick 把两人拉回原位（不能走动、不能被打飞），并递减锁定计数。 */
    private static void tickHugs(MinecraftServer server) {
        if (HUGS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Pair<UUID, UUID>, HugStatus>> it = HUGS.entrySet().iterator();
        while (it.hasNext()) {
            HugStatus status = it.next().getValue();
            ServerPlayer sender = server.getPlayerList().getPlayer(status.senderId);
            ServerPlayer receiver = server.getPlayerList().getPlayer(status.receiverId);

            if (sender == null || receiver == null) {          // PORT-FIX K9
                it.remove();
                broadcastHugEnd(status);                        // 让旁观者解锁
                continue;
            }

            sender.teleportTo(status.senderPos.x, status.senderPos.y, status.senderPos.z);
            receiver.teleportTo(status.receiverPos.x, status.receiverPos.y, status.receiverPos.z);

            if (--status.ticksRemaining <= 0) {
                it.remove();
                broadcastHugEnd(status);
            }
        }
    }

    /** PORT-FIX K9：掉线立刻清理，不等 tick 循环发现；同时广播结束包让旁观者解锁。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();

        Iterator<Map.Entry<Pair<UUID, UUID>, HugRequest>> requests = REQUESTS.entrySet().iterator();
        while (requests.hasNext()) {
            Pair<UUID, UUID> key = requests.next().getKey();
            if (key.getFirst().equals(id) || key.getSecond().equals(id)) {
                requests.remove();
            }
        }

        Iterator<Map.Entry<Pair<UUID, UUID>, HugStatus>> hugs = HUGS.entrySet().iterator();
        while (hugs.hasNext()) {
            HugStatus status = hugs.next().getValue();
            if (!status.senderId.equals(id) && !status.receiverId.equals(id)) {
                continue;
            }
            hugs.remove();
            broadcastHugEnd(status);
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 请求键是<b>有序</b> Pair：A→B 与 B→A 可并存（KEEP K12）。 */
    private static Pair<UUID, UUID> key(ServerPlayer sender, ServerPlayer receiver) {
        return new Pair<>(sender.getUUID(), receiver.getUUID());
    }

    private static Component red(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static Component green2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.GREEN);
    }

    private static Component red2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.RED);
    }
}
