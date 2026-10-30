# Сборка ZenithDLC

Мод под **Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.6+1.21.11** (Java 21).
Внутри jar уже лежат вложенные моды (`META-INF/jars`): Baritone, ViaFabricPlus, AltsMod,
player-animation-lib, zenith-addon-api, figura-runtime — отдельно их ставить не нужно.

## Готовый jar (без локальной сборки)

Каждый push в ветку `arena/**` или `main` запускает GitHub Actions
(`.github/workflows/build-jar.yml`). Он собирает мод, проверяет содержимое jar
(`fabric.mod.json`, миксины, главный класс, вложенные моды) и публикует файл в релиз:

- релизы: https://github.com/v08192012-bot/opus/releases (тег `build-<номер сборки>`, всегда есть `latest`)
- артефакты сборки: вкладка **Actions** → нужный запуск → `zenith-jar`
- лог проверки и SHA-256 — в Summary запуска

Скачанный `zenith-1.0-SNAPSHOT.jar` положить в `.minecraft/mods` и запустить профиль Fabric 1.21.11.

## Локальная сборка

```bash
./gradlew build          # -> build/libs/zenith-1.0-SNAPSHOT.jar (remapped, это и есть мод)
```

Требуется JDK 21. Первый запуск скачивает Minecraft, маппинги Yarn, Fabric API и Loom
(нужен доступ к `maven.fabricmc.net`, `libraries.minecraft.net`, `repo1.maven.org`).

Полезное:

```bash
./gradlew runClient      # dev-запуск клиента с модом
./gradlew build --refresh-dependencies
```

`build/libs` содержит три файла: `zenith-1.0-SNAPSHOT.jar` (то, что нужно игроку),
`-sources.jar` и `-dev.jar` (только для разработки).
