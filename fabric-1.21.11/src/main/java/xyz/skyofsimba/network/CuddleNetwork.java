package xyz.skyofsimba.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import xyz.skyofsimba.command.CuddleActionHandler;

/**
 * 网络注册与发送。
 *
 * <p>与 Forge 不同，Fabric 没有「通道版本」概念：1.x 客户端（协议 3）能连上，但握手包里的
 * {@code protocol} 校验会把它当成「只有内置动作」，不会崩。包 id 与计划 §6.1 的表一一对应。
 */
public final class CuddleNetwork {

    private CuddleNetwork() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(CuddleCapsPayload.ID, CuddleCapsPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CuddleSelectPayload.ID, CuddleSelectPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CuddleRenderPayload.ID, CuddleRenderPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CuddleSelectedPayload.ID, CuddleSelectedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CuddleRequestCapsPayload.ID, CuddleRequestCapsPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CuddleCapsPayload.ID, (payload, context) ->
                context.server().execute(() -> CuddleCapsStore.onCaps(context.player(), payload.toCaps())));

        ServerPlayNetworking.registerGlobalReceiver(CuddleSelectPayload.ID, (payload, context) ->
                context.server().execute(() ->
                        CuddleActionHandler.handleSelect(context.player(), payload.animId(), payload.hand())));
    }

    /** 发给单个玩家（64 格内的旁观者与当事人）。 */
    public static void send(ServerPlayerEntity player, CustomPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
