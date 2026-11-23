package xyz.skyofsimba.core;

/**
 * 一份动作在某一 tick 的骨骼姿势。
 *
 * <p>骨骼名与 playerAnimator/Emotecraft 的 JSON 一致：{@code body}（旧文件的 {@code torso}）、
 * {@code head}、{@code leftArm}、{@code rightArm}、{@code leftLeg}、{@code rightLeg}。
 */
public record CuddlePose(Bone body, Bone head, Bone leftArm, Bone rightArm, Bone leftLeg, Bone rightLeg) {

    public static final CuddlePose REST = new CuddlePose(
            Bone.ZERO, Bone.ZERO, Bone.ZERO, Bone.ZERO, Bone.ZERO, Bone.ZERO);
}
