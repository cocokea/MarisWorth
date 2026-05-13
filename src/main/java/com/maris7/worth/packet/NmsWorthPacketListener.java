package com.maris7.worth.packet;

import com.maris7.worth.MarisWorthPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class NmsWorthPacketListener implements Listener {
    private static final String HANDLER_NAME = "marisworth_nms_lore";
    private static final String SET_CONTENT_PACKET = "net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket";
    private static final String SET_SLOT_PACKET = "net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket";
    private static final String CRAFT_ITEM_STACK = "org.bukkit.craftbukkit.inventory.CraftItemStack";

    private final MarisWorthPlugin plugin;
    private final Class<?> setContentPacketClass;
    private final Class<?> setSlotPacketClass;
    private final Class<?> nmsItemStackClass;
    private final Method asBukkitCopyMethod;
    private final Method asNmsCopyMethod;
    private final Constructor<?> setContentConstructor;
    private final Constructor<?> setSlotConstructor;
    private final Field contentItemsField;
    private final Field setSlotSlotField;
    private final Field setSlotItemField;
    private final Field carriedItemField;
    private final List<Field> contentIntFields;
    private final List<Field> setSlotIntFields;

    public NmsWorthPacketListener(MarisWorthPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.setContentPacketClass = Class.forName(SET_CONTENT_PACKET);
        this.setSlotPacketClass = Class.forName(SET_SLOT_PACKET);
        Class<?> craftItemStackClass = Class.forName(CRAFT_ITEM_STACK);
        this.nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
        this.asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);
        this.asNmsCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
        this.setContentConstructor = findSetContentConstructor(setContentPacketClass);
        this.setSlotConstructor = findSetSlotConstructor(setSlotPacketClass);
        this.contentItemsField = findListField(setContentPacketClass);
        this.setSlotSlotField = findIntField(setSlotPacketClass, "slot");
        this.setSlotItemField = findItemField(setSlotPacketClass);
        this.carriedItemField = findOptionalItemField(setContentPacketClass, "carriedItem");
        this.contentIntFields = findIntFields(setContentPacketClass);
        this.setSlotIntFields = findIntFields(setSlotPacketClass);
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            inject(player);
        }
    }

    public void unregister() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getSchedulerAdapter().runLater(event.getPlayer(), 1L, () -> inject(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    private void inject(Player player) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            ChannelDuplexHandler handler = new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                    super.write(context, transform(player.getUniqueId(), packet), promise);
                }
            };
            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
            } else {
                pipeline.addLast(HANDLER_NAME, handler);
            }
        });
    }

    private void remove(Player player) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        });
    }

    private Object transform(UUID playerId, Object packet) {
        try {
            if (setContentPacketClass.isInstance(packet)) {
                return transformSetContent(playerId, packet);
            } else if (setSlotPacketClass.isInstance(packet)) {
                return transformSetSlot(playerId, packet);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to inject worth lore into outgoing NMS packet: " + throwable.getMessage());
        }
        return packet;
    }

    @SuppressWarnings("unchecked")
    private Object transformSetContent(UUID playerId, Object packet) throws ReflectiveOperationException {
        List<Object> items = (List<Object>) contentItemsField.get(packet);
        List<Object> modified = new ArrayList<>(items);
        boolean changed = false;
        for (int slot = 0; slot < modified.size(); slot++) {
            if (!plugin.shouldDisplayWorthPacket(playerId, slot)) {
                continue;
            }
            Object nmsItem = modified.get(slot);
            Object injected = injectItem(playerId, slot, nmsItem);
            if (injected != nmsItem) {
                modified.set(slot, injected);
                changed = true;
            }
        }
        Object carried = carriedItemField != null ? carriedItemField.get(packet) : null;
        Object modifiedCarried = carried;
        if (carriedItemField != null && plugin.shouldDisplayWorthPacket(playerId, -1)) {
            modifiedCarried = injectItem(playerId, -1, carried);
            if (modifiedCarried != carried) {
                changed = true;
            }
        }
        if (!changed) {
            return packet;
        }
        return newSetContentPacket(packet, modified, modifiedCarried);
    }

    private Object transformSetSlot(UUID playerId, Object packet) throws ReflectiveOperationException {
        int slot = setSlotSlotField.getInt(packet);
        if (!plugin.shouldDisplayWorthPacket(playerId, slot)) {
            return packet;
        }
        Object nmsItem = setSlotItemField.get(packet);
        Object injected = injectItem(playerId, slot, nmsItem);
        if (injected == nmsItem) {
            return packet;
        }
        return newSetSlotPacket(packet, injected);
    }

    private Object newSetContentPacket(Object original, List<Object> items, Object carriedItem) throws ReflectiveOperationException {
        Object[] args = new Object[setContentConstructor.getParameterCount()];
        int intIndex = 0;
        Class<?>[] parameterTypes = setContentConstructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.equals(int.class)) {
                args[i] = contentIntFields.get(intIndex++).getInt(original);
            } else if (List.class.isAssignableFrom(parameterType)) {
                args[i] = items;
            } else if (parameterType.equals(nmsItemStackClass)) {
                args[i] = carriedItem;
            } else {
                throw new ReflectiveOperationException("Unsupported SetContent constructor parameter: " + parameterType.getName());
            }
        }
        return setContentConstructor.newInstance(args);
    }

    private Object newSetSlotPacket(Object original, Object item) throws ReflectiveOperationException {
        Object[] args = new Object[setSlotConstructor.getParameterCount()];
        int intIndex = 0;
        Class<?>[] parameterTypes = setSlotConstructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.equals(int.class)) {
                args[i] = setSlotIntFields.get(intIndex++).getInt(original);
            } else if (parameterType.equals(nmsItemStackClass)) {
                args[i] = item;
            } else {
                throw new ReflectiveOperationException("Unsupported SetSlot constructor parameter: " + parameterType.getName());
            }
        }
        return setSlotConstructor.newInstance(args);
    }

    private Object injectItem(UUID playerId, int rawSlot, Object nmsItem) throws ReflectiveOperationException {
        if (nmsItem == null) {
            return null;
        }
        ItemStack bukkitItem = (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
        if (bukkitItem == null || bukkitItem.getType().isAir()) {
            return nmsItem;
        }
        if (plugin.isStaticTopInventoryItem(playerId, rawSlot, bukkitItem)) {
            return nmsItem;
        }
        ItemStack injected = plugin.injectWorthForPacket(playerId, bukkitItem);
        if (injected == bukkitItem) {
            return nmsItem;
        }
        return asNmsCopyMethod.invoke(null, injected);
    }

    private Channel channel(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = readField(handle, "connection", null);
            if (listener == null) {
                return null;
            }
            Object connection = readField(listener, "connection", "net.minecraft.network.Connection");
            if (connection == null) {
                return null;
            }
            Object channel = readField(connection, "channel", Channel.class.getName());
            return channel instanceof Channel nettyChannel ? nettyChannel : null;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to access NMS player channel for " + player.getName() + ": " + exception.getMessage());
            return null;
        }
    }

    private Object readField(Object target, String preferredName, String requiredTypeName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                boolean nameMatches = preferredName != null && field.getName().equals(preferredName);
                boolean typeMatches = requiredTypeName != null && field.getType().getName().equals(requiredTypeName);
                if (nameMatches || typeMatches) {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (requiredTypeName == null || value == null || value.getClass().getName().equals(requiredTypeName) || field.getType().getName().equals(requiredTypeName)) {
                        return value;
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }


    private Constructor<?> findSetContentConstructor(Class<?> packetClass) throws NoSuchMethodException {
        for (Constructor<?> constructor : packetClass.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            boolean hasList = false;
            boolean hasItem = false;
            boolean onlySupported = true;
            for (Class<?> parameterType : parameterTypes) {
                if (parameterType.equals(int.class)) {
                    continue;
                }
                if (List.class.isAssignableFrom(parameterType)) {
                    hasList = true;
                    continue;
                }
                if (parameterType.equals(nmsItemStackClass)) {
                    hasItem = true;
                    continue;
                }
                onlySupported = false;
                break;
            }
            if (hasList && hasItem && onlySupported) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException("No compatible constructor in " + packetClass.getName());
    }

    private Constructor<?> findSetSlotConstructor(Class<?> packetClass) throws NoSuchMethodException {
        for (Constructor<?> constructor : packetClass.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            boolean hasItem = false;
            boolean onlySupported = true;
            for (Class<?> parameterType : parameterTypes) {
                if (parameterType.equals(int.class)) {
                    continue;
                }
                if (parameterType.equals(nmsItemStackClass)) {
                    hasItem = true;
                    continue;
                }
                onlySupported = false;
                break;
            }
            if (hasItem && onlySupported) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException("No compatible constructor in " + packetClass.getName());
    }

    private List<Field> findIntFields(Class<?> packetClass) throws NoSuchFieldException {
        List<Field> fields = new ArrayList<>();
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType().equals(int.class)) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        if (fields.isEmpty()) {
            throw new NoSuchFieldException("No int fields in " + packetClass.getName());
        }
        return fields;
    }

    private Field findListField(Class<?> packetClass) throws NoSuchFieldException {
        for (Field field : packetClass.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("No item List field in " + packetClass.getName());
    }

    private Field findIntField(Class<?> packetClass, String preferredName) throws NoSuchFieldException {
        Field fallback = null;
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType().equals(int.class)) {
                field.setAccessible(true);
                if (field.getName().equals(preferredName)) {
                    return field;
                }
                fallback = field;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchFieldException("No int field in " + packetClass.getName());
    }

    private Field findItemField(Class<?> packetClass) throws NoSuchFieldException {
        Field fallback = null;
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType().equals(nmsItemStackClass)) {
                field.setAccessible(true);
                fallback = field;
                if (field.getName().equals("itemStack") || field.getName().equals("item")) {
                    return field;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchFieldException("No NMS ItemStack field in " + packetClass.getName());
    }

    private Field findOptionalItemField(Class<?> packetClass, String preferredName) {
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType().equals(nmsItemStackClass) && field.getName().equals(preferredName)) {
                field.setAccessible(true);
                return field;
            }
        }
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType().equals(nmsItemStackClass)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
