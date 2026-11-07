package com.abs.livingvillages;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings displayed in the NeoForge Mods > Living Villages > Config screen. */
public final class LivingVillagesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_AUTONOMOUS_DEVELOPMENT;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGE_ELDER;
    public static final ModConfigSpec.BooleanValue ENABLE_HOUSING_PROJECTS;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGE_BUILDER;
    public static final ModConfigSpec.BooleanValue ENABLE_FARM_PROJECTS;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue VILLAGE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue RESOURCE_CHEST_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue MINIMUM_VILLAGERS;
    public static final ModConfigSpec.BooleanValue ENABLE_PERSONALITIES;
    public static final ModConfigSpec.BooleanValue ENABLE_RELATIONSHIP_MEMORY;
    public static final ModConfigSpec.BooleanValue ENABLE_SNEAK_TO_TALK;
    public static final ModConfigSpec.BooleanValue SHOW_PERSONALITY_NAMES;
    public static final ModConfigSpec.BooleanValue ENABLE_FARMER_STOCKPILING;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGER_TREE_WORK;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGER_REPLANTING;
    public static final ModConfigSpec.IntValue TREE_SEARCH_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue ENABLE_MINER_ROLE;
    public static final ModConfigSpec.BooleanValue ENABLE_MINER_CAVE_EXPLORATION;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGE_GUARDS;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGE_LIGHTING;
    public static final ModConfigSpec.BooleanValue ENABLE_RESOURCE_LOGISTICS;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGE_INDUSTRY;
    public static final ModConfigSpec.BooleanValue ENABLE_PHYSICAL_CRAFTING;
    public static final ModConfigSpec.BooleanValue ENABLE_POPULATION_SUPPORT;
    public static final ModConfigSpec.IntValue POPULATION_FOOD_RESERVE_POINTS;
    public static final ModConfigSpec.IntValue MINER_SEARCH_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue VILLAGE_DEFENSE_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue ENABLE_VILLAGER_PROGRESSION;
    public static final ModConfigSpec.IntValue MAX_PROFESSION_LEVEL;
    public static final ModConfigSpec.BooleanValue ENABLE_TACTICAL_MONSTER_AI;
    public static final ModConfigSpec.IntValue MONSTER_TACTICAL_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_AI_CHECKS_PER_TICK;
    public static final ModConfigSpec.BooleanValue ENABLE_ANIMAL_BEHAVIOR;
    public static final ModConfigSpec.BooleanValue ENABLE_DAILY_LIFE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLE_AUTONOMOUS_DEVELOPMENT = builder
            .comment("Главный переключатель автоматического развития поселений.")
            .translation("livingvillages.config.enable_autonomous_development")
            .define("enable_autonomous_development", true);

        ENABLE_VILLAGE_ELDER = builder
            .comment("Выбирать старосту из обычных взрослых жителей. ПКМ пустой рукой открывает меню управления деревней.")
            .translation("livingvillages.config.enable_village_elder")
            .define("enable_village_elder", true);

        ENABLE_HOUSING_PROJECTS = builder
            .comment("Разрешить автоматическую постройку хижин, когда заняты все подходящие кровати и нужно место для роста населения.")
            .translation("livingvillages.config.enable_housing_projects")
            .define("enable_housing_projects", true);

        ENABLE_VILLAGE_BUILDER = builder
            .comment("Назначать строителя при нехватке жилья. Строитель забирает реальные материалы из хранилищ и ставит блоки по одному.")
            .translation("livingvillages.config.enable_village_builder")
            .define("enable_village_builder", true);

        ENABLE_FARM_PROJECTS = builder
            .comment("Разрешить автоматическое создание фермы после удовлетворения потребности в жилье.")
            .translation("livingvillages.config.enable_farm_projects")
            .define("enable_farm_projects", true);

        SCAN_INTERVAL_SECONDS = builder
            .comment("Как часто планировщик проверяет активные поселения, в секундах.")
            .translation("livingvillages.config.scan_interval_seconds")
            .defineInRange("scan_interval_seconds", 60, 15, 600);

        VILLAGE_RADIUS_BLOCKS = builder
            .comment("Радиус поиска жителей, кроватей и готовых построек, в блоках.")
            .translation("livingvillages.config.village_radius_blocks")
            .defineInRange("village_radius_blocks", 32, 16, 64);

        RESOURCE_CHEST_RADIUS_BLOCKS = builder
            .comment("Радиус поиска сундуков с материалами, в блоках.")
            .translation("livingvillages.config.resource_chest_radius_blocks")
            .defineInRange("resource_chest_radius_blocks", 16, 4, 32);

        MINIMUM_VILLAGERS = builder
            .comment("Минимальное число жителей в области, чтобы запускать развитие.")
            .translation("livingvillages.config.minimum_villagers")
            .defineInRange("minimum_villagers", 2, 1, 20);

        ENABLE_PERSONALITIES = builder
            .comment("Включить сохраняемые черты характера и изменение настроения жителей.")
            .translation("livingvillages.config.enable_personalities")
            .define("enable_personalities", true);

        ENABLE_RELATIONSHIP_MEMORY = builder
            .comment("Жители запоминают подарки, беседы и то, причинял ли игрок им вред.")
            .translation("livingvillages.config.enable_relationship_memory")
            .define("enable_relationship_memory", true);

        ENABLE_SNEAK_TO_TALK = builder
            .comment("Shift + ПКМ пустой рукой — разговор; Shift + ПКМ с подарком — подарить предмет.")
            .translation("livingvillages.config.enable_sneak_to_talk")
            .define("enable_sneak_to_talk", true);

        SHOW_PERSONALITY_NAMES = builder
            .comment("Показывать личное имя над головой жителя. В больших деревнях может загромождать экран.")
            .translation("livingvillages.config.show_personality_names")
            .define("show_personality_names", false);

        ENABLE_FARMER_STOCKPILING = builder
            .comment("Фермеры сами относят излишки еды в ближайшие сундуки, сохраняя запас для размножения.")
            .translation("livingvillages.config.enable_farmer_stockpiling")
            .define("enable_farmer_stockpiling", true);

        ENABLE_VILLAGER_TREE_WORK = builder
            .comment("Незанятые взрослые жители могут самостоятельно искать деревья, сначала убирать листья на линии видимости, а затем рубить ствол с близкого расстояния.")
            .translation("livingvillages.config.enable_villager_tree_work")
            .define("enable_villager_tree_work", true);

        ENABLE_VILLAGER_REPLANTING = builder
            .comment("После рубки житель сажает только настоящий саженец из дропа, инвентаря или склада. Если саженца нет, посадка ждёт; бесплатных саженцев нет.")
            .translation("livingvillages.config.enable_villager_replanting")
            .define("enable_villager_replanting", true);

        TREE_SEARCH_RADIUS_BLOCKS = builder
            .comment("Радиус поиска деревьев для заготовки древесины.")
            .translation("livingvillages.config.tree_search_radius_blocks")
            .defineInRange("tree_search_radius_blocks", 10, 4, 16);

        ENABLE_MINER_ROLE = builder
            .comment("Разрешить назначать шахтёров безработным взрослым жителям и добывать доступные руды.")
            .translation("livingvillages.config.enable_miner_role")
            .define("enable_miner_role", true);

        ENABLE_MINER_CAVE_EXPLORATION = builder
            .comment("Шахтёры запоминают пройденный путь в естественных пещерах, ставят настоящие факелы и возвращаются к дому по сохранённым точкам. Нужны реальные факелы.")
            .translation("livingvillages.config.enable_miner_cave_exploration")
            .define("enable_miner_cave_exploration", true);

        ENABLE_VILLAGE_GUARDS = builder
            .comment("Назначать защитников деревни: они экипируются из сундуков и атакуют ближайших враждебных существ.")
            .translation("livingvillages.config.enable_village_guards")
            .define("enable_village_guards", true);

        ENABLE_VILLAGE_LIGHTING = builder
            .comment("Защитники освещают тёмные участки своих патрульных маршрутов настоящими факелами из запасов поселения; во время боя освещение приостанавливается.")
            .translation("livingvillages.config.enable_village_lighting")
            .define("enable_village_lighting", true);

        ENABLE_RESOURCE_LOGISTICS = builder
            .comment("Рабочие подбирают близкие реальные ресурсы и переносят их в настоящий склад; ничего не создаётся.")
            .translation("livingvillages.config.enable_resource_logistics")
            .define("enable_resource_logistics", true);

        ENABLE_VILLAGE_INDUSTRY = builder
            .comment("Автоматическое развитие мастерской: бревно в доски, реальные рецепты инструментов и загрузка существующей печи реальной рудой и топливом.")
            .translation("livingvillages.config.enable_village_industry")
            .define("enable_village_industry", true);

        ENABLE_PHYSICAL_CRAFTING = builder
            .comment("Ремесленник физически забирает реальные ингредиенты из хранилища, идёт к верстаку, тратит время на изготовление и относит результат обратно. При отключении используется упрощённое производство из склада.")
            .translation("livingvillages.config.enable_physical_crafting")
            .define("enable_physical_crafting", true);

        ENABLE_POPULATION_SUPPORT = builder
            .comment("Поддерживать естественное размножение: курьер физически несёт настоящую еду из хранилища взрослым жителям, когда есть свободная кровать. Само рождение и рост остаются ванильными.")
            .translation("livingvillages.config.enable_population_support")
            .define("enable_population_support", true);

        POPULATION_FOOD_RESERVE_POINTS = builder
            .comment("Минимальный запас питания в хранилищах, который курьер не забирает на размножение. Хлеб даёт 4 очка, морковь/картофель/свёкла — по 1.")
            .translation("livingvillages.config.population_food_reserve_points")
            .defineInRange("population_food_reserve_points", 8, 0, 256);

        MINER_SEARCH_RADIUS_BLOCKS = builder
            .comment("Радиус поиска видимой руды и открытого камня для получения булыжника. Поиск ограничен для безопасности.")
            .translation("livingvillages.config.miner_search_radius_blocks")
            .defineInRange("miner_search_radius_blocks", 8, 4, 16);

        VILLAGE_DEFENSE_RADIUS_BLOCKS = builder
            .comment("Радиус, в котором защитники обнаруживают враждебных существ.")
            .translation("livingvillages.config.village_defense_radius_blocks")
            .defineInRange("village_defense_radius_blocks", 18, 8, 32);

        ENABLE_VILLAGER_PROGRESSION = builder
            .comment("Минимальная система прокачки жителей: опыт за завершённые рабочие задачи и уровни профессий (без создания бесплатных предметов).")
            .translation("livingvillages.config.enable_villager_progression")
            .define("enable_villager_progression", true);

        MAX_PROFESSION_LEVEL = builder
            .comment("Максимальный уровень рабочей профессии жителя (от 1 до 5).")
            .translation("livingvillages.config.max_profession_level")
            .defineInRange("max_profession_level", 5, 1, 5);

        ENABLE_TACTICAL_MONSTER_AI = builder
            .comment("Тактическое поведение враждебных мобов на основе честного восприятия (прямая видимость и звуки работ) без всеведения.")
            .translation("livingvillages.config.enable_tactical_monster_ai")
            .define("enable_tactical_monster_ai", true);

        MONSTER_TACTICAL_RADIUS_BLOCKS = builder
            .comment("Радиус тактической оценки обстановки для враждебных мобов, в блоках.")
            .translation("livingvillages.config.monster_tactical_radius_blocks")
            .defineInRange("monster_tactical_radius_blocks", 24, 8, 48);

        MAX_AI_CHECKS_PER_TICK = builder
            .comment("Лимит тяжёлых проверок ИИ за один серверный тик для защиты производительности.")
            .translation("livingvillages.config.max_ai_checks_per_tick")
            .defineInRange("max_ai_checks_per_tick", 24, 4, 128);

        ENABLE_ANIMAL_BEHAVIOR = builder
            .comment("Улучшенное поведение животных: реакция стада на опасность, защита детёнышей, поиск корма/укрытия и разведение.")
            .translation("livingvillages.config.enable_animal_behavior")
            .define("enable_animal_behavior", true);

        ENABLE_DAILY_LIFE = builder
            .comment("Короткие социальные визиты взрослых жителей в свободное время. Не заменяет ванильное расписание и рабочие задачи.")
            .translation("livingvillages.config.enable_daily_life")
            .define("enable_daily_life", true);

        SPEC = builder.build();
    }

    private LivingVillagesConfig() {
    }
}
