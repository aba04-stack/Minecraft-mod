# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-25, GitHub Actions completed `./gradlew build` successfully and confirmed that the dedicated server reached its ready state for commit `21fc0e26478e84649056c3d2a77a2644dd132554`. A shared HOME-anchored nearest-resident-group selector now scopes major village subsystems, but it is not a persistent global village registry and does not fully isolate nearby storage blocks. The client UI and in-world behavior with two neighboring villages still need play-testing before treating this version as a release.

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, and a dedicated-server startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40230735991) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40230735991/artifacts/12613050550).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
