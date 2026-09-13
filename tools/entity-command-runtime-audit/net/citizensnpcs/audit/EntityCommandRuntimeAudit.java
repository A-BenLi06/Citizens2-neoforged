package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.HorseModifiers;
import net.citizensnpcs.trait.SlimeSize;
import net.citizensnpcs.trait.VillagerProfession;
import net.citizensnpcs.trait.versioned.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class EntityCommandRuntimeAudit {
    private static State state;
    private static boolean forced, finished;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) {
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) level.setChunkForced(x, z, true);
            forced = true;
        }
        try {
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(x, z)) || !level.isPositionEntityTicking(new BlockPos(x * 16, -60, z * 16))) {
                    if (server.getTickCount() > 1500) throw new AssertionError("Fixture loading timed out");
                    return;
                }
            }
            if (state == null) { state = new State(server); state.run(); return; }
            if (!state.delayed()) return;
            LoggerFactory.getLogger("citizens").info("[ENTITYCOMMANDAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[ENTITYCOMMANDAUDIT] FAILED", failure);
        }
        finished = true;
        try { if (state != null) state.close(); } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[ENTITYCOMMANDAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    private record Case(EntityType<?> type, String command, Predicate<NPC> stored, Predicate<Entity> live) { }
    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final NPCRegistry registry = CitizensAPI.getNPCRegistry();
        final List<PermissionUtil.Attachment> permissions = new ArrayList<>();
        final List<NPC> originals = new ArrayList<>();
        Actor actor;
        CommandSourceStack source, restricted;
        boolean isolated;
        NPC warden;
        int poseStart, poseStage;

        State(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void run() throws Exception {
            if (!Files.exists(Path.of("entity-command-audit-fixture.txt")) || registry.iterator().hasNext())
                throw new AssertionError("Entity command audit needs an empty isolated fixture");
            isolated = true;
            actor = player(); source = actor.createCommandSourceStack().withPermission(4);
            restricted = actor.createCommandSourceStack().withPermission(0);
            List<Case> cases = cases(); int index = 0;
            for (Case test : cases) {
                NPC npc = npc(test.type, test.command.split(" ")[0]); originals.add(npc);
                select(source, npc);
                ok(source, "npc " + test.command);
                check(test.stored.test(npc), "stored_" + test.command.split(" ")[0]);
                npc.getOrAddTrait(Spawned.class).setSpawned(false);
                NPC copy = npc.copy();
                check(test.stored.test(copy), "copy_" + test.command.split(" ")[0]); copy.destroy();
                double x = 3 + (index % 6) * 10, z = 3 + (index / 6) * 10; index++;
                check(npc.spawn(new Location(level, x, -60, z)), "spawn_" + test.command.split(" ")[0]);
                ((net.citizensnpcs.api.npc.AbstractNPC) npc).update();
                check(test.live.test(npc.getEntity()), "live_" + test.command.split(" ")[0]);
                npc.despawn(DespawnReason.PLUGIN); npc.spawn(new Location(level, x, -60, z)); ((net.citizensnpcs.api.npc.AbstractNPC) npc).update();
                check(test.live.test(npc.getEntity()), "respawn_" + test.command.split(" ")[0]);
            }
            aliasesAndValidation();
            customRegistries();
            unresolvedValues();
            warden = npc(EntityType.WARDEN, "Warden");
            warden.spawn(new Location(level, 3, -60, 53)); select(source, warden);
            ok(source, "npc warden anger " + actor.getUUID() + " 100"); ((net.citizensnpcs.api.npc.AbstractNPC) warden).update();
            check(((Warden) warden.getEntity()).getAngerManagement().getActiveAnger(actor) == 100, "warden_anger_uuid_target");
            ok(source, "npc warden anger " + actor.getGameProfile().getName() + " 70"); ((net.citizensnpcs.api.npc.AbstractNPC) warden).update();
            check(((Warden) warden.getEntity()).getAngerManagement().getActiveAnger(actor) == 70, "warden_anger_name_target");
            bad(source, "npc warden anger missing-player 50");
            bad(source, "npc warden anger " + actor.getGameProfile().getName() + " -1");
            check(true, "warden_invalid_targets_and_anger_fail");
            ok(source, "npc warden emerge"); poseStart = server.getTickCount();
            check(warden.getEntity().getPose() == Pose.EMERGING, "warden_emerge_pose_applied");
        }

        List<Case> cases() {
            return List.of(
                new Case(EntityType.ALLAY, "allay -d", n -> n.getOrAddTrait(AllayTrait.class).isDancing(), e -> ((Allay)e).isDancing()),
                new Case(EntityType.ARMADILLO, "armadillo --state ROLLING_UP", n -> n.getOrAddTrait(ArmadilloTrait.class).getState() == Armadillo.ArmadilloState.ROLLING, e -> ((Armadillo)e).getState() == Armadillo.ArmadilloState.ROLLING),
                new Case(EntityType.AXOLOTL, "axolotl --variant BLUE -d", n -> n.getOrAddTrait(AxolotlTrait.class).getVariant() == Axolotl.Variant.BLUE && n.getOrAddTrait(AxolotlTrait.class).isPlayingDead(), e -> ((Axolotl)e).getVariant() == Axolotl.Variant.BLUE && ((Axolotl)e).isPlayingDead()),
                new Case(EntityType.BEE, "bee --anger 80 -sn", n -> n.getOrAddTrait(BeeTrait.class).getAnger() == 80 && n.getOrAddTrait(BeeTrait.class).hasNectar(), e -> ((Bee)e).hasNectar() && ((Bee)e).hasStung() && ((Bee)e).getRemainingPersistentAngerTime() == 80),
                new Case(EntityType.CAMEL, "camel --pose SITTING", n -> n.getOrAddTrait(CamelTrait.class).getPose() == CamelTrait.CamelPose.SITTING, e -> ((Camel)e).isCamelSitting()),
                new Case(EntityType.CAT, "cat --type siamese --ccolor RED -sl", n -> n.getOrAddTrait(CatTrait.class).isSitting() && n.getOrAddTrait(CatTrait.class).isLyingDown(), e -> ((Cat)e).getVariant().is(CatVariant.SIAMESE) && ((Cat)e).getCollarColor() == DyeColor.RED && ((Cat)e).isInSittingPose() && ((Cat)e).isLying()),
                new Case(EntityType.FOX, "fox --type SNOW --sleeping true --crouching true --interested true --pouncing false --faceplanted false", n -> n.getOrAddTrait(FoxTrait.class).getType() == Fox.Type.SNOW && n.getOrAddTrait(FoxTrait.class).isSleeping(), e -> ((Fox)e).getVariant() == Fox.Type.SNOW && ((Fox)e).isSleeping() && ((Fox)e).isCrouching()),
                new Case(EntityType.FROG, "frog --variant COLD", n -> n.getOrAddTrait(FrogTrait.class).getVariant().is(FrogVariant.COLD), e -> ((Frog)e).getVariant().is(FrogVariant.COLD)),
                new Case(EntityType.GOAT, "goat -n", n -> !n.getOrAddTrait(GoatTrait.class).isLeftHorn() && !n.getOrAddTrait(GoatTrait.class).isRightHorn(), e -> !((Goat)e).hasLeftHorn() && !((Goat)e).hasRightHorn()),
                new Case(EntityType.LLAMA, "llama --colour GRAY --strength 8 -c", n -> n.getOrAddTrait(LlamaTrait.class).getColor() == Llama.Variant.GRAY && n.getOrAddTrait(LlamaTrait.class).getStrength() == 5, e -> ((Llama)e).getVariant() == Llama.Variant.GRAY && ((Llama)e).getStrength() == 5 && ((Llama)e).hasChest()),
                new Case(EntityType.MOOSHROOM, "mushroomcow --variant BROWN", n -> n.getOrAddTrait(MushroomCowTrait.class).getVariant() == MushroomCow.MushroomType.BROWN, e -> ((MushroomCow)e).getVariant() == MushroomCow.MushroomType.BROWN),
                new Case(EntityType.PANDA, "panda --gene WEAK --hiddengene LAZY -rne", n -> n.getOrAddTrait(PandaTrait.class).getMainGene() == Panda.Gene.WEAK && n.getOrAddTrait(PandaTrait.class).isRolling(), e -> ((Panda)e).getMainGene() == Panda.Gene.WEAK && ((Panda)e).getHiddenGene() == Panda.Gene.LAZY && ((Panda)e).isRolling() && !((Panda)e).isOnBack()),
                new Case(EntityType.PARROT, "parrot --variant BLUE", n -> n.getOrAddTrait(ParrotTrait.class).getVariant() == Parrot.Variant.BLUE, e -> ((Parrot)e).getVariant() == Parrot.Variant.BLUE),
                new Case(EntityType.POLAR_BEAR, "polarbear -r", n -> n.getOrAddTrait(PolarBearTrait.class).isRearing(), e -> ((PolarBear)e).isStanding()),
                new Case(EntityType.PUFFERFISH, "pufferfish --state 5", n -> n.getOrAddTrait(PufferFishTrait.class).getPuffState() == 2, e -> ((Pufferfish)e).getPuffState() == 2),
                new Case(EntityType.SNIFFER, "sniffer --state SCENTING", n -> n.getOrAddTrait(SnifferTrait.class).getState() == Sniffer.State.SCENTING, e -> ((Sniffer)e).getState() == Sniffer.State.SCENTING),
                new Case(EntityType.SNOW_GOLEM, "snowman -df", n -> n.getOrAddTrait(SnowmanTrait.class).isDerp() && n.getOrAddTrait(SnowmanTrait.class).shouldFormSnow(), e -> !((SnowGolem)e).hasPumpkin()),
                new Case(EntityType.TROPICAL_FISH, "tropicalfish --body RED --patterncolor YELLOW --pattern BETTY", n -> n.getOrAddTrait(TropicalFishTrait.class).getPattern() == TropicalFish.Pattern.BETTY, e -> ((TropicalFish)e).getBaseColor() == DyeColor.RED && ((TropicalFish)e).getPatternColor() == DyeColor.YELLOW && ((TropicalFish)e).getVariant() == TropicalFish.Pattern.BETTY),
                new Case(EntityType.VILLAGER, "villager --type SNOW --profession LIBRARIAN --level 5", n -> n.getOrAddTrait(VillagerTrait.class).getType() == VillagerType.SNOW && n.getOrAddTrait(VillagerTrait.class).getLevel() == 5, e -> ((Villager)e).getVillagerData().getType() == VillagerType.SNOW && ((Villager)e).getVillagerData().getLevel() == 5 && ((Villager)e).getVillagerData().getProfession() == net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN),
                new Case(EntityType.AREA_EFFECT_CLOUD, "areaeffectcloud --radius 2.5 --radius_per_tick -0.001 --duration 2400 --color 17,34,51 --potiontype poison --particle flame", n -> n.getOrAddTrait(AreaEffectCloudTrait.class).getColor() == 0xFF112233 && n.getOrAddTrait(AreaEffectCloudTrait.class).getDuration() == 2400, EntityCommandRuntimeAudit::cloudMatches),
                new Case(EntityType.BOAT, "boat --type BIRCH", n -> n.getOrAddTrait(BoatTrait.class).getType() == Boat.Type.BIRCH, e -> ((Boat)e).getVariant() == Boat.Type.BIRCH),
                new Case(EntityType.ENDER_DRAGON, "enderdragon --phase HOVER --destroywalls false", n -> n.getOrAddTrait(EnderDragonTrait.class).getPhase() == EnderDragonTrait.DragonPhase.HOVER && !n.getOrAddTrait(EnderDragonTrait.class).isDestroyWalls(), e -> ((EnderDragon)e).getPhaseManager().getCurrentPhase().getPhase() == EnderDragonTrait.DragonPhase.HOVER.vanilla()),
                new Case(EntityType.PHANTOM, "phantom --size 7", n -> n.getOrAddTrait(PhantomTrait.class).getSize() == 7, e -> ((Phantom)e).getPhantomSize() == 7),
                new Case(EntityType.PIGLIN, "piglin --dancing true", n -> n.getOrAddTrait(PiglinTrait.class).isDancing(), e -> ((Piglin)e).isDancing()),
                new Case(EntityType.SHULKER, "shulker --peek 75 --color CYAN", n -> n.getOrAddTrait(ShulkerTrait.class).getPeek() == 75 && n.getOrAddTrait(ShulkerTrait.class).getColor() == DyeColor.CYAN, e -> e.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getByte("Peek") == 75 && ((Shulker)e).getVariant().orElse(null) == DyeColor.CYAN),
                new Case(EntityType.EVOKER, "spellcaster --spell FANGS", n -> n.getOrAddTrait(SpellcasterTrait.class).getSpell() == SpellcasterIllager.IllagerSpell.FANGS, e -> currentSpell(e) == SpellcasterIllager.IllagerSpell.FANGS),
                new Case(EntityType.VEX, "vex --charging true", n -> Boolean.TRUE.equals(n.getOrAddTrait(VexTrait.class).isCharging()), e -> ((Vex)e).isCharging())
            );
        }

        void aliasesAndValidation() throws Exception {
            NPC mushroom = originals.stream().filter(n -> n.getEntity() instanceof MushroomCow).findFirst().orElseThrow(); select(source, mushroom);
            ok(source, "npc mooshroom --variant RED"); ((net.citizensnpcs.api.npc.AbstractNPC) mushroom).update(); check(((MushroomCow)mushroom.getEntity()).getVariant() == MushroomCow.MushroomType.RED, "mooshroom_alias");
            NPC snowman = originals.stream().filter(n -> n.getEntity() instanceof SnowGolem).findFirst().orElseThrow(); select(source, snowman);
            ok(source, "npc snowgolem -d"); ((net.citizensnpcs.api.npc.AbstractNPC) snowman).update(); check(((SnowGolem)snowman.getEntity()).hasPumpkin(), "snowgolem_alias");
            NPC trader = npc(EntityType.TRADER_LLAMA, "Trader"); select(source, trader); ok(source, "npc llama --color WHITE --strength 3");
            check(trader.getOrAddTrait(LlamaTrait.class).getStrength() == 3, "trader_llama_supported");
            NPC chestBoat = npc(EntityType.CHEST_BOAT, "ChestBoat"); select(source, chestBoat); ok(source, "npc boat --type SPRUCE");
            check(chestBoat.getOrAddTrait(BoatTrait.class).getType() == Boat.Type.SPRUCE, "chest_boat_supported");
            NPC illusioner = npc(EntityType.ILLUSIONER, "Illusioner"); select(source, illusioner); ok(source, "npc spellcaster --spell BLINDNESS");
            check(illusioner.getOrAddTrait(SpellcasterTrait.class).getSpell() == SpellcasterIllager.IllagerSpell.BLINDNESS, "illusioner_supported");
            NPC fresh = npc(EntityType.CAT, "FreshCat"); select(source, fresh);
            for (String command : List.of("npc cat", "npc cat --type unknown", "npc cat --type white --ccolor invalid", "npc cat --bogus true", "npc bee -n")) {
                bad(source, command); check(!fresh.hasTrait(CatTrait.class) && !fresh.hasTrait(BeeTrait.class), "reject_without_trait_mutation_" + command.replace(' ', '_'));
            }
            NPC fox = npc(EntityType.FOX, "FreshFox"); select(source, fox); bad(source, "npc fox --sleeping banana");
            check(!fox.hasTrait(FoxTrait.class), "invalid_boolean_does_not_mutate");
            NPC cloud = npc(EntityType.AREA_EFFECT_CLOUD, "FreshCloud"); select(source, cloud);
            for (String command : List.of("npc areaeffectcloud --radius NaN", "npc areaeffectcloud --duration broken", "npc areaeffectcloud --radius 2 --color 300,0,0", "npc areaeffectcloud --particle missing", "npc areaeffectcloud --radius 2 --potiontype missing")) bad(source, command);
            check(!cloud.hasTrait(AreaEffectCloudTrait.class), "invalid_cloud_flags_are_atomic");
            NPC freshWarden = npc(EntityType.WARDEN, "FreshWarden"); select(source, freshWarden);
            for (String command : List.of("npc warden unknown", "npc warden anger missing-player 50", "npc warden anger missing-player -1", "npc warden emerge")) bad(source, command);
            check(!freshWarden.hasTrait(WardenTrait.class), "invalid_warden_commands_do_not_attach_trait");
            select(restricted, fresh); bad(restricted, "npc cat --type black");
            permissions.add(PermissionUtil.grantTemporary(actor, List.of("citizens.npc.cat")));
            ok(restricted, "npc cat --type black"); check(fresh.hasTrait(CatTrait.class), "non_operator_command_permission_grant");
            fresh.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID()); bad(restricted, "npc cat --type white");
            check(fresh.getOrAddTrait(CatTrait.class).getType().is(CatVariant.BLACK), "ownership_denial_preserves_variant");
            var suggestions = server.getCommands().getDispatcher().getCompletionSuggestions(
                    server.getCommands().getDispatcher().parse("npc llama --colo", source)).get().getList();
            check(suggestions.stream().anyMatch(s -> s.getText().contains("colour")) && suggestions.stream().anyMatch(s -> s.getText().contains("color")), "flag_aliases_are_suggested");
            NPC slime = npc(EntityType.SLIME, "Slime"); slime.spawn(new Location(level, 63, -60, 3));
            slime.getOrAddTrait(SlimeSize.class).setSize(6);
            check(((Slime)slime.getEntity()).getSize() == 6, "live_slime_size_update");
        }

        void customRegistries() throws Exception {
            for (String[] spec : List.of(new String[] {"cat", "cat --type", "cattrait.type", "cat"},
                    new String[] {"frog", "frog --variant", "frogtrait.variant", "frog"},
                    new String[] {"villager", "villager --type", "villagertrait.type", "villager"},
                    new String[] {"villager", "villager --profession", "profession", "profession"},
                    new String[] {"area_effect_cloud", "areaeffectcloud --potiontype", "areaeffectcloudtrait.type", "potion"},
                    new String[] {"area_effect_cloud", "areaeffectcloud --particle", "areaeffectcloudtrait.particle", "particle"})) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(spec[0]));
                NPC npc = npc(type, "Custom" + spec[3]); select(source, npc);
                String id = EntityCommandRegistryRuntimeAudit.id(spec[3]).toString(); ok(source, "npc " + spec[1] + " " + id);
                var data = new MemoryDataKey(); npc.saveSnapshot(data);
                check(data.getString("traits." + spec[2]).equals(id), "custom_registry_id_saved_" + spec[3]);
                NPC copy = npc.copy(); var copied = new MemoryDataKey(); copy.saveSnapshot(copied);
                check(copied.getString("traits." + spec[2]).equals(id), "custom_registry_id_copied_" + spec[3]); copy.destroy();
                check(npc.spawn(new Location(level, 7, -60, 59)), "custom_registry_spawn_" + spec[3]);
                ((net.citizensnpcs.api.npc.AbstractNPC) npc).update();
                Entity entity = npc.getCosmeticEntity();
                String liveId = switch (spec[3]) {
                    case "cat" -> ((Cat) entity).getVariant().unwrapKey().orElseThrow().location().toString();
                    case "frog" -> ((Frog) entity).getVariant().unwrapKey().orElseThrow().location().toString();
                    case "villager" -> BuiltInRegistries.VILLAGER_TYPE.getKey(((Villager) entity).getVillagerData().getType()).toString();
                    case "profession" -> BuiltInRegistries.VILLAGER_PROFESSION.getKey(((Villager) entity).getVillagerData().getProfession()).toString();
                    case "potion" -> entity.saveWithoutId(new CompoundTag()).getCompound("potion_contents").getString("potion");
                    case "particle" -> BuiltInRegistries.PARTICLE_TYPE.getKey(((net.minecraft.world.entity.AreaEffectCloud) entity).getParticle().getType()).toString();
                    default -> throw new AssertionError(spec[3]);
                };
                check(liveId.equals(id), "custom_registry_applied_live_" + spec[3]);
                npc.despawn();
            }
        }

        void unresolvedValues() throws Exception {
            NPC cat = npc(EntityType.CAT, "UnresolvedCat"); var catData = new MemoryDataKey(); catData.setString("type", "absent:cat");
            var catTrait = cat.getOrAddTrait(CatTrait.class); catTrait.load(catData); var saved = new MemoryDataKey(); catTrait.save(saved);
            check(saved.getString("type").equals("absent:cat"), "missing_cat_provider_identity_retained");
            select(source, cat); ok(source, "npc cat --type white"); catTrait.save(saved); check(saved.getString("type").equals("minecraft:white"), "explicit_cat_change_replaces_unresolved_identity");
            NPC frog = npc(EntityType.FROG, "UnresolvedFrog"); var frogData = new MemoryDataKey(); frogData.setString("variant", "absent:frog");
            var frogTrait = frog.getOrAddTrait(FrogTrait.class); frogTrait.load(frogData); frogTrait.save(saved);
            check(saved.getString("variant").equals("absent:frog"), "missing_frog_provider_identity_retained");
            NPC villager = npc(EntityType.VILLAGER, "UnresolvedVillager"); var type = new MemoryDataKey(); type.setString("type", "absent:villager");
            var villagerTrait = villager.getOrAddTrait(VillagerTrait.class); villagerTrait.load(type); villagerTrait.save(saved);
            check(saved.getString("type").equals("absent:villager"), "missing_villager_provider_identity_retained");
            var profession = new MemoryDataKey().getRelative("profession"); profession.setString("", "absent:profession");
            var professionTrait = villager.getOrAddTrait(VillagerProfession.class); professionTrait.load(profession); var profSaved = new MemoryDataKey().getRelative("profession"); professionTrait.save(profSaved);
            check(profSaved.getString("").equals("absent:profession"), "missing_profession_identity_retained");
            NPC cloud = npc(EntityType.AREA_EFFECT_CLOUD, "UnresolvedCloud"); var raw = new MemoryDataKey(); raw.setString("particle", "absent:particle"); raw.setString("type", "absent:potion");
            var cloudTrait = cloud.getOrAddTrait(AreaEffectCloudTrait.class); cloudTrait.load(raw); cloudTrait.save(saved);
            check(saved.getString("particle").equals("absent:particle") && saved.getString("type").equals("absent:potion"), "missing_cloud_provider_identities_retained");
        }

        boolean delayed() throws Exception {
            if (poseStage == 0 && server.getTickCount() - poseStart >= 136) {
                check(warden.getEntity().getPose() == Pose.STANDING, "warden_emerge_resets_after_duration");
                select(source, warden); ok(source, "npc warden roar"); poseStart = server.getTickCount(); poseStage = 1;
                check(warden.getEntity().getPose() == Pose.ROARING, "warden_roar_pose_applied");
            } else if (poseStage == 1 && server.getTickCount() - poseStart >= 86) {
                check(warden.getEntity().getPose() == Pose.STANDING, "warden_roar_resets_after_duration");
                ok(source, "npc warden dig"); check(warden.getEntity().getPose() == Pose.DIGGING, "warden_dig_pose_applied"); return true;
            }
            return false;
        }
        NPC npc(EntityType<?> type, String name) {
            NPC npc = registry.createNPC(type, name); npc.getOrAddTrait(Owner.class).setOwner(actor.getUUID());
            npc.getOrAddTrait(Spawned.class).setSpawned(false);
            if (npc.getCosmeticEntityType() != type) throw new AssertionError("Creation type changed: expected "
                    + EntityType.getKey(type) + ", stored " + EntityType.getKey(npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.MobType.class).getType())
                    + ", cosmetic " + EntityType.getKey(npc.getCosmeticEntityType()));
            return npc;
        }
        void select(CommandSourceStack sender, NPC npc) {
            CitizensAPI.getDefaultNPCSelector().select(sender, npc);
            if (CitizensAPI.getDefaultNPCSelector().getSelected(sender) != npc) throw new AssertionError("Selector resolved a different NPC");
        }
        void ok(CommandSourceStack sender, String command) throws Exception {
            try { if (server.getCommands().getDispatcher().execute(command, sender) <= 0) throw new AssertionError("Command failed: " + command); }
            catch (CommandSyntaxException failure) { throw new AssertionError("Command failed: " + command, failure); }
        }
        void bad(CommandSourceStack sender, String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, sender); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Invalid command succeeded: " + command);
        }
        Actor player() {
            var player = new Actor(server, level); player.setPos(1, -60, 1);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }
        void close() {
            permissions.forEach(PermissionUtil.Attachment::remove);
            if (isolated) registry.deregisterAll();
            if (actor != null) { server.getPlayerList().remove(actor); actor.channel.finishAndReleaseAll(); }
        }
    }
    private static class Actor extends ServerPlayer {
        EmbeddedChannel channel;
        Actor(MinecraftServer server, ServerLevel level) { super(server, level, new GameProfile(UUID.randomUUID(), "EntityAudit"), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { }
    }
    private static boolean cloudMatches(Entity entity) {
        var cloud = (net.minecraft.world.entity.AreaEffectCloud) entity;
        var contents = cloud.saveWithoutId(new CompoundTag()).getCompound("potion_contents");
        return cloud.getRadius() == 2.5F && cloud.getRadiusPerTick() == -0.001F && cloud.getDuration() == 2400
                && BuiltInRegistries.PARTICLE_TYPE.getKey(cloud.getParticle().getType()).toString().equals("minecraft:flame")
                && contents.getString("potion").equals("minecraft:poison") && contents.getInt("custom_color") == 0x112233;
    }
    private static SpellcasterIllager.IllagerSpell currentSpell(Entity entity) {
        try {
            var method = SpellcasterIllager.class.getDeclaredMethod("getCurrentSpell"); method.setAccessible(true);
            return (SpellcasterIllager.IllagerSpell) method.invoke(entity);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[ENTITYCOMMANDAUDIT] PASS {}", name); }
}
