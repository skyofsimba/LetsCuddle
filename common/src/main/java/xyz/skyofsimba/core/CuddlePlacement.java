package xyz.skyofsimba.core;

/**
 * 落点与站位数学（1.x 行为，冻结）。
 *
 * <p>语义（KEEP K4）：接受者被摆到<b>发起者另一侧</b> 1.3 格处，即
 * {@code P' = S + 1.3 * normalize(S - R)}，两人因此面对面、相距 1.3 格。
 */
public final class CuddlePlacement {

    /** 水平重合判定的容差；小于它视为无法确定方向。 */
    private static final double EPSILON = 1.0E-4D;

    private CuddlePlacement() {}

    /**
     * 计算接受者的落点。
     *
     * @param sender   发起者站位
     * @param receiver 接受者站位
     * @return 落点；两人 XZ 重合（无法确定方向）时返回 {@code null}
     */
    public static Vec3d standPosition(Vec3d sender, Vec3d receiver) {
        Vec3d delta = sender.subtract(receiver);
        double horizontal = delta.horizontalLength();
        if (horizontal < EPSILON) {
            return null;
        }
        double scale = CuddleConstants.STAND_OFFSET / horizontal;
        return new Vec3d(sender.x() + delta.x() * scale, sender.y(), sender.z() + delta.z() * scale);
    }

    /** 两人朝向对面那人的 yaw（度）。渲染与镜头用同一公式，保证模型和镜头一致。 */
    public static float yawTo(Vec3d from, Vec3d to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
    }

    /**
     * 动作 {@code body} 位移在世界空间的水平分量。
     *
     * <p>渲染矩阵链是 {@code Rx(180) · T(0,-1.5,0) · Ry(θ) · T(px, py+0.7, pz) · ...}，
     * 把 (px, pz) 过 Ry(θ) 再翻 y/z 符号，得到世界位移：
     * {@code dx = px·cosθ + pz·sinθ}、{@code dz = px·sinθ − pz·cosθ}。
     */
    public static Vec3d worldBodyOffset(float bodyX, float bodyZ, float yawDegrees) {
        double theta = Math.toRadians(yawDegrees);
        double dx = bodyX * Math.cos(theta) + bodyZ * Math.sin(theta);
        double dz = bodyX * Math.sin(theta) - bodyZ * Math.cos(theta);
        return new Vec3d(dx, 0.0D, dz);
    }
}
