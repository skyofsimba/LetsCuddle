package xyz.skyofsimba.network;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端的「客户端能力」快照：{@code UUID → CuddleCaps}。
 *
 * <p>只从 {@link CuddleCapsPayload} 写入，只在服务端主线程读写。
 * 玩家登录时发 {@link CuddleRequestCapsPayload}；{@link CuddleConstants#CAPS_TIMEOUT_TICKS} 内没收到
 * 就按「只有内置动作」处理并提示一次，避免发起请求时无限等待。
 */
public final class CuddleCapsStore {

    /** 内置动作的能力快照：客户端没上报时的兜底。 */
    public static final CuddleCaps BUILTIN_ONLY = CuddleCaps.sanitize(
            CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
            Map.of(CuddleConstants.BUILTIN_NORMAL,
                            new CuddleCaps.Anim(CuddleConstants.DEFAULT_LOCK_TICKS, "1"),
                    CuddleConstants.BUILTIN_TOUCH_HEAD,
                            new CuddleCaps.Anim(CuddleConstants.DEFAULT_LOCK_TICKS, "1")));

    private record Entry(CuddleCaps caps, long joinTick, boolean reported, boolean timedOutNotice) {}

    private static final Map<UUID, Entry> CAPS = new HashMap<>();

    private CuddleCapsStore() {}

    /** 玩家登录：登记空快照、请求能力。 */
    public static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        long now = server == null ? 0L : server.getTickCount();
        CAPS.put(player.getUUID(), new Entry(CuddleCaps.EMPTY, now, false, false));
        CuddleNetwork.sendTo(player, new CuddleRequestCapsPayload());
    }

    /** 收到能力上报：清洗后替换快照；协议/格式不符的记录并忽略（按内置处理）。 */
    public static void onCaps(ServerPlayer player, CuddleCaps caps) {
        Entry previous = CAPS.get(player.getUUID());
        long joinTick = previous == null ? 0L : previous.joinTick();
        if (!caps.compatible()) {
            Letscuddle.LOGGER.warn("LetsCuddle: ignoring incompatible caps from {} (protocol={}, format={})",
                    player.getGameProfile().getName(), caps.protocol(), caps.format());
            return;
        }
        CAPS.put(player.getUUID(), new Entry(caps, joinTick, true, true));
        Letscuddle.LOGGER.debug("LetsCuddle: {} reported {} animations",
                player.getGameProfile().getName(), caps.animations().size());
    }

    public static void onDisconnect(ServerPlayer player) {
        CAPS.remove(player.getUUID());
    }

    /** 该玩家的能力；没上报/超时按内置处理。 */
    public static CuddleCaps capsOf(ServerPlayer player) {
        Entry entry = CAPS.get(player.getUUID());
        return entry != null && entry.reported() ? entry.caps() : BUILTIN_ONLY;
    }

    /** 每 tick：给超时未上报的玩家提示一次。 */
    public static void tick(MinecraftServer server) {
        if (CAPS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<UUID> timedOut = new ArrayList<>();
        for (Map.Entry<UUID, Entry> mapEntry : CAPS.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (!entry.reported() && !entry.timedOutNotice()
                    && now - entry.joinTick() >= CuddleConstants.CAPS_TIMEOUT_TICKS) {
                timedOut.add(mapEntry.getKey());
            }
        }
        for (UUID id : timedOut) {
            Entry entry = CAPS.get(id);
            CAPS.put(id, new Entry(entry.caps(), entry.joinTick(), false, true));
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                player.sendSystemMessage(Component.translatable("letscuddle.message.caps_timeout")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    /** 退出世界/服务端重启时清空，避免把上一个存档的快照带到下一个。 */
    public static void clear() {
        CAPS.clear();
    }
}
