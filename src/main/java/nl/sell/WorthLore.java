package nl.sell;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Voegt de waarde toe aan de tooltip van items, alleen client-side (via packets).
 * De echte items op de server worden NIET aangepast.
 */
public class WorthLore extends PacketListenerAbstract {

    private final SellPlugin plugin;

    private WorthLore(SellPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    public static Object register(SellPlugin plugin) {
        WorthLore l = new WorthLore(plugin);
        PacketEvents.getAPI().getEventManager().registerListener(l);
        return l;
    }

    public static void unregister(Object listener) {
        if (listener instanceof WorthLore l) {
            PacketEvents.getAPI().getEventManager().unregisterListener(l);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            handle(event);
        } catch (Throwable t) {
            plugin.onLoreError(t);
        }
    }

    private void handle(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean setSlot = type == PacketType.Play.Server.SET_SLOT;
        boolean window = type == PacketType.Play.Server.WINDOW_ITEMS;

        if (!setSlot && !window) {
            // Nieuwe servers sturen losse inventory-wijzigingen (bv. oppakken) via een eigen packet.
            // Die laten we ongemoeid, maar we sturen daarna het hele inventory opnieuw (met tooltip).
            if (String.valueOf(type).equals("SET_PLAYER_INVENTORY")) {
                UUID u = event.getUser().getUUID();
                if (u != null) plugin.resyncInventory(u);
            }
            return;
        }

        UUID uuid = event.getUser().getUUID();
        if (uuid == null || !plugin.shouldShowLore(uuid)) return;

        if (setSlot) {
            WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(event);
            if (w.getWindowId() < 0) return; // cursor / speciale vensters
            if (plugin.skipSlot(uuid, w.getWindowId(), w.getSlot())) return;
            var modified = addLore(w.getItem(), uuid);
            if (modified != null) {
                w.setItem(modified);
                event.markForReEncode(true);
            }
        } else {
            WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(event);
            List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = new ArrayList<>(w.getItems());
            boolean changed = false;
            for (int i = 0; i < items.size(); i++) {
                if (plugin.skipSlot(uuid, w.getWindowId(), i)) continue;
                var modified = addLore(items.get(i), uuid);
                if (modified != null) {
                    items.set(i, modified);
                    changed = true;
                }
            }
            if (changed) {
                w.setItems(items);
                event.markForReEncode(true);
            }
        }
    }

    private com.github.retrooper.packetevents.protocol.item.ItemStack addLore(
            com.github.retrooper.packetevents.protocol.item.ItemStack packetItem, UUID uuid) {
        if (packetItem == null || packetItem.isEmpty()) return null;

        org.bukkit.inventory.ItemStack item = SpigotConversionUtil.toBukkitItemStack(packetItem);
        double unit = plugin.displayUnitPrice(item, uuid);
        if (unit < 0) return null;

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.loreLine(unit * item.getAmount()));
        item.lore(lore);
        plugin.onLoreApplied();
        return SpigotConversionUtil.fromBukkitItemStack(item);
    }
}
