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

/**
 * 「包一层」渲染：不取消原版渲染，只在 {@code setupTransforms} 末尾把模型转向伙伴。
 *
 * <p>骨骼姿势、body 前压位移都由 PAL 在模型内部完成（对应 1.20.1 的 playerAnimator），
 * 这里**不能**再加位移，否则会叠两倍。皮肤、盔甲、手持物、披风全部由原版渲染器继续画。
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
        // 原版此时已经乘了 Ry(180 - bodyYaw)，再右乘 Ry(bodyYaw - targetYaw) 就是朝向 targetYaw
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bodyYaw - targetYaw));
    }

    /** 接管期间不乘原版 0.9375：模型高度与 1.20.1 的 2.0 对齐，前压位移也正好是 1 格。 */
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
    }
}
