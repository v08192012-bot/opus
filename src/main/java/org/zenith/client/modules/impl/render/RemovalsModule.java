package org.zenith.client.modules.impl.render;

import com.darkmagician6.eventapi.EventTarget;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.zenith.ModuleCategory;
import org.zenith.ModuleInfo;
import org.zenith.base.Module;
import org.zenith.client.events.UpdateEvent;
import org.zenith.client.settings.BooleanSetting;
import org.zenith.client.settings.ModeSetting;
import org.zenith.client.settings.MultiSelectSetting;
import org.zenith.client.settings.SliderSetting;
import org.zenith.utility.misc.ModuleHookDefinitions;

/**
 * Removals — убирает визуальный шум: огонь, накладку в блоке, туман от эффектов, bossbar,
 * свечение игроков, частицы, звуки, растительность и скалк, а также позволяет прятать
 * сущности (предметы/игроков) по условию.
 */
@ModuleInfo(
   name = "Removals",
   description = "module.removals.description",
   category = ModuleCategory.RENDER
)
public final class RemovalsModule extends Module {
   public static final RemovalsModule INSTANCE = new RemovalsModule();

   private static final Set<Block> SCULK_BLOCKS = Set.of(
      Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST, Blocks.SCULK_SHRIEKER
   );
   private static final Set<Block> SCULK_SENSOR_BLOCKS = Set.of(Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR);
   private static final Set<ParticleType<?>> SCULK_PARTICLES = Set.of(
      ParticleTypes.VIBRATION,
      ParticleTypes.SCULK_CHARGE,
      ParticleTypes.SCULK_CHARGE_POP,
      ParticleTypes.SCULK_SOUL,
      ParticleTypes.SHRIEK
   );

   private static final String[] VEGETATION = new String[]{
      "short_grass", "tall_grass", "fern", "large_fern", "short_dry_grass", "tall_dry_grass", "dead_bush", "bush",
      "firefly_bush", "seagrass", "tall_seagrass", "crimson_roots", "warped_roots", "nether_sprouts", "dandelion",
      "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
      "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
      "torchflower", "pitcher_plant", "open_eyeblossom", "closed_eyeblossom", "pink_petals", "wildflowers",
      "spore_blossom"
   };

   private static volatile Set<Block> hiddenBlocks = Set.of();
   private static volatile String hiddenSignature = "";

   private final MultiSelectSetting elements = new MultiSelectSetting(
      "module.removals.elements",
      "module.removals.elements.desc",
      MultiSelectSetting.Entry.method5231("module.removals.element.fire", true),
      MultiSelectSetting.Entry.method5231("module.removals.element.badEffects", true),
      MultiSelectSetting.Entry.method5231("module.removals.element.bossbar", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.playerGlow", true),
      MultiSelectSetting.Entry.method5231("module.removals.element.sculk", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.sculkSensors", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.sculkParticles", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.vegetation", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.blockParticles", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.explosionParticles", false),
      MultiSelectSetting.Entry.method5231("module.removals.element.removeSounds", false)
   );

   private final MultiSelectSetting soundCheck = new MultiSelectSetting(
      "module.removals.soundCheck",
      "module.removals.soundCheck.desc",
      MultiSelectSetting.Entry.method5231("module.removals.sound.eventSpawn", true),
      MultiSelectSetting.Entry.method5231("module.removals.sound.witherHit", true),
      MultiSelectSetting.Entry.method5231("module.removals.sound.trident", true),
      MultiSelectSetting.Entry.method5231("module.removals.sound.expBottle", true),
      MultiSelectSetting.Entry.method5231("module.removals.sound.thunder", true),
      MultiSelectSetting.Entry.method5231("module.removals.sound.totem", true)
   );

   private final BooleanSetting hideEntities = new BooleanSetting("module.removals.hideEntities", "module.removals.hideEntities.desc", false);
   private final MultiSelectSetting hideTypes = new MultiSelectSetting(
      "module.removals.hideTypes",
      "module.removals.hideTypes.desc",
      MultiSelectSetting.Entry.method5231("module.removals.hide.items", true),
      MultiSelectSetting.Entry.method5231("module.removals.hide.players", false)
   );
   private final ModeSetting hideCondition = new ModeSetting(
      "module.removals.hideCondition",
      "module.removals.hideCondition.desc",
      "module.removals.hideCondition.always",
      "module.removals.hideCondition.closer",
      "module.removals.hideCondition.farther"
   );
   private final SliderSetting hideDistance = new SliderSetting(
      "module.removals.hideDistance", 16.0F, 1.0F, 128.0F, 1.0F, "module.removals.hideDistance.desc", "b"
   );

   private RemovalsModule() {
      this.soundCheck.setVisible(() -> this.elements.method5226("module.removals.element.removeSounds"));
      this.hideTypes.setVisible(this.hideEntities::isEnabled);
      this.hideCondition.setVisible(this.hideEntities::isEnabled);
      this.hideDistance.setVisible(this.hideEntities::isEnabled);

      ModuleHookDefinitions.class125.method1646(0, entry -> {
         if (isRemoved("module.removals.element.fire")) {
            entry.cancelled = true;
         }
      });
      ModuleHookDefinitions.class128.method1646(0, entry -> {
         if (isRemoved("module.removals.element.badEffects")) {
            entry.cancelled = true;
         }
      });
      ModuleHookDefinitions.class1215.method1646(0, entry -> {
         if (isRemoved("module.removals.element.removeSounds") && isSoundBlocked(entry.text)) {
            entry.cancelled = true;
         }
      });
   }

   @Override
   public void onEnable() {
      super.onEnable();
      this.updateHiddenBlocks();
   }

   @Override
   public void onDisable() {
      super.onDisable();
      this.updateHiddenBlocks();
   }

   @EventTarget
   public void onUpdate(UpdateEvent event) {
      if (!this.isEnabled()) {
         return;
      }

      String signature = this.signature();
      if (!signature.equals(hiddenSignature)) {
         this.updateHiddenBlocks();
      }
   }

   private String signature() {
      return (this.elements.method5226("module.removals.element.sculk") ? "1" : "0")
         + (this.elements.method5226("module.removals.element.sculkSensors") ? "1" : "0")
         + (this.elements.method5226("module.removals.element.vegetation") ? "1" : "0");
   }

   private void updateHiddenBlocks() {
      HashSet<Block> blocks = new HashSet<>();
      if (this.isEnabled() && this.elements.method5226("module.removals.element.sculk")) {
         blocks.addAll(SCULK_BLOCKS);
      }

      if (this.isEnabled() && this.elements.method5226("module.removals.element.sculkSensors")) {
         blocks.addAll(SCULK_SENSOR_BLOCKS);
      }

      if (this.isEnabled() && this.elements.method5226("module.removals.element.vegetation")) {
         for (String name : VEGETATION) {
            Registries.BLOCK.getOptionalValue(Identifier.ofVanilla(name)).ifPresent(blocks::add);
         }
      }

      hiddenSignature = this.isEnabled() ? this.signature() : "off";
      if (!blocks.equals(hiddenBlocks)) {
         hiddenBlocks = Set.copyOf(blocks);
         if (mc.world != null) {
            mc.execute(mc.worldRenderer::reload);
         }
      }
   }

   // --- публичные проверки для миксинов ---------------------------------

   public static RemovalsModule getInstance() {
      return INSTANCE;
   }

   public static boolean isRemoved(String key) {
      return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.elements.method5226(key);
   }

   public static boolean isBlockHidden(Block block) {
      return block != null && hiddenBlocks.contains(block);
   }

   public static boolean isEntityHidden(Entity entity) {
      return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.shouldHideEntity(entity);
   }

   public static boolean isParticleRemoved(ParticleEffect effect) {
      if (effect == null) {
         return false;
      }

      ParticleType<?> type = effect.getType();
      if (SCULK_PARTICLES.contains(type)) {
         return isRemoved("module.removals.element.sculkParticles");
      }

      if (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER) {
         return isRemoved("module.removals.element.explosionParticles");
      }

      return false;
   }

   public boolean isSoundBlocked(String soundId) {
      if (soundId == null) {
         return false;
      }

      if (soundId.equals("ui.toast.challenge_complete") && this.soundCheck.method5226("module.removals.sound.eventSpawn")) {
         return true;
      }

      if (soundId.equals("entity.experience_orb.pickup") && this.soundCheck.method5226("module.removals.sound.witherHit")) {
         return true;
      }

      if (soundId.startsWith("item.trident") && this.soundCheck.method5226("module.removals.sound.trident")) {
         return true;
      }

      if (soundId.equals("item.totem.use") && this.soundCheck.method5226("module.removals.sound.totem")) {
         return true;
      }

      return soundId.startsWith("entity.lightning_bolt") && this.soundCheck.method5226("module.removals.sound.thunder");
   }

   // --- скрытие сущностей -----------------------------------------------

   private boolean shouldHideEntity(Entity entity) {
      if (!this.hideEntities.isEnabled() || entity == null || mc.player == null) {
         return false;
      }

      if (entity == mc.player) {
         return false;
      }

      return this.isHideType(entity) && this.matchesHideCondition(entity);
   }

   private boolean isHideType(Entity entity) {
      if (entity instanceof ItemEntity) {
         return this.hideTypes.method5226("module.removals.hide.items");
      }

      return entity instanceof PlayerEntity && this.hideTypes.method5226("module.removals.hide.players");
   }

   private boolean matchesHideCondition(Entity entity) {
      if (this.hideCondition.method2101("module.removals.hideCondition.always")) {
         return true;
      }

      if (mc.player == null) {
         return false;
      }

      double distance = Math.sqrt(mc.player.squaredDistanceTo(entity));
      double limit = this.hideDistance.getValue3();
      return this.hideCondition.method2101("module.removals.hideCondition.closer") ? distance <= limit : distance >= limit;
   }

   public MultiSelectSetting getElements() {
      return this.elements;
   }

   public MultiSelectSetting getSoundCheck() {
      return this.soundCheck;
   }

   public BooleanSetting getHideEntities() {
      return this.hideEntities;
   }

   public MultiSelectSetting getHideTypes() {
      return this.hideTypes;
   }

   public ModeSetting getHideCondition() {
      return this.hideCondition;
   }

   public SliderSetting getHideDistance() {
      return this.hideDistance;
   }
}
