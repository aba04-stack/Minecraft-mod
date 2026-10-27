# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-27, GitHub Actions passed static validation, `./gradlew build`, ten in-world Minecraft GameTests, dedicated-server startup, and a headless client UI-layout check for commit `c0f111dd81eea237aa3262c0913c27de42ef8022`. The client test created the real elder screen and verified all seven buttons fit within the logical `427×240` viewport; button clicks/server responses are not simulated. GameTests cover competing/stale chest claims, rejection of foreign-village stock, ownership separation for chests/beds/workstations between neighboring HOME groups, HOME-less grouping and relocation near another village, build-site separation, identity recovery after HOME loss, `SavedData` save/load round-trip, and migration from a legacy registry without HOME aliases. Village IDs remain heuristic, not a census of unloaded villagers. Interactive elder button/server actions, a full two-village gameplay cycle, and worker-death recovery still need manual in-game testing. [CI result](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40560233119) · [JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40560233119/artifacts/12761080201).

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, reservation GameTests, dedicated-server startup, and a headless client startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40560233119) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40560233119/artifacts/12761080201).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
