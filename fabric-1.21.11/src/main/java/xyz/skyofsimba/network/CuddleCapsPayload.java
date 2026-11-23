package xyz.skyofsimba.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端能力上报（C2S，包 0）：{@code protocol, format, count, (id, lockTicks, contentVersion) × count}。
 */
public record CuddleCapsPayload(int protocol, int format, Map<String, Entry> entries) implements CustomPayload {

    /** 单条：锁定 tick + 内容版本。 */
    public record Entry(int lockTicks, String contentVersion) {}

    public static final CustomPayload.Id<CuddleCapsPayload> ID =
            new CustomPayload.Id<>(Identifier.of(CuddleConstants.MODID, "caps"));

    public static final PacketCodec<RegistryByteBuf, CuddleCapsPayload> CODEC =
            PacketCodec.ofStatic(CuddleCapsPayload::encode, CuddleCapsPayload::decode);

    private static void encode(RegistryByteBuf buf, CuddleCapsPayload payload) {
        buf.writeVarInt(payload.protocol);
        buf.writeVarInt(payload.format);
        int count = Math.min(payload.entries.size(), CuddleConstants.MAX_ANIMS_PER_CLIENT);
        buf.writeVarInt(count);
        int written = 0;
        for (Map.Entry<String, Entry> entry : payload.entries.entrySet()) {
            if (written++ >= count) {
                break;
            }
            buf.writeString(entry.getKey(), CuddleConstants.MAX_ANIM_ID_LEN);
            buf.writeVarInt(entry.getValue().lockTicks());
            buf.writeString(entry.getValue().contentVersion(), CuddleConstants.MAX_CONTENT_VERSION_LEN);
        }
    }

    private static CuddleCapsPayload decode(RegistryByteBuf buf) {
        int protocol = buf.readVarInt();
        int format = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), CuddleConstants.MAX_ANIMS_PER_CLIENT);
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = buf.readString(CuddleConstants.MAX_ANIM_ID_LEN);
            int lock = buf.readVarInt();
            String version = buf.readString(CuddleConstants.MAX_CONTENT_VERSION_LEN);
            entries.put(id, new Entry(lock, version));
        }
        return new CuddleCapsPayload(protocol, format, entries);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public CuddleCaps toCaps() {
        Map<String, CuddleCaps.Anim> map = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            map.put(entry.getKey(), new CuddleCaps.Anim(entry.getValue().lockTicks(),
                    entry.getValue().contentVersion()));
        }
        return CuddleCaps.sanitize(protocol, format, map);
    }

    public static CuddleCapsPayload of(CuddleCaps caps) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Map.Entry<String, CuddleCaps.Anim> entry : caps.animations().entrySet()) {
            map.put(entry.getKey(), new Entry(entry.getValue().lockTicks(), entry.getValue().contentVersion()));
        }
        return new CuddleCapsPayload(caps.protocol(), caps.format(), map);
    }
}
