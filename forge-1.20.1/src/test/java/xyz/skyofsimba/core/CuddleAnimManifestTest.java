package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuddleAnimManifestTest {

    private static final String VALID = """
            {
              "format": 1,
              "content_version": "3",
              "sender": "heartpack:warm_sender",
              "receiver": "heartpack:warm_receiver",
              "name_key": "heartpack.animation.warm",
              "name": "Warm",
              "speed": 0.75,
              "lock_ticks": 160,
              "order": 10
            }
            """;

    @Test
    void validManifestIsParsed() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm", VALID);
        assertTrue(result.ok(), result.error());
        assertEquals("heartpack:warm", result.def().id());
        assertEquals("3", result.def().contentVersion());
        assertEquals("heartpack:warm_sender", result.def().senderId());
        assertEquals("heartpack:warm_receiver", result.def().receiverId());
        assertEquals("heartpack.animation.warm", result.def().nameKey());
        assertEquals(0.75F, result.def().speed(), 1.0E-6);
        assertEquals(160, result.def().lockTicks());
        assertEquals(10, result.def().order());
    }

    @Test
    void missingFormatIsRejected() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"sender\":\"a:b\",\"receiver\":\"a:c\"}");
        assertFalse(result.ok());
    }

    @Test
    void futureFormatIsRejected() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"format\":2,\"sender\":\"a:b\",\"receiver\":\"a:c\"}");
        assertFalse(result.ok());
        assertTrue(result.error().contains("newer"));
    }

    @Test
    void invalidSenderIsRejected() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"format\":1,\"sender\":\"Bad Id\",\"receiver\":\"a:c\"}");
        assertFalse(result.ok());
    }

    @Test
    void outOfRangeNumbersAreClamped() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"format\":1,\"sender\":\"a:b\",\"receiver\":\"a:c\",\"speed\":99,\"lock_ticks\":1}");
        assertTrue(result.ok());
        assertEquals(CuddleConstants.MAX_SPEED, result.def().speed(), 1.0E-6);
        assertEquals(CuddleConstants.MIN_LOCK_TICKS, result.def().lockTicks());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void unknownFieldsOnlyWarn() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"format\":1,\"sender\":\"a:b\",\"receiver\":\"a:c\",\"future\":true}");
        assertTrue(result.ok());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void brokenJsonIsRejected() {
        assertNull(CuddleAnimManifest.parse("heartpack:warm", "not json").def());
        assertNull(CuddleAnimManifest.parse("heartpack:warm", "[]").def());
    }

    @Test
    void legacyShortSenderIsNormalised() {
        CuddleAnimManifest.Result result = CuddleAnimManifest.parse("heartpack:warm",
                "{\"format\":1,\"sender\":\"normal\",\"receiver\":\"touch_head\"}");
        assertTrue(result.ok());
        assertEquals(CuddleConstants.BUILTIN_NORMAL, result.def().senderId());
        assertEquals(CuddleConstants.BUILTIN_TOUCH_HEAD, result.def().receiverId());
    }
}
