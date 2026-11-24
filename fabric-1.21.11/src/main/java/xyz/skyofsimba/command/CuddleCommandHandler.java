package xyz.skyofsimba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.skyofsimba.core.CuddleBuiltins;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;
import xyz.skyofsimba.core.CuddlePlacement;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsStore;
import xyz.skyofsimba.network.CuddleNetwork;
import xyz.skyofsimba.network.CuddleRenderPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端状态机（1.21.11）：请求 → 接受/拒绝 → 锁定 → 解锁。
 *
 * <p>与 1.20.1 版本同名同结构，只换平台 API；行为、消息顺序、回退规则完全一致。
 */
public final class CuddleCommandHandler {

    private static final Map<Pair<UUID, UUID>, CuddleRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Pair<UUID, UUID>, CuddleStatus> CUDDLES = new ConcurrentHashMap<>();

    private CuddleCommandHandler() {}

    public record CuddleRequest(UUID senderId, UUID receiverId, long expireAt,
                                String animId, int lockTicks, String contentVersion) {}

    private static final class CuddleStatus {
        final UUID senderId;
        final UUID receiverId;
        final Vec3d senderPos;
        final Vec3d receiverPos;
        final List<ServerPlayerEntity> nearbyPlayers;
        final String animId;
        final String contentVersion;
        int ticksRemaining;
        int sneakTicks;

        CuddleStatus(UUID senderId, UUID receiverId, Vec3d senderPos, Vec3d receiverPos,
                     List<ServerPlayerEntity> nearbyPlayers, String animId, String contentVersion,
                     int ticksRemaining) {
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

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("letscuddle")
                .then(CommandManager.literal("acceptRequest")
                        .then(CommandManager.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayerEntity sender = ctx.getSource().getServer().getPlayerManager()
                                            .getPlayer(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayerEntity receiver = ctx.getSource().getPlayerOrThrow();
                                    if (sender == null) {
                                        ctx.getSource().sendError(red("letscuddle.message.sendernotfound"));
                                        return 0;
                                    }
                                    handleCuddleAcceptance(sender, receiver);
                                    return 1;
                                })))
                .then(CommandManager.literal("rejectRequest")
                        .then(CommandManager.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayerEntity sender = ctx.getSource().getServer().getPlayerManager()
                                            .getPlayer(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayerEntity receiver = ctx.getSource().getPlayerOrThrow();
                                    if (sender == null) {
                                        ctx.getSource().sendError(red("letscuddle.message.sendernotfound"));
                                        return 0;
                                    }
                                    handleCuddleRejection(sender, receiver);
                                    return 1;
                                }))));
    }

    // ------------------------------------------------------------------ 发起 / 接受 / 拒绝

    public static boolean startCuddleRequest(ServerPlayerEntity sender, ServerPlayerEntity receiver,
                                             String requestedAnimId) {
        MinecraftServer server = serverOf(sender);
        if (server == null) {
            return false;
        }
        Pair<UUID, UUID> key = key(sender, receiver);
        if (REQUESTS.containsKey(key)) {
            sender.sendMessage(red("letscuddle.message.have_request"), false);
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
            sender.sendMessage(yellow(Text.translatable("letscuddle.message.anim_unavailable",
                    receiver.getName().getString(), animName(requested, senderCaps))), false);
            animId = CuddleConstants.DEFAULT_ANIM_ID;
        } else if (!senderCaps.sameContent(requested, receiverCaps)) {
            sender.sendMessage(yellow(Text.translatable("letscuddle.message.anim_version_mismatch",
                    receiver.getName().getString(), animName(requested, senderCaps))), false);
            animId = CuddleConstants.DEFAULT_ANIM_ID;
        }

        int lockTicks = senderCaps.negotiatedLock(animId, receiverCaps);
        String contentVersion = senderCaps.contentVersion(animId);
        if (contentVersion == null) {
            contentVersion = "1";
        }

        REQUESTS.put(key, new CuddleRequest(sender.getUuid(), receiver.getUuid(),
                server.getTicks() + CuddleConstants.REQUEST_TIMEOUT_TICKS, animId, lockTicks, contentVersion));

        CuddleTicketItem.sendRequestMessage(sender, receiver, animName(animId, senderCaps));
        sender.sendMessage(Text.translatable("letscuddle.message.sendsuccess",
                receiver.getName().getString(), animName(animId, senderCaps)).formatted(Formatting.GREEN), false);
        return true;
    }

    private static void handleCuddleAcceptance(ServerPlayerEntity sender, ServerPlayerEntity receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        CuddleRequest request = REQUESTS.get(key);

        if (request == null || !request.receiverId().equals(receiver.getUuid())) {
            receiver.sendMessage(red("letscuddle.message.invalid_request"), false);
            return;
        }
        if (CUDDLES.containsKey(key)) {
            sender.sendMessage(red2("letscuddle.message.cuddle_in_progress_sender", receiver), false);
            receiver.sendMessage(red2("letscuddle.message.cuddle_in_progress_receiver", sender), false);
            return;
        }
        if (sender.getEntityWorld() != receiver.getEntityWorld()) {
            receiver.sendMessage(red2("letscuddle.message.invalid_level", sender), false);
            return;
        }
        if (receiver.distanceTo(sender) > CuddleConstants.MAX_DISTANCE) {
            receiver.sendMessage(red2("letscuddle.message.too_far", sender), false);
            return;
        }

        Vec3d stand = computeStandPosition(sender, receiver);
        if (stand == null || !isPositionSafe(stand, sender.getEntityWorld())) {
            receiver.sendMessage(red2("letscuddle.message.unsafe_position", sender), false);
            return;
        }

        sender.sendMessage(green2("letscuddle.message.sender_accepted", receiver), false);
        receiver.sendMessage(green2("letscuddle.message.receiver_accepted", sender), false);

        teleportTo(receiver, stand);
        receiver.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES,
                new Vec3d(sender.getX(), sender.getEyeY(), sender.getZ()));
        sender.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES,
                new Vec3d(receiver.getX(), receiver.getEyeY(), receiver.getZ()));

        broadcastCuddleStart(sender, receiver, request);
        REQUESTS.remove(key);
    }

    private static void handleCuddleRejection(ServerPlayerEntity sender, ServerPlayerEntity receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        CuddleRequest request = REQUESTS.get(key);

        if (request != null && request.receiverId().equals(receiver.getUuid())) {
            sender.sendMessage(red2("letscuddle.message.sender_rejected", receiver), false);
            receiver.sendMessage(red2("letscuddle.message.receiver_rejected", sender), false);
            REQUESTS.remove(key);
        } else {
            receiver.sendMessage(red("letscuddle.message.invalid_request"), false);
        }
    }

    // ------------------------------------------------------------------ 落点与安全

    private static Vec3d computeStandPosition(ServerPlayerEntity sender, ServerPlayerEntity receiver) {
        xyz.skyofsimba.core.Vec3d position = CuddlePlacement.standPosition(
                new xyz.skyofsimba.core.Vec3d(sender.getX(), sender.getY(), sender.getZ()),
                new xyz.skyofsimba.core.Vec3d(receiver.getX(), receiver.getY(), receiver.getZ()));
        return position == null ? null : new Vec3d(position.x(), position.y(), position.z());
    }

    private static boolean isPositionSafe(Vec3d pos, World world) {
        return canStand(pos, world) && canPass(pos, world);
    }

    private static boolean canStand(Vec3d pos, World world) {
        Box box = new Box(
                pos.x - CuddleConstants.STAND_HALF_WIDTH, pos.y - 0.1D, pos.z - CuddleConstants.STAND_HALF_WIDTH,
                pos.x + CuddleConstants.STAND_HALF_WIDTH, pos.y, pos.z + CuddleConstants.STAND_HALF_WIDTH);
        return world.getBlockCollisions(null, box).iterator().hasNext();
    }

    private static boolean canPass(Vec3d pos, World world) {
        Box box = new Box(
                pos.x - CuddleConstants.STAND_HALF_WIDTH, pos.y + 0.1D, pos.z - CuddleConstants.STAND_HALF_WIDTH,
                pos.x + CuddleConstants.STAND_HALF_WIDTH, pos.y + 1.7D, pos.z + CuddleConstants.STAND_HALF_WIDTH);
        return !world.getBlockCollisions(null, box).iterator().hasNext();
    }

    /**
     * 把玩家按在原地，但**不动视角**。
     *
     * <p>1.21 的 {@code teleport} 每 tick 都会把服务端的 yaw/pitch 绝对写回客户端；玩家转视角时
     * 服务端的值总是慢半拍，于是镜头被反复拽回去（抽搐）。用相对旋转标志 + 0 偏移即可只同步位置。
     */
    private static final Set<PositionFlag> KEEP_ROTATION =
            Set.of(PositionFlag.Y_ROT, PositionFlag.X_ROT);

    private static void teleportTo(ServerPlayerEntity player, Vec3d pos) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        player.teleport(world, pos.x, pos.y, pos.z, KEEP_ROTATION, 0.0F, 0.0F, false);
    }

    // ------------------------------------------------------------------ 广播与状态

    private static void broadcastCuddleStart(ServerPlayerEntity sender, ServerPlayerEntity receiver,
                                             CuddleRequest request) {
        List<ServerPlayerEntity> nearby = getNearbyPlayers(sender);
        CUDDLES.put(key(sender, receiver), new CuddleStatus(
                sender.getUuid(), receiver.getUuid(),
                sender.getEntityPos(), receiver.getEntityPos(), nearby,
                request.animId(), request.contentVersion(), request.lockTicks()));

        CuddleRenderPayload payload = new CuddleRenderPayload(
                sender.getUuid().toString(), receiver.getUuid().toString(), false, false,
                request.animId(), request.contentVersion());
        for (ServerPlayerEntity player : nearby) {
            CuddleNetwork.send(player, payload);
        }
    }

    private static void broadcastCuddleEnd(CuddleStatus status, boolean cancelled) {
        CuddleRenderPayload payload = new CuddleRenderPayload(
                status.senderId.toString(), status.receiverId.toString(), true, cancelled, "", "");
        for (ServerPlayerEntity player : status.nearbyPlayers) {
            if (player == null || player.isDisconnected()) {
                continue;
            }
            CuddleNetwork.send(player, payload);
        }
    }

    private static List<ServerPlayerEntity> getNearbyPlayers(ServerPlayerEntity sender) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        MinecraftServer server = serverOf(sender);
        if (server == null) {
            return players;
        }
        World world = sender.getEntityWorld();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getEntityWorld() != world) {
                continue;
            }
            if (player.distanceTo(sender) < CuddleConstants.NEARBY_RADIUS) {
                players.add(player);
            }
        }
        return players;
    }

    // ------------------------------------------------------------------ tick / 连接

    /** 服务端 tick 末：过期扫描、锁定维持、能力超时提示。 */
    public static void onServerTick(MinecraftServer server) {
        expireRequests(server);
        tickCuddles(server);
        CuddleCapsStore.tick(server);
    }

    private static void expireRequests(MinecraftServer server) {
        if (REQUESTS.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleRequest>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            CuddleRequest request = it.next().getValue();
            if (now < request.expireAt()) {
                continue;
            }
            it.remove();

            ServerPlayerEntity sender = server.getPlayerManager().getPlayer(request.senderId());
            ServerPlayerEntity receiver = server.getPlayerManager().getPlayer(request.receiverId());
            if (sender == null || receiver == null) {
                continue;
            }
            sender.sendMessage(red2("letscuddle.message.request_expired", receiver), false);
            receiver.sendMessage(red2("letscuddle.message.request_expired", sender), false);
        }
    }

    private static void tickCuddles(MinecraftServer server) {
        if (CUDDLES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Pair<UUID, UUID>, CuddleStatus>> it = CUDDLES.entrySet().iterator();
        while (it.hasNext()) {
            CuddleStatus status = it.next().getValue();
            ServerPlayerEntity sender = server.getPlayerManager().getPlayer(status.senderId);
            ServerPlayerEntity receiver = server.getPlayerManager().getPlayer(status.receiverId);

            if (sender == null || receiver == null) {
                it.remove();
                broadcastCuddleEnd(status, true);
                continue;
            }

            teleportTo(sender, status.senderPos);
            teleportTo(receiver, status.receiverPos);

            if (sender.isSneaking() || receiver.isSneaking()) {
                if (++status.sneakTicks >= CuddleConstants.SNEAK_CANCEL_TICKS) {
                    it.remove();
                    broadcastCuddleEnd(status, true);
                    Text message = Text.translatable("letscuddle.message.cuddle_cancelled")
                            .formatted(Formatting.YELLOW);
                    sender.sendMessage(message, false);
                    receiver.sendMessage(message, false);
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

    /** 掉线：清请求、解锁并广播结束。 */
    public static void onDisconnect(ServerPlayerEntity player) {
        UUID id = player.getUuid();

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

    private static Pair<UUID, UUID> key(ServerPlayerEntity sender, ServerPlayerEntity receiver) {
        return new Pair<>(sender.getUuid(), receiver.getUuid());
    }

    private static MinecraftServer serverOf(Entity entity) {
        return entity.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld.getServer() : null;
    }

    /**
     * 服务端能显示的动作名：优先用客户端上报的 name_key/name（这样资源包里的名字能显示出来），
     * 其次内置动作走语言键，最后才是 id。
     */
    private static Text animName(String animId, CuddleCaps source) {
        CuddleCaps.Anim anim = source == null ? null : source.anim(animId);
        if (anim != null) {
            if (anim.hasNameKey()) {
                return Text.translatableWithFallback(anim.nameKey(), anim.hasName() ? anim.name() : animId);
            }
            if (anim.hasName()) {
                return Text.literal(anim.name());
            }
        }
        String key = CuddleBuiltins.builtinNameKey(animId);
        return key == null ? Text.literal(animId) : Text.translatable(key);
    }

    private static Text red(String key) {
        return Text.translatable(key).formatted(Formatting.RED);
    }

    private static Text yellow(Text text) {
        return text.copy().formatted(Formatting.YELLOW);
    }

    private static Text green2(String key, ServerPlayerEntity other) {
        return Text.translatable(key, other.getName().getString()).formatted(Formatting.GREEN);
    }

    private static Text red2(String key, ServerPlayerEntity other) {
        return Text.translatable(key, other.getName().getString()).formatted(Formatting.RED);
    }
}

