package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 采样语义必须与 playerAnimator 1.0.2 的 {@code KeyframeAnimationPlayer} 一致：
 * 首帧之前从 0 插值、两帧之间用前一帧缓动、endTick 之后保持、stopTick 回落。
 */
class CuddleAnimClipTest {

    private static final String CLIP = """
            {"emote":{"beginTick":0,"endTick":53,"stopTick":61,"degrees":false,"moves":[
              {"tick":20,"easing":"EASEINOUTQUAD","torso":{"z":-1.0}},
              {"tick":40,"easing":"EASEINOUTQUAD","head":{"yaw":1.0}},
              {"tick":10,"easing":"EASEINOUTQUAD","rightArm":{"pitch":-0.5}}
            ]}}
            """;

    @Test
    void bodyOffsetIsHeldBetweenKeyframeAndEndTick() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals(0F, clip.value("body", "z", 0F), 1.0E-6);
        assertEquals(-0.5F, clip.value("body", "z", 10F), 1.0E-6);   // INOUTSINE 中点
        assertEquals(-1F, clip.value("body", "z", 20F), 1.0E-6);
        assertEquals(-1F, clip.value("body", "z", 40F), 1.0E-6);
        assertEquals(-1F, clip.value("body", "z", 53F), 1.0E-6);      // 保持到 endTick
        assertEquals(0F, clip.value("body", "z", 61F), 1.0E-6);       // stopTick 回落
    }

    @Test
    void channelsBeforeTheirFirstKeyframeInterpolateFromZero() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals(0F, clip.value("head", "yaw", 0F), 1.0E-6);
        assertEquals(0.5F, clip.value("head", "yaw", 20F), 1.0E-6);   // 0 → 1 的 INOUTSINE 中点
        assertEquals(1F, clip.value("head", "yaw", 40F), 1.0E-6);
        // 40 → 53 保持 1，53 → 61 回落到 0
        assertEquals(1F, clip.value("head", "yaw", 53F), 1.0E-6);
    }

    @Test
    void multipleChannelsAreIndependent() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        CuddlePose pose = clip.sample(5F);
        // body.z：0 → -1 的 INOUTSINE 四分之一点
        assertEquals(-0.1464466F, pose.body().z(), 1.0E-5);
        // rightArm.pitch：0 → -0.5 的中点
        assertEquals(-0.25F, pose.rightArm().pitch(), 1.0E-5);
        // head.yaw：0 → 1 的 INOUTSINE 八分之一点
        assertEquals(0.0380602F, pose.head().yaw(), 1.0E-5);
    }

    @Test
    void torsoIsAnAliasForBody() {
        CuddleAnimClip clip = CuddleAnimClip.parse(CLIP);
        assertNotNull(clip);
        assertEquals(-1F, clip.value("body", "z", 30F), 1.0E-6);
        assertEquals(0F, clip.value("head", "z", 30F), 1.0E-6);
    }

    @Test
    void degreesFlagIsConvertedToRadians() {
        CuddleAnimClip clip = CuddleAnimClip.parse("""
                {"emote":{"beginTick":0,"endTick":10,"stopTick":10,"degrees":true,
                  "moves":[{"tick":0,"easing":"linear","head":{"yaw":90}}]}}
                """);
        assertNotNull(clip);
        assertEquals(Math.PI / 2, clip.value("head", "yaw", 5F), 1.0E-5);
    }

    @Test
    void brokenJsonIsRejected() {
        assertNull(CuddleAnimClip.parse("{}"));
        assertNull(CuddleAnimClip.parse("not json"));
        assertNull(CuddleAnimClip.parse(""));
    }
}
