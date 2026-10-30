package org.zenith.utility.mixin.render;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.zenith.client.modules.impl.render.RemovalsModule;

/** Скрытие сущностей (предметы/игроки) из Removals — до того, как их начнут рендерить. */
@Mixin({EntityRenderManager.class})
public class MixinEntityRenderManager {
   @Inject(
      method = {"shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void zenith$hideEntity(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> info) {
      if (RemovalsModule.isEntityHidden(entity)) {
         info.setReturnValue(false);
      }
   }
}
