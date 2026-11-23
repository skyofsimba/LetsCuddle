package xyz.skyofsimba.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.skyofsimba.core.CuddleConstants;

/**
 * 贴贴开始/结束广播（S2C）。线上顺序：
 * {@code sender, receiver, done, cancelled, animId, contentVersion}。
 */
public record CuddleRenderPayload(String sender, String receiver, boolean done, boolean cancelled,
                                  String animId, String contentVersion) implements CustomPayload {

    public static final CustomPayload.Id<CuddleRenderPayload> ID =
            new CustomPayload.Id<>(Identifier.of(CuddleConstants.MODID, "render"));

    public static final PacketCodec<RegistryByteBuf, CuddleRenderPayload> CODEC =
            PacketCodec.ofStatic(CuddleRenderPayload::encode, CuddleRenderPayload::decode);

    private static void encode(RegistryByteBuf buf, CuddleRenderPayload payload) {
        buf.writeString(payload.sender, 36);
        buf.writeString(payload.receiver, 36);
        buf.writeBoolean(payload.done);
        buf.writeBoolean(payload.cancelled);
        buf.writeString(payload.animId, CuddleConstants.MAX_ANIM_ID_LEN);
        buf.writeString(payload.contentVersion, CuddleConstants.MAX_CONTENT_VERSION_LEN);
    }

    private static CuddleRenderPayload decode(RegistryByteBuf buf) {
        return new CuddleRenderPayload(buf.readString(36), buf.readString(36), buf.readBoolean(),
                buf.readBoolean(), buf.readString(CuddleConstants.MAX_ANIM_ID_LEN),
                buf.readString(CuddleConstants.MAX_CONTENT_VERSION_LEN));
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
