package org.zenith.client.modules.impl.render;

import com.darkmagician6.eventapi.EventTarget;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.zenith.ModuleCategory;
import org.zenith.ModuleInfo;
import org.zenith.Zenith;
import org.zenith.ZenithColor;
import org.zenith.base.Module;
import org.zenith.base.font.Font;
import org.zenith.base.font.Fonts;
import org.zenith.client.events.DrawEvent;
import org.zenith.client.settings.BooleanSetting;
import org.zenith.client.settings.SliderSetting;
import org.zenith.utility.api.LyricsFetcher;
import org.zenith.utility.misc.ColorUtils;
import org.zenith.utility.misc.MediaSessionManager;
import org.zenith.utility.render.ProjectionUtils;
import org.zenith.utility.render.display.base.CustomDrawContext;
import org.zenith.utility.render.display.base.ProxyDotRadiusData;

/**
 * Karaoke — текст песни прямо в мире.
 *
 * Берёт трек, который сейчас играет в любом плеере на ПК (тот же источник, что у панели
 * «Музыка»: системные медиа-сессии Windows), тянет синхронный текст с LRCLIB и рисует
 * строки в воздухе перед игроком: текущая строка появляется, заполняется слева направо
 * как в караоке, плавно поднимается вверх и растворяется, следующая вступает снизу.
 *
 * Позиции строк фиксируются в мире в момент их появления, поэтому текст остаётся
 * «в пространстве», а не липнет к экрану, и уезжает из вида, если отвернуться.
 */
@ModuleInfo(
   name = "Karaoke",
   category = ModuleCategory.RENDER,
   description = "module.karaoke.description"
)
public class KaraokeModule extends Module {
   public static final KaraokeModule INSTANCE = new KaraokeModule();

   private static final long FADE_IN_MS = 220L;
   private static final long FADE_OUT_MS = 520L;
   private static final double PREVIEW_ALPHA = 0.35;

   private final SliderSetting distance = new SliderSetting(
      "module.karaoke.distance", 3.2F, 1.5F, 8.0F, 0.1F, "module.karaoke.distance.desc", "b"
   );
   private final SliderSetting height = new SliderSetting(
      "module.karaoke.height", 0.8F, -1.5F, 3.0F, 0.05F, "module.karaoke.height.desc", "b"
   );
   private final SliderSetting size = new SliderSetting(
      "module.karaoke.size", 1.0F, 0.5F, 2.5F, 0.05F, "module.karaoke.size.desc", "x"
   );
   private final SliderSetting lines = new SliderSetting(
      "module.karaoke.lines", 2.0F, 1.0F, 4.0F, 1.0F, "module.karaoke.lines.desc", ""
   );
   private final SliderSetting rise = new SliderSetting(
      "module.karaoke.rise", 0.45F, 0.05F, 1.5F, 0.05F, "module.karaoke.rise.desc", "b/s"
   );
   private final SliderSetting offset = new SliderSetting(
      "module.karaoke.offset", 0.0F, -3.0F, 3.0F, 0.1F, "module.karaoke.offset.desc", "s"
   );
   private final BooleanSetting highlight = new BooleanSetting(
      "module.karaoke.highlight", "module.karaoke.highlight.desc", true
   );
   private final BooleanSetting background = new BooleanSetting(
      "module.karaoke.background", "module.karaoke.background.desc", true
   );
   private final BooleanSetting onlyPlaying = new BooleanSetting(
      "module.karaoke.onlyPlaying", "module.karaoke.onlyPlaying.desc", true
   );

   private final List<KaraokeModule.Line> active = new ArrayList<>(8);
   private String trackKey = "";
   private int lastIndex = Integer.MIN_VALUE;

   private KaraokeModule() {
   }

   @Override
   public void onDisable() {
      this.reset();
      super.onDisable();
   }

   private void reset() {
      this.active.clear();
      this.trackKey = "";
      this.lastIndex = Integer.MIN_VALUE;
   }

   @EventTarget
   public void onDraw(DrawEvent event) {
      CustomDrawContext var2 = event.getCustomDrawContext();
      MediaSessionManager var3 = MediaSessionManager.getInstance();
      if (var2 == null || mc.player == null || mc.world == null || !var3.active()) {
         this.reset();
         return;
      }

      if (this.onlyPlaying.isEnabled() && !var3.method6007()) {
         this.active.clear();
         return;
      }

      String var4 = var3.title() + '\u0000' + var3.method6006();
      if (!var4.equals(this.trackKey)) {
         this.trackKey = var4;
         this.active.clear();
         this.lastIndex = Integer.MIN_VALUE;
      }

      double var5 = var3.method6008();
      LyricsFetcher.Data2 var7 = LyricsFetcher.method528(var3.title(), var3.method6006(), var5);
      if (!var7.isFlag() || var7.lines().isEmpty()) {
         this.active.clear();
         return;
      }

      double var8 = var3.method6009() + this.offset.getValue3();
      int var10 = var7.method541(var8);
      if (var10 < 0) {
         this.active.clear();
         return;
      }

      this.spawn(var7, var10);
      this.drawLines(var2, var7, var8, var10);
   }

   /** Заводим объекты для строк, которые сейчас надо показать (текущая и следующие). */
   private void spawn(LyricsFetcher.Data2 lyrics, int current) {
      int var3 = Math.max(1, (int)this.lines.getValue3());

      for (int var4 = current; var4 < current + var3 && var4 < lyrics.lines().size(); var4++) {
         if (this.find(var4) == null) {
            this.active.add(new KaraokeModule.Line(var4, this.anchor()));
         }
      }
   }

   private KaraokeModule.Line find(int index) {
      for (KaraokeModule.Line var3 : this.active) {
         if (var3.index == index) {
            return var3;
         }
      }

      return null;
   }

   /** Точка появления новой строки: перед игроком, чуть выше глаз. */
   private Vec3d anchor() {
      Vec3d var1 = mc.player.getEyePos();
      Vec3d var2 = mc.player.getRotationVec(1.0F);
      Vec3d var3 = new Vec3d(var2.x, 0.0, var2.z);
      if (var3.lengthSquared() < 1.0E-4) {
         var3 = new Vec3d(0.0, 0.0, 1.0);
      }

      return var1.add(var3.normalize().multiply(this.distance.getValue3())).add(0.0, this.height.getValue3(), 0.0);
   }

   private void drawLines(CustomDrawContext ctx, LyricsFetcher.Data2 lyrics, double position, int current) {
      long var5 = System.currentTimeMillis();
      int var7 = Math.max(1, (int)this.lines.getValue3());
      ZenithColor var8 = Zenith.getInstance().getStyleManager().getCurrentStyle().getTextEnable().getZenithColor();
      ZenithColor var9 = Zenith.getInstance().getStyleManager().getClientColor(90);
      Vec3d var10 = mc.gameRenderer.getCamera().getCameraPos();
      this.active.removeIf(var3 -> var3.index < current - 1 || var3.index > current + var7);

      for (KaraokeModule.Line var12 : this.active) {
         double var13 = lyrics.lines().get(var12.index).time();
         double var15 = var12.index + 1 < lyrics.lines().size() ? lyrics.lines().get(var12.index + 1).time() : var13 + 4.0;
         double var17 = Math.max(0.45, var15 - var13);
         boolean var19 = var12.index == current;
         double var20 = position - var13;
         float var22;
         if (var19) {
            // текущая строка: мягко появляется и живёт до конца своей длительности
            var22 = (float)MathHelper.clamp(var20 / 0.16, 0.0, 1.0);
            double var23 = var17 + FADE_OUT_MS / 1000.0;
            if (var20 > var23) {
               continue;
            }

            if (var20 > var17) {
               var22 *= (float)MathHelper.clamp(1.0 - (var20 - var17) / (FADE_OUT_MS / 1000.0), 0.0, 1.0);
            }
         } else if (var12.index > current) {
            var22 = (float)(PREVIEW_ALPHA * MathHelper.clamp(1.0 - (var12.index - current - 1) * 0.3, 0.0, 1.0));
         } else {
            // прошедшая строка ещё немного уезжает вверх и догорает
            double var23 = var20 - var17;
            if (var23 > FADE_OUT_MS / 1000.0) {
               continue;
            }

            var22 = (float)MathHelper.clamp(1.0 - var23 / (FADE_OUT_MS / 1000.0), 0.0, 1.0);
         }

         if (var22 <= 0.02F) {
            continue;
         }

         double var24 = Math.max(0.0, var20) * this.rise.getValue3();
         Vec3d var25 = var12.anchor.add(0.0, var24, 0.0);
         Vec3d var26 = ProjectionUtils.method301(var25);
         if (var26 == null || var26.z <= 0.0 || var26.z >= 1.0) {
            continue;
         }

         double var27 = var25.squaredDistanceTo(var10);
         float var29 = MathHelper.clamp((float)(26.0 / Math.max(2.0, var27)), 0.55F, 3.2F) * this.size.getValue3();
         if (!var19) {
            var29 *= 0.8F;
         }

         Font var30 = Fonts.NEW_SEMIBOLD.getFont(MathHelper.clamp(var29 * 3.4F, 5.0F, 22.0F));
         String var31 = lyrics.lines().get(var12.index).text().strip();
         if (var31.isEmpty()) {
            continue;
         }

         float var32 = var30.width(var31);
         float var33 = (float)var26.x - var32 / 2.0F;
         float var34 = (float)var26.y - var30.height() / 2.0F;
         if (this.background.isEnabled() && var19) {
            float var35 = 3.0F;
            ctx.drawRoundedRect(
               var33 - var35,
               var34 - 2.0F,
               var32 + var35 * 2.0F,
               var30.height() + 4.0F,
               ProxyDotRadiusData.method8069(4.0F),
               new ZenithColor(8, 8, 12, (int)(110.0F * var22))
            );
         }

         int var48 = ColorUtils.method6439(var8.method7287(), var22 * 0.55F);
         int var36 = ColorUtils.method6439(var8.method7287(), var22);
         int var37 = ColorUtils.method6439(var9.method7287(), var22);
         // тень, чтобы текст читался на любом фоне
         ctx.drawText(var30, var31, var33 + 0.8F, var34 + 0.8F, new ZenithColor(0, 0, 0, (int)(150.0F * var22)));
         if (var19 && this.highlight.isEnabled()) {
            // «караоке»: уже пропетая часть ярче и цветом акцента
            double var38 = MathHelper.clamp(var20 / var17, 0.0, 1.0);
            int var40 = (int)Math.round(var38 * var31.length());
            ctx.drawText(var30, var31, var33, var34, new ZenithColor(255, 255, 255, (int)(70.0F * var22)));
            if (var40 > 0) {
               String var41 = var31.substring(0, Math.min(var40, var31.length()));
               ctx.drawText(var30, var41, var33, var34, new ZenithColor(var37));
            }

            // полоска прогресса строки под текстом
            float var42 = var32 * (float)var38;
            if (var42 > 0.5F) {
               ctx.drawRoundedRect(
                  var33, var34 + var30.height() + 2.5F, var42, 1.6F, ProxyDotRadiusData.method8069(0.8F),
                  new ZenithColor(
                     (var37 >> 16) & 0xFF, (var37 >> 8) & 0xFF, var37 & 0xFF, (int)(200.0F * var22)
                  )
               );
            }
         } else {
            ctx.drawText(var30, var31, var33, var34, new ZenithColor(var19 ? var36 : var48));
         }
      }
   }

   /** Строка караоке: индекс в тексте, якорь в мире и момент появления. */
   private static final class Line {
      private final int index;
      private final Vec3d anchor;

      private Line(int index, Vec3d anchor) {
         this.index = index;
         this.anchor = anchor;
      }

      @Override
      public boolean equals(Object other) {
         return other instanceof KaraokeModule.Line var2 && var2.index == this.index;
      }

      @Override
      public int hashCode() {
         return this.index;
      }
   }
}
