package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 贴贴渲染状态包。线上字段顺序固定：
 * sender(String) → receiver(String) → done(boolean) → cancelled(boolean) → animation(String)。
 *
 * <p>v1.5：animation 由 ordinal(int) 改为稳定字符串 id（K18），新增动作不再受枚举顺序影响。
 *
 * @param sender   发起者 UUID 字符串
 * @param receiver 接受者 UUID 字符串
 * @param done     true = 贴贴结束（解锁渲染），false = 开始
 * @param cancelled 仅 {@code done=true} 时有意义：true 表示被蹲下提前取消，客户端要立即收掉动作
 * @param animation {@link xyz.skyofsimba.animation.CuddleAnimationEnum#id()}，结束包里是空串
 */
public record CuddleRenderPayload(String sender, String receiver, boolean done, boolean cancelled, String animation) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sender);
        buf.writeUtf(receiver);
        buf.writeBoolean(done);
        buf.writeBoolean(cancelled);
        buf.writeUtf(animation);
    }

    public static CuddleRenderPayload decode(FriendlyByteBuf buf) {
        return new CuddleRenderPayload(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                buf.readUtf());
    }

    /**
     * PORT-FIX K15：本包类在双端都会加载，客户端处理类只允许在物理客户端被触碰，
     * 因此用 {@link DistExecutor#unsafeRunWhenOn} 包一层，专用服务端（不装 playeranimator）也不会 NoClassDefFoundError。
     */
    public static void handle(CuddleRenderPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CuddleRenderClientHandler.handle(message)));
        context.setPacketHandled(true);
    }
}
