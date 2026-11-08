package org.zenith.utility.render;

import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.zenith.MinecraftAccess;
import org.zenith.RenderState;
import org.zenith.Zenith;
import org.zenith.utility.misc.ColorUtils;

/**
 * Режим «Молнии» для TargetESP.
 *
 * Вокруг цели бьют короткие ветвящиеся разряды: у каждого излома считается своя
 * лента (ribbon), повёрнутая к камере, поэтому молния объёмная, а не плоский спрайт.
 * Рисуется тремя слоями — широкое свечение, средний ореол и яркое ядро — плюс
 * мягкие блумы на узлах излома. Всё складывается аддитивно (SRC_ALPHA, ONE),
 * поэтому на светлом фоне разряды выглядят как настоящая вспышка.
 *
 * Координаты локальные относительно ног цели, камера в этом пространстве — в нуле,
 * матрица — та же, что у остального world-рендера.
 */
public final class TargetEspLightning implements MinecraftAccess {
   /** Мягкое радиальное свечение из клиентских ассетов — для блумов на узлах. */
   private static final Identifier identifier = Zenith.method1501("textures/glow.png");

   private static final float THICK_OUTER = 0.045F;
   private static final float THICK_MID = 0.020F;
   private static final float THICK_CORE = 0.007F;
   private static final int ALPHA_OUTER = 48;
   private static final int ALPHA_MID = 96;
   private static final int ALPHA_CORE = 232;
   private static final int MAX_BOLTS = 24;

   private final List<TargetEspLightning.Bolt> bolts = new ArrayList<>(MAX_BOLTS);
   private long lastSpawn;

   /** Сброс при выключении модуля или смене цели насовсем. */
   public void reset() {
      this.bolts.clear();
      this.lastSpawn = 0L;
   }

   /**
    * @param matrix     матрица world-рендера
    * @param base       позиция центра цели относительно камеры (ноги)
    * @param width      ширина хитбокса цели
    * @param height     высота хитбокса цели
    * @param appear     прогресс появления 0..1
    * @param glowColor  цвет разрядов (уже сведён с красным при ударе), ARGB
    * @param coreColor  цвет ядра, ARGB
    * @param count      сколько разрядов держать одновременно
    * @param widthScale масштаб толщины
    * @param speed      множитель скорости (влияет на частоту вспышек)
    * @param visible    видна ли цель напрямую (иначе рисуем сквозь стены)
    */
   public void render(
      Matrix4f matrix,
      Vec3d base,
      float width,
      float height,
      float appear,
      int glowColor,
      int coreColor,
      float count,
      float widthScale,
      float speed,
      boolean visible
   ) {
      if (matrix == null || base == null || appear <= 0.001F) {
         this.bolts.clear();
         return;
      }

      float rate = MathHelper.clamp(speed, 0.15F, 4.0F);
      int maxBolts = MathHelper.clamp(Math.round(count) * 2, 4, MAX_BOLTS);
      long interval = (long)Math.max(12.0F, 65.0F / Math.max(0.4F, count / 3.0F) / rate);
      long now = System.currentTimeMillis();
      if (now - this.lastSpawn > interval && this.bolts.size() < maxBolts) {
         this.bolts.add(this.spawnBolt(width, height));
         this.lastSpawn = now;
      }

      this.bolts.removeIf(var2 -> now - var2.spawn > var2.life);
      if (this.bolts.isEmpty()) {
         return;
      }

      float scale = MathHelper.clamp(widthScale, 0.2F, 4.0F);
      Vec3d right = right();
      Vec3d up = up(right);

      RenderState.method1871();
      RenderState.method1882(false);
      if (visible) {
         RenderState.method1878();
      } else {
         RenderState.method1879();
      }

      RenderState.method1877();
      RenderState.method1875(SourceFactor.SRC_ALPHA, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE);
      RenderState.method1898("core/position_color");
      this.drawLayer(matrix, base, now, appear, glowColor, THICK_OUTER * scale, ALPHA_OUTER, 0.85F);
      this.drawLayer(matrix, base, now, appear, glowColor, THICK_MID * scale, ALPHA_MID, 0.8F);
      this.drawLayer(matrix, base, now, appear, coreColor, THICK_CORE * scale, ALPHA_CORE, 0.85F);
      this.drawBloom(matrix, base, right, up, now, appear, glowColor, coreColor, scale);

      RenderState.method1878();
      RenderState.method1876();
      RenderState.method1882(true);
      RenderState.method1872();
      RenderState.method1873();
   }

   // --- слои --------------------------------------------------------------

   private void drawLayer(
      Matrix4f matrix, Vec3d base, long now, float appear, int color, float thickness, int alpha, float branchScale
   ) {
      if ((color >>> 24) == 0) {
         return;
      }

      BufferBuilder var10 = Tessellator.getInstance().begin(DrawMode.QUADS, VertexFormats.POSITION_COLOR);

      for (TargetEspLightning.Bolt var12 : this.bolts) {
         float var13 = this.fade(var12, now) * appear;
         if (var13 <= 0.02F) {
            continue;
         }

         int var14 = ColorUtils.method6439(color, var13 * (alpha / 255.0F));
         if (var14 >>> 24 != 0) {
            this.appendRibbon(var10, matrix, base, var12.points, thickness, var14);
         }

         int var15 = ColorUtils.method6439(color, var13 * (alpha / 255.0F) * branchScale);
         if (var15 >>> 24 != 0) {
            for (List<Vec3d> var17 : var12.branches) {
               this.appendRibbon(var10, matrix, base, var17, thickness * 0.7F, var15);
            }
         }
      }

      ImmediateRenderer.method8015(var10.end());
   }

   private void drawBloom(
      Matrix4f matrix, Vec3d base, Vec3d right, Vec3d up, long now, float appear, int glowColor, int coreColor, float scale
   ) {
      RenderState.method1898("core/position_tex_color");
      RenderState.method1890(0, identifier);
      BufferBuilder var11 = Tessellator.getInstance().begin(DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

      for (TargetEspLightning.Bolt var13 : this.bolts) {
         float var14 = this.fade(var13, now) * appear;
         if (var14 <= 0.03F) {
            continue;
         }

         int var15 = ColorUtils.method6439(glowColor, var14 * (46.0F / 255.0F));
         if (var15 >>> 24 > 0) {
            for (int var16 = 0; var16 < var13.points.size(); var16 += 2) {
               this.appendBillboard(var11, matrix, base, right, up, var13.points.get(var16), 0.30F * scale, var15);
            }
         }

         int var20 = ColorUtils.method6439(coreColor, var14 * (120.0F / 255.0F));
         if (var20 >>> 24 > 0) {
            for (Vec3d var22 : var13.points) {
               this.appendBillboard(var11, matrix, base, right, up, var22, 0.085F * scale, var20);
            }
         }
      }

      ImmediateRenderer.method8015(var11.end());
   }

   /** Лента из точек, всегда повёрнутая к камере (камера в этом пространстве — в нуле). */
   private void appendRibbon(BufferBuilder buffer, Matrix4f matrix, Vec3d base, List<Vec3d> points, float thickness, int color) {
      if (points.size() < 2) {
         return;
      }

      float var7 = thickness * 0.5F;

      for (int var8 = 0; var8 < points.size() - 1; var8++) {
         Vec3d var9 = base.add(points.get(var8));
         Vec3d var10 = base.add(points.get(var8 + 1));
         Vec3d var11 = var10.subtract(var9);
         if (var11.lengthSquared() < 1.0E-8) {
            continue;
         }

         Vec3d var12 = var11.crossProduct(var9.add(var10).multiply(0.5));
         double var13 = var12.length();
         if (var13 < 1.0E-8 || !Double.isFinite(var13)) {
            continue;
         }

         var12 = var12.multiply(1.0 / var13);
         float var15 = (float)var12.x * var7;
         float var16 = (float)var12.y * var7;
         float var17 = (float)var12.z * var7;
         buffer.vertex(matrix, (float)var9.x - var15, (float)var9.y - var16, (float)var9.z - var17).color(color);
         buffer.vertex(matrix, (float)var9.x + var15, (float)var9.y + var16, (float)var9.z + var17).color(color);
         buffer.vertex(matrix, (float)var10.x + var15, (float)var10.y + var16, (float)var10.z + var17).color(color);
         buffer.vertex(matrix, (float)var10.x - var15, (float)var10.y - var16, (float)var10.z - var17).color(color);
      }
   }

   private void appendBillboard(
      BufferBuilder buffer, Matrix4f matrix, Vec3d base, Vec3d right, Vec3d up, Vec3d local, float size, int color
   ) {
      Vec3d var10 = base.add(local);
      float var11 = size * 0.5F;
      buffer.vertex(
            matrix,
            (float)(var10.x - right.x * var11 - up.x * var11),
            (float)(var10.y - right.y * var11 - up.y * var11),
            (float)(var10.z - right.z * var11 - up.z * var11)
         )
         .texture(0.0F, 1.0F)
         .color(color);
      buffer.vertex(
            matrix,
            (float)(var10.x + right.x * var11 - up.x * var11),
            (float)(var10.y + right.y * var11 - up.y * var11),
            (float)(var10.z + right.z * var11 - up.z * var11)
         )
         .texture(1.0F, 1.0F)
         .color(color);
      buffer.vertex(
            matrix,
            (float)(var10.x + right.x * var11 + up.x * var11),
            (float)(var10.y + right.y * var11 + up.y * var11),
            (float)(var10.z + right.z * var11 + up.z * var11)
         )
         .texture(1.0F, 0.0F)
         .color(color);
      buffer.vertex(
            matrix,
            (float)(var10.x - right.x * var11 + up.x * var11),
            (float)(var10.y - right.y * var11 + up.y * var11),
            (float)(var10.z - right.z * var11 + up.z * var11)
         )
         .texture(0.0F, 0.0F)
         .color(color);
   }

   // --- базис камеры ------------------------------------------------------

   private static Vec3d right() {
      Camera var0 = mc.gameRenderer.getCamera();
      float var1 = var0.getYaw() * (float) (Math.PI / 180.0);
      float var2 = var0.getPitch() * (float) (Math.PI / 180.0);
      Vec3d var3 = new Vec3d(-MathHelper.sin(var1) * MathHelper.cos(var2), -MathHelper.sin(var2), MathHelper.cos(var1) * MathHelper.cos(var2));
      Vec3d var4 = var3.crossProduct(new Vec3d(0.0, 1.0, 0.0));
      return var4.lengthSquared() < 1.0E-6 ? new Vec3d(1.0, 0.0, 0.0) : var4.normalize();
   }

   private static Vec3d up(Vec3d right) {
      Camera var1 = mc.gameRenderer.getCamera();
      float var2 = var1.getYaw() * (float) (Math.PI / 180.0);
      float var3 = var1.getPitch() * (float) (Math.PI / 180.0);
      Vec3d var4 = new Vec3d(-MathHelper.sin(var2) * MathHelper.cos(var3), -MathHelper.sin(var3), MathHelper.cos(var2) * MathHelper.cos(var3));
      Vec3d var5 = right.crossProduct(var4);
      return var5.lengthSquared() < 1.0E-6 ? new Vec3d(0.0, 1.0, 0.0) : var5.normalize();
   }

   // --- разряды -----------------------------------------------------------

   private TargetEspLightning.Bolt spawnBolt(float width, float height) {
      ThreadLocalRandom var2 = ThreadLocalRandom.current();
      float var3 = Math.max(0.16F, width * 0.5F);
      float var4 = Math.max(0.6F, height);
      double var5 = var2.nextDouble() * Math.PI * 2.0;
      double var7 = 0.15 + var2.nextDouble() * Math.max(0.2F, var4 - 0.4F);
      Vec3d var9 = new Vec3d(Math.cos(var5) * var3 * 0.7, var7, Math.sin(var5) * var3 * 0.7);
      double var10 = var2.nextDouble() * Math.PI * 2.0;
      double var12 = Math.toRadians((var2.nextDouble() - 0.5) * 150.0);
      Vec3d var14 = new Vec3d(Math.cos(var12) * Math.cos(var10), Math.sin(var12) * 0.8 + 0.25, Math.cos(var12) * Math.sin(var10)).normalize();
      double var15 = 0.35 + var2.nextDouble() * 0.45;
      Vec3d var17 = var9.add(var14.multiply(var15));
      ArrayList<Vec3d> var18 = new ArrayList<>(List.of(var9, var17));
      var18 = this.displace(var18, 0.09, 3);
      ArrayList<List<Vec3d>> var19 = new ArrayList<>();
      int var20 = 1 + var2.nextInt(3);

      for (int var21 = 0; var21 < var20; var21++) {
         int var22 = 1 + var2.nextInt(Math.max(1, var18.size() - 2));
         Vec3d var23 = var18.get(var22);
         Vec3d var24 = this.perpendicular(var14);
         if (var2.nextBoolean()) {
            var24 = var24.multiply(-1.0);
         }

         double var25 = 0.12 + var2.nextDouble() * 0.18;
         Vec3d var27 = var23.add(var24.multiply(var25));
         ArrayList<Vec3d> var28 = new ArrayList<>(List.of(var23, var27));
         var19.add(this.displace(var28, 0.05, 2));
      }

      return new TargetEspLightning.Bolt(var18, var19, 140L + var2.nextLong(160L), var2.nextFloat() * 6.28F);
   }

   private ArrayList<Vec3d> displace(ArrayList<Vec3d> input, double amount, int passes) {
      ArrayList<Vec3d> var5 = new ArrayList<>(input);
      double var6 = amount;

      for (int var8 = 0; var8 < passes; var8++) {
         ArrayList<Vec3d> var9 = new ArrayList<>(var5.size() * 2);

         for (int var10 = 0; var10 < var5.size() - 1; var10++) {
            Vec3d var11 = var5.get(var10);
            Vec3d var12 = var5.get(var10 + 1);
            var9.add(var11);
            Vec3d var13 = var12.subtract(var11);
            if (var13.lengthSquared() < 1.0E-7) {
               var9.add(var11.add(var12).multiply(0.5));
            } else {
               Vec3d var14 = this.perpendicular(var13.normalize());
               double var15 = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2.0 * var6;
               var9.add(var11.add(var12).multiply(0.5).add(var14.multiply(var15)));
            }
         }

         var9.add(var5.get(var5.size() - 1));
         var5 = var9;
         var6 *= 0.5;
      }

      return var5;
   }

   private Vec3d perpendicular(Vec3d dir) {
      Vec3d var2 = Math.abs(dir.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
      Vec3d var3 = dir.crossProduct(var2).normalize();
      Vec3d var4 = dir.crossProduct(var3).normalize();
      double var5 = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
      return var3.multiply(Math.cos(var5)).add(var4.multiply(Math.sin(var5)));
   }

   private float fade(TargetEspLightning.Bolt bolt, long now) {
      float var3 = MathHelper.clamp((float)(now - bolt.spawn) / bolt.life, 0.0F, 1.0F);
      float var4 = var3 < 0.18F ? var3 / 0.18F : 1.0F - (var3 - 0.18F) / 0.82F;
      float var5 = 0.78F + 0.22F * MathHelper.sin((float)now * 0.045F + bolt.phase);
      return MathHelper.clamp(var4, 0.0F, 1.0F) * var5;
   }

   private static final class Bolt {
      private final List<Vec3d> points;
      private final List<List<Vec3d>> branches;
      private final long spawn;
      private final long life;
      private final float phase;

      private Bolt(List<Vec3d> points, List<List<Vec3d>> branches, long life, float phase) {
         this.points = points;
         this.branches = branches;
         this.spawn = System.currentTimeMillis();
         this.life = life;
         this.phase = phase;
      }
   }
}
