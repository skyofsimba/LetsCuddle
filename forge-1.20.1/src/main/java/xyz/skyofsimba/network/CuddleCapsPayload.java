package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端能力上报（C2S，协议包 0）。
 *
 * <p>主体（协议 v4 原样）：{@code protocol, format, count, (id, lockTicks, contentVersion) × count}。
 * 之后是**可选尾段**（beta2 起）：{@code count, (id, nameKey, name) × count}，用于让服务端发提示时
 * 显示资源包里的名字。旧客户端发来的包没有尾段（{@link FriendlyByteBuf#readableBytes()} 为 0），
 * 新服务端回退到 id/语言键；新客户端发给旧服务端时，旧端读完后忽略多余字节。
 */
public record CuddleCapsPayload(int protocol, int format, Map<String, Entry> entries) {

    /** 单条：锁定 tick、内容版本、显示名（名字只出现在尾段）。 */
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
            buf.writeUtf(entry.getKey(), CuddleConstants.MAX_ANIM_ID_LEN);
            buf.writeVarInt(entry.getValue().lockTicks());
            buf.writeUtf(entry.getValue().contentVersion(), CuddleConstants.MAX_CONTENT_VERSION_LEN);
        }

        buf.writeVarInt(count);
        written = 0;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (written++ >= count) {
                break;
            }
            Entry value = entry.getValue();
            buf.writeUtf(entry.getKey(), CuddleConstants.MAX_ANIM_ID_LEN);
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
            entries.put(id, new Entry(lock, version));
        }

        if (buf.readableBytes() > 0) {
            int nameCount = Math.min(buf.readVarInt(), CuddleConstants.MAX_ANIMS_PER_CLIENT);
            for (int i = 0; i < nameCount; i++) {
                String id = buf.readUtf(CuddleConstants.MAX_ANIM_ID_LEN);
                String nameKey = buf.readUtf(CuddleConstants.MAX_NAME_KEY_LEN);
                String name = buf.readUtf(CuddleConstants.MAX_NAME_LEN);
                Entry entry = entries.get(id);
                if (entry != null) {
                    entries.put(id, new Entry(entry.lockTicks(), entry.contentVersion(), nameKey, name));
                }
            }
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
