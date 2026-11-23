package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.command.CuddleActionHandler;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.function.Supplier;

/**
 * 选择动作（C2S，协议包 1）：{@code animId, hand}。
 *
 * <p>选择由客户端发起（它才知道自己装了哪些包），服务端校验「在能力列表里」后写 NBT，
 * 再用 {@link CuddleSelectedPayload} 回执。
 *
 * @param hand 0 = 主手，1 = 副手；其它值忽略
 */
public record CuddleSelectPayload(String animId, int hand) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(animId, CuddleConstants.MAX_ANIM_ID_LEN);
        buf.writeByte(hand);
    }

    public static CuddleSelectPayload decode(FriendlyByteBuf buf) {
        return new CuddleSelectPayload(buf.readUtf(CuddleConstants.MAX_ANIM_ID_LEN), buf.readByte());
    }

    public static void handle(CuddleSelectPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                CuddleActionHandler.handleSelect(context.getSender(), message.animId(), message.hand());
            }
        });
        context.setPacketHandled(true);
    }
}
