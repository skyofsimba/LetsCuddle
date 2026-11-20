package xyz.skyofsimba.events;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import xyz.skyofsimba.Letscuddle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 渲染接管：拥抱期间取消原版玩家渲染，自己摆好「面对面」的模型再画一遍。
 *
 * <p>公式一个符号都不能改（original-design 7.8.3 有完整推导）：
 * <pre>
 *   push
 *   mulPose(XP 180°)              // 模型空间 Y 向上 → 世界 Y 向下翻转
 *   translate(0, -1.5, 0)         // 把 1.5 格高的模型原点拉回脚底
 *   mulPose(Ry(targetYaw))        // 朝向伙伴
 *   [body 根节点变换：translate(x, y+0.7, z) → Rz → Ry → Rx → translate(0, -0.7, 0)]
 *   renderModel
 *   pop
 * </pre>
 *
 * <p>取消 {@code RenderPlayerEvent.Pre} 会跳过整条 {@code PlayerRenderer#render}，
 * 其中包含 playerAnimator 在 {@code setupRotations} 里做的 body 根节点变换，
 * 所以那 5 行必须由本类补上（否则 {@code hug_normal_receiver} 的 {@code torso.z = -1.0} 不会生效，两人之间会空出 1 格）。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RenderPlayerEventHandler {

    /** PORT-FIX K9 客户端兜底：服务端结束包丢失时，最多锁这么久（tick）。 */
    private static final long LOCK_TIMEOUT_TICKS = 200L;

    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    private RenderPlayerEventHandler() {}

    /** @param partner 对面那人；@param hideHead 第一人称下是否把头藏掉（发起者为 true） */
    private record Lock(UUID partner, boolean hideHead, long expireAt) {}

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        Lock lock = LOCKS.get(player.getUUID());
        if (lock == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            LOCKS.remove(player.getUUID());
            return;
        }
        if (client.level.getGameTime() > lock.expireAt()) {
            LOCKS.remove(player.getUUID());
            return;
        }
        Player partnerEntity = client.level.getPlayerByUUID(lock.partner());
        if (!(partnerEntity instanceof AbstractClientPlayer partner)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        float targetYaw = calculateYawToTarget(player, partner);

        poseStack.pushPose();
        // PoseStack 是后乘：最后调用的最先作用到顶点，所以下面这段的顺序不能调换
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
        poseStack.translate(0.0D, -1.5D, 0.0D);
        poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(targetYaw)));

        var animation = ((IAnimatedPlayer) player).playerAnimator_getAnimation();
        animation.setTickDelta(event.getPartialTick());
        if (animation.isActive()) {
            Vec3f position = animation.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
            poseStack.translate(position.getX(), position.getY() + 0.7D, position.getZ());
            Vec3f rotation = animation.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
            poseStack.mulPose(Axis.ZP.rotation(rotation.getZ()));
            poseStack.mulPose(Axis.YP.rotation(rotation.getY()));
            poseStack.mulPose(Axis.XP.rotation(rotation.getX()));
            poseStack.translate(0.0D, -0.7D, 0.0D);
        }

        renderPlayerModel(event.getRenderer(), player, poseStack,
                event.getMultiBufferSource(), event.getPackedLight(), lock.hideHead());

        poseStack.popPose();
        event.setCanceled(true);
    }

    private static void renderPlayerModel(PlayerRenderer renderer, AbstractClientPlayer entity, PoseStack poseStack,
                                          MultiBufferSource buffer, int packedLight, boolean hideHead) {
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        model.young = entity.isBaby();
        // 共享模型实例：每次渲染都显式赋值（K10），不做「只改一次」的优化
        model.head.visible = Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON
                || !hideHead
                || !(entity instanceof LocalPlayer);

        model.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);   // 四肢摆动清零 → 姿势冻结在动作上
        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(renderer.getTextureLocation(entity)));

        // 1.20.1 是 8 参浮点颜色（1.20.2+ 才是单 int 颜色），别照抄 1.21 代码
        model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static float calculateYawToTarget(AbstractClientPlayer from, AbstractClientPlayer to) {
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
    }

    public static void lockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        long until = (Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0L) + LOCK_TIMEOUT_TICKS;
        LOCKS.put(sender.getUUID(), new Lock(receiver.getUUID(), true, until));
        LOCKS.put(receiver.getUUID(), new Lock(sender.getUUID(), false, until));
    }

    public static void unlockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        LOCKS.remove(sender.getUUID());
        LOCKS.remove(receiver.getUUID());
    }
}
