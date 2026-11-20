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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.client.HugCameraHandler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>v1.4 追加：
 * <ul>
 *   <li><b>阴影</b>：1.20.1 的阴影由 {@code EntityRenderDispatcher} 在渲染器返回后按<b>实体坐标</b>单独绘制，
 *       不跟随我们在 PoseStack 里的位移。这里在渲染前把当事人临时置为 invisible（阴影的绘制条件含 {@code !isInvisible()}），
 *       在 {@code AFTER_ENTITIES} 阶段立刻恢复 —— 只影响这一帧，不会让无模组客户端看到人消失。</li>
 *   <li><b>K10</b>：记录我们隐藏过谁的头，解锁后恢复。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RenderPlayerEventHandler {

    /** PORT-FIX K9 客户端兜底：服务端结束包丢失时，最多锁这么久（tick）。 */
    private static final long LOCK_TIMEOUT_TICKS = 200L;

    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    /** 本帧被我们临时置为 invisible 的玩家（用于隐藏阴影），AFTER_ENTITIES 时恢复。 */
    private static final Set<UUID> SHADOW_HIDDEN = new HashSet<>();

    /** 被我们隐藏过头的玩家（K10），解锁后需要恢复 visible。 */
    private static final Set<UUID> HEAD_HIDDEN = new HashSet<>();

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
            restoreHead(event.getRenderer(), player);      // K10：解锁后把头放回来
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            LOCKS.remove(player.getUUID());
            return;
        }
        if (client.level.getGameTime() > lock.expireAt()) {   // 兜底超时
            LOCKS.remove(player.getUUID());
            if (isLocalPlayer(player)) {
                HugCameraHandler.onHugEnd();
            }
            restoreHead(event.getRenderer(), player);
            return;
        }
        Player partnerEntity = client.level.getPlayerByUUID(lock.partner());
        if (!(partnerEntity instanceof AbstractClientPlayer partner)) {
            return;
        }

        hideShadowForThisFrame(player);

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

    /**
     * 阴影纠偏：阴影在 {@code EntityRenderDispatcher} 里按实体坐标绘制，且要求实体不可见。
     * 这里只把「本帧」置为不可见，{@link #onRenderLevelStage} 在实体批次结束后立刻恢复。
     */
    private static void hideShadowForThisFrame(AbstractClientPlayer player) {
        if (!player.isInvisible()) {
            player.setInvisible(true);
            SHADOW_HIDDEN.add(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            restoreShadow();
        }
    }

    /** 兜底：万一某帧没走到 AFTER_ENTITIES（例如异常中断），tick 末也要把 invisible 复位。 */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            restoreShadow();
        }
    }

    private static void restoreShadow() {
        if (SHADOW_HIDDEN.isEmpty()) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            for (UUID id : SHADOW_HIDDEN) {
                Player player = level.getPlayerByUUID(id);
                if (player != null) {
                    player.setInvisible(false);
                }
            }
        }
        SHADOW_HIDDEN.clear();
    }

    private static void renderPlayerModel(PlayerRenderer renderer, AbstractClientPlayer entity, PoseStack poseStack,
                                          MultiBufferSource buffer, int packedLight, boolean hideHead) {
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        model.young = entity.isBaby();
        // 共享模型实例：每次渲染都显式赋值（K10），并记录我们改过谁
        model.head.visible = Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON
                || !hideHead
                || !(entity instanceof LocalPlayer);
        if (model.head.visible) {
            HEAD_HIDDEN.remove(entity.getUUID());
        } else {
            HEAD_HIDDEN.add(entity.getUUID());
        }

        model.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);   // 四肢摆动清零 → 姿势冻结在动作上
        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(renderer.getTextureLocation(entity)));

        // 1.20.1 是 8 参浮点颜色（1.20.2+ 才是单 int 颜色），别照抄 1.21 代码
        model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void restoreHead(PlayerRenderer renderer, AbstractClientPlayer player) {
        if (HEAD_HIDDEN.remove(player.getUUID())) {
            renderer.getModel().head.visible = true;
        }
    }

    private static boolean isLocalPlayer(AbstractClientPlayer player) {
        LocalPlayer local = Minecraft.getInstance().player;
        return local != null && local.getUUID().equals(player.getUUID());
    }

    private static float calculateYawToTarget(AbstractClientPlayer from, AbstractClientPlayer to) {
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
    }

    /**
     * v1.5.1：被渲染模型相对实体坐标的<b>世界空间水平位移</b>，供第三人称镜头偏移使用。
     *
     * <p>渲染时的矩阵链是 {@code Rx(180) · T(0,-1.5,0) · Ry(θ) · T(px, py+0.7, pz) · Rbody · T(0,-0.7,0)}
     * （见 {@link #onRenderPlayer}），把 (px, pz) 依次过 Ry(θ) 与 Rx(180)（只翻 y/z 符号）就得到世界位移：
     * <pre>
     *   dx =  px·cosθ + pz·sinθ
     *   dz =  px·sinθ − pz·cosθ
     * </pre>
     * 例：接受者在 (1.3,0,0)、面向 −X（θ=90°）、body.z=−1 → 位移 (−1,0,0)，模型落在 (0.3,0,0)，正确。
     *
     * @return 未在拥抱中 / 动作未激活时返回 {@link Vec3#ZERO}
     */
    public static Vec3 modelOffset(AbstractClientPlayer player, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return Vec3.ZERO;
        }
        Lock lock = LOCKS.get(player.getUUID());
        if (lock == null) {
            return Vec3.ZERO;
        }
        Player partnerEntity = client.level.getPlayerByUUID(lock.partner());
        if (!(partnerEntity instanceof AbstractClientPlayer partner)) {
            return Vec3.ZERO;
        }

        var animation = ((IAnimatedPlayer) player).playerAnimator_getAnimation();
        animation.setTickDelta(partialTick);
        if (!animation.isActive()) {
            return Vec3.ZERO;
        }

        Vec3f body = animation.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
        double theta = Math.toRadians(calculateYawToTarget(player, partner));
        double px = body.getX();
        double pz = body.getZ();
        double dx = px * Math.cos(theta) + pz * Math.sin(theta);
        double dz = px * Math.sin(theta) - pz * Math.cos(theta);
        return new Vec3(dx, 0.0D, dz);
    }

    /** 该玩家当前是否处于拥抱锁定状态（镜头事件用）。 */
    public static boolean isLocked(AbstractClientPlayer player) {
        return LOCKS.containsKey(player.getUUID());
    }

    public static void lockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        long until = (Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0L) + LOCK_TIMEOUT_TICKS;
        LOCKS.put(sender.getUUID(), new Lock(receiver.getUUID(), true, until));
        LOCKS.put(receiver.getUUID(), new Lock(sender.getUUID(), false, until));

        if (isLocalPlayer(sender) || isLocalPlayer(receiver)) {
            HugCameraHandler.onHugStart();                  // v1.4：当事人自动切第三人称
        }
    }

    public static void unlockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        LOCKS.remove(sender.getUUID());
        LOCKS.remove(receiver.getUUID());
        // HEAD_HIDDEN / SHADOW_HIDDEN 不在这里清：头部在下一帧的 lock==null 分支恢复，
        // 阴影在本帧 AFTER_ENTITIES 恢复，提前清掉会漏还原。

        if (isLocalPlayer(sender) || isLocalPlayer(receiver)) {
            HugCameraHandler.onHugEnd();
        }
    }
}
