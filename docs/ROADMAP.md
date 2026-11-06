# Дорожная карта разработки Living Civilization

> **Связанные документы:** [Техническое задание (`PROJECT_SPEC.md`)](PROJECT_SPEC.md) · [Инженерные стандарты (`ENGINEERING_STANDARDS.md`)](ENGINEERING_STANDARDS.md) · [Известные проблемы (`KNOWN_ISSUES.md`)](KNOWN_ISSUES.md) · [Тестирование (`TESTING.md`)](TESTING.md) · [Качество релизов (`RELEASE_QUALITY.md`)](RELEASE_QUALITY.md) · [Экономика ресурсов (`RESOURCE_ECONOMY.md`)](RESOURCE_ECONOMY.md)

Настоящая дорожная карта описывает последовательность этапов превращения прототипа `Living Villages 0.15.0` в полноценную, стабильную и расширяемую систему **Living Civilization**.

> **Правило планирования:** этапы упорядочены по техническим зависимостям, без выдуманных календарных дат и сроков. Статус `TESTED` присваивается только после успешного прогона автоматических проверок, сборки `./gradlew build` и внутриигровых тестов `./gradlew runGameTestServer` в CI.

---

## Этап 0. Восстановление структуры репозитория и воспроизводимой сборки (`TESTED`)

Инфраструктурные блокеры плоской выгрузки устранены: стандартная структура каталогов восстановлена, биты исполнения `+x` выставлены, полная сборка и тесты проходят в GitHub Actions CI.

### Подэтап 0.1. Восстановление дерева каталогов проекта (`TESTED`)
- **Результат:** все исходные файлы размещены в штатных каталогах Gradle/NeoForge (`src/main/java/com/abs/livingvillages/`, `src/main/java/com/abs/livingvillages/domain/`, `src/main/resources/assets/livingvillages/lang/`, `src/main/resources/data/livingvillages/structure/`, `src/main/templates/META-INF/`, `tools/`, `gradle/wrapper/`, `.github/workflows/build.yml`).
- **Проверка:** `python3 tools/validate_project.py`, `java tools/JavaSyntaxCheck.java src/main/java`, все 13 скриптов `tools/test_*.py` и `sh -n gradlew BUILD_LOCAL.sh` проходят успешно (`PASS`).

### Подэтап 0.2. Первая полная компиляция с Minecraft/NeoForge classpath (`TESTED`)
- **Результат:** `./gradlew build` с JDK 21 (Temurin 21) и контрольной суммой Gradle `9.2.1` успешно компилирует проект и собирает `build/libs/livingvillages-0.15.0.jar`.

### Подэтап 0.3. Дымовая проверка в клиенте, на выделенном сервере и GameTestServer (`TESTED`)
- **Результат:** в CI автоматически выполняются `./gradlew runGameTestServer` (24 внутриигровых `@GameTest`), запуск выделенного сервера (`./gradlew runServer`) и headless-клиента с программным OpenGL (`tools/smoke_test_client.sh`, проверяющий `ElderScreen` и сетевые `StreamCodec`).

---

## Этап 1. Архитектурный фундамент, сохранения и тестовый контур (`TESTED`)

### Подэтап 1.1. Выделение независимого доменного слоя (`TESTED`)
- **Результат:** выделен пакет `com.abs.livingvillages.domain` (`VillagerProgressionModel`, `MonsterTacticalModel`, `TickBudgetModel`, `SettlementSimulationModel`, `SettlementPorts`) без единого импорта `net.minecraft.*` / `net.neoforged.*`.
- **Проверка:** `tools/test_domain_portability.py` контролирует отсутствие платформенных импортов и чистоту DTO/портовых контрактов.

### Подэтап 1.2. Версионированная схема сохранений и миграции (`TESTED`)
- **Результат:** внедрён `schema_version = 1` в `VillagerProgression`, `VillageRegistry` и `VillagerSoul` с сохранением неизвестных полей и резервной копией `_raw_backup_tag`.
- **Проверка:** внутриигровые тесты `villagerProgressionPreservesSkillAcrossRoleChangesAndMigratesSchema` и `portsAdapterSoulMigrationElderSkillSummaryAndAutoNeighborTradeWorkInWorld`.

### Подэтап 1.3. Изоляция версионных адаптеров Minecraft/NeoForge (`TESTED`)
- **Результат:** в `com.abs.livingvillages.domain.SettlementPorts` определены платформо-независимые интерфейсы `WorldAccessPort`, `StorageAccessPort`, `AgentControlPort` и `PersistencePort`, а в `VillageRegistry` реализованы их адаптеры для `ServerLevel` (см. [`VERSION_ADAPTERS.md`](VERSION_ADAPTERS.md)).
- **Проверка:** внутриигровой тест `portsAdapterSoulMigrationElderSkillSummaryAndAutoNeighborTradeWorkInWorld`.

### Подэтап 1.4. Базовое профилирование и телеметрия нагрузки (`TESTED`)
- **Результат:** реализованы `TickBudgetModel.TickTelemetrySnapshot`, `VillageTickBudget.recordSubsystemNanos` и `VillageTickBudget.snapshotTelemetry`, подключённые к `LivingVillages.onServerTick` и `VillageMonsterAI.onEntityTick`.
- **Проверка:** внутриигровые тесты `tacticalMonsterAiRespectsHonestLineOfSightAndTickBudget` и `orphanedFarmProjectResumesAndParallelCraftingTelemetryAndTradeRulesHold`.

---

## Этап 2. Единый реестр поселений и стабилизация ядра `0.15.0` (`TESTED`)

### Подэтап 2.1. Реестр поселений на уровне мира (`TESTED`)
- **Результат:** `VillageRegistry` (`SavedData`) поддерживает устойчивые `UUID` поселений, привязку к `HOME`-якорям и владение сундуками, кроватями и рабочими блоками без аннексии ресурсов соседней деревни.
- **Проверка:** внутриигровые тесты `nearbyVillagesKeepDistinctIdentitiesAndDoNotAnnexNeighborStorage`, `villagesMaintainIdentityWhenResidentsRelocateHomes`, `relocatedHomelessVillageKeepsDistinctIdentityNearNeighbor` и `twoNeighboringVillagesHaveIndependentEldersOrdersAndExecuteHonestSurplusTrade`.

### Подэтап 2.2. Единый планировщик заказов и резервирование ресурсов (`TESTED`)
- **Результат:** `VillageReservations` обеспечивает атомарное резервирование сундуков и строительных площадок с очисткой при завершении задачи или разрушении контейнера, а `VillageRegistry` хранит контрольные точки `houseCheckpoints` и `farmCheckpoints` для передачи прерванной стройки свободному жителю.
- **Проверка:** внутриигровые тесты `competingStorageClaimsRejectSecondWorkerAndReleaseCleanly`, `destroyedChestClearsDanglingStorageReservation`, `orphanedHouseProjectIsResumedByReplacementBuilderInSameVillage` и `orphanedFarmProjectResumesAndParallelCraftingTelemetryAndTradeRulesHold`.

### Подэтап 2.3. Стабилизация производственной цепочки и поэтапного строительства (`TESTED`)
- **Результат:** системы строителя домов (`VillageBuilder`), ферм (`VillageFarmBuilder`), ремесленника (`VillageCraftingJobs`), промышленности (`VillageIndustry`), курьера еды (`VillagePopulation`) и старосты (`VillageElder`) работают на честных физических ресурсах и поддерживают параллельный крафт каменных/железных предметов во время стройки.
- **Проверка:** все 13 регрессионных скриптов `tools/test_*.py` и 19 внутриигровых `@GameTest` в `VillageReservationGameTests`.

---

## Этап 3. Система прокачки профессий жителей (`TESTED`)

### Подэтап 3.1. Доменная модель опыта и уровней профессий (`TESTED`)
- **Результат:** в `VillagerProgressionModel` реализована детерминированная модель уровней `1..5` (пороги `0, 10, 25, 50, 90 XP`) для 7 ролей с анти-эксплойт кулдауном и сохранением опыта при смене роли.

### Подэтап 3.2. Сохранение и миграция данных прокачки (`TESTED`)
- **Результат:** `VillagerProgression.ensureMigratedTag` мигрирует старые записи `v0 -> v1` без потери неизвестных полей и с резервным сохранением повреждённых тегов в `_raw_backup_tag`.

### Подэтап 3.3. Начисление опыта за подтверждённые рабочие задачи (`TESTED`)
- **Результат:** `VillagerProgression.recordCompletedWork` подключён во всех 9 рабочих классах (`VillagerTreeWork`, `VillagerMining`, `VillageBuilder`, `VillageFarmBuilder`, `VillageCraftingJobs`, `VillageDefense`, `VillagePopulation`, `VillageLogistics`, `VillagerAutonomy`).

### Подэтап 3.4. Малые объяснимые бонусы мастерства и отображение в UI (`TESTED`)
- **Результат:** `VillagerProgression.adjustedStepTicks` умеренно ускоряет шаги строительства и ремесла (без создания бесплатных предметов), `guardRetreatRatio` корректирует порог тактического отступления стражи, а уровень мастера отображается как в диалоге жителя (`VillagerSoul`), так и в сводке панели старосты (`VillageElder.createPanelSnapshot`).
- **Проверка:** внутриигровые тесты `villagerProgressionPreservesSkillAcrossRoleChangesAndMigratesSchema`, `orphanedHouseProjectIsResumedByReplacementBuilderInSameVillage` и `portsAdapterSoulMigrationElderSkillSummaryAndAutoNeighborTradeWorkInWorld`.

---

## Этап 4. Опасный мир и тактический ИИ ванильных монстров (`TESTED`)

### Подэтап 4.1. Подсистема честного восприятия и тактических состояний мобов (`TESTED`)
- **Результат:** `MonsterTacticalModel` и `VillageMonsterAI` реализуют проверку дистанции и прямой видимости (`ClipContext.Block.COLLIDER`), машину состояний (`STATE_IDLE`, `STATE_ALERTED`, `STATE_ENGAGING`, `STATE_DISENGAGE`), лимит `MAX_PATH_RETRIES = 3`, `DEFAULT_STATE_TIMEOUT_TICKS = 200L` и квоту `VillageTickBudget`.

### Подэтап 4.2. Тактическое поведение зомби и скелетов (`TESTED`)
- **Результат:** реализованы групповой штурм зомби (`ACTION_COORDINATED_PRESS`) и удержание дистанции скелетами (`ACTION_HOLD_RANGE_OR_STRAFE`).

### Подэтап 4.3. Тактическое поведение криперов, пауков и ведьм (`TESTED`)
- **Результат:** реализованы учёт `GameRules.RULE_MOBGRIEFING` для криперов (`ACTION_CAUTIOUS_APPROACH` / `ACTION_BREACH_POSITION`), вертикальный обход пауков (`ACTION_VERTICAL_FLANK`) и поддержка раненых союзников ведьмами (`ACTION_SUPPORT_ALLIES` с `MobEffects.REGENERATION` и `MobEffects.DAMAGE_RESISTANCE`).
- **Проверка:** внутриигровой тест `monsterArchetypesWitchAllySupportCreeperMobGriefingAndVisitCatchUpWorkInWorld`.

### Подэтап 4.4. Развитие обороны поселения (`TESTED`)
- **Результат:** `VillageDefense` поднимает тревогу поселения (`VillageRegistry.raiseVillageAlarm`), звонит в ближайший колокол (`Blocks.BELL`), уводит мирных жителей и детей к домашним кроватям (`evacuateNearbyCivilians`) и выставляет приоритетный запрос на ковку мечей (`setGuardEquipmentRequested`), который обрабатывается `VillageCraftingJobs.chooseRecipe`.
- **Проверка:** внутриигровые тесты `villageAlarmEvacuatesCiviliansAndDomainSimulationHonorsResourceConservation` и `monsterArchetypesWitchAllySupportCreeperMobGriefingAndVisitCatchUpWorkInWorld`.

---

## Этап 5. Удалённая симуляция, разбойники и межпоселенческое взаимодействие (`TESTED`)

### Подэтап 5.1. Честная упрощённая симуляция удалённых поселений (`TESTED`)
- **Результат:** `VillageRegistry.recordVisitAndRunCatchUp` (вызываемый в `LivingVillages.onServerTick`) и `applyCatchUpSmeltingIfEligible` выполняют догоняющую плавку по реальному времени отсутствия только при наличии в поселении печи, сырой руды (`Items.RAW_IRON`), угля (`Items.COAL`) и места в сундуке.
- **Проверка:** внутриигровые тесты `catchUpSmeltingRaiderSquadRetreatAndGuardEquipmentRequestWorkInWorld` и `monsterArchetypesWitchAllySupportCreeperMobGriefingAndVisitCatchUpWorkInWorld`.

### Подэтап 5.2. Организованные разбойники и рейдовые отряды (`TESTED`)
- **Результат:** `SettlementSimulationModel.evaluateRaiderSquad` и `VillageMonsterAI.evaluateRaiderSquadTactics` (подключённый к `evaluateMobTactics` для всех `Raider`) учитывают численность отряда, ожидание разведки и организованное отступление со сбросом цели при потере `>= 50%` состава.
- **Проверка:** внутриигровой тест `catchUpSmeltingRaiderSquadRetreatAndGuardEquipmentRequestWorkInWorld`.

### Подэтап 5.3. Межпоселенческое взаимодействие и дипломатия (`TESTED`)
- **Результат:** `VillageRegistry.tryTradeWithKnownNeighbors` (вызываемый в `LivingVillages.runIndustryInActiveAreas`), `executeNeighborTradeIfEligible` и `executeBiomeResourceTradeIfEligible` выполняют честный обмен излишками хлеба и досок `1:1`, а также биомными строительными ресурсами (`SANDSTONE` / `COBBLESTONE` ↔ `OAK_PLANKS`) между сундуками мирных соседних деревень и обновляют дипломатический рейтинг `diplomaticStandings` в `SavedData`.
- **Проверка:** внутриигровые тесты `twoNeighboringVillagesHaveIndependentEldersOrdersAndExecuteHonestSurplusTrade`, `portsAdapterSoulMigrationElderSkillSummaryAndAutoNeighborTradeWorkInWorld` и `interactiveRequestPreferencesLivestockCareTrustAndElderLivestockSummaryWorkInWorld`.

### Этап 6. Полный аудит (30 пунктов), биомная адаптация, 16 цветов кроватей и стабилизация ИИ (`TESTED`)
- **Результат:**
  - Поддержка строительства домов из биомных блоков (`SANDSTONE`, `CUT_SANDSTONE`, `RED_SANDSTONE`, `COBBLESTONE`, `STONE_BRICKS`, `TERRACOTTA`, `MUD_BRICKS`, `SNOW_BLOCK`) и всех 16 цветов кроватей (`ALL_BED_ITEMS`, `selectAvailableBedItem`, `selectAvailableDoorItem`).
  - Развитие поселений в пустыне без деревьев: честное сжатие `4 SAND -> 1 SANDSTONE` (`VillageIndustry.compressSandToSandstoneIfEligible`), добыча песчаника и терракоты шахтёрами (`VillagerMining`) и межпоселенческий обмен песчаника/булыжника на доски (`VillageRegistry.executeBiomeResourceTradeIfEligible`).
  - Арбитраж с ванильным ИИ (`shouldYieldToVanillaVillagerAi`, `shouldYieldToVanillaAnimalAi`), защита от зависания задач (`VillagerRoles.recoverIfTaskStuck`, `MAX_TASK_STUCK_TICKS = 400`) и защита от метания между профессиями (`MIN_ROLE_TENURE_TICKS = 600`).
- **Проверка:** внутриигровые тесты `twoNeighboringVillagesHaveIndependentEldersOrdersAndExecuteHonestSurplusTrade` и `interactiveRequestPreferencesLivestockCareTrustAndElderLivestockSummaryWorkInWorld` (всего 24 внутриигровых `@GameTest`).
---

## Этап 7. Спросо-ориентированная экономика и непрерывные рабочие серии (`PARTIALLY TESTED / CI_PENDING`)

> Этап реализован в `feature/demand-driven-village-economy` от `arena/fbf16766-minecraft-mod`. CI run [#150](https://github.com/aba04-stack/Minecraft-mod/actions/runs/42242949596) для коммита `a1621e247ced9d4c7792bf711a94772ae0bd519f` прошёл сборку, 26 GameTests, smoke-test сервера и headless-клиента. Ветка остаётся отдельной; `arena`, `main` и опубликованный релиз не изменены.

### Подэтап 7.1. Первичная оценка запасов и динамические квоты рабочих (`TESTED`)
- **Результат:** добавлены независимая доменная модель `VillageResourceDemandModel` и серверный адаптер `VillageResourcePlanner`. Они считают реальные запасы дерева, каменных материалов, руды/металлов и еды, проверяют локальные источники и выдают желаемые квоты лесорубов, шахтёров и фермеров.
- **Ограничения первой версии:** модель укрупнённая; не ведёт отдельный баланс всех видов руды, топлива, семян и зарезервированных материалов. Снимок кэшируется на 100 игровых тиков; назначение ролей выполняется по одному жителю за проход.
- **Проверка:** `resourceDemandScalesWorkforceToRealStockAndLocalSources` прошёл в CI run #150 (26/26 GameTests).

### Подэтап 7.2. Завершение связной добывающей/сборочной серии (`TESTED` для базовых партий)
- **Результат:** лесоруб фиксирует ограниченную очередь связного дерева (до 512 брёвен), шахтёр — жилу одного типа руды (до 128 блоков), фермер собирает до 4 зрелых культур за проход и повторяет цикл до завершения доступного урожая. Реальные инструменты, семена, видимость, досягаемость и правила мира сохраняются.
- **Проверка:** `workersProcessConnectedTreesOreVeinsAndWholeRipeCropBatches` прошёл в CI run #150 (26/26 GameTests).

 
### Подэтап 7.4. Полный начальный цикл фермерского поля (`IMPLEMENTED / CI_PENDING`)
- **Результат:** фермер обнаруживает принадлежащие деревне зрелые культуры и пустые освещённые грядки; собирает/пересаживает урожай и засевает пустое поле партиями до 4. При отсутствии посадочного материала он резервирует собственный склад и забирает до 16 реальных семян/посадочных клубней обычной навигацией. Без ресурсов поле не меняется.
- **Проверка:** добавлены GameTests `farmerPlantsEmptyOwnedFarmlandFromRealSeedsInBoundedBatches` и проверка срока резерва `farmer_seed_fetch`; последний полный прогон выявил сбой существующего теста изоляции соседей. После очистки временных тестовых жителей нужен повторный чистый CI run.

### Подэтап 7.3. Баланс экономики и нагрузочные испытания (`PLANNED`)
- Разделить остатки железа, меди, золота, угля/топлива, инструментов, семян и строительных материалов; учитывать резервы заказов без двойного счёта.
- Научить планировщик отличать нехватку конкретных предметов, а не только укрупнённых категорий, и проверять наличие доступной добычи до назначения шахтёра.
- Добавить обслуживание посевов/роста культур, сводку дефицитов в панели старосты и долгие тесты нескольких соседних деревень.
- **Проверка:** полная сборка, все GameTests, интерактивный клиентский прогон и профили MSPT/TPS/RAM на малых и крупных поселениях.

