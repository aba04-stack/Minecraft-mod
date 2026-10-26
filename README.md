# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-26, GitHub Actions passed static validation, `./gradlew build`, dedicated-server startup, headless client startup under Xvfb/software OpenGL through resource initialization, and JAR artifact upload for commit `367c86d679bb35d4284532c35ac81a51a70d6eaa`. The mod stores village IDs and ownership of chests/barrels, beds, and workstations through Minecraft `SavedData`; primary storage scans, bed counts, food logistics, industry, and physical crafting consult that ownership. Temporary task reservations prevent simultaneous jobs from using the same physical chest, and stale claims are reconciled against their owning task and loaded block state. Village IDs remain heuristic through HOME anchors, not a complete census of unloaded villagers. Opening/using the elder UI, two-village behavior, and worker-death recovery still need in-game testing. [CI result](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40325488491) · [JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40325488491/artifacts/12650529821).

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
