package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.function.Supplier;

/** 选择回执（S2C，协议包 3）：服务端已把动作写进物品，客户端刷新 action bar / tooltip。 */
public record CuddleSelectedPayload(String animId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(animId, CuddleConstants.MAX_ANIM_ID_LEN);
    }

    public static CuddleSelectedPayload decode(FriendlyByteBuf buf) {
        return new CuddleSelectedPayload(buf.readUtf(CuddleConstants.MAX_ANIM_ID_LEN));
    }

    public static void handle(CuddleSelectedPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CuddleRenderClientHandler.handleSelected(message)));
        context.setPacketHandled(true);
    }
}
