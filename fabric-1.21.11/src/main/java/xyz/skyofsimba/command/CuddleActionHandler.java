package xyz.skyofsimba.command;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import xyz.skyofsimba.core.CuddleIds;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsStore;
import xyz.skyofsimba.network.CuddleNetwork;
import xyz.skyofsimba.network.CuddleSelectedPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 服务端动作选择：校验「在能力列表里 + 每 tick 最多一次」后写组件，并回执。 */
public final class CuddleActionHandler {

    private static final Map<UUID, Long> LAST_SELECT_TICK = new HashMap<>();

    private CuddleActionHandler() {}

    public static void handleSelect(ServerPlayerEntity player, String rawAnimId, int hand) {
        MinecraftServer server = serverOf(player);
        if (server != null) {
            long now = server.getTicks();
            Long last = LAST_SELECT_TICK.get(player.getUuid());
            if (last != null && last == now) {
                return;
            }
            LAST_SELECT_TICK.put(player.getUuid(), now);
        }

        String animId = CuddleIds.normalize(rawAnimId);
        if (animId == null || !CuddleCapsStore.capsOf(player).has(animId)) {
            return;
        }

        Hand targetHand = hand == 1 ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack stack = player.getStackInHand(targetHand);
        if (!(stack.getItem() instanceof CuddleTicketItem)) {
            return;
        }
        if (animId.equals(CuddleTicketItem.getSelectedAnimation(stack))) {
            return;
        }

        CuddleTicketItem.setSelectedAnimation(stack, animId);
        CuddleNetwork.send(player, new CuddleSelectedPayload(animId));
    }

    public static void onDisconnect(UUID playerId) {
        LAST_SELECT_TICK.remove(playerId);
    }

    private static MinecraftServer serverOf(ServerPlayerEntity player) {
        return player.getEntityWorld() instanceof ServerWorld world ? world.getServer() : null;
    }
}
