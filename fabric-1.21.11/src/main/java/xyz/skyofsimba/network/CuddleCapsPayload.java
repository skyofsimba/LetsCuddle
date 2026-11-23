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
 * 客户端能力上报（C2S，包 0）：
 * {@code protocol, format, count, (id, lockTicks, contentVersion, nameKey, name) × count}。
 *
 * <p>名字随能力一起上报，服务端才能用资源包里的名字发提示（服务端不解析包）。
 */
public record CuddleCapsPayload(int protocol, int format, Map<String, Entry> entries) implements CustomPayload {

    /** 单条：锁定 tick、内容版本、显示名。 */
    public record Entry(int lockTicks, String contentVersion, String nameKey, String name) {

        public Entry(int lockTicks, String contentVersion) {
            this(lockTicks, contentVersion, "", "");
        }
    }

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
            Entry value = entry.getValue();
            buf.writeString(entry.getKey(), CuddleConstants.MAX_ANIM_ID_LEN);
            buf.writeVarInt(value.lockTicks());
            buf.writeString(value.contentVersion(), CuddleConstants.MAX_CONTENT_VERSION_LEN);
            buf.writeString(nullToEmpty(value.nameKey()), CuddleConstants.MAX_NAME_KEY_LEN);
            buf.writeString(nullToEmpty(value.name()), CuddleConstants.MAX_NAME_LEN);
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
            String nameKey = buf.readString(CuddleConstants.MAX_NAME_KEY_LEN);
            String name = buf.readString(CuddleConstants.MAX_NAME_LEN);
            entries.put(id, new Entry(lock, version, nameKey, name));
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
            Entry value = entry.getValue();
            map.put(entry.getKey(), new CuddleCaps.Anim(value.lockTicks(), value.contentVersion(),
                    value.nameKey(), value.name()));
        }
        return CuddleCaps.sanitize(protocol, format, map);
    }

    public static CuddleCapsPayload of(CuddleCaps caps) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Map.Entry<String, CuddleCaps.Anim> entry : caps.animations().entrySet()) {
            CuddleCaps.Anim value = entry.getValue();
            map.put(entry.getKey(), new Entry(value.lockTicks(), value.contentVersion(),
                    nullToEmpty(value.nameKey()), nullToEmpty(value.name())));
        }
        return new CuddleCapsPayload(caps.protocol(), caps.format(), map);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
