package xyz.skyofsimba.client;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.events.RenderPlayerEventHandler;

/**
 * 贴贴期间的第三人称切换与镜头跟随（仅客户端、仅当事人）。
 *
 * <p>开始贴贴时记住原视角并切到第三人称；结束时只有视角仍停在
 * {@link CameraType#THIRD_PERSON_BACK} 才恢复（玩家中途按过 F5 就尊重玩家）。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CuddleCameraHandler {

    private static boolean active = false;
    private static CameraType previous = CameraType.FIRST_PERSON;

    private CuddleCameraHandler() {}

    /** 本地玩家（发起者或接受者）开始贴贴。 */
    public static void onCuddleStart() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null) {
            return;
        }
        if (!active) {
            previous = client.options.getCameraType();
            active = true;
        }
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** 本地玩家所在的贴贴结束（正常结束、蹲下取消、掉线、客户端兜底超时都走这里）。 */
    public static void onCuddleEnd() {
        if (!active) {
            return;
        }
        active = false;
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }
        // 玩家中途按过 F5 就不动他的选择，只有仍停在我们切过去的视角时才恢复
        if (client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            client.options.setCameraType(previous);
        }
    }

    /** 退出世界时兜底复位，避免把第三人称带到下一个存档。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
    }

    /**
     * 第三人称镜头跟随渲染模型的实际位置。
     *
     * <p>原版相机绕实体坐标旋转，而贴贴动画把模型推开了约 1 格；本事件在 {@code Camera#setup}
     * 之后触发，此时平移相机即可作用于这一帧（AT 打开了 {@code Camera#setPosition}）。第一人称不偏移。
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Camera camera = event.getCamera();
        if (!camera.isDetached()) {
            return;
        }
        if (!(camera.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (!RenderPlayerEventHandler.isLocked(player)) {
            return;
        }

        Vec3 offset = RenderPlayerEventHandler.modelOffset(player, (float) event.getPartialTick());
        if (offset.lengthSqr() < 1.0E-6D) {
            return;
        }
        camera.setPosition(camera.getPosition().add(offset));
    }
}
