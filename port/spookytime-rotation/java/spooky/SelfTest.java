package spooky;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Самопроверка извлечённой модели: гоняет эталонные тики из motor.bin через
 * forward-pass и сравнивает с записанными ответами.
 *
 *   java -cp out spooky.SelfTest model/motor.bin
 */
public final class SelfTest {
    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args.length > 0 ? args[0] : "model/motor.bin");
        SpookyModel model = SpookyModel.load(path);

        System.out.printf("модель: %d признаков, MLP %d, GRU %dx%d, смесь %d, latency %d%n",
                model.nFeatures, model.mlpHidden, model.gruLayers, model.gruHidden, model.mix, model.latency);
        System.out.printf("огибающая: %s%n", model.hasEnvelope()
                ? model.envelopeCapsYaw.length + "/" + model.envelopeCapsPitch.length + " корзин"
                : "выключена");

        if (model.goldenCount() == 0) {
            System.out.println("в модели нет эталонных тиков -- проверять нечего");
            System.exit(2);
        }

        double worst = model.selfCheck();
        System.out.printf("эталонных тиков: %d, максимальное расхождение: %.3e%n", model.goldenCount(), worst);
        if (worst > 1.0E-9) {
            System.out.println("ОШИБКА: forward-pass не совпал с эталоном");
            System.exit(1);
        }

        // быстрый прогон рантайма: цель прямо перед игроком, смотрим, что ротация доводится
        SpookyRotation rotation = new SpookyRotation(model, MathX.pixelStep(0.5), new java.util.Random(1));
        Rotation current = new Rotation(0.0F, 0.0F);
        SpookyRotation.Target target = new SpookyRotation.Target(42, true, 3.0, 0.0, 3.0, 3.6, 1.8, 3.6);
        SpookyRotation.PlayerState player = new SpookyRotation.PlayerState(
                0.0, 1.62, 0.0, true, false, false, false, false, true, false, false, 1.0, 20);

        double total = 0.0;
        int ticks = 0;
        for (int tick = 0; tick < 40; tick++) {
            Rotation next = rotation.tick(current, target, player, tick);
            total += next.distanceTo(current);
            current = next;
            ticks++;
            if (tick % 8 == 0) {
                System.out.println("  тик " + tick + ": " + next);
            }
        }
        System.out.printf("рантайм: %d тиков, суммарно повернулись на %.1f град, итог %s%n", ticks, total, current);
        if (total <= 0.0) {
            System.out.println("ОШИБКА: ротация не двигается");
            System.exit(1);
        }
        System.out.println("OK");
    }
}
