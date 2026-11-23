package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuddleIdsTest {

    @Test
    void legacyShortIdsAreMigrated() {
        assertEquals(CuddleConstants.BUILTIN_NORMAL, CuddleIds.normalize("normal"));
        assertEquals(CuddleConstants.BUILTIN_TOUCH_HEAD, CuddleIds.normalize("touch_head"));
        // 大小写与空白也要能吃下
        assertEquals(CuddleConstants.BUILTIN_NORMAL, CuddleIds.normalize("  NORMAL "));
    }

    @Test
    void validIdsAreKept() {
        assertEquals("heartpack:warm", CuddleIds.normalize("heartpack:warm"));
        assertEquals("heartpack:warm_sender", CuddleIds.normalize("heartpack:warm_sender"));
        assertTrue(CuddleIds.isValid("letscuddle:sub/path"));
    }

    @Test
    void invalidIdsAreRejected() {
        assertNull(CuddleIds.normalize(null));
        assertNull(CuddleIds.normalize(""));
        assertNull(CuddleIds.normalize("MissingNamespace"));
        assertNull(CuddleIds.normalize("ns:.."));
        assertNull(CuddleIds.normalize("ns:/abs"));
        assertNull(CuddleIds.normalize("ns:"));
        assertFalse(CuddleIds.isValid("ns:" + "a".repeat(CuddleConstants.MAX_ANIM_ID_LEN)));
        // 大小写会被规范化成小写，但原始串本身不合法
        assertFalse(CuddleIds.isValid("Upper:case"));
        assertEquals("upper:case", CuddleIds.normalize("Upper:case"));
    }

    @Test
    void contentVersionIsClamped() {
        assertEquals("1", CuddleIds.contentVersion(null));
        assertEquals("1", CuddleIds.contentVersion("   "));
        assertEquals("2", CuddleIds.contentVersion(" 2 "));
        String longVersion = "x".repeat(CuddleConstants.MAX_CONTENT_VERSION_LEN + 10);
        assertEquals(CuddleConstants.MAX_CONTENT_VERSION_LEN, CuddleIds.contentVersion(longVersion).length());
    }

    @Test
    void builtinNamespaceIsRecognised() {
        assertTrue(CuddleIds.isBuiltin(CuddleConstants.DEFAULT_ANIM_ID));
        assertFalse(CuddleIds.isBuiltin("heartpack:warm"));
    }
}
