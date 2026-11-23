package xyz.skyofsimba.core;

/** 一根骨骼在某一 tick 的变换：位移（米/弧度按轴含义）与三轴旋转（弧度）。 */
public record Bone(float x, float y, float z, float yaw, float pitch, float roll) {

    public static final Bone ZERO = new Bone(0F, 0F, 0F, 0F, 0F, 0F);
}
