# ADR-0001: Toolchain & Version Policy

- **Status:** Accepted
- **Date:** 2026-08-16
- **Deciders:** Nornir Launcher plan effort (grilling on issue #4)
- **Related:** issue #4 (toolchain & version policy), issue #2 / T2 research,
  issue #1 (wayfinder map)

## Context

The Nornir Launcher implementation plan (#1) is plan-only: it resolves open
design decisions as ADRs + a written plan, then hands off. No app code is
written in this effort. Issue #4 required locking the JVM/build-tool layer of
the toolchain: JDK, Kotlin, AGP, Gradle wrapper, Compose BOM, and the
Android Studio vs barebones-CLI build path — reconciling the blueprint
(`launcher-ideas.md`: minSdk 26, compileSdk 34/35, OpenJDK 17/21, Gradle
wrapper, Compose; offers both an Android Studio and a CLI path) with the T2
findings and current Android norms.

The SDK levels (minSdk 26, compileSdk 36, targetSdk 36) were already locked by
T2 (issue #2). Issue #4 therefore only adds the JVM/build-tool layer on top.

## Decision

The resolved toolchain & version policy:

| Dimension            | Decision                              | Notes |
|----------------------|---------------------------------------|-------|
| JDK                  | OpenJDK **21 (LTS)**                   | `jvmToolchain(21)`. AGP 8.8 runs on JDK 21. |
| Kotlin               | **2.1.x**                              | K2 compiler; Compose via `org.jetbrains.kotlin.plugin.compose` (no separate Compose compiler artifact). |
| AGP                  | **8.8.2** (pinned)                     | |
| Gradle wrapper       | **8.11.x+** (min for AGP 8.8.2 = 8.10.2) | *Derived* — the wrapper is the single source of truth; commit `gradlew` + `gradle-wrapper.properties`; never hand-install Gradle. |
| Compose BOM          | **2025.x latest stable** (baseline pin) | A 2025.x BOM aligns with Kotlin 2.1\'s bundled Compose compiler. Baseline = the then-current 2025.x BOM at scaffold time (e.g. `2025.01.00`+); exact patch resolved per the version policy below. |
| minSdk               | **26**                                 | Locked by T2 (not #4). |
| compileSdk           | **36**                                 | Locked by T2. |
| targetSdk            | **36** (= compileSdk)                  | Greenfield: align targetSdk with compileSdk. |
| Build / IDE path     | **Both**, wrapper as SSOT              | Android Studio ("Ladybug or newer") for ergonomics (Layout Inspector, ADB); fully CLI-reproducible build (`gradlew assembleRelease` / `gradlew test`) for CI and the VS Code/Neovim path. |
| Version policy       | **"latest stable at build time"**, baseline pinned | Values above are the documented baseline; implementers re-resolve "latest stable" when the scaffold is generated. |

### Compose BOM / Kotlin 2.1 alignment note

Kotlin 2.0+ bundles the Compose compiler in the Kotlin plugin, which must
match the Kotlin version (here 2.1.x). The originally considered BOM
`2024.12.01` (Dec 2024) belongs to the Kotlin 2.0.x compiler era and would
emit a Compose-compiler-version mismatch warning under Kotlin 2.1. Decision:
pin the baseline to a **2025.x BOM**, the release line aligned with Kotlin
2.1\'s Compose compiler, eliminating the mismatch.

## Consequences

- The build is reproducible from the Gradle wrapper alone; Android Studio is
  optional ergonomics, not a build dependency.
- The stack (JDK 21 + Kotlin 2.1 + AGP 8.8.2 + Gradle 8.11 + 2025.x BOM) is
  internally consistent and current as of the plan date.
- The written plan and any generated scaffold must re-resolve "latest stable"
  at implementation time; the values here are the recorded baseline, not a
  permanent hard pin.
- SDK levels remain owned by T2; if T2\'s findings change, this ADR\'s
  `minSdk`/`compileSdk`/`targetSdk` rows must be revisited.

## Grilling record (issue #4)

Round 1 frontier (Q1–Q7) was fully answered by the user; the #4 frontier is
empty. See issue #4 for the decision transcript.
