package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 采样语义必须与 playerAnimator 1.0.2 的 {@code KeyframeAnimationPlayer} 一致：
 * 首帧之前从 0 插值、两帧之间用前一帧缓动、endTick 之后保持、stopTick 回落。
 * 只保留第三人称镜头需要的 body x/z。
 */
class CuddleAnimClipTest {

    private static final String CLIP = """
            {"name":"warm_receiver",
             "emote":{"beginTick":0,"endTick":53,"stopTick":61,"degrees":false,"moves":[
              {"tick":20,"easing":"EASEINOUTQUAD","torso":{"z":-1.0,"x":0.5}},
              {"tick":40,"easing":"EASEINOUTQUAD","head":{"yaw":1.0}},
              {"tick":10,"easing":"EASEINOUTQUAD","rightArm":{"pitch":-0.5}}
            ]}}
            """;

    @Test
    void bodyOffsetIsHeldBetweenKeyframeAndEndTick() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals(0F, clip.bodyZ(0F), 1.0E-6);
        assertEquals(-0.5F, clip.bodyZ(10F), 1.0E-6);   // INOUTSINE 中点
        assertEquals(-1F, clip.bodyZ(20F), 1.0E-6);
        assertEquals(-1F, clip.bodyZ(40F), 1.0E-6);
        assertEquals(-1F, clip.bodyZ(53F), 1.0E-6);      // 保持到 endTick
        assertEquals(0F, clip.bodyZ(61F), 1.0E-6);       // stopTick 回落
    }

    @Test
    void bodyAxesInterpolateIndependently() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        // x 与 z 各自从 0 插值到 tick 20 的关键帧
        assertEquals(0F, clip.bodyX(0F), 1.0E-6);
        assertEquals(0.25F, clip.bodyX(10F), 1.0E-6);
        assertEquals(0.5F, clip.bodyX(20F), 1.0E-6);
        // 四分之一点：INOUTSINE(0.25) ≈ 0.1464466
        assertEquals(0.0732233F, clip.bodyX(5F), 1.0E-5);
        assertEquals(-0.1464466F, clip.bodyZ(5F), 1.0E-5);
    }

    @Test
    void torsoIsAnAliasForBody() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals(-1F, clip.bodyZ(30F), 1.0E-6);
    }

    @Test
    void bonesOtherThanBodyAreIgnored() {
        CuddleAnimClip clip = CuddleAnimClip.parse("""
                {"emote":{"beginTick":0,"endTick":10,"stopTick":10,
                  "moves":[{"tick":0,"easing":"linear","head":{"yaw":1.0},"leftArm":{"x":2.0}}]}}
                """);
        assertNotNull(clip);
        assertEquals(0F, clip.bodyX(5F), 1.0E-6);
        assertEquals(0F, clip.bodyZ(5F), 1.0E-6);
    }

    @Test
    void nameIsReadFromTheRootObject() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals("warm_receiver", clip.name());
        // 带命名空间的 name 不能直接拼 id，视为缺失
        CuddleAnimClip namespaced = CuddleAnimClip.parse("""
                {"name":"heartpack:warm","emote":{"moves":[]}}
                """);
        assertNotNull(namespaced);
        assertNull(namespaced.name());
    }

    @Test
    void brokenJsonIsRejected() {
        assertNull(CuddleAnimClip.parse("{}"));
        assertNull(CuddleAnimClip.parse("not json"));
        assertNull(CuddleAnimClip.parse(""));
    }
}
