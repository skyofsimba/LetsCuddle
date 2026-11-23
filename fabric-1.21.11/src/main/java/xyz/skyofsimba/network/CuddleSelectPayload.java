package xyz.skyofsimba.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.skyofsimba.core.CuddleConstants;

/**
 * 选择动作（C2S，包 1）：{@code animId, hand}。
 *
 * @param hand 0 = 主手，1 = 副手；其它值忽略
 */
public record CuddleSelectPayload(String animId, int hand) implements CustomPayload {

    public static final CustomPayload.Id<CuddleSelectPayload> ID =
            new CustomPayload.Id<>(Identifier.of(CuddleConstants.MODID, "select"));

    public static final PacketCodec<RegistryByteBuf, CuddleSelectPayload> CODEC =
            PacketCodec.ofStatic(CuddleSelectPayload::encode, CuddleSelectPayload::decode);

    private static void encode(RegistryByteBuf buf, CuddleSelectPayload payload) {
        buf.writeString(payload.animId, CuddleConstants.MAX_ANIM_ID_LEN);
        buf.writeByte(payload.hand);
    }

    private static CuddleSelectPayload decode(RegistryByteBuf buf) {
        return new CuddleSelectPayload(buf.readString(CuddleConstants.MAX_ANIM_ID_LEN), buf.readByte());
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
