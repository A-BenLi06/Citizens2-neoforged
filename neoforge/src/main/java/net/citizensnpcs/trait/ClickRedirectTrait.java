package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
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

    public ClickRedirectTrait() {
        super("clickredirecttrait");
    }

    public ClickRedirectTrait(NPC redirectTo) {
        this();
        this.redirectTo = redirectTo;
    }

    public NPC getRedirectToNPC() {
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
        redirectTo = uuid.isEmpty() ? null : CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(UUID.fromString(uuid));
    }

    @Override
    public void onRemove() {
        if (redirectTo != null && redirectTo.hasTrait(PlayerFilter.class))
            redirectTo.getTrait(PlayerFilter.class).removeChildNPC(npc);
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
        if (redirectTo == null)
            return;
        key.setString("uuid", redirectTo.getUniqueId().toString());
    }
}
