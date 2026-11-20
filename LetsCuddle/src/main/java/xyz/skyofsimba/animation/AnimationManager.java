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

/**
 * 动作播放（仅物理客户端）：注册自定义动画层 {@code letscuddle:animations}，优先级 42。
 *
 * <p>三个必须保持的参数（port-plan 6.3）：
 * <ol>
 *   <li>层 id 与优先级不变（优先级 42 高于主层，保证动作能覆盖）；</li>
 *   <li>{@link SpeedModifier} 0.5F —— 动作文件 61 tick，降速后约 122 tick ≈ 6.1 秒，刚好比服务端 120 tick 锁定略长；</li>
 *   <li>动作 id 四个：{@code cuddle_normal_sender/receiver}、{@code cuddle_touch_sender/receiver}（命名空间跟随 modid）。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AnimationManager {

    /** 自定义动画层 id：播放时取层必须用同一个 id。 */
    public static final ResourceLocation LAYER_ID = new ResourceLocation(Letscuddle.MODID, "animations");

    private AnimationManager() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork：把工厂注册挪到客户端主线程再做
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID, 42, player -> {
                    ModifierLayer<IAnimation> layer = new ModifierLayer<>();
                    layer.addModifierBefore(new SpeedModifier(0.5F));   // 全局 50% 速度
                    return layer;
                }));
    }

    /**
     * 给两名玩家各自的动画层挂上互补的动作。
     * <p>PORT-FIX K19：动作数据缺失时打日志放弃播放，不再对 null 构造 KeyframeAnimationPlayer。
     */
    public static void playCuddleAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver,
                                        CuddleAnimationEnum kind) {
        ModifierLayer<IAnimation> senderLayer = layerOf(sender);
        ModifierLayer<IAnimation> receiverLayer = layerOf(receiver);
        if (senderLayer == null || receiverLayer == null) {
            Letscuddle.LOGGER.warn("LetsCuddle: animation layer {} missing", LAYER_ID);
            return;
        }

        KeyframeAnimation senderAnim = lookup(kind, true);
        KeyframeAnimation receiverAnim = lookup(kind, false);
        if (senderAnim == null || receiverAnim == null) {
            Letscuddle.LOGGER.warn("LetsCuddle: animation data missing for {}", kind);
            return;
        }

        senderLayer.setAnimation(newPlayer(senderAnim));
        receiverLayer.setAnimation(newPlayer(receiverAnim));
    }

    /**
     * 提前结束（蹲下取消）：立刻收掉动作层上的动画，避免玩家带着贴贴姿势走开。
     * <p>正常结束不调用这里 —— 原设计让动作自然播完（122 tick 略长于 120 tick 锁定）。
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

    /** 1.20 线的 {@code getAnimation} 直接返回 {@code @Nullable KeyframeAnimation}，无需强转。 */
    @Nullable
    private static KeyframeAnimation lookup(CuddleAnimationEnum kind, boolean sender) {
        String path = switch (kind) {
            case TOUCH_HEAD -> sender ? "cuddle_touch_sender" : "cuddle_touch_receiver";
            default -> sender ? "cuddle_normal_sender" : "cuddle_normal_receiver";
        };
        return PlayerAnimationRegistry.getAnimation(new ResourceLocation(Letscuddle.MODID, path));
    }

    private static KeyframeAnimationPlayer newPlayer(KeyframeAnimation animation) {
        return new KeyframeAnimationPlayer(animation)
                .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                .setFirstPersonConfiguration(new FirstPersonConfiguration()
                        .setShowLeftArm(true).setShowLeftItem(false)
                        .setShowRightArm(true).setShowRightItem(false));
    }
}
