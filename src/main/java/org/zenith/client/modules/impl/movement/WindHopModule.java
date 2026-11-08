package org.zenith.client.modules.impl.movement;

import com.darkmagician6.eventapi.EventTarget;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.zenith.ChatLogger;
import org.zenith.KeyEvent;
import org.zenith.ModuleCategory;
import org.zenith.ModuleInfo;
import org.zenith.Rotation;
import org.zenith.StopWatch;
import org.zenith.Zenith;
import org.zenith.base.Module;
import org.zenith.client.events.Event5;
import org.zenith.client.settings.BindSetting;
import org.zenith.client.settings.BooleanSetting;
import org.zenith.client.settings.SliderSetting;
import org.zenith.utility.misc.RotationRequest;
import org.zenith.utility.network.InventoryUtils;
import org.zenith.utility.network.UseItemUtils;

/**
 * WindHop — «прыжок ветром» по бинду.
 *
 * По нажатию бинда модуль тихо (silent-ротация, камера игрока не двигается) плавно
 * опускает прицел вниз, кидает заряд ветра себе под ноги и подпрыгивает в момент взрыва,
 * из-за чего игрока подбрасывает выше обычного. После этого в руку возвращается предмет,
 * который был до бинда (либо булава, если включён соответствующий чекбокс).
 *
 * Работает и при включённой ауре: заявка на ротацию уходит с высоким приоритетом,
 * поэтому перебивает любые другие модули на время одного действия.
 */
@ModuleInfo(
   name = "WindHop",
   description = "module.windhop.description",
   category = ModuleCategory.MOVEMENT
)
public final class WindHopModule extends Module {
   public static final WindHopModule INSTANCE = new WindHopModule();

   /** Приоритет ротации: выше ауры (1/3) и остальных модулей. */
   private static final int PRIORITY = 20;
   private static final int MAX_ROTATE_TICKS = 20;
   private static final int MAX_THROW_TICKS = 10;
   private static final int MAX_RETURN_TICKS = 12;

   public final BindSetting trigger = new BindSetting("module.windhop.trigger", "module.windhop.trigger.desc");
   private final BooleanSetting takeMace = new BooleanSetting("module.windhop.takeMace", "module.windhop.takeMace.desc", true);
   private final SliderSetting lookPitch = new SliderSetting(
      "module.windhop.lookPitch", 90.0F, 60.0F, 90.0F, 1.0F, "module.windhop.lookPitch.desc", "°"
   );
   private final SliderSetting rotateSpeed = new SliderSetting(
      "module.windhop.rotateSpeed", 35.0F, 5.0F, 90.0F, 1.0F, "module.windhop.rotateSpeed.desc", "°/t"
   );
   private final BooleanSetting smoothReturn = new BooleanSetting(
      "module.windhop.smoothReturn", "module.windhop.smoothReturn.desc", true
   );
   private final SliderSetting jumpDelay = new SliderSetting(
      "module.windhop.jumpDelay", 2.0F, 0.0F, 8.0F, 1.0F, "module.windhop.jumpDelay.desc", "t"
   );
   private final SliderSetting cooldown = new SliderSetting(
      "module.windhop.cooldown", 400.0F, 0.0F, 2000.0F, 50.0F, "module.windhop.cooldown.desc", "ms"
   );

   private final StopWatch cooldownWatch = new StopWatch();

   private Phase phase = Phase.IDLE;
   private int ticks;
   private int usedAtAge;
   private int previousSlot = -1;
   private int chargeHotbarSlot = -1;
   private int chargeInventoryIndex = -1;
   private boolean chargeMovedIntoHotbar;
   private Hand chargeHand = Hand.MAIN_HAND;
   private float yaw;
   private float fromPitch;
   private float toPitch;

   private WindHopModule() {
   }

   @Override
   public void onEnable() {
      this.reset();
      this.cooldownWatch.setLongValue(0L);
      super.onEnable();
   }

   @Override
   public void onDisable() {
      this.reset();
      super.onDisable();
   }

   private void reset() {
      this.phase = Phase.IDLE;
      this.ticks = 0;
      this.usedAtAge = 0;
      this.previousSlot = -1;
      this.chargeHotbarSlot = -1;
      this.chargeInventoryIndex = -1;
      this.chargeMovedIntoHotbar = false;
      this.chargeHand = Hand.MAIN_HAND;
   }

   @EventTarget
   public void onKey(KeyEvent event) {
      if (this.trigger.getKeyCode() == -1 || !event.isPress(this.trigger.getKeyCode())) {
         return;
      }

      this.start();
   }

   @EventTarget
   public void onTick(Event5 event) {
      if (mc.player == null || mc.world == null || mc.interactionManager == null) {
         this.reset();
         return;
      }

      if (mc.player.isDead() || !mc.player.isAlive()) {
         this.reset();
         return;
      }

      switch (this.phase) {
         case ROTATING -> this.tickRotate();
         case THROWING -> this.tickThrow();
         case JUMPING -> this.tickJump();
         case RETURNING -> this.tickReturn();
         default -> {
         }
      }
   }

   // --- запуск -----------------------------------------------------------

   private void start() {
      if (this.phase != Phase.IDLE || mc.player == null || mc.world == null || mc.interactionManager == null) {
         return;
      }

      if (!this.cooldownWatch.method7545(this.cooldown.getValue3())) {
         return;
      }

      this.locateCharge();
      if (this.chargeHotbarSlot == -1 && this.chargeInventoryIndex == -1 && this.chargeHand != Hand.OFF_HAND) {
         ChatLogger.method2021(Zenith.getInstance().getLocalization().translate("module.windhop.noCharge"));
         return;
      }

      this.previousSlot = mc.player.getInventory().getSelectedSlot();
      this.chargeMovedIntoHotbar = false;
      this.yaw = rotationManager.method7844().getFloatValue();
      this.fromPitch = MathHelper.clamp(rotationManager.method7844().getFloatValue2(), -89.0F, 89.0F);
      this.toPitch = this.lookPitch.getValue3();
      this.ticks = 0;
      this.phase = Phase.ROTATING;
   }

   private void locateCharge() {
      this.chargeHotbarSlot = -1;
      this.chargeInventoryIndex = -1;
      this.chargeHand = Hand.MAIN_HAND;

      for (int slot = 0; slot < 9; slot++) {
         if (mc.player.getInventory().getStack(slot).isOf(Items.WIND_CHARGE)) {
            this.chargeHotbarSlot = slot;
            return;
         }
      }

      // заряд в левой руке — ничего перекладывать не нужно
      if (mc.player.getOffHandStack().isOf(Items.WIND_CHARGE)) {
         this.chargeHand = Hand.OFF_HAND;
         return;
      }

      for (int index = 9; index < 36; index++) {
         if (mc.player.getInventory().getStack(index).isOf(Items.WIND_CHARGE)) {
            this.chargeInventoryIndex = index;
            return;
         }
      }
   }

   // --- фаза 1: плавно смотрим вниз -------------------------------------

   private void tickRotate() {
      if (++this.ticks > MAX_ROTATE_TICKS) {
         this.finish();
         return;
      }

      float step = this.rotateSpeed.getValue3();
      float delta = this.toPitch - this.fromPitch;
      // easing: несколько тиков с ускорением и торможением вместо одного «рывка»
      int planTicks = Math.max(1, MathHelper.ceil(Math.abs(delta) / step));
      float progress = MathHelper.clamp((float) this.ticks / (float) planTicks, 0.0F, 1.0F);
      float eased = progress * progress * (3.0F - 2.0F * progress);
      float pitch = this.fromPitch + delta * eased;

      Rotation target = new Rotation(this.yaw, MathHelper.clamp(pitch, -90.0F, 90.0F));
      this.requestRotation(target);

      // сервер уже видел нас с опущенным прицелом -> можно кидать заряд
      Rotation sent = rotationManager.getRotation2();
      boolean looksDown = sent != null && sent.getFloatValue2() >= this.toPitch - 2.0F;
      if (looksDown && progress >= 1.0F) {
         this.prepareCharge();
         this.phase = Phase.THROWING;
         this.ticks = 0;
      }
   }

   // --- фаза 2: заряд ветра под себя -------------------------------------

   private void tickThrow() {
      if (++this.ticks > MAX_THROW_TICKS) {
         this.finish();
         return;
      }

      // пока держим прицел внизу, чтобы и следующий пакет ушёл туда же
      this.requestRotation(new Rotation(this.yaw, this.toPitch));

      if (!mc.player.getStackInHand(this.chargeHand).isOf(Items.WIND_CHARGE)) {
         this.prepareCharge();
         return;
      }

      Rotation sent = rotationManager.getRotation2();
      if (sent == null || sent.getFloatValue2() < this.toPitch - 2.0F) {
         return;
      }

      UseItemUtils.useItem(this.chargeHand);
      this.usedAtAge = mc.player.age;
      this.phase = Phase.JUMPING;
      this.ticks = 0;
   }

   /** Кладём заряд в руку: из хотбара — выбором слота, из инвентаря — однократным свапом. */
   private void prepareCharge() {
      if (this.chargeHand == Hand.OFF_HAND || mc.player.getMainHandStack().isOf(Items.WIND_CHARGE)) {
         return;
      }

      if (this.chargeHotbarSlot != -1) {
         this.selectSlot(this.chargeHotbarSlot);
         return;
      }

      if (this.chargeInventoryIndex != -1 && !this.chargeMovedIntoHotbar) {
         this.swapInventoryWithHotbar(this.chargeInventoryIndex, mc.player.getInventory().getSelectedSlot());
         this.chargeMovedIntoHotbar = true;
      }
   }

   // --- фаза 3: подпрыгнуть под взрыв ------------------------------------

   private void tickJump() {
      this.requestRotation(new Rotation(this.yaw, this.toPitch));

      int delay = (int) this.jumpDelay.getValue3();
      if (mc.player.age - this.usedAtAge < delay) {
         return;
      }

      if (mc.player.isOnGround() || mc.player.isGliding()) {
         mc.player.jump();
      } else if (mc.player.getVelocity().y < 0.2) {
         // уже в воздухе — добавляем импульс вверх, чтобы не терять высоту
         mc.player.setVelocity(mc.player.getVelocity().x, 0.42, mc.player.getVelocity().z);
         mc.player.velocityDirty = true;
      }

      this.phase = Phase.RETURNING;
      this.ticks = 0;
      this.fromPitch = MathHelper.clamp(rotationManager.method7844().getFloatValue2(), -90.0F, 90.0F);
      this.toPitch = MathHelper.clamp(mc.player.getPitch(), -90.0F, 90.0F);
      this.yaw = mc.player.getYaw();
   }

   // --- фаза 4: возврат предмета и прицела -------------------------------

   private void tickReturn() {
      if (++this.ticks > MAX_RETURN_TICKS) {
         this.finish();
         return;
      }

      if (this.ticks == 1) {
         this.restoreItem();
      }

      if (!this.smoothReturn.isEnabled()) {
         this.finish();
         return;
      }

      float step = this.rotateSpeed.getValue3();
      float delta = this.toPitch - this.fromPitch;
      int planTicks = Math.max(1, MathHelper.ceil(Math.abs(delta) / step));
      float progress = MathHelper.clamp((float) this.ticks / (float) planTicks, 0.0F, 1.0F);
      float eased = progress * progress * (3.0F - 2.0F * progress);
      Rotation target = new Rotation(this.yaw, MathHelper.clamp(this.fromPitch + delta * eased, -90.0F, 90.0F));
      this.requestRotation(target);

      if (progress >= 1.0F) {
         this.finish();
      }
   }

   /** Возврат предмета в руку: булава (если включено) либо то, что было до бинда. */
   private void restoreItem() {
      if (this.chargeMovedIntoHotbar && this.chargeInventoryIndex != -1) {
         int selected = mc.player.getInventory().getSelectedSlot();
         this.swapInventoryWithHotbar(this.chargeInventoryIndex, selected);
         this.chargeMovedIntoHotbar = false;
      }

      if (this.takeMace.isEnabled() && this.selectMace()) {
         return;
      }

      if (this.previousSlot != -1) {
         this.selectSlot(this.previousSlot);
      }
   }

   private boolean selectMace() {
      for (int slot = 0; slot < 9; slot++) {
         if (mc.player.getInventory().getStack(slot).isOf(Items.MACE)) {
            this.selectSlot(slot);
            return true;
         }
      }

      for (int index = 9; index < 36; index++) {
         if (mc.player.getInventory().getStack(index).isOf(Items.MACE)) {
            this.swapInventoryWithHotbar(index, mc.player.getInventory().getSelectedSlot());
            return true;
         }
      }

      return false;
   }

   private void finish() {
      this.cooldownWatch.reset();
      this.phase = Phase.IDLE;
      this.ticks = 0;
   }

   // --- вспомогательное --------------------------------------------------

   private void requestRotation(Rotation rotation) {
      rotationManager.method7849(
         new RotationRequest(rotation, () -> rotation, bcCameraTestnerv.getRotationConfig()), PRIORITY, this
      );
   }

   private void selectSlot(int slot) {
      if (mc.player == null || mc.interactionManager == null) {
         return;
      }

      if (mc.player.getInventory().getSelectedSlot() != slot) {
         mc.player.getInventory().setSelectedSlot(slot);
      }

      mc.interactionManager.syncSelectedSlot();
   }

   /** SWAP-клик: меняет местами слот инвентаря и слот хотбара (без открытия экрана). */
   private void swapInventoryWithHotbar(int inventoryIndex, int hotbarSlot) {
      if (mc.player == null || mc.interactionManager == null) {
         return;
      }

      int screenSlot = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
      mc.interactionManager.clickSlot(
         mc.player.playerScreenHandler.syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, mc.player
      );
      InventoryUtils.closeScreen();
   }

   private enum Phase {
      IDLE,
      ROTATING,
      THROWING,
      JUMPING,
      RETURNING
   }
}
