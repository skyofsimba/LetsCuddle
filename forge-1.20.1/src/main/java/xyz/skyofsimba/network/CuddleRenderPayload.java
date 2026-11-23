package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.function.Supplier;

/**
 * 贴贴开始/结束广播（S2C）。线上顺序：
 * {@code sender, receiver, done, cancelled, animId, contentVersion}。
 *
 * @param animId         动作 id；结束包为空串
 * @param contentVersion 发起者侧该动作的内容版本；结束包为空串
 */
public record CuddleRenderPayload(String sender, String receiver, boolean done, boolean cancelled,
                                  String animId, String contentVersion) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sender, 36);
        buf.writeUtf(receiver, 36);
        buf.writeBoolean(done);
        buf.writeBoolean(cancelled);
        buf.writeUtf(animId, CuddleConstants.MAX_ANIM_ID_LEN);
        buf.writeUtf(contentVersion, CuddleConstants.MAX_CONTENT_VERSION_LEN);
    }

    public static CuddleRenderPayload decode(FriendlyByteBuf buf) {
        return new CuddleRenderPayload(buf.readUtf(36), buf.readUtf(36), buf.readBoolean(), buf.readBoolean(),
                buf.readUtf(CuddleConstants.MAX_ANIM_ID_LEN), buf.readUtf(CuddleConstants.MAX_CONTENT_VERSION_LEN));
    }

    /** 本包双端都会加载；客户端处理类只允许在物理客户端被触碰（K15）。 */
    public static void handle(CuddleRenderPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CuddleRenderClientHandler.handle(message)));
        context.setPacketHandled(true);
    }
}
