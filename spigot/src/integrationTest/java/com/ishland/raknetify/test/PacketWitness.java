package com.ishland.raknetify.test;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Observes the installed PacketEvents API without embedding another copy of it. */
public final class PacketWitness extends JavaPlugin implements Listener {
    /** Bounds output to one witness per packet direction and type. */
    private final Set<String> observed = ConcurrentHashMap.newKeySet();

    /**
     * Registers a real external listener through the dependency's own class loader.
     *
     * @throws IllegalStateException if listener registration fails
     */
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        try {
            ClassLoader loader =
                    getServer()
                            .getPluginManager()
                            .getPlugin("packetevents")
                            .getClass()
                            .getClassLoader();
            Class<?> listener =
                    loader.loadClass("com.github.retrooper.packetevents.event.PacketListener");
            Class<?> priority =
                    loader.loadClass(
                            "com.github.retrooper.packetevents.event.PacketListenerPriority");
            Object proxy =
                    Proxy.newProxyInstance(loader, new Class<?>[] {listener}, this::dispatch);
            Object manager = api().getClass().getMethod("getEventManager").invoke(api());
            manager.getClass()
                    .getMethod("registerListener", listener, priority)
                    .invoke(manager, proxy, priority.getField("MONITOR").get(null));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PacketEvents listener registration failed", error);
        }
    }

    /**
     * Resolves the API instance that belongs to the installed plugin.
     *
     * @return active plugin API
     * @throws ReflectiveOperationException if the pinned API is unavailable
     */
    private Object api() throws ReflectiveOperationException {
        ClassLoader loader =
                getServer()
                        .getPluginManager()
                        .getPlugin("packetevents")
                        .getClass()
                        .getClassLoader();
        return loader.loadClass("com.github.retrooper.packetevents.PacketEvents")
                .getMethod("getAPI")
                .invoke(null);
    }

    /**
     * Preserves default interface behavior while recording send and receive callbacks.
     *
     * @param proxy listener instance
     * @param method invoked API method
     * @param arguments invocation arguments
     * @return default result or null for a notification
     * @throws Throwable if the real default implementation fails
     */
    @SuppressWarnings("ReferenceEquality")
    private @Nullable Object dispatch(
            final Object proxy, final Method method, final Object[] arguments) throws Throwable {
        if ("onPacketSend".equals(method.getName()) || "onPacketReceive".equals(method.getName())) {
            observe(method.getName(), arguments[0]);
            return null;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, arguments);
        }
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "PacketWitness listener";
            default -> null;
        };
    }

    /**
     * Emits packet metadata only for the synthetic player after login.
     *
     * @param direction packet callback name
     * @param event real plugin event
     * @throws ReflectiveOperationException if packet metadata is unavailable
     */
    private void observe(final String direction, final Object event)
            throws ReflectiveOperationException {
        Object user = event.getClass().getMethod("getUser").invoke(event);
        if (user == null
                || !"TransportProbe".equals(user.getClass().getMethod("getName").invoke(user))) {
            return;
        }
        String marker = direction + " " + event.getClass().getMethod("getPacketName").invoke(event);
        if (observed.add(marker)) {
            getLogger().info("WITNESS " + marker);
        }
    }

    /**
     * Sends a packet through PacketEvents after the synthetic player has joined.
     *
     * @param event native player join
     */
    @EventHandler
    public void joined(final PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> sendMessage(event), 10L);
    }

    /**
     * Requires PacketEvents to own the player before injecting a chat message.
     *
     * @param event joined player
     * @throws IllegalStateException if the player or message API is unavailable
     */
    private void sendMessage(final PlayerJoinEvent event) {
        try {
            Object active = api();
            ClassLoader loader = active.getClass().getClassLoader();
            Object manager =
                    loader.loadClass("com.github.retrooper.packetevents.PacketEventsAPI")
                            .getMethod("getPlayerManager")
                            .invoke(active);
            Object user =
                    loader.loadClass(
                                    "com.github.retrooper.packetevents.manager.player.PlayerManager")
                            .getMethod("getUser", Object.class)
                            .invoke(manager, event.getPlayer());
            if (user == null) {
                throw new IllegalStateException("PacketEvents missed joined player");
            }
            loader.loadClass("com.github.retrooper.packetevents.protocol.player.User")
                    .getMethod("sendMessage", String.class)
                    .invoke(user, "PacketEvents fixture message");
            getLogger().info("WITNESS PacketEvents message injected");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PacketEvents message injection failed", error);
        }
    }
}
