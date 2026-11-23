package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 登录时的能力询问（S2C，协议包 4，空包）：客户端收到后回 {@link CuddleCapsPayload}。 */
public record CuddleRequestCapsPayload() {

    public void encode(FriendlyByteBuf buf) {
        // 空包
    }

    public static CuddleRequestCapsPayload decode(FriendlyByteBuf buf) {
        return new CuddleRequestCapsPayload();
    }

    public static void handle(CuddleRequestCapsPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> CuddleRenderClientHandler::sendCaps));
        context.setPacketHandled(true);
    }
}
