package xyz.skyofsimba.command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsStore;
import xyz.skyofsimba.network.CuddleNetwork;
import xyz.skyofsimba.network.CuddleSelectedPayload;
import xyz.skyofsimba.core.CuddleIds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端动作选择：校验「在能力列表里 + 每 tick 最多一次」后写物品 NBT，并回执。
 *
 * <p>客户端只负责算出「下一个动作 id」，最终以服务端写入的 NBT 为准。
 */
public final class CuddleActionHandler {

    private static final Map<UUID, Long> LAST_SELECT_TICK = new HashMap<>();

    private CuddleActionHandler() {}

    public static void handleSelect(ServerPlayer player, String rawAnimId, int hand) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            long now = server.getTickCount();
            Long last = LAST_SELECT_TICK.get(player.getUUID());
            if (last != null && last == now) {
                return;                                   // 同一 tick 只处理一次
            }
            LAST_SELECT_TICK.put(player.getUUID(), now);
        }

        String animId = CuddleIds.normalize(rawAnimId);
        if (animId == null) {
            return;
        }
        if (!CuddleCapsStore.capsOf(player).has(animId)) {
            return;                                       // 不在该客户端上报的列表里：忽略
        }

        InteractionHand interactionHand = hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(interactionHand);
        if (!(stack.getItem() instanceof CuddleTicketItem)) {
            return;
        }
        if (animId.equals(CuddleTicketItem.getSelectedAnimation(stack))) {
            return;
        }

        CuddleTicketItem.setSelectedAnimation(stack, animId);
        CuddleNetwork.sendTo(player, new CuddleSelectedPayload(animId));
    }

    /** 掉线时清理节流表。 */
    public static void onDisconnect(UUID playerId) {
        LAST_SELECT_TICK.remove(playerId);
    }
}
