# Матрица совместимости версий Minecraft и загрузчиков (`VERSION_COMPATIBILITY`)

> **Связанные документы:** [Архитектура адаптеров версий (`VERSION_ADAPTERS.md`)](VERSION_ADAPTERS.md) · [Техническое задание (`PROJECT_SPEC.md`)](PROJECT_SPEC.md) · [Известные проблемы (`KNOWN_ISSUES.md`)](KNOWN_ISSUES.md) · [Тестирование (`TESTING.md`)](TESTING.md) · [Качество релизов (`RELEASE_QUALITY.md`)](RELEASE_QUALITY.md)

Настоящий документ фиксирует фактически настроенные, проверенные и предварительные целевые конфигурации окружения для **Living Civilization** (`livingvillages`).

---

## 1. Правила ведения матрицы совместимости

1. **Поддерживаются только реально собираемые и проверяемые версии:** конфигурация не считается поддерживаемой только потому, что её номер упомянут в концепции или прописан в `gradle.properties`.
2. **Запрет авансового статуса `TESTED`:** ни одна строка матрицы не может получить статус `TESTED`, пока на данной комбинации версий реально не выполнены `./gradlew build`, запуск клиента/сервера и функциональные тесты из [`TESTING.md`](TESTING.md).
3. **Честное отношение к переносу версий:** выделение версионных адаптеров ([`VERSION_ADAPTERS.md`](VERSION_ADAPTERS.md)) сокращает объём ручных правок при выходе новых версий Minecraft и NeoForge, но **не делает обновление автоматическим и не гарантирует совместимость с будущими релизами без ручной адаптации и проверки**.

---

## 2. Матрица совместимости

| Minecraft version | Java version | Loader и версия loader | Версия мода | Статус | Результат сборки | Результат запуска | Результат функциональных тестов | Известные ограничения |
|---|---|---|---|---|---|---|---|---|
| `1.21.1` | `Java 21` | `NeoForge 21.1.264` (плагин `net.neoforged.moddev 2.0.148`, Parchment `2024.11.17`) | `0.15.0` | `BLOCKED` / `UNVERIFIED` | **В архиве 0.15.0:** синтаксический разбор `JavaSyntaxCheck` и `validate_project.py` пройдены (`PASS`), но полная сборка `./gradlew build` с classpath Minecraft/NeoForge не завершена.<br>**В текущем репозитории (`8635934`):** `FAIL` (файлы загружены в корень репозитория без структуры `src/main/java/...`, у `gradlew` сброшен бит `+x`, в среде аудита отсутствуют JDK 21 и доступ к `services.gradle.org`). | `NOT RUN` (не запускался ни в `runClient`, ни в `runServer`) | **Статические регрессионные тесты (`test_*.py` в архиве 0.15.0):** `PASS` (4 из 4).<br>**Игровые функциональные тесты в мире:** `NOT RUN`. | 1. Нарушена структура каталогов при загрузке через веб-интерфейс GitHub (`KNOWN_ISSUES.md`).<br>2. Требуется первая полная компиляция с разрешением символов Minecraft 1.21.1 / NeoForge 21.1.264.<br>3. Вся логика 0.15.0 работает только в загруженных чанках вокруг игрока. |
| `26.3` (концептуальная цель из ТЗ) | `UNVERIFIED` | `NeoForge (версия не определена)` | — | `UNVERIFIED` | `NOT RUN` | `NOT RUN` | `NOT RUN` | Упомянута как предварительная цель в задании. В конфигурации проекта (`gradle.properties`) отсутствует; фактическая доступность версии, официальные системные требования и API не подтверждены. |

---

## 3. Сведения о текущем техническом базовом профиле (`gradle.properties`)

Фактически прописанные в файлах сборки репозитория параметры:

- **`minecraft_version`:** `1.21.1` (`minecraft_version_range = [1.21.1]`)
- **`neo_version`:** `21.1.264` (`loader_version_range = [1,)`)
- **`parchment_minecraft_version`:** `1.21.1` (`parchment_mappings_version = 2024.11.17`)
- **Версия Java Toolchain (`build.gradle`):** `JavaLanguageVersion.of(21)`
- **Версия Gradle (`gradlew`, `gradle-wrapper.properties`):** `9.2.1` (SHA-256: `72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f`)
- **Идентификатор и версия мода:** `mod_id = livingvillages`, `mod_version = 0.15.0`, группа `com.abs.livingvillages`
- **Версия схемы сохранений (`schema_version`):** в `0.15.0` не выделена (используются неверсионированные ключи `CompoundTag` в `Villager.getPersistentData()`; внедрение явной версии схемы запланировано в [`SAVE_DATA.md`](SAVE_DATA.md)).

---

## 4. Условия перевода конфигурации `1.21.1 / NeoForge 21.1.264` в статус `TESTED`

Конфигурация `Minecraft 1.21.1 + NeoForge 21.1.264 + Java 21` может быть переведена из статуса `BLOCKED` / `UNVERIFIED` в `IMPLEMENTED`, а затем в `TESTED` только после последовательного выполнения следующих шагов:

1. Восстановление штатной структуры каталогов (`src/main/java/com/abs/livingvillages/`, `src/main/resources/...`, `src/main/templates/...`, `tools/`, `gradle/wrapper/`, `.github/workflows/`).
2. Успешное прохождение всех проверок `python3 tools/validate_project.py` и `java tools/JavaSyntaxCheck.java src/main/java`.
3. Успешное выполнение `./gradlew build` с формированием `build/libs/livingvillages-0.15.0.jar` (переводит сборку в статус `IMPLEMENTED`).
4. Успешный запуск `./gradlew runClient` и `./gradlew runServer` (проверка отсутствия client-only вызовов на сервере).
5. Полное прохождение обязательных игровых сценариев из [`TESTING.md`](TESTING.md) и [`RELEASE_QUALITY.md`](RELEASE_QUALITY.md) с записью результатов в отчёт.
