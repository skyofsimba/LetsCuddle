package xyz.skyofsimba.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 能力包的线格式：新格式带名字尾段，旧格式（协议 v4 beta1）没有尾段也要能读。 */
class CuddleCapsPayloadTest {

    private static final String WARM = "heartpack:warm";

    @Test
    void roundTripKeepsNames() {
        CuddleCaps caps = new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(140, "2", "heartpack.animation.warm", "Warm")));

        CuddleCapsPayload decoded = decode(encode(CuddleCapsPayload.of(caps)));

        assertEquals(CuddleConstants.PROTOCOL, decoded.protocol());
        assertEquals(CuddleConstants.PACK_FORMAT, decoded.format());
        CuddleCapsPayload.Entry entry = decoded.entries().get(WARM);
        assertEquals(140, entry.lockTicks());
        assertEquals("2", entry.contentVersion());
        assertEquals("heartpack.animation.warm", entry.nameKey());
        assertEquals("Warm", entry.name());
    }

    @Test
    void legacyPayloadWithoutNamesStillDecodes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(CuddleConstants.PROTOCOL);
        buf.writeVarInt(CuddleConstants.PACK_FORMAT);
        buf.writeVarInt(1);
        buf.writeUtf(WARM);
        buf.writeVarInt(120);
        buf.writeUtf("1");

        CuddleCapsPayload decoded = decode(buf);
        CuddleCapsPayload.Entry entry = decoded.entries().get(WARM);
        assertEquals(120, entry.lockTicks());
        assertEquals("1", entry.contentVersion());
        assertTrue(entry.nameKey().isEmpty());
        assertTrue(entry.name().isEmpty());
    }

    @Test
    void capsSanitizeStillDropsBadIds() {
        Map<String, CuddleCapsPayload.Entry> entries = new LinkedHashMap<>();
        entries.put(WARM, new CuddleCapsPayload.Entry(120, "1", "heartpack.animation.warm", "Warm"));
        entries.put("Bad Id", new CuddleCapsPayload.Entry(120, "1", "", ""));
        CuddleCapsPayload payload = new CuddleCapsPayload(CuddleConstants.PROTOCOL,
                CuddleConstants.PACK_FORMAT, entries);

        CuddleCaps caps = payload.toCaps();
        assertTrue(caps.has(WARM));
        assertFalse(caps.has("Bad Id"));
    }

    private static FriendlyByteBuf encode(CuddleCapsPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        payload.encode(buf);
        return buf;
    }

    private static CuddleCapsPayload decode(FriendlyByteBuf buf) {
        return CuddleCapsPayload.decode(buf);
    }
}
