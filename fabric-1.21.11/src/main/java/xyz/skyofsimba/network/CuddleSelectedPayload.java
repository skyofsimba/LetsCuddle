package xyz.skyofsimba.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.skyofsimba.core.CuddleConstants;

/** 选择回执（S2C，包 3）：服务端已把动作写进物品。 */
public record CuddleSelectedPayload(String animId) implements CustomPayload {

    public static final CustomPayload.Id<CuddleSelectedPayload> ID =
            new CustomPayload.Id<>(Identifier.of(CuddleConstants.MODID, "selected"));

    public static final PacketCodec<RegistryByteBuf, CuddleSelectedPayload> CODEC =
            PacketCodec.ofStatic(CuddleSelectedPayload::encode, CuddleSelectedPayload::decode);

    private static void encode(RegistryByteBuf buf, CuddleSelectedPayload payload) {
        buf.writeString(payload.animId, CuddleConstants.MAX_ANIM_ID_LEN);
    }

    private static CuddleSelectedPayload decode(RegistryByteBuf buf) {
        return new CuddleSelectedPayload(buf.readString(CuddleConstants.MAX_ANIM_ID_LEN));
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
