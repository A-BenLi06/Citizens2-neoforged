package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.api.event.NPCAddTraitEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.DataKey;

/**
 * Marks this NPC's clicks as belonging to another {@link NPC}.
 * <p>
 * Hologram lines are themselves NPCs sitting on top of the real one, so a player aiming at a nameplate would otherwise
 * click the hologram and nothing would happen. Carrying the redirect on the helper NPC lets
 * {@code EventListen} hand the click to the NPC the player meant.
 * <p>
 * The trait also keeps the helper in the parent's {@link PlayerFilter} child list, so hiding an NPC from a player hides
 * its holograms too.
 */
@TraitName("clickredirecttrait")
public class ClickRedirectTrait extends Trait {
    private NPC redirectTo;
    private UUID savedRedirect;

    public ClickRedirectTrait() {
        super("clickredirecttrait");
    }

    public ClickRedirectTrait(NPC redirectTo) {
        this();
        this.redirectTo = redirectTo;
    }

    public NPC getRedirectToNPC() {
        // A loaded reference can precede its target or belong to a provisional registry. Resolve against its owner
        // when used, so it cannot retain the old default registry's NPC after replacement.
        if (savedRedirect != null && npc != null && npc.getOwningRegistry() != null) {
            replaceRedirect(npc.getOwningRegistry().getByUniqueIdGlobal(savedRedirect));
        }
        return redirectTo;
    }

    @Override
    public void linkToNPC(NPC npc) {
        super.linkToNPC(npc);
        if (redirectTo != null && redirectTo.hasTrait(PlayerFilter.class)) {
            redirectTo.getOrAddTrait(PlayerFilter.class).addChildNPC(npc);
        }
    }

    @Override
    public void load(DataKey key) {
        String uuid = key.getString("uuid", "");
        UUID loaded = uuid.isEmpty() ? null : UUID.fromString(uuid);
        replaceRedirect(null);
        savedRedirect = loaded;
    }

    @Override
    public void onRemove() {
        replaceRedirect(null);
        savedRedirect = null;
    }

    @Override public void onSpawn() { getRedirectToNPC(); }
    @Override public void run() { if (savedRedirect != null) getRedirectToNPC(); }

    private void replaceRedirect(NPC target) {
        if (redirectTo == target) return;
        if (npc != null && redirectTo != null && redirectTo.hasTrait(PlayerFilter.class))
            redirectTo.getTraitNullable(PlayerFilter.class).removeChildNPC(npc);
        redirectTo = target;
        if (npc != null && redirectTo != null && redirectTo.hasTrait(PlayerFilter.class))
            redirectTo.getTraitNullable(PlayerFilter.class).addChildNPC(npc);
    }

    /** The parent may gain a {@link PlayerFilter} after this trait was attached; pick it up when it does. */
    @TraitEventHandler
    private void onTraitAdd(NPCAddTraitEvent event) {
        if (event.getNPC() == redirectTo && event.getTrait() instanceof PlayerFilter filter) {
            filter.addChildNPC(npc);
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("uuid");
        UUID target = savedRedirect != null ? savedRedirect : redirectTo == null ? null : redirectTo.getUniqueId();
        if (target != null) key.setString("uuid", target.toString());
    }
}
