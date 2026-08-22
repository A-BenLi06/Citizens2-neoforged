package net.citizensnpcs.api.command;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.RequirementMissingException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.EntityType;

/** Enforces {@link Requirements} before a command body runs. */
public class RequirementsProcessor implements CommandAnnotationProcessor {
    @Override
    public Class<? extends Annotation> getAnnotationClass() {
        return Requirements.class;
    }

    @Override
    public void process(CommandSourceStack sender, CommandContext context, Annotation instance, Object[] methodArgs)
            throws CommandException {
        Requirements requirements = (Requirements) instance;
        NPC npc = methodArgs.length >= 3 && methodArgs[2] instanceof NPC ? (NPC) methodArgs[2] : null;

        // --id and --uuid let a command act on an NPC other than the selected one, for anybody allowed to select
        boolean canRedefineSelected = (context.hasValueFlag("uuid") || context.hasValueFlag("id"))
                && PermissionUtil.hasPermission(sender, "npc.select");
        String error = Messaging.tr(CommandMessages.MUST_HAVE_SELECTED);
        if (canRedefineSelected) {
            if (context.hasValueFlag("uuid")) {
                npc = CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(UUID.fromString(context.getFlag("uuid")));
            } else {
                npc = CitizensAPI.getNPCRegistry().getById(context.getFlagInteger("id"));
            }
            if (methodArgs.length >= 3) {
                methodArgs[2] = npc;
            }
            if (npc == null) {
                error += ' '
                        + Messaging.tr(CommandMessages.ID_NOT_FOUND, context.getFlag("id", context.getFlag("uuid")));
            }
        }
        if (requirements.selected() && npc == null)
            throw new RequirementMissingException(error);

        if (requirements.ownership() && npc != null && !PermissionUtil.hasPermission(sender, "citizens.admin")
                && !npc.getOrAddTrait(Owner.class).isOwnedBy(sender))
            throw new RequirementMissingException(Messaging.tr(CommandMessages.MUST_BE_OWNER));

        if (npc == null)
            return;

        for (Class<? extends Trait> clazz : requirements.traits()) {
            if (!npc.hasTrait(clazz))
                throw new RequirementMissingException(
                        Messaging.tr(CommandMessages.MISSING_TRAIT, clazz.getSimpleName()));
        }
        Set<EntityType<?>> excluded = resolve(requirements.excludedTypes());
        EntityType<?> cosmetic = npc.getCosmeticEntityType();
        if (!allows(requirements.cosmeticTypes(), excluded, cosmetic))
            throw new RequirementMissingException(
                    Messaging.tr(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, describe(cosmetic)));

        EntityType<?> type = npc.getOrAddTrait(MobType.class).getType();
        if (!allows(requirements.types(), excluded, type))
            throw new RequirementMissingException(
                    Messaging.tr(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, describe(type)));

        if (requirements.livingEntity() && !EntityUtil.isLivingType(type, sender.getLevel()))
            throw new RequirementMissingException(
                    Messaging.tr(CommandMessages.REQUIREMENTS_MUST_BE_LIVING_ENTITY, describe(type)));
    }

    /**
     * @param allowed
     *            the declared list; empty means any type
     */
    private static boolean allows(String[] allowed, Set<EntityType<?>> excluded, EntityType<?> type) {
        if (type == null || excluded.contains(type))
            return false;
        if (allowed.length == 0)
            return true;
        return resolve(allowed).contains(type);
    }

    /** Names that match no entity type are dropped rather than failing the whole check. */
    private static Set<EntityType<?>> resolve(String[] names) {
        Set<EntityType<?>> resolved = new HashSet<>();
        for (String name : names) {
            EntityType<?> type = MobType.match(name);
            if (type != null) {
                resolved.add(type);
            }
        }
        return resolved;
    }

    private static String describe(EntityType<?> type) {
        return type == null ? "unknown"
                : EntityType.getKey(type).getPath().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
