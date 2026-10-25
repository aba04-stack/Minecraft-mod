# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-25, GitHub Actions completed `./gradlew build` successfully and confirmed that the dedicated server reached its ready state for the mod source. The client UI and in-world villager behaviors still need play-testing before treating this version as a release.

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation and Gradle compilation, checks that active-area scans avoid counting dead villagers or touching unloaded chunks, then performs a dedicated-server startup smoke test on pull requests. [Latest successful server smoke test and JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40197573229).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
