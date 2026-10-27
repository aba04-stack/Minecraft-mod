# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-27, GitHub Actions passed static validation, `./gradlew build`, seven in-world Minecraft GameTests, dedicated-server startup, headless client startup under Xvfb/software OpenGL through resource initialization, and JAR artifact upload for commit `d47ab9d8d02d5237bf866deeb47eadbd2935c964`. GameTests cover competing/stale chest claims, ownership separation for chests/beds/workstations between neighboring HOME groups, HOME-less grouping, build-site separation, village identity recovery after HOME loss and relocation, and `SavedData` save/load round-trip. Village IDs remain heuristic, not a census of unloaded villagers. Interactive elder UI, a full two-village gameplay cycle, and worker-death recovery still need manual in-game testing. [CI result](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40552220317) · [JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40552220317/artifacts/12754744512).

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, reservation GameTests, dedicated-server startup, and a headless client startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40552220317) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40552220317/artifacts/12754744512).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
