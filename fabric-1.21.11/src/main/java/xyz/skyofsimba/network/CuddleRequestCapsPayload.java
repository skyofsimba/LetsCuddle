package xyz.skyofsimba.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.skyofsimba.core.CuddleConstants;

/** 登录时的能力询问（S2C，包 4，空包）。 */
public record CuddleRequestCapsPayload() implements CustomPayload {

    public static final CustomPayload.Id<CuddleRequestCapsPayload> ID =
            new CustomPayload.Id<>(Identifier.of(CuddleConstants.MODID, "request_caps"));

    public static final PacketCodec<RegistryByteBuf, CuddleRequestCapsPayload> CODEC =
            PacketCodec.ofStatic((buf, payload) -> {}, buf -> new CuddleRequestCapsPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
