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
import xyz.skyofsimba.client.CuddleCameraHandler;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddlePlacement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1.20.1 渲染接管：贴贴期间取消原版玩家渲染，自己摆好「面对面」的模型再画一遍。
 *
 * <p>矩阵链是后乘，顺序不可调换：
 * {@code Rx(180) · T(0,-1.5,0) · Ry(targetYaw) · [body 位移 + 旋转] · model}。
 * 取消 {@code RenderPlayerEvent.Pre} 会跳过 playerAnimator 在 {@code setupRotations} 里做的
 * body 根节点变换，所以那几行必须由本类补上（否则 {@code torso.z = -1.0} 不生效，两人之间空 1 格）。
 *
 * <p>副作用：接管期间不画盔甲/手持物/名字牌；阴影按实体坐标单独绘制，渲染前临时置为
 * invisible，并在 {@code AFTER_ENTITIES} 阶段恢复（只影响这一帧）。第一人称会隐藏自己的头（K10）。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RenderPlayerEventHandler {

    /** PORT-FIX K9 客户端兜底：服务端结束包丢失时，最多锁这么久（tick）。 */
    private static final long LOCK_TIMEOUT_TICKS = CuddleConstants.CLIENT_LOCK_FALLBACK_TICKS;

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
                CuddleCameraHandler.onCuddleEnd();
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
        float targetYaw = yawTo(player, partner);

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

    private static float yawTo(AbstractClientPlayer from, AbstractClientPlayer to) {
        return CuddlePlacement.yawTo(
                new xyz.skyofsimba.core.Vec3d(from.getX(), from.getY(), from.getZ()),
                new xyz.skyofsimba.core.Vec3d(to.getX(), to.getY(), to.getZ()));
    }

    /**
     * 模型相对实体坐标的世界空间水平位移（第三人称镜头用）。
     * 与 {@link CuddlePlacement#worldBodyOffset} 同一公式，保证模型与镜头一致。
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
        xyz.skyofsimba.core.Vec3d offset = CuddlePlacement.worldBodyOffset(
                body.getX(), body.getZ(), yawTo(player, partner));
        return new Vec3(offset.x(), offset.y(), offset.z());
    }

    /** 该玩家当前是否处于贴贴锁定状态（镜头事件用）。 */
    public static boolean isLocked(AbstractClientPlayer player) {
        return LOCKS.containsKey(player.getUUID());
    }

    public static void lockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        long until = (Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0L) + LOCK_TIMEOUT_TICKS;
        LOCKS.put(sender.getUUID(), new Lock(receiver.getUUID(), true, until));
        LOCKS.put(receiver.getUUID(), new Lock(sender.getUUID(), false, until));

        if (isLocalPlayer(sender) || isLocalPlayer(receiver)) {
            CuddleCameraHandler.onCuddleStart();                  // v1.4：当事人自动切第三人称
        }
    }

    public static void unlockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        LOCKS.remove(sender.getUUID());
        LOCKS.remove(receiver.getUUID());
        // HEAD_HIDDEN / SHADOW_HIDDEN 不在这里清：头部在下一帧的 lock==null 分支恢复，
        // 阴影在本帧 AFTER_ENTITIES 恢复，提前清掉会漏还原。

        if (isLocalPlayer(sender) || isLocalPlayer(receiver)) {
            CuddleCameraHandler.onCuddleEnd();
        }
    }
}
