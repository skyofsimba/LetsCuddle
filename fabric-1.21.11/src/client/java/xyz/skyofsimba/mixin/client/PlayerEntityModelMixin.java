package xyz.skyofsimba.mixin.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.skyofsimba.client.CuddleAnimBackend;
import xyz.skyofsimba.core.Bone;
import xyz.skyofsimba.core.CuddlePose;

/**
 * 把动作姿势叠加到玩家模型上。
 *
 * <p>注入点在 {@code setAngles} 末尾：1.21.11 的模型姿势在渲染队列真正绘制时才计算，
 * 这里拿到的 {@code state.id} 就是该玩家，且原版姿势已经摆好，只做加法即可。
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V",
            at = @At("TAIL"))
    private void letscuddle$applyCuddlePose(PlayerEntityRenderState state, CallbackInfo ci) {
        CuddlePose pose = CuddleAnimBackend.pose(state.id, 0.0F);
        if (pose == null) {
            return;
        }
        BipedEntityModel<?> model = (BipedEntityModel<?>) (Object) this;
        apply(model.head, pose.head());
        apply(model.body, pose.body());
        apply(model.leftArm, pose.leftArm());
        apply(model.rightArm, pose.rightArm());
        apply(model.leftLeg, pose.leftLeg());
        apply(model.rightLeg, pose.rightLeg());
    }

    private static void apply(ModelPart part, Bone bone) {
        part.pitch += bone.pitch();
        part.yaw += bone.yaw();
        part.roll += bone.roll();
    }
}
