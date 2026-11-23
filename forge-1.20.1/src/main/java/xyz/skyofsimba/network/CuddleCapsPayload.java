package xyz.skyofsimba.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端能力上报（C2S，协议包 0）：{@code protocol, format, count, (id, lockTicks, contentVersion) × count}。
 *
 * <p>服务端只把它当「这个客户端能播什么」的声明，用于发起请求时的回退判断与锁定时间协商。
 */
public record CuddleCapsPayload(int protocol, int format, Map<String, Entry> entries) {

    /** 单条：锁定 tick + 内容版本。 */
    public record Entry(int lockTicks, String contentVersion) {}

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
