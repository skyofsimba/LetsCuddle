package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuddleCapsTest {

    private static final String WARM = "heartpack:warm";

    @Test
    void lockTicksAreClamped() {
        assertEquals(CuddleConstants.MIN_LOCK_TICKS, CuddleCaps.clampLock(0));
        assertEquals(CuddleConstants.MAX_LOCK_TICKS, CuddleCaps.clampLock(9999));
        assertEquals(120, CuddleCaps.clampLock(120));
    }

    @Test
    void sanitizeDropsInvalidIdsAndClampsLocks() {
        CuddleCaps caps = CuddleCaps.sanitize(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, Map.of(
                WARM, new CuddleCaps.Anim(5, "2"),
                "Bad Id", new CuddleCaps.Anim(200, "1"),
                "normal", new CuddleCaps.Anim(9999, "x".repeat(100))));

        assertTrue(caps.has(WARM));
        assertFalse(caps.has("Bad Id"));
        assertTrue(caps.has(CuddleConstants.BUILTIN_NORMAL));
        assertEquals(CuddleConstants.MIN_LOCK_TICKS, caps.lockTicks(WARM, 120));
        assertEquals(CuddleConstants.MAX_LOCK_TICKS, caps.lockTicks(CuddleConstants.BUILTIN_NORMAL, 0));
        assertEquals(CuddleConstants.MAX_CONTENT_VERSION_LEN,
                caps.contentVersion(CuddleConstants.BUILTIN_NORMAL).length());
    }

    @Test
    void sanitizeTruncatesTooManyEntries() {
        Map<String, CuddleCaps.Anim> tooMany = new java.util.HashMap<>();
        for (int i = 0; i < CuddleConstants.MAX_ANIMS_PER_CLIENT + 50; i++) {
            tooMany.put("pack:anim" + i, new CuddleCaps.Anim(120, "1"));
        }
        CuddleCaps caps = CuddleCaps.sanitize(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, tooMany);
        assertEquals(CuddleConstants.MAX_ANIMS_PER_CLIENT, caps.animations().size());
    }

    @Test
    void negotiatedLockTakesTheSmallerValue() {
        CuddleCaps sender = new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(200, "1")));
        CuddleCaps receiver = new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(80, "1")));
        assertEquals(80, sender.negotiatedLock(WARM, receiver));
    }

    @Test
    void contentVersionMismatchIsDetected() {
        CuddleCaps first = new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(120, "1")));
        CuddleCaps second = new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(120, "2")));
        assertTrue(first.sameContent(WARM, first));
        assertFalse(first.sameContent(WARM, second));
        assertFalse(first.sameContent(WARM, CuddleCaps.EMPTY));
    }

    @Test
    void protocolMismatchIsIncompatible() {
        assertTrue(CuddleCaps.EMPTY.compatible());
        assertFalse(new CuddleCaps(3, CuddleConstants.PACK_FORMAT, Map.of()).compatible());
    }

    @Test
    void displayNamesSurviveSanitize() {
        CuddleCaps caps = CuddleCaps.sanitize(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(120, "1", "heartpack.animation.warm", "Warm")));
        CuddleCaps.Anim anim = caps.anim(WARM);
        assertEquals("heartpack.animation.warm", anim.nameKey());
        assertEquals("Warm", anim.name());
    }

    @Test
    void overlongOrBlankNamesBecomeNull() {
        CuddleCaps caps = CuddleCaps.sanitize(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT,
                Map.of(WARM, new CuddleCaps.Anim(120, "1", "   ", "x".repeat(200))));
        CuddleCaps.Anim anim = caps.anim(WARM);
        assertFalse(anim.hasNameKey());
        assertEquals(CuddleConstants.MAX_NAME_LEN, anim.name().length());
    }
}
