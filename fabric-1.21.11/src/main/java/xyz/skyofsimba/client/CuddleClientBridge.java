package xyz.skyofsimba.client;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

/**
 * 主源码集到客户端源码集的桥（Fabric 的 split source sets 里 main 不能直接引用 client）。
 *
 * <p>客户端入口 {@code LetscuddleClient} 在初始化时替换成真实实现；专用服务端永远是 {@link #NONE}。
 */
public abstract class CuddleClientBridge {

    /** 专用服务端的空实现。 */
    public static final CuddleClientBridge NONE = new CuddleClientBridge() {
        @Override
        public void selectNextAnimation(ItemStack stack, Hand hand) {
        }

        @Override
        public Text displayName(String animId) {
            return Text.literal(animId);
        }

        @Override
        public void sendCaps() {
        }
    };

    private static CuddleClientBridge instance = NONE;

    public static CuddleClientBridge get() {
        return instance;
    }

    public static void set(CuddleClientBridge bridge) {
        instance = bridge == null ? NONE : bridge;
    }

    /** 循环切到下一个动作并上报服务端。 */
    public abstract void selectNextAnimation(ItemStack stack, Hand hand);

    /** 动作显示名：name_key → name → id。 */
    public abstract Text displayName(String animId);

    /** 把当前注册表的能力上报给服务端。 */
    public abstract void sendCaps();
}
