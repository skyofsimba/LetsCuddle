package xyz.skyofsimba.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.skyofsimba.client.CuddleAnimBackend;

/**
 * 第三人称镜头跟随贴贴模型。
 *
 * <p>原版相机绕实体坐标旋转，而贴贴把模型推开了约 1 格；在 {@code update} 末尾把相机
 * 平移到模型位置即可（{@code Camera#setPos} 由 access widener 打开）。第一人称不动。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", at = @At("TAIL"))
    private void letscuddle$followCuddleModel(World area, Entity focusedEntity, boolean thirdPerson, boolean smooth,
                                              float tickProgress, CallbackInfo ci) {
        if (!thirdPerson || !(focusedEntity instanceof PlayerEntity player)) {
            return;
        }
        Vec3d offset = CuddleAnimBackend.bodyOffset(player.getId(), tickProgress);
        if (offset.lengthSquared() < 1.0E-6D) {
            return;
        }
        Camera camera = (Camera) (Object) this;
        camera.setPos(camera.getCameraPos().add(offset));
    }
}
