# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-26, GitHub Actions passed static validation, `./gradlew build`, dedicated-server startup, and JAR artifact upload for commit `be5e5128bec31dcb3fc749e020e3f7cc7dbfaf91`. The mod now stores village IDs and ownership of chests/barrels, beds, and workstations through Minecraft `SavedData`; main storage scans, housing counts, food logistics, industry, and physical crafting consult that ownership. Temporary task reservations separately prevent simultaneous jobs from using the same physical chest. Village IDs are still matched heuristically through HOME anchors rather than a full census of unloaded villagers. The client UI, two-village behavior, and worker-death recovery still need play-testing before treating the mod as a release.

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, and a dedicated-server startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40305622309) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40305622309/artifacts/12642915688).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
