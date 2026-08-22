package net.citizensnpcs.trait;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.shop.CommandAction;
import net.citizensnpcs.trait.shop.CommandAction.CommandActionGUI;
import net.citizensnpcs.trait.shop.ConditionAction;
import net.citizensnpcs.trait.shop.ConditionAction.ConditionActionGUI;
import net.citizensnpcs.trait.shop.ExperienceAction;
import net.citizensnpcs.trait.shop.ExperienceAction.ExperienceActionGUI;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.ItemAction.ItemActionGUI;
import net.citizensnpcs.trait.shop.MoneyAction;
import net.citizensnpcs.trait.shop.MoneyAction.MoneyActionGUI;
import net.citizensnpcs.trait.shop.NPCShop;
import net.citizensnpcs.trait.shop.NPCShopAction;
import net.citizensnpcs.trait.shop.NPCShopStorage;
import net.citizensnpcs.trait.shop.OpenShopAction;
import net.citizensnpcs.trait.shop.OpenShopAction.OpenShopActionGUI;
import net.citizensnpcs.trait.shop.PermissionAction;
import net.citizensnpcs.trait.shop.PermissionAction.PermissionActionGUI;
import net.citizensnpcs.trait.shop.StoredShops;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gives an NPC a shop.
 * <p>
 * The shop itself, its pages, its items and its editors all live in {@code trait.shop} — upstream nests every one of them
 * inside this file.
 */
@TraitName("shop")
public class ShopTrait extends Trait {
    @Persist
    private String rightClickShop;
    private StoredShops shops;
    @Persist(reify = true)
    private final NPCShopStorage storage = new NPCShopStorage();

    public ShopTrait() {
        super("shop");
    }

    public ShopTrait(StoredShops shops) {
        this();
        this.shops = shops;
    }

    /** The NPC's own shop, named after its UUID, created on demand. */
    public NPCShop getDefaultShop() {
        return shops.npcShops.computeIfAbsent(npc.getUniqueId().toString(), NPCShop::new);
    }

    /** A named global shop, created on demand. */
    public NPCShop getShop(String name) {
        return shops.globalShops.computeIfAbsent(name, NPCShop::new);
    }

    public String getRightClickShop() {
        return rightClickShop;
    }

    public NPCShopStorage getStorage() {
        return storage;
    }

    @Override
    public void onRemove() {
        shops.deleteShop(getDefaultShop());
    }

    @TraitEventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (rightClickShop == null || rightClickShop.isEmpty())
            return;
        ServerPlayer player = event.getClicker();
        event.setDelayedCancellation(true);

        String globalViewPermission = Setting.SHOP_GLOBAL_VIEW_PERMISSION.asString();
        if (!globalViewPermission.isEmpty() && !PermissionUtil.hasPermission(player, globalViewPermission))
            return;

        NPCShop shop = shops.globalShops.getOrDefault(rightClickShop, getDefaultShop());
        shop.display(storage, player);
    }

    public void setDefaultShop(NPCShop shop) {
        shops.npcShops.put(npc.getUniqueId().toString(), shop);
    }

    public void setRightClickShop(String name) {
        rightClickShop = name;
    }

    public void setShops(StoredShops shops) {
        this.shops = shops;
    }

    static {
        NPCShopAction.register(ItemAction.class, "items", new ItemActionGUI());
        NPCShopAction.register(PermissionAction.class, "permissions", new PermissionActionGUI());
        NPCShopAction.register(MoneyAction.class, "money", new MoneyActionGUI());
        NPCShopAction.register(CommandAction.class, "command", new CommandActionGUI());
        NPCShopAction.register(ExperienceAction.class, "experience", new ExperienceActionGUI());
        NPCShopAction.register(OpenShopAction.class, "open_shop", new OpenShopActionGUI());
        NPCShopAction.register(ConditionAction.class, "condition", new ConditionActionGUI());
    }
}
