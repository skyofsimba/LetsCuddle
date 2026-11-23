package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端能力上报（C2S，协议包 0）：
 * {@code protocol, format, count, (id, lockTicks, contentVersion, nameKey, name) × count}。
 *
 * <p>名字随能力一起上报，服务端才能用资源包里的名字发提示（服务端不解析包）。
 */
public record CuddleCapsPayload(int protocol, int format, Map<String, Entry> entries) {

    /** 单条：锁定 tick、内容版本、显示名。 */
    public record Entry(int lockTicks, String contentVersion, String nameKey, String name) {

        public Entry(int lockTicks, String contentVersion) {
            this(lockTicks, contentVersion, "", "");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(protocol);
        buf.writeVarInt(format);
        int count = Math.min(entries.size(), CuddleConstants.MAX_ANIMS_PER_CLIENT);
        buf.writeVarInt(count);
        int written = 0;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (written++ >= count) {
                break;
            }
            Entry value = entry.getValue();
            buf.writeUtf(entry.getKey(), CuddleConstants.MAX_ANIM_ID_LEN);
            buf.writeVarInt(value.lockTicks());
            buf.writeUtf(value.contentVersion(), CuddleConstants.MAX_CONTENT_VERSION_LEN);
            buf.writeUtf(nullToEmpty(value.nameKey()), CuddleConstants.MAX_NAME_KEY_LEN);
            buf.writeUtf(nullToEmpty(value.name()), CuddleConstants.MAX_NAME_LEN);
        }
    }

    public static CuddleCapsPayload decode(FriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        int format = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), CuddleConstants.MAX_ANIMS_PER_CLIENT);
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf(CuddleConstants.MAX_ANIM_ID_LEN);
            int lock = buf.readVarInt();
            String version = buf.readUtf(CuddleConstants.MAX_CONTENT_VERSION_LEN);
            String nameKey = buf.readUtf(CuddleConstants.MAX_NAME_KEY_LEN);
            String name = buf.readUtf(CuddleConstants.MAX_NAME_LEN);
            entries.put(id, new Entry(lock, version, nameKey, name));
        }
        return new CuddleCapsPayload(protocol, format, entries);
    }

    public static void handle(CuddleCapsPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                CuddleCapsStore.onCaps(context.getSender(), message.toCaps());
            }
        });
        context.setPacketHandled(true);
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
