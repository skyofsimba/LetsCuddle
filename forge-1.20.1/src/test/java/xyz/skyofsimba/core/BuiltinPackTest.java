package xyz.skyofsimba.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验仓库里真实的内置动作包，避免"文档对了、数据错了"。 */
class BuiltinPackTest {

    private static final List<String> MANIFESTS = List.of(
            "normal.json", "touch_head.json");

    @Test
    void builtinManifestsAreValid() throws Exception {
        for (String name : MANIFESTS) {
            String resource = "/assets/letscuddle/cuddle_animations/" + name;
            try (InputStream stream = getClass().getResourceAsStream(resource)) {
                assertNotNull(stream, "missing built-in manifest " + resource);
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                String id = CuddleConstants.MODID + ":" + name.substring(0, name.length() - ".json".length());
                CuddleAnimManifest.Result result = CuddleAnimManifest.parse(id, json);
                assertTrue(result.ok(), id + ": " + result.error());
                assertEquals(CuddleConstants.DEFAULT_LOCK_TICKS, result.def().lockTicks());
                assertEquals(CuddleConstants.DEFAULT_SPEED, result.def().speed(), 1.0E-6);
                assertTrue(CuddleIds.isValid(result.def().senderId()));
                assertTrue(CuddleIds.isValid(result.def().receiverId()));
            }
        }
    }

    @Test
    void builtinAnimationCanBeSampled() throws Exception {
        // 构建时会把 common/anim/raw 复制到各平台的动画目录；测试直接读源文件
        Path source = Path.of("../common/src/main/resources/anim/raw/normal_r.json");
        Assumptions.assumeTrue(Files.exists(source), "built-in animation source not found");
        CuddleAnimClip clip = CuddleAnimClip.parse(Files.readString(source));
        assertNotNull(clip);
        assertEquals(61, clip.stopTick());
        // torso.z 在 tick 20 到达 -1（推进对方怀里 1 格）
        assertEquals(-1.0F, clip.value("body", "z", 20.0F), 1.0E-4);
        // 头部在动作中段有可见偏转
        assertTrue(Math.abs(clip.value("head", "yaw", 34.0F)) > 0.1F);
    }
}
