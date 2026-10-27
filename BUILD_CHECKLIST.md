## 0.15.0 — regression checks
- [x] Farm supports explicit vanilla seed/crop pairs for wheat, carrots, potatoes and beetroot.
- [x] Food courier selects the hungriest eligible adult first, with deterministic tie-break.
- [x] POSIX/Windows Gradle bootstrap pins version 9.2.1 and verifies distribution SHA-256.
- [x] Run `python3 tools/validate_project.py`; this validates source structure but does not resolve Minecraft/NeoForge symbols.
- [x] `./gradlew build` passed in GitHub Actions on Java 21 with Minecraft 1.21.1 / NeoForge 21.1.264.
- [x] Active-area safety regression: population query filters dead villagers; farmland/bed scans, house/farm site searches, and guard patrol-point selection skip unloaded chunks.
- [x] Shared HOME-anchored village-group selector is used by planner, roles, home/farm projects, crafting, industry, food courier and elder population display.
- [x] Active-area deduplication compares selected villager UUID overlap instead of suppressing areas only by player distance.
- [x] Persistent storage reservation contract test: claims are saved on worker data, checked for conflicts, and released with a task-kind guard.
- [x] House, farm, crafting, food courier, logistics, farmer stockpiling, miner/tool and guard storage paths check reservations.
- [x] Newly discovered source/destination chests are claimed before worker routing.
- [x] House/farm site searches and project start guard against nearby active build sites, including projects owned by a different resident group.
- [x] Physical storage selection checks that a chest's nearest HOME-anchored resident group overlaps the requesting village group.
- [ ] Run `runClient` and manually verify farm project initialization, food courier behavior and the elder panel.
- [ ] In a normal client world, run two neighboring villages with overlapping resource-search radii and check the full gameplay cycle.
- [ ] In a test world, start a house and farm project from neighboring settlements and verify the actual projects do not overlap; GameTest covers the geometry guard.
- [x] GitHub Actions GameTests confirm that two HOME groups do not claim each other's chests, beds or workstations.
- [x] GitHub Actions GameTests confirm that a task cannot reuse a rival chest claim, and that removing/replacing a chest clears stale reservation/ownership.
- [x] A village ID survives HOME-memory loss and resident relocation in a GameTest.
- [x] HOME-less villagers keep their saved group when relocated next to another village; verified in GameTest on CI commit `6325d2ff9aff69ad163303b0cb8ec6216986654e`.
- [x] A SavedData round-trip preserves village IDs, HOME aliases, known residents and storage/bed/work-block owners.
- [x] A legacy SavedData payload without the HOME-alias section promotes its primary anchor into the new alias map.
- [ ] Run two real resource tasks in a normal world and verify one cannot take from a chest claimed by the other.
- [ ] Check that cancelling/disabling logistics does not release a house/farm/crafting claim on the same worker.
- [x] Chest removal/replacement is covered by GameTest; worker death and recovery of an in-flight project remain untested and unimplemented.
- [ ] In a normal client world, place two nearby villages and confirm elder, population counts, roles and work orders remain independent.
- [ ] Builder: 96 same-type planks, matching door, red bed; 96 planks remain after crafting door.
- [ ] Farm: 24 seed items, water bucket, composter, hoe; worker makes each block placement in sequence.
- [ ] Config toggles: pausing a project does not block unrelated village work when its feature is disabled.

# Список проверок Living Villages 0.15.0

## Статические проверки
- [x] Версия проекта и ожидаемого JAR синхронизирована: 0.15.0.
- [x] Метаданные NeoForge описывают материально-зависимую экономику.
- [x] JSON-файлы RU/EN должны разбираться штатным JSON parser.
- [x] Сборочный shell-скрипт должен проходить `sh -n`.
- [x] Код не содержит резервного генератора саженцев и формулировок про бесплатные саженцы в исходниках.
- [x] Шахтёр имеет режим добычи открытого камня при низком запасе булыжника.
- [x] Лесоруб ждёт реальный саженец и не создаёт предметы/не начинает новые рубки из-за тайм-аута.
- [x] Ремесленник может расходовать настоящие железные слитки и палочки для железной кирки; шахтёр предпочитает более качественную кирку, если она действительно доступна.
- [x] Назначение новых шахтёров/стражников требует инструмента, доступного лично или в местном складе.
- [x] Добавлена поддержка естественного размножения без ручного создания детей: курьер доставляет настоящую еду, а рождение/рост контролирует ванильная механика.
- [x] Курьер не начинает рейс без двух взрослых, потенциально свободной подходящей кровати и реального запаса еды сверх резерва.
- [x] Сканирование грядок/кроватей, поиск площадок дома/фермы и выбор патрульной точки проверяют загрузку чанка до доступа к блокам/heightmap; запросы деревни исключают мёртвых жителей.
- [x] При сбое возврата контейнера невмещённые реальные предметы выбрасываются рядом с курьером, а не удаляются молча.
- [x] Полная Gradle-компиляция на Java 21 с Minecraft 1.21.1 / NeoForge 21.1.264 подтверждена в GitHub Actions.
- [ ] Запуск `runClient` и тест поведения на игровом клиенте.

- [x] Добавлен конечный автомат физического ремесленника с фазами сбора входов, работы у верстака и доставки результата.
- [x] Отдельная настройка `enable_physical_crafting` и RU/EN подписи.
- [x] Планировщик и логистика не конкурируют с активным заказом ремесленника.
- [ ] Полевая проверка маршрута между двумя сундуками, верстаком и обратным складированием.

## Ресурсный баланс — обязательные тесты в мире

1. Запусти деревню с реальным деревом и без саженцев в сундуке. После рубки мод не должен создавать саженец; он должен ждать натуральный дроп/реальный склад.
2. Удали все брёвна/доски из инвентарей и хранилищ. Мод не должен строить сундук или верстак.
3. Дай лесорубу срубить дерево. Проверь, что выпавшие брёвна попадают в инвентарь/на землю, затем доставляются в реальный сундук.
4. При наличии одного настоящего бревна проверь преобразование 1 бревно → 4 доски. Введи точный счёт предметов до/после.
5. Проверь, что первый сундук требует 8 досок, верстак — 4 доски, а печь — 8 булыжников. Если входов нет, блок не размещается.
5a. Оставь деревню без саженцев после рубки: лесоруб должен ждать естественный дроп/склад и не продолжать рубить следующие деревья.
5b. Проверь, что открытый камень даёт реальный булыжник только при наличии подходящей кирки, а железная кирка требует ровно 3 железных слитка и 2 палочки.
6. Проверь палочки: из 2 досок получаются 4 палочки. Пустое место для результата должно останавливать рецепт, не теряя входы.
7. Убери реальные инструменты из инвентарей/склада. Шахтёр/стражник не должен назначаться или продолжать соответствующую работу, если инструмент недоступен.
8. Дай шахтёру деревянную кирку и открой доступ к камню. Он может добывать только видимые/доступные каменные блоки, не ломает блок под ногами и тратит прочность инструмента.
9. Убери топливо или сырую руду. Новая печь не должна строиться, а выплавка должна ждать ресурсы.
10. Если печь загружена настоящей рудой и топливом, проверь, что ванильная печь производит слиток и система переносит его в реальный склад без дублирования.
11. Проверь строительство хижины: 96 досок одного типа + настоящая дверь того же типа + красная кровать. Если двери нет, проверь, что ремесленник сначала изготовит её из шести дополнительных досок и не потратит зарезервированные материалы дома.
12. Проверь ферму: 24 посадочных предмета, настоящее ведро воды и настоящий компостер; пустое ведро должно вернуться в хранилище, а не дублироваться.
13. Заполни сундуки до отказа. Логистика должна оставить не помещающиеся предметы в инвентаре/не продолжать рейс без места.
14. Перезапусти мир и проверь, что личность/отношения жителей сохраняются.
15. Разложи 3 булыжника в одном сундуке и 2 палочки в другом. Проверь, что ремесленник делает два отдельных рейса и расходует ровно 3 булыжника + 2 палочки на каменную кирку.
16. Удали/сломай верстак во время заказа. Проверь, что ремесленник не создаёт инструмент; имеющиеся ингредиенты остаются у него и могут вернуться логистикой.
17. Заполни все сундуки. Изготовленный инструмент должен оставаться у ремесленника и заказ должен ждать свободного места, а не пропадать.
18. При отключённом `enable_physical_crafting` проверь упрощённый режим, а при включённом убедись, что инструменты не создаются фоновым рецептом из того же склада.

## Текущий статус

Компиляция и старт dedicated server подтверждены GitHub Actions; игровой клиент и функциональные сценарии по чек-листу ещё не проверены. Не считать текущий артефакт релизом для основного мира до выполнения игровых тестов.

- [x] Добавлен строитель, который забирает реальные материалы и возводит хижину по блокам.
- [x] Материалы и проект строителя сохраняются в persistent data жителя.
- [x] Строитель проверяет досягаемость, прямую видимость, загруженность чанка и `mobGriefing`.
- [x] Параллельная физическая работа ремесленника приостанавливается на время стройки.
- [x] Полная Gradle-компиляция с Minecraft/NeoForge dependency classpath — успешно на GitHub Actions.
- [ ] Тест `runClient`: наблюдать подход к складу, сбор 96 досок + двери + кровати, постановку блоков/двух частей двери и сохранение проекта после перезахода.
- [ ] Тест `runClient`: ферма строится по одному действию; при исчезновении ресурса проект ждёт, не создаёт предметы и не перезаписывает блоки игрока.
- [ ] Тест отключения ролей: отключение шахтёра, защитника, ремесленника или строителя должно освобождать роль/маршрут без зависшего `WALK_TARGET`.


## Population validation
- [ ] Verify a courier walks to the actual container and carries only existing food.
- [ ] Verify it retains the configured food reserve and does not start without open bed capacity.
- [ ] Verify two adults pick up food and vanilla AI creates a baby only when vanilla conditions are met.
- [ ] Verify babies grow at vanilla speed and are excluded from specialist assignment until adult.
- [ ] Verify a full/no-space inventory or unavailable recipient causes a wait/return, not item deletion.

## 0.15.0 — староста и экран управления
- [x] Староста выбирается из уже существующих взрослых жителей после достижения `minimum_villagers`; новая сущность не создаётся.
- [x] Ванильная профессия и торговля выбранного жителя не заменяются.
- [x] ПКМ пустой рукой открывает панель; Shift+ПКМ и интеракции с предметом не перехватываются.
- [x] Сервер проверяет включённость функции, UUID, маркер старосты, жизнь сущности и расстояние до игрока перед применением действия.
- [x] Заказы дома/фермы учитывают включённые настройки и очищаются, если нужную функцию выключили.
- [x] Статический регрессионный тест охватывает payloads, выбор старосты, действия меню и метод рендера для 1.21.1.
- [ ] В `runClient` открыть меню модов и проверить, что экран конфигурации виден.
- [ ] В мире с достаточным числом взрослых жителей проверить, что одному жителю добавляется имя старосты.
- [ ] Проверить обычный ПКМ пустой рукой, Shift+ПКМ и ПКМ с предметом в руке.
- [ ] Нажать все кнопки, проверить ответ сервера, появление/отмену запросов и изменение приоритетов.
- [ ] Убедиться, что после перезахода староста и его состояние остаются согласованными, а повторного старосты не появляется.
- [x] Smoke-test dedicated server: сервер стартует с модом и достигает состояния готовности без загрузки GUI-клиента.
