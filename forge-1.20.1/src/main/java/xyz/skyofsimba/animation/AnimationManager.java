package xyz.skyofsimba.animation;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleAnimDef;

/**
 * 1.20.1 的动作播放后端（playerAnimator）。
 *
 * <p>层 id {@code letscuddle:animations}、优先级 42 不变；播放速度改由 manifest 的 {@code speed} 决定
 * （1.x 是写死的 0.5），用一层带 {@link SpeedModifier} 的内层 {@link ModifierLayer} 包住动画，
 * 这样同一个层可以播不同速度的动作。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AnimationManager {

    /** 自定义动画层 id：播放时取层必须用同一个 id。 */
    @SuppressWarnings("removal")
    private static final ResourceLocation LAYER_ID = new ResourceLocation(Letscuddle.MODID + ":animations");

    private AnimationManager() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID, 42, player -> new ModifierLayer<>()));
    }

    /**
     * 给两名玩家各自的动画层挂上互补的动作。
     * <p>K19：动作数据缺失时打日志放弃播放，不对 null 构造 {@link KeyframeAnimationPlayer}。
     */
    public static void playCuddleAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver,
                                           CuddleAnimDef def) {
        ModifierLayer<IAnimation> senderLayer = layerOf(sender);
        ModifierLayer<IAnimation> receiverLayer = layerOf(receiver);
        if (senderLayer == null || receiverLayer == null) {
            Letscuddle.LOGGER.warn("LetsCuddle: animation layer {} missing", LAYER_ID);
            return;
        }

        KeyframeAnimation senderAnim = lookup(def.senderId());
        KeyframeAnimation receiverAnim = lookup(def.receiverId());
        if (senderAnim == null || receiverAnim == null) {
            Letscuddle.LOGGER.warn("LetsCuddle: animation data missing for {} ({}/{})",
                    def.id(), def.senderId(), def.receiverId());
            return;
        }

        senderLayer.setAnimation(wrap(senderAnim, def.speed()));
        receiverLayer.setAnimation(wrap(receiverAnim, def.speed()));
    }

    /**
     * 提前结束（蹲下取消）：立刻收掉动作层上的动画。
     * <p>正常结束不调用这里 —— 让动作自然播完（约 122 tick，略长于 120 tick 锁定）。
     */
    public static void stopCuddleAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        ModifierLayer<IAnimation> senderLayer = layerOf(sender);
        if (senderLayer != null) {
            senderLayer.setAnimation(null);
        }
        ModifierLayer<IAnimation> receiverLayer = layerOf(receiver);
        if (receiverLayer != null) {
            receiverLayer.setAnimation(null);
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static ModifierLayer<IAnimation> layerOf(AbstractClientPlayer player) {
        IAnimation animation = PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        return animation instanceof ModifierLayer<?> layer ? (ModifierLayer<IAnimation>) layer : null;
    }

    @Nullable
    private static KeyframeAnimation lookup(String animationId) {
        ResourceLocation id = ResourceLocation.tryParse(animationId);
        return id == null ? null : PlayerAnimationRegistry.getAnimation(id);
    }

    private static IAnimation wrap(KeyframeAnimation animation, float speed) {
        ModifierLayer<IAnimation> wrapper = new ModifierLayer<>();
        wrapper.addModifierBefore(new SpeedModifier(speed));
        wrapper.setAnimation(newPlayer(animation));
        return wrapper;
    }

    private static KeyframeAnimationPlayer newPlayer(KeyframeAnimation animation) {
        return new KeyframeAnimationPlayer(animation)
                .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                .setFirstPersonConfiguration(new FirstPersonConfiguration()
                        .setShowLeftArm(true).setShowLeftItem(false)
                        .setShowRightArm(true).setShowRightItem(false));
    }
}
