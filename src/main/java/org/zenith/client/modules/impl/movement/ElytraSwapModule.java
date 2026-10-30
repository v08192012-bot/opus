package org.zenith.client.modules.impl.movement;

import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.zenith.ChatLogger;
import org.zenith.KeyEvent;
import org.zenith.ModuleCategory;
import org.zenith.ModuleInfo;
import org.zenith.StopWatch;
import org.zenith.Zenith;
import org.zenith.base.Module;
import org.zenith.client.events.UpdateEvent;
import org.zenith.client.settings.BindSetting;
import org.zenith.client.settings.BooleanSetting;
import org.zenith.client.settings.TextSetting;
import org.zenith.utility.network.InventoryUtils;

/**
 * ElytraSwap — быстрый обмен «нагрудник ↔ элитры» с серверными таймингами и опциональным
 * авто-включением полёта командами после свапа.
 */
@ModuleInfo(
   name = "ElytraSwap",
   description = "module.elytraswap.description",
   category = ModuleCategory.MOVEMENT
)
public final class ElytraSwapModule extends Module {
   public static final ElytraSwapModule INSTANCE = new ElytraSwapModule();

   private static final int ARMOR_CHEST_SLOT = 6;
   private static final List<Item> CHESTPLATES = List.of(
      Items.NETHERITE_CHESTPLATE,
      Items.DIAMOND_CHESTPLATE,
      Items.IRON_CHESTPLATE,
      Items.CHAINMAIL_CHESTPLATE,
      Items.GOLDEN_CHESTPLATE,
      Items.LEATHER_CHESTPLATE
   );

   private static final Timing DEFAULT_TIMING = new Timing(2, 2, 2);
   private static final Timing FUNTIME_TIMING = new Timing(50, 50, 50);
   private static final Timing SPOOKYTIME_TIMING = new Timing(50, 50, 150);
   private static final Timing HOLY_TIMING = new Timing(50, 50, 50);

   public final BindSetting swap = new BindSetting("module.elytraswap.swap", "module.elytraswap.swap.desc");
   public final BindSetting use = new BindSetting("module.elytraswap.use", "module.elytraswap.use.desc");
   private final BooleanSetting autoFly = new BooleanSetting("module.elytraswap.autoFly", "module.elytraswap.autoFly.desc", true);
   private final TextSetting flyCommands = new TextSetting(
      "module.elytraswap.flyCommands", "module.elytraswap.flyCommands.desc", "fly; speed 10", this.autoFly::isEnabled
   );
   private final BooleanSetting oldSwap = new BooleanSetting("module.elytraswap.oldSwap", "module.elytraswap.oldSwap.desc", false);
   private final BooleanSetting autoSwapInPvp = new BooleanSetting(
      "module.elytraswap.autoSwapInPvp", "module.elytraswap.autoSwapInPvp.desc", false
   );

   private final ArrayDeque<Delayed> queue = new ArrayDeque<>();
   private final StopWatch flyTimer = new StopWatch();
   private boolean flyCommandsPending;

   private ElytraSwapModule() {
   }

   @Override
   public void onEnable() {
      this.reset();
      super.onEnable();
   }

   @Override
   public void onDisable() {
      this.reset();
      super.onDisable();
   }

   private void reset() {
      this.queue.clear();
      this.flyCommandsPending = false;
      this.flyTimer.reset();
   }

   @EventTarget
   public void onUpdate(UpdateEvent event) {
      ClientPlayerEntity player = mc.player;
      if (player == null || mc.world == null) {
         this.reset();
         return;
      }

      this.tickQueue();
      this.tickAutoFly(player);

      if (this.autoSwapInPvp.isEnabled() && player.age % 3 == 0 && this.canAutoSwap()) {
         if (player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            this.swapElytra();
         }
      }
   }

   @EventTarget
   public void onKey(KeyEvent event) {
      if (this.swap.getKeyCode() != -1 && event.isPress(this.swap.getKeyCode())) {
         if (this.queue.isEmpty()) {
            this.swapElytra();
         }
      } else if (this.use.getKeyCode() != -1 && event.isPress(this.use.getKeyCode()) && mc.player != null && mc.player.isGliding()) {
         this.useFirework();
      }
   }

   private void useFirework() {
      if (InventoryUtils.method906(Items.FIREWORK_ROCKET) != null) {
         InventoryUtils.method929(Items.FIREWORK_ROCKET, net.minecraft.util.Hand.OFF_HAND);
      } else {
         ChatLogger.method2021(Zenith.getInstance().getLocalization().translate("module.elytraswap.noFirework"));
      }
   }

   // --- авто-полёт -------------------------------------------------------

   private void tickAutoFly(ClientPlayerEntity player) {
      if (!this.flyCommandsPending || !this.autoFly.isEnabled()) {
         return;
      }

      if (!player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
         return;
      }

      if (!this.queue.isEmpty()) {
         return;
      }

      // даём серверу время применить свап, но не ждём дольше тайминга
      if (!this.flyTimer.method7545(this.timing().total())) {
         return;
      }

      this.flyCommandsPending = false;
      this.runFlyCommands();
   }

   private void runFlyCommands() {
      String raw = this.flyCommands.getValue();
      if (raw == null || raw.isBlank()) {
         return;
      }

      ClientPlayNetworkHandler handler = mc.getNetworkHandler();
      if (handler == null) {
         return;
      }

      for (String part : raw.split("[;\\n]")) {
         String command = part.trim();
         if (command.isEmpty()) {
            continue;
         }

         if (command.startsWith("/")) {
            handler.sendChatCommand(command.substring(1));
         } else {
            handler.sendChatMessage(command);
         }
      }
   }

   // --- сам свап ---------------------------------------------------------

   private void swapElytra() {
      ClientPlayerEntity player = mc.player;
      if (player == null) {
         return;
      }

      this.flyCommandsPending = false;
      ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
      Slot chestplate = this.findChestplateSlot();

      if (!chest.isOf(Items.ELYTRA)) {
         if (!chest.isEmpty() || chestplate == null) {
            Slot elytra = this.findElytraSlot();
            if (elytra == null) {
               ChatLogger.method2021(Zenith.getInstance().getLocalization().translate("module.elytraswap.noElytra"));
               return;
            }

            this.swapSlot(elytra.id);
            if (this.autoFly.isEnabled()) {
               this.flyCommandsPending = true;
               this.flyTimer.reset();
            }
            return;
         }
      } else if (chestplate == null) {
         this.swapViaFreeSlot();
         return;
      }

      if (chestplate != null) {
         this.swapSlot(chestplate.id);
      } else {
         this.swapViaFreeSlot();
      }
   }

   private void swapSlot(int slotId) {
      if (this.oldSwap.isEnabled()) {
         this.legacySwap(slotId);
      } else {
         Slot slot = this.slotById(slotId);
         if (slot == null) {
            return;
         }

         // штатный путь клиента: клик по слоту с переносом в слот нагрудника (6) + закрытие экрана
         InventoryUtils.method934(slot, ARMOR_CHEST_SLOT, true, false);
      }
   }

   /** Старый способ: три обычных PICKUP-клика, как делали до появления SWAP-кликов. */
   private void legacySwap(int slotId) {
      Timing timing = this.timing();
      this.queue.clear();
      this.schedule(0, () -> InventoryUtils.method904(slotId, 0, SlotActionType.PICKUP, false));
      this.schedule(timing.firstAt(), () -> InventoryUtils.method904(ARMOR_CHEST_SLOT, 0, SlotActionType.PICKUP, false));
      this.schedule(timing.armorAt(), () -> InventoryUtils.method904(slotId, 0, SlotActionType.PICKUP, false));
      this.schedule(timing.backAt(), InventoryUtils::closeScreen);
   }

   private void swapViaFreeSlot() {
      Slot free = this.findEmptySlot();
      if (free == null) {
         ChatLogger.method2021(Zenith.getInstance().getLocalization().translate("module.elytraswap.noSpace"));
         return;
      }

      Timing timing = this.timing();
      this.queue.clear();
      this.schedule(0, () -> InventoryUtils.method904(ARMOR_CHEST_SLOT, 0, SlotActionType.PICKUP, false));
      this.schedule(timing.firstAt(), () -> InventoryUtils.method904(free.id, 0, SlotActionType.PICKUP, false));
      this.schedule(timing.backAt(), InventoryUtils::closeScreen);
   }

   // --- поиск слотов -----------------------------------------------------

   private Slot slotById(int id) {
      for (Slot slot : InventoryUtils.method926().toList()) {
         if (slot.id == id) {
            return slot;
         }
      }

      return null;
   }

   private Slot findChestplateSlot() {
      for (Item item : CHESTPLATES) {
         Slot slot = this.findMainSlot(item);
         if (slot != null) {
            return slot;
         }
      }

      return null;
   }

   private Slot findElytraSlot() {
      Slot slot = this.findMainSlot(Items.ELYTRA);
      if (slot == null) {
         return null;
      }

      ItemStack stack = slot.getStack();
      return stack.getMaxDamage() > 0 && stack.getDamage() >= stack.getMaxDamage() ? null : slot;
   }

   private Slot findMainSlot(Item item) {
      for (Slot slot : InventoryUtils.method926().toList()) {
         if (slot.id >= 9 && slot.id <= 44 && slot.getStack().isOf(item)) {
            return slot;
         }
      }

      return null;
   }

   private Slot findEmptySlot() {
      for (Slot slot : InventoryUtils.method926().toList()) {
         if (slot.id >= 9 && slot.id <= 44 && slot.getStack().isEmpty()) {
            return slot;
         }
      }

      return null;
   }

   // --- серверные тайминги ----------------------------------------------

   private boolean canAutoSwap() {
      if (mc.player == null) {
         return false;
      }

      if (this.isServer("funtime") || this.isServer("spookytime") || this.isServer("holyworld")) {
         return false;
      }

      return Zenith.getInstance().getServerDetector().isFlag2();
   }

   private boolean isServer(String name) {
      String server = Zenith.getInstance().getServerDetector().getText();
      if (server == null) {
         return false;
      }

      String lower = server.toLowerCase();
      return switch (name) {
         case "funtime" -> lower.contains("funtime");
         case "spookytime" -> lower.contains("spooky") || lower.contains("copytime");
         case "holyworld" -> lower.contains("holy") || lower.contains("holyworld");
         default -> false;
      };
   }

   private Timing timing() {
      if (this.isServer("funtime")) {
         return FUNTIME_TIMING;
      }

      if (this.isServer("spookytime")) {
         return SPOOKYTIME_TIMING;
      }

      if (this.isServer("holyworld")) {
         return HOLY_TIMING;
      }

      return DEFAULT_TIMING;
   }

   // --- внутренняя очередь действий -------------------------------------

   private void schedule(int delayMs, Runnable action) {
      this.queue.add(new Delayed(delayMs, action));
   }

   private void tickQueue() {
      if (this.queue.isEmpty()) {
         return;
      }

      Delayed first = this.queue.peek();
      if (first != null && first.timer.method7545(first.delay)) {
         this.queue.poll();
         first.action.run();
      }
   }

   private static final class Delayed {
      private final int delay;
      private final Runnable action;
      private final StopWatch timer = new StopWatch();

      private Delayed(int delay, Runnable action) {
         this.delay = delay;
         this.action = action;
      }
   }

   private record Timing(int first, int armor, int back) {
      int firstAt() {
         return this.first;
      }

      int armorAt() {
         return this.first + this.armor;
      }

      int backAt() {
         return this.first + this.armor + this.back;
      }

      int total() {
         return this.backAt();
      }
   }
}
