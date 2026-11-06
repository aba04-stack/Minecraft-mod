# Living Civilization (`Living Villages 0.15.0`)

**Current version:** `0.15.0`  
**Target game:** Minecraft Java Edition `1.21.1`  
**Mod loader:** NeoForge `21.1.264` (`net.neoforged.moddev 2.0.148`, Parchment `2024.11.17`)  
**Java:** `21` · **Gradle:** `9.2.1` (checksum-verified bootstrap)

**Living Civilization** (текущий идентификатор в кодовой базе: **Living Villages**, `mod_id = livingvillages`) — мод для Minecraft Java Edition, превращающий деревни и окружающий мир в самостоятельную, материально честную симуляцию живых поселений и цивилизаций.

Жители обладают характером, памятью, рабочими ролями и профессиональным опытом, поселения добывают и перерабатывают реальные физические ресурсы (без генерации предметов из воздуха), пошагово возводят дома и фермы, поддерживают естественный рост населения, управляются через избираемого старосту и защищаются от опасного внешнего мира.

---

## Статус сборки и проверок

- **Конфигурация `Minecraft 1.21.1 + NeoForge 21.1.264 + Java 21`:**
  - В GitHub Actions подтверждены: статическая валидация (`python3 tools/validate_project.py`), синтаксический разбор (`JavaSyntaxCheck`), полная сборка `./gradlew build`, внутриигровые тесты `./gradlew runGameTestServer`, smoke-test выделенного сервера (`runServer`) и headless smoke-test экрана старосты (`ElderScreen` в окне `427×240` под Xvfb/Mesa).
  - Полный интерактивный геймплейный цикл в обычном клиенте (`runClient` с живым игроком) остаётся обязательным этапом полевой верификации перед использованием в основном мире.
- **Предварительный ориентир из концепции (`Minecraft 26.3 + NeoForge`):** статус `UNVERIFIED` (не представлен в `gradle.properties`; доступность и требования не подтверждены).
- Мод **не обещает** автоматического переноса на будущие версии Minecraft, фиксированных показателей FPS/TPS на любом ПК или полного отсутствия ошибок без проведения профильных тестов (см. [`docs/VERSION_COMPATIBILITY.md`](docs/VERSION_COMPATIBILITY.md) и [`docs/RELEASE_QUALITY.md`](docs/RELEASE_QUALITY.md)).

---

## Состояние подсистем проекта

Статусы приведены согласно [`docs/ENGINEERING_STANDARDS.md`](docs/ENGINEERING_STANDARDS.md): **статус документации никогда не превышает статус, подтверждённый кодом и проверками**.

### Реализовано в коде и проверено в CI / GameTest (`TESTED` в части GameTest-контрактов, `IMPLEMENTED` / `PROTOTYPE` для полного игрового цикла)

- **Реестр поселений и владение инфраструктурой (`VillageRegistry`, `VillageArea` — `TESTED` в GameTest):** сохранение `UUID` деревень и владельцев физических сундуков/бочек, кроватей и рабочих блоков в `SavedData` с группировкой жителей по `HOME`-якорям и истории `UUID`.
- **Резервирование хранилищ и строительных площадок (`VillageReservations`, `VillageReservationGameTests` — `TESTED` в GameTest):** защита от конкуренции нескольких задач за один сундук и от пересечения активных стройплощадок домов/ферм.
- **Честная физическая экономика (`VillageIndustry`, `VillageCraftingJobs` — `IMPLEMENTED`):** переработка реальных брёвен в доски (`1 → 4`), создание первого сундука (`8` досок), верстака (`4` доски), печи (`8` булыжников), физические рейсы ремесленника за ингредиентами к сундукам и верстаку, плавка реальной руды на реальном топливе в ванильной печи.
- **Лесозаготовка и честное лесовосстановление (`VillagerTreeWork` — `IMPLEMENTED`):** расчистка листвы на линии взгляда, рубка дерева в радиусе `4` блоков и посадка только реальных саженцев из инвентаря, дропа или склада.
- **Добыча видимой руды и камня (`VillagerMining` — `IMPLEMENTED`):** добыча открытой руды и камня (при запасе булыжника `< 16`) настоящей киркой с проверкой `ClipContext` и защитой от копания под ногами.
- **Поэтапное строительство жилья и ферм (`VillageBuilder`, `VillageFarmBuilder` — `IMPLEMENTED`):** возведение хижины 5×5 (`96` досок одного типа + подходящая дверь + красная кровать) и фермы 5×5 (`24` семени + ведро воды с возвратом пустого ведра + компостер + мотыга) по одному блоку.
- **Поддержка естественного размножения (`VillagePopulation` — `IMPLEMENTED`):** физический курьер доставляет еду сверх резерва наиболее голодным взрослым при наличии свободной кровати; рождение остаётся ванильным.
- **Староста деревни и панель управления (`VillageElder`, `LivingVillagesClient` — `IMPLEMENTED`, верстка проверена в headless smoke-test):** выбор старосты с сохранением ванильной профессии, открытие GUI по ПКМ пустой рукой, серверная валидация сетевых пакетов `ElderAction`, переключение приоритетов и заказы дома/фермы.
- **Базовая стража и личность жителей (`VillageDefense`, `VillagerSoul`, `VillagerRoles` — `IMPLEMENTED`):** экипировка реальным оружием/бронёй со склада, ближний бой и отступление при ранении; имена, 6 характеров, настроение, память подарков и обид.

### Новые архитектурные и геймплейные подсистемы Living Civilization (реализованы и проверены в CI / GameTest)

- **Независимое доменное ядро (`com.abs.livingvillages.domain` — `TESTED`):** `VillagerProgressionModel`, `MonsterTacticalModel` и `TickBudgetModel` не имеют зависимостей от `net.minecraft.*` / `net.neoforged.*` (проверяется `tools/test_domain_portability.py`). Подробнее: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/LANGUAGE_PORTABILITY.md`](docs/LANGUAGE_PORTABILITY.md).
- **Минимальная система прокачки профессий жителей (`VillagerProgression` — `TESTED` в GameTest):** уровни 1–5 по каждой рабочей роли, начисление опыта только за подтверждённые завершённые задачи, сохранение опыта при смене роли и версионированная схема `schema_version = 1`. Подробнее: [`docs/PROGRESSION.md`](docs/PROGRESSION.md).
- **Опасный мир и тактический ИИ ванильных монстров (`VillageMonsterAI` — `TESTED` в GameTest):** честное восприятие через `ClipContext.Block.COLLIDER` и ограниченные сигналы активности, лимит попыток пути (`MAX_PATH_RETRIES = 3`), соблюдение `mobGriefing` и тактика зомби, скелетов, криперов, пауков, ведьм и разбойников. Подробнее: [`docs/MONSTERS.md`](docs/MONSTERS.md), [`docs/MONSTER_AI.md`](docs/MONSTER_AI.md).
- **Квоты производительности (`VillageTickBudget` — `IMPLEMENTED`):** лимит проверок ИИ на тик (`max_ai_checks_per_tick`) и распределённое расписание сущностей. Подробнее: [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md), [`docs/PERFORMANCE_BUDGETS.md`](docs/PERFORMANCE_BUDGETS.md).
- **Версионирование схем сохранений (`schema_version = 1` — `TESTED` в GameTest):** безопасная миграция `v0 → v1` с сохранением неизвестных полей и резервной копией `_raw_backup_tag`. Подробнее: [`docs/SAVE_DATA.md`](docs/SAVE_DATA.md).

---

## Сборка и локальные проверки

На системе с JDK 21 и доступом к сети:

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
./gradlew build
./gradlew runGameTestServer
```

Статические проверки (`validate_project.py`, `JavaSyntaxCheck.java`) проверяют инварианты и синтаксис, но **не заменяют** компиляцию с classpath Minecraft/NeoForge и тестирование в игре.

---

## Навигация по документации

### Техническая спецификация Living Civilization (`docs/`)

1. **[Техническое задание (`docs/PROJECT_SPEC.md`)](docs/PROJECT_SPEC.md)** — единый источник требований, матрица статусов, принятые и открытые решения.
2. **[Дорожная карта (`docs/ROADMAP.md`)](docs/ROADMAP.md)** — поэтапный план развития проекта.
3. **[Целевая архитектура (`docs/ARCHITECTURE.md`)](docs/ARCHITECTURE.md)** — разделение доменного ядра и адаптеров, границы модулей и контракты данных.
4. **[ИИ и поведение жителей (`docs/AI_BEHAVIOR.md`)](docs/AI_BEHAVIOR.md)** — восприятие, планирование, исполнение, приоритеты, тайм-ауты и очистка памяти.
5. **[Система прокачки (`docs/PROGRESSION.md`)](docs/PROGRESSION.md)** — минимальная модель опыта и уровней профессий жителей.
6. **[Опасный мир и угрозы (`docs/MONSTERS.md`)](docs/MONSTERS.md)** — роль монстров, рейдов и враждебных фракций в симуляции поселений.
7. **[Детальный ИИ монстров (`docs/MONSTER_AI.md`)](docs/MONSTER_AI.md)** — правила честного восприятия, тактика ванильных мобов и сценарии проверки.
8. **[Переносимость логики на другой язык (`docs/LANGUAGE_PORTABILITY.md`)](docs/LANGUAGE_PORTABILITY.md)** — изоляция доменного ядра, DTO и реальные ограничения переноса.
9. **[Матрица совместимости версий (`docs/VERSION_COMPATIBILITY.md`)](docs/VERSION_COMPATIBILITY.md)** — проверенные и запланированные конфигурации Minecraft, Java и NeoForge.
10. **[Адаптеры версий и загрузчиков (`docs/VERSION_ADAPTERS.md`)](docs/VERSION_ADAPTERS.md)** — локализация платформенного API и регламент обновления версий.
11. **[Производительность (`docs/PERFORMANCE.md`)](docs/PERFORMANCE.md)** — троттлинг тиков, квоты, кэширование, потокобезопасность и удалённая симуляция.
12. **[Бюджеты производительности (`docs/PERFORMANCE_BUDGETS.md`)](docs/PERFORMANCE_BUDGETS.md)** — методика замеров MSPT/TPS/RAM и 7 нагрузочных профилей.
13. **[Экономика ресурсов (`docs/RESOURCE_ECONOMY.md`)](docs/RESOURCE_ECONOMY.md)** — запасы поселения, динамические квоты сборщиков и ограниченные серии добычи/сбора.
14. **[Сохранения и миграции (`docs/SAVE_DATA.md`)](docs/SAVE_DATA.md)** — версионирование схем NBT/`SavedData`, миграции и защита данных игрока.
15. **[Тестирование (`docs/TESTING.md`)](docs/TESTING.md)** — уровни тестов, тест-кейсы всех подсистем и протоколы проверок.
16. **[Качество релизов (`docs/RELEASE_QUALITY.md`)](docs/RELEASE_QUALITY.md)** — критерии стабильности и условия блокировки релиза (Release Gate).
17. **[Инженерные стандарты (`docs/ENGINEERING_STANDARDS.md`)](docs/ENGINEERING_STANDARDS.md)** — система статусов, инварианты кода и правила ведения документации.
18. **[Известные проблемы (`docs/KNOWN_ISSUES.md`)](docs/KNOWN_ISSUES.md)** — реально обнаруженные ограничения и непроверенные сценарии.

### Документация в корне репозитория

- **[Подробное руководство по механикам (`README_RU.md`)](README_RU.md)**
- **[Архитектура и материальный баланс (`ARCHITECTURE.md`)](ARCHITECTURE.md)**
- **[Список проверок (`BUILD_CHECKLIST.md`)](BUILD_CHECKLIST.md)** и **[Отчёт проверки (`TEST_REPORT_RU.md`)](TEST_REPORT_RU.md)**
- **[История изменений (`CHANGELOG_RU.md`)](CHANGELOG_RU.md)**, **[Памятка разработчику (`HANDOFF_TO_NEXT_AI_RU.md`)](HANDOFF_TO_NEXT_AI_RU.md)** и **[Исследование идей модов (`RESEARCH_IDEAS_RU.md`)](RESEARCH_IDEAS_RU.md)**
19. **[Пещерная разведка и укрепления (docs/CAVE_EXPLORATION_AND_FORTIFICATIONS.md)](docs/CAVE_EXPLORATION_AND_FORTIFICATIONS.md)** — состояние памяти маршрута шахтёра, настоящих факелов, ограничений разведки и план безопасных оград.
