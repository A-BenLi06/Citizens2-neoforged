package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.ShopTrait;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * A shop: a named set of pages of items, with its own till and stock.
 * <p>
 * Upstream nests this and everything around it inside {@code ShopTrait}, one 1500-line file. Here each piece is its own
 * class in this package, which is also what let {@link NPCShopStorage} break the cycle with {@link NPCShopAction}.
 */
public class NPCShop {
    @Persist(value = "")
    private String name;
    @Persist(reify = true)
    private final List<NPCShopPage> pages = new ArrayList<>();
    @Persist(reify = true)
    NPCShopStorage storage = new NPCShopStorage();
    @Persist
    private String title;
    @Persist
    private ShopType type = ShopType.DEFAULT;
    @Persist
    private String viewPermission;
    private final ShopSessionOwner sessions = new ShopSessionOwner();

    <T> Runnable trackSession(T target, java.util.function.Consumer<T> close) {
        return sessions.track(target, close);
    }

    public boolean isActive() { return sessions.isActive(); }
    boolean beginTrade() { return sessions.beginTrade(); }
    void endTrade() { sessions.endTrade(); }
    void requireIdle() { sessions.requireIdle(); storage.requireIdle(); }
    void retire() {
        requireIdle();
        Throwable failure = null;
        try { sessions.retire(); } catch (RuntimeException | Error ex) { failure = ex; }
        try { storage.retire(); } catch (RuntimeException | Error ex) {
            if (failure == null) failure = ex;
            else if (failure != ex) failure.addSuppressed(ex);
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    private void present(InventoryMenu menu, NPCShopStorage stock, ServerPlayer player) {
        menu.addCloseCallback(trackSession(menu, InventoryMenu::dispose));
        try { menu.addCloseCallback(stock.trackSession(menu, InventoryMenu::dispose)); }
        catch (RuntimeException | Error failure) { menu.dispose(); throw failure; }
        menu.present(player);
    }

    private NPCShop() {
    }

    public NPCShop(String name) {
        this.name = name;
    }

    public boolean canEdit(NPC npc, CommandSourceStack sender) {
        if (PermissionUtil.hasPermission(sender, "citizens.admin")
                || PermissionUtil.hasPermission(sender, "citizens.npc.shop.edit")
                || PermissionUtil.hasPermission(sender, "citizens.npc.shop.edit." + getName()))
            return true;
        return npc != null && npc.getOrAddTrait(Owner.class).isOwnedBy(sender);
    }

    public boolean canView(ServerPlayer sender) {
        if (viewPermission != null && !PermissionUtil.hasPermission(sender, viewPermission))
            return false;
        String global = Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString();
        return global.isEmpty() || PermissionUtil.hasPermission(sender, global);
    }

    public void display(NPCShopStorage storage, ServerPlayer sender) {
        if (!isActive() || !storage.isActive()) return;
        if (!canView(sender))
            return;
        if (pages.isEmpty()) {
            Messaging.sendError(sender.createCommandSourceStack(), "Empty shop");
            return;
        }
        if (getShopType() == ShopType.TRADER) {
            NPCTraderShopViewer.open(this, storage, sender);
        } else {
            present(InventoryMenu.createSelfRegistered(new NPCShopViewer(this, storage, sender)), storage, sender);
        }
    }

    public void display(ServerPlayer sender) {
        display(storage, sender);
    }

    public void displayEditor(ShopTrait trait, ServerPlayer sender) {
        if (!isActive()) return;
        NPCShopStorage stock = trait == null ? storage : trait.getStorage();
        if (!stock.isActive()) return;
        present(InventoryMenu.createSelfRegistered(new NPCShopSettings(trait, this)), stock, sender);
    }

    public String getName() {
        return name == null ? "" : name;
    }

    /** The NPC this shop belongs to, for a per-NPC shop — those are named after the NPC's UUID. */
    public Optional<NPC> getNPC() {
        if (getName().contains("-")) {
            try {
                UUID uuid = UUID.fromString(name);
                return Optional.ofNullable(CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(uuid));
            } catch (IllegalArgumentException e) {
            }
        }
        return Optional.empty();
    }

    public NPCShopPage getOrCreatePage(int page) {
        while (pages.size() <= page) {
            pages.add(new NPCShopPage(pages.size()));
        }
        return pages.get(page);
    }

    public List<NPCShopPage> getPages() {
        return pages;
    }

    public String getRequiredPermission() {
        return viewPermission;
    }

    public ShopType getShopType() {
        return type == null ? type = ShopType.DEFAULT : type;
    }

    public NPCShopStorage getStorage() {
        return storage;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void removePage(int index) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).index == index) {
                pages.remove(i--);
                index = -1;
            } else if (index == -1) {
                pages.get(i).index--;
            }
        }
    }

    public void setPermission(String permission) {
        viewPermission = permission == null || permission.isEmpty() ? null : permission;
    }

    public void setShopType(ShopType type) {
        this.type = type;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
