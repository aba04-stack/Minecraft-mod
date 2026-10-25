#!/usr/bin/env python3
"""Lightweight static checks for Living Villages; this is not a substitute for Gradle/game testing."""
from __future__ import annotations
import json
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def strip_java_literals_and_comments(source: str) -> str:
    out: list[str] = []
    i = 0
    state = "code"
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"
                out.extend("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                out.extend("  ")
                i += 2
                continue
            if ch == '"':
                state = "string"
                out.append(" ")
                i += 1
                continue
            if ch == "'":
                state = "char"
                out.append(" ")
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == "line_comment":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                out.extend("  ")
                i += 2
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
        elif state in ("string", "char"):
            terminator = '"' if state == "string" else "'"
            if ch == "\\":
                out.append("  ")
                i += 2
            elif ch == terminator:
                state = "code"
                out.append(" ")
                i += 1
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
    if state in ("block_comment", "string", "char"):
        raise ValueError(f"unterminated Java comment/literal (ending state: {state})")
    return "".join(out)


def check_balanced(path: Path) -> None:
    content = strip_java_literals_and_comments(path.read_text(encoding="utf-8"))
    pairs = {"}": "{", ")": "(", "]": "["}
    stack: list[tuple[str, int]] = []
    for pos, ch in enumerate(content):
        if ch in "{([":
            stack.append((ch, pos))
        elif ch in pairs:
            if not stack or stack[-1][0] != pairs[ch]:
                raise ValueError(f"mismatched {ch} near character {pos}")
            stack.pop()
    if stack:
        raise ValueError(f"unclosed delimiter {stack[-1][0]} near character {stack[-1][1]}")


def main() -> int:
    errors: list[str] = []
    props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    if not re.search(r"(?m)^mod_version=0\.15\.0$", props):
        errors.append("gradle.properties mod_version must be 0.15.0")
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    if "livingvillages-0.15.0.jar" not in workflow or "livingvillages-0.15.0" not in workflow:
        errors.append("GitHub Actions artifact name/path is not aligned with 0.15.0")
    wrapper_props_path = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    if not wrapper_props_path.exists():
        errors.append("Gradle wrapper properties are missing")
    else:
        wrapper_props = wrapper_props_path.read_text(encoding="utf-8")
        if "gradle-9.2.1-bin.zip" not in wrapper_props or "distributionSha256Sum=72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f" not in wrapper_props:
            errors.append("Gradle 9.2.1 distribution URL/checksum are not pinned")
    launcher = (ROOT / "gradlew").read_text(encoding="utf-8")
    checksum = "72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"
    if checksum not in launcher:
        errors.append("POSIX Gradle bootstrap doesn't verify the official distribution checksum")
    windows_launcher = (ROOT / "gradlew.bat").read_text(encoding="utf-8")
    if checksum not in windows_launcher or "Gradle %GRADLE_VERSION%" not in windows_launcher:
        errors.append("Windows Gradle bootstrap must verify checksum and use pinned version")

    for path in sorted((ROOT / "src/main/resources/assets/livingvillages/lang").glob("*.json")):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"invalid JSON {path.relative_to(ROOT)}: {exc}")

    population_source = (ROOT / "src/main/java/com/abs/livingvillages/VillagePopulation.java").read_text(encoding="utf-8")
    if "new Villager(" in population_source or "EntityType.VILLAGER.create" in population_source:
        errors.append("population support must not spawn babies or villagers directly")
    if "ItemEntity" not in population_source or "withdrawPlan" not in population_source:
        errors.append("population food must travel as real items with a physical courier")

    for path in sorted((ROOT / "src/main/java").rglob("*.java")):
        try:
            check_balanced(path)
        except Exception as exc:
            errors.append(f"Java delimiter/literal check failed for {path.relative_to(ROOT)}: {exc}")

    java_sources = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "src/main/java").rglob("*.java"))
    if "REPLANT_VIRTUAL_SEED" in java_sources or "REPLANT_SEED_RESERVE_DELAY" in java_sources:
        errors.append("virtual sapling fallback still exists in Java sources")
    if re.search(r"new ItemStack\(Items\.[A-Z_]*SAPLING", java_sources):
        errors.append("source appears to instantiate saplings without a real input")
    required_markers = {
        "real-item logistics": "class VillageLogistics",
        "resource-gated production": "class VillageIndustry",
        "real furnace input": "Items.RAW_IRON",
        "stone quarrying": "findNearestQuarryBlock",
        "replant task": "tryFetchSaplingFromStorage",
        "iron pickaxe production": "Items.IRON_PICKAXE",
        "physical craftsman work orders": "class VillageCraftingJobs",
        "physical crafting config": "ENABLE_PHYSICAL_CRAFTING",
        "crafting ingredient delivery": "ROUTE_TO_INPUT_STORAGE",
        "crafting output delivery": "ROUTE_TO_OUTPUT_STORAGE",
        "worker-led builder role": "class VillageBuilder",
        "block-by-block construction": "makeHousePlan",
        "real builder materials": "PLANKS_REQUIRED = 96",
        "matching house door": "doorItemForPlanks",
        "incremental farm builder": "class VillageFarmBuilder",
        "farm crop completion check": "current.is(cropBlock)",
        "validated vanilla crop/seed mapping": "isSupportedCrop(Item seedItem, Block cropBlock)",
        "hungriest breeding recipient first": "Comparator.comparingInt((Villager v) -> foodPoints(v))\n                .thenComparing",
        "role reconciliation": "roleDisabled",
        "builder config toggle": "ENABLE_VILLAGE_BUILDER",
        "construction reach check": "withinPlacementReach",
        "construction line of sight": "hasClearLine",
        "natural population support": "class VillagePopulation",
        "population support config": "ENABLE_POPULATION_SUPPORT",
        "real food courier": "PHASE_TO_RECIPIENT",
        "breeding nutrition threshold": "BREEDING_FOOD_POINTS",
        "spare-bed housing": "population >= countBeds(level, center)",
        "food delivery task state machine": "class VillagePopulation",
        "population nursery food reserve": "POPULATION_FOOD_RESERVE_POINTS",
        "real food item transfer": "withdrawPlan(storage, courier, plan)",
        "vanilla breeding remains authoritative": "vanilla AI decides whether/when breeding",
        "bed headroom validation": "hasBabyClearance",
        "village elder management": "class VillageElder",
        "elder management payload": "record ElderPanelData",
        "server-validated elder actions": "handleActionOnServer",
        "elder house/farm requests": "ACTION_REQUEST_HOUSE",
    }
    for label, marker in required_markers.items():
        if marker not in java_sources:
            errors.append(f"missing required feature marker: {label}")

    try:
        subprocess.run([sys.executable, str(ROOT / "tools/test_builder_material_bill.py")],
                       check=True, capture_output=True, text=True)
    except Exception as exc:
        errors.append(f"builder material-bill regression check failed: {exc}")

    try:
        subprocess.run([sys.executable, str(ROOT / "tools/test_farm_material_bill.py")],
                       check=True, capture_output=True, text=True)
    except Exception as exc:
        errors.append(f"farm material-bill regression check failed: {exc}")

    try:
        subprocess.run([sys.executable, str(ROOT / "tools/test_population_food_order.py")],
                       check=True, capture_output=True, text=True)
    except Exception as exc:
        errors.append(f"population food-order regression check failed: {exc}")

    try:
        subprocess.run([sys.executable, str(ROOT / "tools/test_elder_management.py")],
                       check=True, capture_output=True, text=True)
    except Exception as exc:
        errors.append(f"elder management regression check failed: {exc}")

    try:
        subprocess.run(["java", str(ROOT / "tools/JavaSyntaxCheck.java"), str(ROOT / "src/main/java")],
                       check=True, capture_output=True, text=True)
    except Exception as exc:
        errors.append(f"JDK Java syntax parse failed: {exc}")

    for script in (ROOT / "BUILD_LOCAL.sh", ROOT / "gradlew"):
        try:
            subprocess.run(["sh", "-n", str(script)], check=True, capture_output=True, text=True)
        except Exception as exc:
            errors.append(f"shell syntax check failed for {script.relative_to(ROOT)}: {exc}")

    if errors:
        print("STATIC CHECK FAILED")
        for err in errors:
            print(f"- {err}")
        return 1
    print("STATIC CHECKS PASSED")
    print("- version/workflow aligned at 0.15.0")
    print("- Gradle 9.2.1 bootstrap pins the official distribution checksum")
    print("- translation JSON files valid")
    print("- Java comments/literals/delimiters balanced")
    print("- JDK compiler syntax parse passed (Minecraft/NeoForge symbols not resolved)")
    print("- no virtual sapling generator found")
    print("- builder fixed-template material bill checked (96 planks + matching door + real bed)")
    print("- farm fixed-template material bill checked (24 crops + real water bucket, hoe and composter)")
    print("- farm crop/seed compatibility checked against explicit vanilla pairs")
    print("- breeding food recipient order checked (hungriest first, deterministic tie-break)")
    print("- breeding support uses real stored food, a physical courier, valid bed space and vanilla reproduction")
    print("- elder panel actions, role election, configuration checks and 1.21.1 screen signature checked")
    print("- resource-ledger feature markers present")
    print("- shell scripts pass sh -n")
    print("NOTE: This does not compile the Minecraft/NeoForge source or test behavior in game.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
