package xyz.skyofsimba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleConstants;

/**
 * 网络通道（Forge 47.x 的 SimpleImpl），协议 {@code 4}。
 *
 * <p>协议字符串参与通道版本校验：1.6（协议 3）的客户端会被 Forge 直接拒绝连接。
 * 消息 id 与 {@code doc/2.0.0-beta1-plan.md} §6.1 的表一一对应。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CuddleNetwork {

    private static final String PROTOCOL = Integer.toString(CuddleConstants.PROTOCOL);

    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Letscuddle.MODID + ":main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private CuddleNetwork() {}

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, CuddleCapsPayload.class,
                CuddleCapsPayload::encode, CuddleCapsPayload::decode, CuddleCapsPayload::handle);
        CHANNEL.registerMessage(1, CuddleSelectPayload.class,
                CuddleSelectPayload::encode, CuddleSelectPayload::decode, CuddleSelectPayload::handle);
        CHANNEL.registerMessage(2, CuddleRenderPayload.class,
                CuddleRenderPayload::encode, CuddleRenderPayload::decode, CuddleRenderPayload::handle);
        CHANNEL.registerMessage(3, CuddleSelectedPayload.class,
                CuddleSelectedPayload::encode, CuddleSelectedPayload::decode, CuddleSelectedPayload::handle);
        CHANNEL.registerMessage(4, CuddleRequestCapsPayload.class,
                CuddleRequestCapsPayload::encode, CuddleRequestCapsPayload::decode, CuddleRequestCapsPayload::handle);
    }

    /** 发给单个玩家（64 格内的旁观者与当事人）。 */
    public static void sendTo(ServerPlayer player, CuddleRenderPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendTo(ServerPlayer player, CuddleSelectedPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendTo(ServerPlayer player, CuddleRequestCapsPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** 客户端 → 服务端。 */
    public static void sendCapsToServer(CuddleCapsPayload message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendSelectToServer(CuddleSelectPayload message) {
        CHANNEL.sendToServer(message);
    }
}
