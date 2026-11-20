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

/**
 * 网络通道（Forge 47.x 的经典 SimpleImpl）。
 *
 * <p>原工程用 NeoForge 的 {@code CustomPacketPayload} + {@code StreamCodec}，1.20.1 没有这套 API，
 * 需要独立一个类承载 {@link SimpleChannel}；协议本体（字段顺序、语义）不变，见 {@link HugRenderPayload}。
 *
 * <p>注意包名：{@code net.minecraftforge.network.simple.SimpleChannel}。
 * {@code net.minecraftforge.network.SimpleChannel} 是 1.20.6+ 的写法，47.x 上不存在。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HugNetwork {

    /** v1.5：animation 改为字符串 id，协议号再 +1（新旧客户端不能互连）。 */
    private static final String PROTOCOL = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Letscuddle.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private HugNetwork() {}

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, HugRenderPayload.class,
                HugRenderPayload::encode,
                HugRenderPayload::decode,
                HugRenderPayload::handle);
    }

    /** 发给单个玩家（64 格内的旁观者与当事人）。 */
    public static void sendTo(ServerPlayer player, HugRenderPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
