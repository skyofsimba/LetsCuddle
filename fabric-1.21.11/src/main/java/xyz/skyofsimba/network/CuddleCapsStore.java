package xyz.skyofsimba.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端的「客户端能力」快照：{@code UUID → CuddleCaps}，只在服务端主线程读写。
 *
 * <p>玩家加入时发 {@link CuddleRequestCapsPayload}；超时没收到按「只有内置动作」处理并提示一次。
 */
public final class CuddleCapsStore {

    /** 内置动作的能力快照：客户端没上报时的兜底。 */
    private static final CuddleCaps BUILTIN_ONLY = CuddleCaps.builtinOnly();

    private record Entry(CuddleCaps caps, long joinTick, boolean reported, boolean timedOutNotice) {}

    private static final Map<UUID, Entry> CAPS = new HashMap<>();

    private CuddleCapsStore() {}

    public static void onJoin(ServerPlayerEntity player) {
        long now = player.getEntityWorld() instanceof ServerWorld world ? world.getServer().getTicks() : 0L;
        CAPS.put(player.getUuid(), new Entry(CuddleCaps.EMPTY, now, false, false));
        CuddleNetwork.send(player, new CuddleRequestCapsPayload());
    }

    public static void onCaps(ServerPlayerEntity player, CuddleCaps caps) {
        Entry previous = CAPS.get(player.getUuid());
        long joinTick = previous == null ? 0L : previous.joinTick();
        if (!caps.compatible()) {
            Letscuddle.LOGGER.warn("LetsCuddle: ignoring incompatible caps from {} (protocol={}, format={})",
                    player.getName().getString(), caps.protocol(), caps.format());
            return;
        }
        CAPS.put(player.getUuid(), new Entry(caps, joinTick, true, true));
        Letscuddle.LOGGER.debug("LetsCuddle: {} reported {} animations",
                player.getName().getString(), caps.animations().size());
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        CAPS.remove(player.getUuid());
    }

    public static CuddleCaps capsOf(ServerPlayerEntity player) {
        Entry entry = CAPS.get(player.getUuid());
        return entry != null && entry.reported() ? entry.caps() : BUILTIN_ONLY;
    }

    /** 每 tick：给超时未上报的玩家提示一次。 */
    public static void tick(MinecraftServer server) {
        if (CAPS.isEmpty()) {
            return;
        }
        long now = server.getTicks();
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
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) {
                player.sendMessage(Text.translatable("letscuddle.message.caps_timeout")
                        .formatted(Formatting.YELLOW), false);
            }
        }
    }

    public static void clear() {
        CAPS.clear();
    }
}