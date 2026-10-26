# Living Villages

**Current version:** 0.15.0  
**Target game:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.264  
**Java:** 21

Living Villages expands village life through autonomous villager roles, resource logistics, crafting work orders, mining and tree work, village construction, farming, defense, and population support.

This is an experimental hobby project. On 2026-10-26, GitHub Actions passed static validation, `./gradlew build`, and dedicated-server startup for commit `ddf83bd54454e1254af7b3b055cae895db7be624`. A shared HOME-anchored resident-group selector scopes major village subsystems. Persistent, coarse-grained storage claims now prevent active jobs from competing for the same physical chest; claims are saved on worker data and released by task kind. This is not yet a global village registry or per-item ledger, and the client UI, two-village behavior, and worker-death recovery still need play-testing before treating the mod as a release.

## Build

On a system with Java 21 and internet access:

```sh
./gradlew build
```

The build workflow runs static validation, regression tests, Gradle compilation, and a dedicated-server startup smoke test on pull requests. [Latest successful checks](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40280957652) · [Download the built JAR artifact](https://github.com/aba04-stack/Minecraft-mod/actions/runs/40280957652/artifacts/12631739505).

## Local checks

```sh
python3 tools/validate_project.py
java tools/JavaSyntaxCheck.java src/main/java
for test_file in tools/test_*.py; do python3 "$test_file"; done
sh -n gradlew BUILD_LOCAL.sh
```

These checks validate selected invariants and source syntax; they do **not** replace compiling against Minecraft/NeoForge or testing the mod in-game.

More details are available in [the Russian README](README_RU.md), [the architecture notes](ARCHITECTURE.md), [the changelog](CHANGELOG_RU.md), and [the build checklist](BUILD_CHECKLIST.md).
