# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-27, GitHub Actions passed static validation, `./gradlew build`, eight in-world Minecraft GameTests, dedicated-server startup, headless client startup under Xvfb/software OpenGL through resource initialization, and JAR artifact upload for commit `708a655c8a4425f455e8bca7ff970ab31d5ef8a0`. GameTests cover competing/stale chest claims, ownership separation for chests/beds/workstations between neighboring HOME groups, HOME-less grouping, build-site separation, village identity recovery after HOME loss and relocation, `SavedData` save/load round-trip, and migration from a legacy registry without HOME aliases. Village IDs remain heuristic, not a census of unloaded villagers. Interactive elder UI, a full two-village gameplay cycle, and worker-death recovery still need manual in-game testing. [CI result](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40553636283) · [JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40553636283/artifacts/12756606252).

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, reservation GameTests, dedicated-server startup, and a headless client startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40553636283) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40553636283/artifacts/12756606252).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
