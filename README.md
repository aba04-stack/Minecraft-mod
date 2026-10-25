# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. The repository contains source code and static checks, but a successful full Gradle build and in-game behavior must be confirmed by CI and play-testing before treating this version as a working release.

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow also runs the project's static validation, Java syntax parsing, regression checks, and shell syntax checks.

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
