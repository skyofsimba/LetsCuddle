package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 拥抱渲染状态包。线上字段顺序固定：
 * sender(String) → receiver(String) → done(boolean) → cancelled(boolean) → animation(VarInt)。
 *
 * @param sender   发起者 UUID 字符串
 * @param receiver 接受者 UUID 字符串
 * @param done     true = 拥抱结束（解锁渲染），false = 开始
 * @param cancelled 仅 {@code done=true} 时有意义：true 表示被蹲下提前取消，客户端要立即收掉动作
 * @param animation {@link xyz.skyofsimba.animation.HugAnimationEnum} 的 ordinal（v1.5 改为字符串 id）
 */
public record HugRenderPayload(String sender, String receiver, boolean done, boolean cancelled, int animation) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sender);
        buf.writeUtf(receiver);
        buf.writeBoolean(done);
        buf.writeBoolean(cancelled);
        buf.writeVarInt(animation);
    }

    public static HugRenderPayload decode(FriendlyByteBuf buf) {
        return new HugRenderPayload(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt());
    }

    /**
     * PORT-FIX K15：本包类在双端都会加载，客户端处理类只允许在物理客户端被触碰，
     * 因此用 {@link DistExecutor#unsafeRunWhenOn} 包一层，专用服务端（不装 playeranimator）也不会 NoClassDefFoundError。
     */
    public static void handle(HugRenderPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> HugRenderClientHandler.handle(message)));
        context.setPacketHandled(true);
    }
}
