package xyz.skyofsimba.core;

/** 纯数据三元组；平台层再转成各自的 {@code Vec3}/{@code Vec3d}。 */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public boolean isZero() {
        return x == 0 && y == 0 && z == 0;
    }
}
