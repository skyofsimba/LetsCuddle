package xyz.skyofsimba.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CuddlePlacementTest {

    @Test
    void receiverLandsOnTheFarSideOfTheSender() {
        // 接受者在原点、发起者在 +X：接受者被摆到发起者另一侧，即 +1.3
        Vec3d stand = CuddlePlacement.standPosition(new Vec3d(2, 64, 0), new Vec3d(0, 64, 0));
        assertNotNull(stand);
        assertEquals(2 + CuddleConstants.STAND_OFFSET, stand.x(), 1.0E-9);
        assertEquals(64, stand.y(), 1.0E-9);
        assertEquals(0, stand.z(), 1.0E-9);
    }

    @Test
    void placementIsSymmetricAroundBothPlayers() {
        Vec3d sender = new Vec3d(10, 70, -4);
        Vec3d receiver = new Vec3d(8, 70, -1);
        Vec3d stand = CuddlePlacement.standPosition(sender, receiver);
        assertNotNull(stand);
        double distance = Math.sqrt(Math.pow(stand.x() - sender.x(), 2) + Math.pow(stand.z() - sender.z(), 2));
        assertEquals(CuddleConstants.STAND_OFFSET, distance, 1.0E-9);
    }

    @Test
    void coincidentPlayersCannotStand() {
        assertNull(CuddlePlacement.standPosition(new Vec3d(5, 64, 5), new Vec3d(5, 64, 5)));
    }

    @Test
    void yawPointsFromOnePlayerToTheOther() {
        // +X 方向的 yaw = -90
        assertEquals(-90.0F, CuddlePlacement.yawTo(new Vec3d(0, 64, 0), new Vec3d(1, 64, 0)), 1.0E-4);
        // +Z 方向（南）的 yaw = 0
        assertEquals(0.0F, CuddlePlacement.yawTo(new Vec3d(0, 64, 0), new Vec3d(0, 64, 1)), 1.0E-4);
    }

    @Test
    void bodyOffsetFollowsTheSameFormulaAsTheRenderer() {
        // theta = 90°，body.z = -1：世界位移应是 (-1, 0, 0)
        Vec3d offset = CuddlePlacement.worldBodyOffset(0F, -1F, 90F);
        assertEquals(-1.0, offset.x(), 1.0E-6);
        assertEquals(0.0, offset.z(), 1.0E-6);
    }
}
