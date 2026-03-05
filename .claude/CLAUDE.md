# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code)
when working with code in this repository.

## Build & Test Commands

```bash
./gradlew build          # compile + JAR
./gradlew test           # run JUnit 5 tests
./gradlew cleanTest test # force re-run all tests
```

The JAR is output to `build/libs/freechains-1.0-SNAPSHOT.jar`.

Run a single test:
```bash
./gradlew test --tests "Tests.m01_args"
```

Shell integration tests live in `src/test/shell/test/` and
are run separately from Gradle (via `tests.sh`).

## Architecture

Freechains is a **peer-to-peer blockchain system** with
reputation-based consensus.
Two binaries share the codebase:

| Binary             | Entry point                        | Role                  |
| ------------------ | ---------------------------------- | --------------------- |
| `freechains-host`  | `org.freechains.host.MainKt`       | Daemon managing chain |
| `freechains`       | `org.freechains.cli.MainKt`        | CLI client over TCP   |

Communication between CLI and host uses a simple
line-based TCP protocol on port 8330 (default), prefixed
with `"FC v0.10.1"`.

### Source layout (`src/main/kotlin/org/freechains/`)

- **cli/Main.kt** -- CLI command parser; sends commands to
  the daemon via TCP socket.
- **host/Daemon.kt** -- Multi-threaded TCP server; dispatches
  commands, synchronizes chain access.
- **host/Host.kt** -- Host instance: loads/saves chains,
  manages the chain map.
- **host/Chain.kt** -- Core data structure and consensus
  algorithm (reputation, forks, consolidation).
  Time constants: `T12h_new`, `T24h_old`, `T7d_fork`.
  Reputation limit: `LK30_max = 30`.
- **host/Block.kt** -- Block and payload data classes;
  hash/height utilities.
- **host/Chain_Block.kt** -- Block creation, validation,
  encryption, and revocation logic.
- **host/Crypto.kt** -- Libsodium wrappers (key derivation,
  symmetric/asymmetric encrypt/decrypt).
- **common/Util.kt** -- Shared constants (`VERSION`,
  `PORT_8330`), argument parsing, I/O helpers.

### Chain types (determined by name prefix)

| Prefix | Type                  |
| ------ | --------------------- |
| `#`    | Public (multi-pioneer)|
| `$`    | Private (shared key)  |
| `@`    | Identity              |
| `@!`   | Locked identity       |

### Dependencies

- **lazysodium-java** + JNA -- libsodium bindings for crypto
- **kotlinx-serialization** -- JSON persistence of chains
  and blocks
- **JUnit 5 + AssertJ** -- testing

## Test structure

All JUnit tests are in a single file:
`src/test/kotlin/Test.kt` (~70 tests in class `Tests`).
Tests are ordered alphabetically (`@TestMethodOrder`).
`@BeforeAll` wipes `/tmp/freechains/tests/`;
`@BeforeEach` stops any running test hosts.
Six tests are `@Disabled` (consensus tests requiring
specific conditions).
