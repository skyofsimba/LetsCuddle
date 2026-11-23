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
import xyz.skyofsimba.core.CuddleAnimNames;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;
import xyz.skyofsimba.core.CuddlePlacement;
import xyz.skyofsimba.core.Vec3d;
import xyz.skyofsimba.network.CuddleCapsStore;
import xyz.skyofsimba.network.CuddleNetwork;
import xyz.skyofsimba.network.CuddleRenderPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端状态机：请求 → 接受/拒绝 → 锁定 → 解锁。
 *
 * <p>1.x 的 PORT-FIX 全部保留：过期在主线程 tick（K1/K2）、先校验后播「已接受」（K3）、
 * 重合退化为无法站立（K5）、掉线立刻清理（K9）、结束走同一条广播路径（K11）、
 * 校验请求接收者（K13）。2.0 新增动作 id 字符串化与能力回退（§6.3）。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CuddleCommandHandler {

    private static final Map<Pair<UUID, UUID>, CuddleRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Pair<UUID, UUID>, CuddleStatus> CUDDLES = new ConcurrentHashMap<>();

    private CuddleCommandHandler() {}

    // ------------------------------------------------------------------ 状态对象

    /**
     * @param animId         最终生效的动作 id（已按双方能力回退过）
     * @param lockTicks      锁定 tick（双方 lock_ticks 取小后夹紧）
     * @param contentVersion 发起者侧该动作的内容版本，随广播下发供旁观者比对
     */
    public record CuddleRequest(UUID senderId, UUID receiverId, long expireAt,
                                String animId, int lockTicks, String contentVersion) {}

    private static final class CuddleStatus {
        final UUID senderId;
        final UUID receiverId;
        final Vec3 senderPos;
        final Vec3 receiverPos;
        final List<ServerPlayer> nearbyPlayers;
        final String animId;
        final String contentVersion;
        int ticksRemaining;
        /** v1.4：连续蹲下的 tick 数，达到 SNEAK_CANCEL_TICKS 立刻结束贴贴。 */
        int sneakTicks;

        CuddleStatus(UUID senderId, UUID receiverId, Vec3 senderPos, Vec3 receiverPos,
                     List<ServerPlayer> nearbyPlayers, String animId, String contentVersion, int ticksRemaining) {
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.senderPos = senderPos;
            this.receiverPos = receiverPos;
            this.nearbyPlayers = nearbyPlayers;
            this.animId = animId;
            this.contentVersion = contentVersion;
            this.ticksRemaining = ticksRemaining;
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
                                    handleCuddleAcceptance(sender, receiver);
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
                                    handleCuddleRejection(sender, receiver);
                                    return 1;
                                }))));
    }

    // ------------------------------------------------------------------ 发起 / 接受 / 拒绝

    /**
     * 登记请求并按双方能力决定最终动作与锁定时间（§6.3）：
     * 接受者没有该动作、或两端内容版本不一致 → 回退默认动作并提示发起者。
     *
     * @param requestedAnimId 发起者券上选的动作
     * @return 是否登记成功
     */
    public static boolean startCuddleRequest(ServerPlayer sender, ServerPlayer receiver, String requestedAnimId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }

        Pair<UUID, UUID> key = key(sender, receiver);
        if (REQUESTS.containsKey(key)) {
            sender.sendSystemMessage(red("letscuddle.message.have_request"));
            return false;
        }

        CuddleCaps senderCaps = CuddleCapsStore.capsOf(sender);
        CuddleCaps receiverCaps = CuddleCapsStore.capsOf(receiver);
        String requested = CuddleIds.normalize(requestedAnimId);
        if (requested == null) {
            requested = CuddleConstants.DEFAULT_ANIM_ID;
        }

        String animId = requested;
        if (!receiverCaps.has(requested)) {
            sender.sendSystemMessage(yellow(Component.translatable("letscuddle.message.anim_unavailable",
                    receiver.getName().getString(), animName(requested))));
            animId = CuddleConstants.DEFAULT_ANIM_ID;
        } else if (!senderCaps.sameContent(requested, receiverCaps)) {
            sender.sendSystemMessage(yellow(Component.translatable("letscuddle.message.anim_version_mismatch",
                    receiver.getName().getString(), animName(requested))));
            animId = CuddleConstants.DEFAULT_ANIM_ID;
        }

        int lockTicks = senderCaps.negotiatedLock(animId, receiverCaps);
        String contentVersion = senderCaps.contentVersion(animId);
        if (contentVersion == null) {
            contentVersion = "1";
        }

        REQUESTS.put(key, new CuddleRequest(sender.getUUID(), receiver.getUUID(),
                server.getTickCount() + CuddleConstants.REQUEST_TIMEOUT_TICKS, animId, lockTicks, contentVersion));

        xyz.skyofsimba.item.CuddleTicketItem.sendRequestMessage(sender, receiver, animName(animId));
        sender.sendSystemMessage(Component.translatable("letscuddle.message.sendsuccess",
                receiver.getName().getString(), animName(animId)).withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static void handleCuddleAcceptance(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        CuddleRequest request = REQUESTS.get(key);

        if (request == null || !request.receiverId().equals(receiver.getUUID())) {   // PORT-FIX K13
            receiver.sendSystemMessage(red("letscuddle.message.invalid_request"));
            return;
        }
        if (CUDDLES.containsKey(key)) {
            sender.sendSystemMessage(red2("letscuddle.message.cuddle_in_progress_sender", receiver));
            receiver.sendSystemMessage(red2("letscuddle.message.cuddle_in_progress_receiver", sender));
            return;
        }
        if (sender.level() != receiver.level()) {
            receiver.sendSystemMessage(red2("letscuddle.message.invalid_level", sender));
            return;
        }
        if (receiver.distanceTo(sender) > CuddleConstants.MAX_DISTANCE) {
            receiver.sendSystemMessage(red2("letscuddle.message.too_far", sender));
            return;
        }

        Vec3 stand = computeStandPosition(sender, receiver);
        if (stand == null || !isPositionSafe(stand, sender.level())) {               // PORT-FIX K5
            receiver.sendSystemMessage(red2("letscuddle.message.unsafe_position", sender));
            return;
        }

        // PORT-FIX K3：先校验、后播「已接受」
        sender.sendSystemMessage(green2("letscuddle.message.sender_accepted", receiver));
        receiver.sendSystemMessage(green2("letscuddle.message.receiver_accepted", sender));

        receiver.teleportTo(stand.x, stand.y, stand.z);
        receiver.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(sender.getX(), sender.getEyeY(), sender.getZ()));
        // v1.5.1：发起者也转向接受者，否则第三人称镜头仍朝旧方向、整段贴贴跑到画面外
        sender.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(receiver.getX(), receiver.getEyeY(), receiver.getZ()));

        broadcastCuddleStart(sender, receiver, request);
        REQUESTS.remove(key);
    }

    private static void handleCuddleRejection(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        CuddleRequest request = REQUESTS.get(key);

        if (request != null && request.receiverId().equals(receiver.getUUID())) {
            sender.sendSystemMessage(red2("letscuddle.message.sender_rejected", receiver));
            receiver.sendSystemMessage(red2("letscuddle.message.receiver_rejected", sender));
            REQUESTS.remove(key);
        } else {
            receiver.sendSystemMessage(red("letscuddle.message.invalid_request"));
        }
    }

    // ------------------------------------------------------------------ 落点与安全检查

    /** 1.x 公式：接受者落到发起者另一侧 {@link CuddleConstants#STAND_OFFSET} 格（KEEP K4）。 */
    private static Vec3 computeStandPosition(ServerPlayer sender, ServerPlayer receiver) {
        Vec3d position = CuddlePlacement.standPosition(
                new Vec3d(sender.getX(), sender.getY(), sender.getZ()),
                new Vec3d(receiver.getX(), receiver.getY(), receiver.getZ()));
        return position == null ? null : new Vec3(position.x(), position.y(), position.z());
    }

    private static boolean isPositionSafe(Vec3 pos, Level level) {
        return canStand(pos, level) && canPass(pos, level);
    }

    /** 脚下要有碰撞（可站立）。 */
    private static boolean canStand(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - CuddleConstants.STAND_HALF_WIDTH, pos.y - 0.1D, pos.z - CuddleConstants.STAND_HALF_WIDTH,
                pos.x + CuddleConstants.STAND_HALF_WIDTH, pos.y, pos.z + CuddleConstants.STAND_HALF_WIDTH);
        return level.getBlockCollisions(null, box).iterator().hasNext();
    }

    /** 身体高度内不能有碰撞（可通过）。 */
    private static boolean canPass(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - CuddleConstants.STAND_HALF_WIDTH, pos.y + 0.1D, pos.z - CuddleConstants.STAND_HALF_WIDTH,
                pos.x + CuddleConstants.STAND_HALF_WIDTH, pos.y + 1.7D, pos.z + CuddleConstants.STAND_HALF_WIDTH);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    // ------------------------------------------------------------------ 广播与状态

    private static void broadcastCuddleStart(ServerPlayer sender, ServerPlayer receiver, CuddleRequest request) {
        List<ServerPlayer> nearby = getNearbyPlayers(sender);
        CUDDLES.put(key(sender, receiver), new CuddleStatus(
                sender.getUUID(), receiver.getUUID(),
                sender.position(), receiver.position(), nearby,
                request.animId(), request.contentVersion(), request.lockTicks()));

        CuddleRenderPayload payload = new CuddleRenderPayload(
                sender.getUUID().toString(), receiver.getUUID().toString(), false, false,
                request.animId(), request.contentVersion());
        for (ServerPlayer player : nearby) {
            CuddleNetwork.sendTo(player, payload);
        }
    }

    /**
     * @param cancelled true 表示被蹲下提前取消，客户端要立即收掉动作；
     *                  正常结束/掉线解锁传 false（动作自然播完）
     */
    private static void broadcastCuddleEnd(CuddleStatus status, boolean cancelled) {
        CuddleRenderPayload payload = new CuddleRenderPayload(
                status.senderId.toString(), status.receiverId.toString(), true, cancelled, "", "");
        for (ServerPlayer player : status.nearbyPlayers) {
            if (player == null || player.hasDisconnected()) {
                continue;
            }
            CuddleNetwork.sendTo(player, payload);
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
            if (player.distanceTo(sender) < CuddleConstants.NEARBY_RADIUS) {
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
        tickCuddles(server);
        CuddleCapsStore.tick(server);
    }

    /** PORT-FIX K1/K2：过期扫描在主线程 tick，没有调度线程。 */
    private static void expireRequests(MinecraftServer server) {
        if (REQUESTS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleRequest>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            CuddleRequest request = it.next().getValue();
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
    private static void tickCuddles(MinecraftServer server) {
        if (CUDDLES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleStatus>> it = CUDDLES.entrySet().iterator();
        while (it.hasNext()) {
            CuddleStatus status = it.next().getValue();
            ServerPlayer sender = server.getPlayerList().getPlayer(status.senderId);
            ServerPlayer receiver = server.getPlayerList().getPlayer(status.receiverId);

            if (sender == null || receiver == null) {          // PORT-FIX K9
                it.remove();
                broadcastCuddleEnd(status, true);
                continue;
            }

            sender.teleportTo(status.senderPos.x, status.senderPos.y, status.senderPos.z);
            receiver.teleportTo(status.receiverPos.x, status.receiverPos.y, status.receiverPos.z);

            if (sender.isShiftKeyDown() || receiver.isShiftKeyDown()) {
                if (++status.sneakTicks >= CuddleConstants.SNEAK_CANCEL_TICKS) {
                    it.remove();
                    broadcastCuddleEnd(status, true);
                    Component message = Component.translatable("letscuddle.message.cuddle_cancelled")
                            .withStyle(ChatFormatting.YELLOW);
                    sender.sendSystemMessage(message);
                    receiver.sendSystemMessage(message);
                    continue;
                }
            } else {
                status.sneakTicks = 0;
            }

            if (--status.ticksRemaining <= 0) {
                it.remove();
                broadcastCuddleEnd(status, false);
            }
        }
    }

    /** PORT-FIX K9：掉线立刻清理并广播结束包。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        CuddleCapsStore.onDisconnect(player);
        CuddleActionHandler.onDisconnect(id);

        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleRequest>> requests = REQUESTS.entrySet().iterator();
        while (requests.hasNext()) {
            Pair<UUID, UUID> key = requests.next().getKey();
            if (key.getFirst().equals(id) || key.getSecond().equals(id)) {
                requests.remove();
            }
        }

        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleStatus>> cuddles = CUDDLES.entrySet().iterator();
        while (cuddles.hasNext()) {
            CuddleStatus status = cuddles.next().getValue();
            if (!status.senderId.equals(id) && !status.receiverId.equals(id)) {
                continue;
            }
            cuddles.remove();
            broadcastCuddleEnd(status, true);
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 请求键是<b>有序</b> Pair：A→B 与 B→A 可并存（KEEP K12）。 */
    private static Pair<UUID, UUID> key(ServerPlayer sender, ServerPlayer receiver) {
        return new Pair<>(sender.getUUID(), receiver.getUUID());
    }

    /** 服务端能显示的动作名：内置动作走语言键，外部动作用 id。 */
    private static Component animName(String animId) {
        String key = CuddleAnimNames.builtinNameKey(animId);
        return key == null ? Component.literal(animId) : Component.translatable(key);
    }

    private static Component red(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static Component yellow(Component component) {
        return component.copy().withStyle(ChatFormatting.YELLOW);
    }

    private static Component green2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.GREEN);
    }

    private static Component red2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.RED);
    }
}
