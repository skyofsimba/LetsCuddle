package xyz.skyofsimba.mixin.client;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.skyofsimba.client.CuddleAnimBackend;
import xyz.skyofsimba.core.CuddlePlacement;

/**
 * 「包一层」渲染：不取消原版渲染，只在 {@code setupTransforms} 末尾把模型转向伙伴、按动作推进怀里。
 *
 * <p>原版此时已经乘了 {@code Ry(180 - bodyYaw)}，再右乘 {@code Ry(bodyYaw - targetYaw)}
 * 就等价于朝向 {@code targetYaw}；位移在翻转之前施加，所以是纯粹的世界坐标位移。
 * 皮肤、盔甲、手持物、披风全部由原版渲染器继续画。
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "setupTransforms(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
            + "Lnet/minecraft/client/util/math/MatrixStack;FF)V", at = @At("TAIL"))
    private void letscuddle$facePartner(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw,
                                        float baseHeight, CallbackInfo ci) {
        Float targetYaw = CuddleAnimBackend.targetYaw(state.id);
        if (targetYaw == null) {
            return;
        }
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bodyYaw - targetYaw));

        var pose = CuddleAnimBackend.pose(state.id, 0.0F);
        if (pose == null) {
            return;
        }
        xyz.skyofsimba.core.Vec3d offset =
                CuddlePlacement.worldBodyOffset(pose.body().x(), pose.body().z(), targetYaw);
        matrices.translate(offset.x(), 0.0D, offset.z());
    }

    /** 接管期间不乘原版 0.9375：模型高度与 1.20.1 的 2.0 对齐。 */
    @Inject(method = "scale(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
            + "Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("HEAD"), cancellable = true)
    private void letscuddle$skipVanillaScale(PlayerEntityRenderState state, MatrixStack matrices, CallbackInfo ci) {
        if (CuddleAnimBackend.isLocked(state.id)) {
            ci.cancel();
        }
    }

    /** 名字牌按实体坐标画，模型已经偏移；接管期间直接不画。 */
    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
            + "Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
            + "Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void letscuddle$hideLabel(PlayerEntityRenderState state, MatrixStack matrices,
                                      OrderedRenderCommandQueue queue, CameraRenderState cameraState,
                                      CallbackInfo ci) {
        if (CuddleAnimBackend.isLocked(state.id)) {
            ci.cancel();
        }
    }}


