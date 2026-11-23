package xyz.skyofsimba.client;

import net.fabricmc.api.ClientModInitializer;

/** 客户端入口：注册收包、动作注册表、动画层工厂与客户端桥。 */
public final class LetscuddleClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CuddleClientBridge.set(CuddleClientHooks.INSTANCE);
        CuddleAnimRegistry.register();
        CuddleClientNetwork.register();
        CuddlePalBridge.registerFactory();
    }
}
