package org.zenith.utility.game;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.Text;
import org.zenith.utility.misc.ColorUtils;

/**
 * Здоровье игроков из серверных неймтегов.
 *
 * Ванильный клиент не получает здоровье чужих игроков: {@code getHealth()} у них всегда 20,
 * поэтому единственный честный источник — то, что сервер сам рисует над головой.
 * Такие серверы (FunTime-подобные, RPG) обычно вешают второй строкой неймтега objective
 * BELOW_NAME с числовым форматом вида «20 Здоровья», «❤ 20» или просто «20»,
 * остальные пишут здоровье прямо в display name игрока.
 *
 * Значение читается из обоих мест и приводится к числу. Если сервер ничего не показал —
 * возвращается {@link #UNKNOWN}, и вызывающая сторона решает, что рисовать (обычно «?»).
 */
public final class NameTagHealth {
   /** Здоровье неизвестно (нет неймтега, игрок в невидимости, другой сервер). */
   public static final float UNKNOWN = Float.NaN;

   /** Кэш на 100 мс: ESP обновляет записи каждый тик, а форматирование score аллоцирует. */
   private static final long TTL_MS = 100L;
   private static final int MAX_CACHED = 512;
   private static final Map<UUID, NameTagHealth.Cached> cache = new ConcurrentHashMap<>();

   private static final Pattern NUMBER = Pattern.compile("\\d{1,4}(?:[.,]\\d{1,2})?");
   private static final Pattern HEALTH_HINT = Pattern.compile(
      "здоров|здор|хп|health|hp|❤|♥", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
   );

   private NameTagHealth() {
      throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
   }

   /** Здоровье игрока из серверного неймтега либо {@link #UNKNOWN}. */
   public static float read(PlayerEntity player) {
      if (player == null) {
         return UNKNOWN;
      }

      UUID var1 = player.getUuid();
      long var2 = System.currentTimeMillis();
      NameTagHealth.Cached var4 = cache.get(var1);
      if (var4 != null && var2 - var4.at < TTL_MS) {
         return var4.value;
      }

      float var5 = compute(player);
      if (cache.size() > MAX_CACHED) {
         cache.clear();
      }

      cache.put(var1, new NameTagHealth.Cached(var5, var2));
      return var5;
   }

   /** Известно ли здоровье игрока (false — рисуем «?»). */
   public static boolean known(PlayerEntity player) {
      return player != null && !player.isInvisible() && !Float.isNaN(read(player));
   }

   /**
    * Строка для чипа: число из неймтега, «?» для невидимого игрока
    * и {@code null}, если показывать нечего.
    */
   public static String display(PlayerEntity player, float fallback) {
      if (player != null && player.isInvisible()) {
         return "?";
      }

      float var2 = player == null ? UNKNOWN : read(player);
      if (!Float.isNaN(var2)) {
         return format(var2);
      }

      return fallback >= 0.0F ? format(fallback) : null;
   }

   public static String format(float value) {
      return String.format(Locale.ROOT, "%.1f", value).replace(",", ".").replace(".0", "");
   }

   private static float compute(PlayerEntity player) {
      float var1 = fromBelowName(player);
      return Float.isNaN(var1) ? fromDisplayName(player) : var1;
   }

   /** Вторая строка неймтега: objective BELOW_NAME с числовым форматом. */
   private static float fromBelowName(PlayerEntity player) {
      try {
         if (player.getEntityWorld() == null) {
            return UNKNOWN;
         }

         ScoreboardObjective var1 = player.getEntityWorld().getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.BELOW_NAME);
         if (var1 == null) {
            return UNKNOWN;
         }

         ReadableScoreboardScore var2 = player.getEntityWorld().getScoreboard().getScore(player, var1);
         if (var2 == null) {
            return UNKNOWN;
         }

         Text var3 = ReadableScoreboardScore.getFormattedScore(var2, var1.getNumberFormatOr(StyledNumberFormat.EMPTY));
         return var3 == null ? UNKNOWN : parse(var3.getString(), true);
      } catch (RuntimeException var4) {
         return UNKNOWN;
      }
   }

   /** Здоровье, дописанное сервером прямо в ник (в том числе второй строкой через \n). */
   private static float fromDisplayName(PlayerEntity player) {
      float var1 = parse(player.getDisplayName() == null ? null : player.getDisplayName().getString(), false);
      if (!Float.isNaN(var1)) {
         return var1;
      }

      return parse(player.getCustomName() == null ? null : player.getCustomName().getString(), false);
   }

   /**
    * Число из строки неймтега.
    *
    * @param allowBareNumber разрешить строку, целиком состоящую из числа (обычный BELOW_NAME),
    *                        иначе требуем рядом слово-подсказку вроде «Здоровья» или сердце
    */
   public static float parse(String raw, boolean allowBareNumber) {
      if (raw == null || raw.isEmpty()) {
         return UNKNOWN;
      }

      String var2 = ColorUtils.method6438(raw);
      if (var2 == null) {
         return UNKNOWN;
      }

      var2 = var2.replace('\u00A0', ' ').replace('\u200B', ' ').strip();
      if (var2.isEmpty()) {
         return UNKNOWN;
      }

      boolean var3 = HEALTH_HINT.matcher(var2).find();
      if (!var3) {
         if (!allowBareNumber || !NUMBER.matcher(var2).matches()) {
            return UNKNOWN;
         }
      }

      Matcher var4 = NUMBER.matcher(var2);

      while (var4.find()) {
         try {
            float var5 = Float.parseFloat(var4.group().replace(',', '.'));
            if (var5 >= 0.0F && var5 <= 1000.0F) {
               return var5;
            }
         } catch (NumberFormatException var6) {
         }
      }

      return UNKNOWN;
   }

   private static final class Cached {
      private final float value;
      private final long at;

      private Cached(float var1, long var2) {
         this.value = var1;
         this.at = var2;
      }
   }
}
