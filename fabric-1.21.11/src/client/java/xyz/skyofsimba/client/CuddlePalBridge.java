package xyz.skyofsimba.client;

import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractModifier;
import com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleConstants;

/**
 * 1.21.11 的动作播放后端：PAL（Player Animation Library，playerAnimator 的继任者）。
 *
 * <p>与 1.20.1 的 playerAnimator 对应：同一个自定义层（优先级 42）、同样的 0.5× 默认速度、
 * 同样的 {@code player_animations/*.json} 目录与 {@code name} 字段作为动画 id。
 *
 * <p>PAL 是可选依赖（{@code fabric.mod.json} 的 {@code suggests}）：没装时所有方法都是 no-op，
 * 贴贴仍然成立（锁定、镜头、位置都正常），只是不动。
 */
public final class CuddlePalBridge {

    /** 层 id 与优先级沿用 1.x 的约定。 */
    private static final Identifier LAYER_ID = Identifier.of(CuddleConstants.MODID, "animations");

    private static final String PAL_MOD_ID = "player_animation_library";
    private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded(PAL_MOD_ID);

    private CuddlePalBridge() {}

    /** 每个客户端玩家创建一次动画层。 */
    public static void registerFactory() {
        if (!AVAILABLE) {
            Letscuddle.LOGGER.error("LetsCuddle: {} is not installed - cuddles will work but nobody will move. "
                    + "Install Player Animation Library for 1.21.11 on the CLIENT.", PAL_MOD_ID);
            return;
        }
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, 42, player -> {
            PlayerAnimationController controller = new PlayerAnimationController(player,
                    (animationController, state, setter) -> PlayState.CONTINUE);
            controller.addModifierBefore(new SpeedModifier(CuddleConstants.DEFAULT_SPEED));
            // 第一人称也画模型（与 1.20.1 的 THIRD_PERSON_MODEL 一致）
            controller.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
            return controller;
        });
    }

    /**
     * 播放一段动画。
     *
     * @param animationId 动画 id（{@code letscuddle:cuddle_normal_sender} 这种完整 id）
     * @param speed       manifest 里的速度倍率
     */
    public static void play(PlayerEntity player, String animationId, float speed) {
        if (!AVAILABLE) {
            return;
        }
        PlayerAnimationController controller = controllerOf(player);
        if (controller == null) {
            return;
        }
        Identifier id = Identifier.tryParse(animationId);
        if (id == null) {
            Letscuddle.LOGGER.warn("LetsCuddle: invalid animation id {}", animationId);
            return;
        }
        applySpeed(controller, speed);
        if (!controller.triggerAnimation(id)) {
            Letscuddle.LOGGER.warn("LetsCuddle: animation {} is not loaded by PAL", id);
        }
    }

    /** 立即收掉动画（蹲下取消/掉线）。 */
    public static void stop(PlayerEntity player) {
        if (!AVAILABLE) {
            return;
        }
        PlayerAnimationController controller = controllerOf(player);
        if (controller != null) {
            controller.stop();
        }
    }

    @Nullable
    private static PlayerAnimationController controllerOf(PlayerEntity player) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER_ID);
        return layer instanceof PlayerAnimationController controller ? controller : null;
    }

    /** 同一个层要播不同速度的动作：改层上那个 SpeedModifier 的倍率。 */
    private static void applySpeed(PlayerAnimationController controller, float speed) {
        for (AbstractModifier modifier : controller.getModifiers()) {
            if (modifier instanceof SpeedModifier speedModifier) {
                speedModifier.speed = speed;
                return;
            }
        }
        controller.addModifierBefore(new SpeedModifier(speed));
    }
}
