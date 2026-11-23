package xyz.skyofsimba.mixin.client;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.skyofsimba.client.CuddleAnimBackend;

/**
 * 阴影按实体坐标画，模型已经偏移约 1 格；接管期间不生成阴影（与 1.20.1 的「本帧隐藏」等效）。
 *
 * <p>在 {@code updateShadow} 入口取消，此时 {@code state.id} 还没写入，所以按实体判断。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "updateShadow(Lnet/minecraft/entity/Entity;"
            + "Lnet/minecraft/client/render/entity/state/EntityRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void letscuddle$hideShadow(Entity entity, EntityRenderState state, CallbackInfo ci) {
        if (entity instanceof PlayerEntity player && CuddleAnimBackend.isLocked(player.getId())) {
            ci.cancel();
        }
    }
}
