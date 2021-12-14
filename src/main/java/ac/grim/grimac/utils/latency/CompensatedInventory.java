package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.inventory.ClickType;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.inventory.InventoryStorage;
import ac.grim.grimac.utils.inventory.WrappedStack;
import ac.grim.grimac.utils.inventory.inventory.AbstractContainerMenu;
import ac.grim.grimac.utils.inventory.inventory.MenuTypes;
import io.github.retrooper.packetevents.event.impl.PacketPlayReceiveEvent;
import io.github.retrooper.packetevents.event.impl.PacketPlaySendEvent;
import io.github.retrooper.packetevents.packettype.PacketType;
import io.github.retrooper.packetevents.packetwrappers.WrappedPacket;
import io.github.retrooper.packetevents.packetwrappers.play.in.blockdig.WrappedPacketInBlockDig;
import io.github.retrooper.packetevents.packetwrappers.play.in.helditemslot.WrappedPacketInHeldItemSlot;
import io.github.retrooper.packetevents.packetwrappers.play.in.windowclick.WrappedPacketInWindowClick;
import io.github.retrooper.packetevents.packetwrappers.play.out.openwindow.WrappedPacketOutOpenWindow;
import io.github.retrooper.packetevents.packetwrappers.play.out.setslot.WrappedPacketOutSetSlot;
import io.github.retrooper.packetevents.packetwrappers.play.out.windowitems.WrappedPacketOutWindowItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

// hmmm... 1.17 added some interesting stuff to the packet...
// It seems to actually add essentials information to the packet...
//
// the client sends the itemstacks that it changes... which is very nice.
// although let's do it the multi-version way anyways as we have proper lag compensated so it has the same result
//
// for the first time... thanks mojang!
public class CompensatedInventory extends PacketCheck {
    // Here are the mappings from the geniuses at Mojang
    // 1, 2, 3, 4 and 0 are the crafting table
    // 5, 6, 7, 8 are the armor slots from helmet to boots
    // 45 is the offhand, only existing on 1.9+ servers
    // 36-44 is the hotbar
    // 9 is top left, through 35 being the bottom right.
    int openWindowID = 0;
    // Temporarily public for debugging
    public Inventory inventory;
    // Temporarily public for debugging
    public AbstractContainerMenu menu;

    public CompensatedInventory(GrimPlayer playerData) {
        super(playerData);

        InventoryStorage storage = new InventoryStorage(46);
        inventory = new Inventory(playerData, storage);

        menu = inventory;
    }

    public ItemStack getHeldItem() {
        return inventory.getHeldItem().getStack();
    }

    public void onPacketReceive(final PacketPlayReceiveEvent event) {
        if (event.getPacketId() == PacketType.Play.Client.BLOCK_DIG) {
            WrappedPacketInBlockDig dig = new WrappedPacketInBlockDig(event.getNMSPacket());

            if (dig.getDigType() == WrappedPacketInBlockDig.PlayerDigType.DROP_ITEM) {
                ItemStack heldItem = inventory.getHeldItem().getStack();
                if (heldItem != null) {
                    heldItem.setAmount(heldItem.getAmount() - 1);
                    if (heldItem.getAmount() <= 0) {
                        heldItem = null;
                    }
                }
                inventory.setHeldItem(heldItem);
            }

            if (dig.getDigType() == WrappedPacketInBlockDig.PlayerDigType.DROP_ALL_ITEMS) {
                inventory.setHeldItem(null);
            }
        }

        if (event.getPacketId() == PacketType.Play.Client.HELD_ITEM_SLOT) {
            WrappedPacketInHeldItemSlot slot = new WrappedPacketInHeldItemSlot(event.getNMSPacket());

            // Stop people from spamming the server with an out-of-bounds exception
            if (slot.getCurrentSelectedSlot() > 8) return;

            inventory.selected = slot.getCurrentSelectedSlot();
        }

        if (event.getPacketId() == PacketType.Play.Client.WINDOW_CLICK) {
            WrappedPacketInWindowClick click = new WrappedPacketInWindowClick(event.getNMSPacket());

            // 0 for left click
            // 1 for right click
            int button = click.getWindowButton();
            // Offset by the number of slots in the inventory actively open
            // Is -999 when clicking off the screen
            int slot = click.getWindowSlot();
            // Self-explanatory, look at the enum's values
            ClickType clickType = ClickType.values()[click.getMode()];

            Bukkit.broadcastMessage("Clicked " + button + " " + slot + " " + clickType);

            menu.doClick(button, slot, clickType);
        }

        if (event.getPacketId() == PacketType.Play.Client.CLOSE_WINDOW) {
            menu = inventory;
            menu.setCarried(WrappedStack.empty()); // Reset carried item
        }
    }

    public boolean isEmpty(ItemStack stack) {
        if (stack == null) return true;
        if (stack.getType() == Material.AIR) return true;
        return stack.getAmount() <= 0;
    }

    public void onPacketSend(final PacketPlaySendEvent event) {
        // Not 1:1 MCP, based on Wiki.VG to be simpler as we need less logic...
        // For example, we don't need permanent storage, only storing data until the client closes the window
        // We also don't need a lot of server-sided only logic
        if (event.getPacketId() == PacketType.Play.Server.OPEN_WINDOW) {
            WrappedPacketOutOpenWindow open = new WrappedPacketOutOpenWindow(event.getNMSPacket());

            // There doesn't seem to be a check against using 0 as the window ID - let's consider that an invalid packet
            // It will probably mess up a TON of logic both client and server sided, so don't do that!
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                openWindowID = open.getWindowId();

                AbstractContainerMenu newMenu = MenuTypes.getMenuFromID(player, inventory, open.getInventoryTypeId().get());
                if (newMenu != null) {
                    menu = newMenu;
                }
            });
        }

        // 1:1 MCP - supports plugins sending stupid packets for stupid reasons that point to an invalid horse
        if (event.getPacketId() == PacketType.Play.Server.OPEN_WINDOW_HORSE) {
            WrappedPacket packet = new WrappedPacket(event.getNMSPacket());
            int windowID = packet.readInt(0);
            int slotCount = packet.readInt(1);
            int entityID = packet.readInt(2);

            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                PacketEntity hopefullyAHorse = player.compensatedEntities.getEntity(entityID);

                if (hopefullyAHorse instanceof PacketEntityHorse) {
                    openWindowID = windowID;
                    //openedInventory = new ArrayList<>(offset);
                }
            });
        }

        // Is this mapped wrong?  Should it be ClientboundMerchantOffersPacket?  What is this packet?
        if (event.getPacketId() == PacketType.Play.Server.OPEN_WINDOW_MERCHANT) {

        }

        // 1:1 MCP
        if (event.getPacketId() == PacketType.Play.Server.CLOSE_WINDOW) {
            // Disregard provided window ID, client doesn't care...
            // We need to do this because the client doesn't send a packet when closing the window
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                openWindowID = 0;
                menu = inventory;
                menu.setCarried(WrappedStack.empty()); // Reset carried item
            });
        }

        // Should be 1:1 MCP
        if (event.getPacketId() == PacketType.Play.Server.WINDOW_ITEMS) {
            WrappedPacketOutWindowItems items = new WrappedPacketOutWindowItems(event.getNMSPacket());

            // State ID is how the game tries to handle latency compensation.
            // Unsure if we need to know about this.
            if (items.getWindowId() == 0) { // Player inventory
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                    List<ItemStack> slots = items.getSlots();
                    for (int i = 0; i < slots.size(); i++) {
                        inventory.getSlot(i).set(slots.get(i));
                    }
                });
            } else {
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                    if (items.getWindowId() == openWindowID) {
                        List<ItemStack> slots = items.getSlots();
                        for (int i = 0; i < slots.size(); i++) {
                            menu.getSlot(i).set(slots.get(i));
                        }
                    }
                });
            }
        }

        // Also 1:1 MCP
        if (event.getPacketId() == PacketType.Play.Server.SET_SLOT) {
            // Only edit hotbar (36 to 44) if window ID is 0
            // Set cursor by putting -1 as window ID and as slot
            // Window ID -2 means any slot can be used
            WrappedPacketOutSetSlot slot = new WrappedPacketOutSetSlot(event.getNMSPacket());

            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                if (slot.getWindowId() == -1) { // Carried item
                    inventory.setCarried(new WrappedStack(slot.getItemStack()));
                } else if (slot.getWindowId() == -2) { // Any slot is allowed to change in inventory
                    inventory.getSlot(slot.getSlot()).set(slot.getItemStack());
                } else if (slot.getWindowId() == 0) { // Player hotbar
                    if (slot.getSlot() >= 36 && slot.getSlot() <= 44) { // Client ignored if not in range
                        inventory.getSlot(slot.getSlot()).set(slot.getItemStack());
                    }
                } else if (slot.getWindowId() == openWindowID) { // Opened inventory
                    menu.getSlot(slot.getSlot()).set(slot.getItemStack());
                }
            });
        }
    }
}
