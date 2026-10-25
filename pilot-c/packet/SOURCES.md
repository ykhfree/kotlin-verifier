# Sources

This is the team's specification text, split into blocks. Each block starts with its anchor in square
brackets and its line numbers. When you cite a block, give its anchor and quote its words exactly as written
here (a quote may be part of a block; it must not join text from two blocks).


## README.md

### (top)

[README.md#top/1] (lines 1-1)
Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

### Req-Shield

[README.md#req-shield/1] (lines 5-5)
A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

### Requirements

[README.md#requirements/1] (lines 9-9)
- Java 17 or later if you are a user.

[README.md#requirements/2] (lines 10-10)
- Kotlin 1.9 or later if you are a user.

[README.md#requirements/3] (lines 11-11)
- (If link this lib with Spring) Spring Boot 3.5 (Spring Framework 6.2) or later if you are a user.

[README.md#requirements/4] (lines 12-12)
- (If you use the Reactor modules) Project Reactor 3.7 or later; (if you use the coroutine modules) kotlinx.coroutines 1.8 or later.

### Usage

[README.md#usage/1] (lines 16-16)
Choose a module based on your platform (there is a detailed explanation in the wiki):

[README.md#usage/2] (lines 18-23)
`implementation("com.linecorp.cse.reqshield:core:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-reactor:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-kotlin-coroutine:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux-kotlin-coroutine:{version}")`<br>

### Release versions

[README.md#release-versions/1] (lines 27-27)
Release versions are available from Maven Central. Add Maven Central and use a release version such as `1.0.0`:

[README.md#release-versions/2] (lines 29-37)
```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.linecorp.cse.reqshield:core:1.0.0")
}
```

### Dev snapshot versions

[README.md#dev-snapshot-versions/1] (lines 41-42)
Dev versions are published as Maven snapshots and require the Maven Central Snapshot repository. Use a snapshot version
such as `1.0.0-SNAPSHOT`:

[README.md#dev-snapshot-versions/2] (lines 44-59)
```kotlin
repositories {
    mavenCentral()
    maven {
        name = "MavenCentralSnapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    implementation("com.linecorp.cse.reqshield:core:1.0.0-SNAPSHOT")
}
```

[README.md#dev-snapshot-versions/3] (lines 61-62)
Snapshot versions can be updated without changing the version string. If Gradle continues to use a cached snapshot,
refresh dependencies with `./gradlew build --refresh-dependencies`.

### Integration tests with Redis (Testcontainers)

[README.md#integration-tests-with-redis-testcontainers/1] (lines 68-68)
- Redis-backed integration tests using Testcontainers always run as part of module test tasks.

[README.md#integration-tests-with-redis-testcontainers/2] (lines 69-71)
- Requirements:
    - A working local Docker daemon with network access to pull `redis:6.2.7-alpine` on first run.
    - Sufficient permissions to start containers from tests.

[README.md#integration-tests-with-redis-testcontainers/3] (lines 72-72)
- If you need to temporarily bypass Redis ITs locally (e.g., no Docker), run specific unit-test-only tasks or exclude the example modules when invoking Gradle.

### Kotlin with Reactor 3.8 (Spring Boot 4)

[README.md#kotlin-with-reactor-3-8-spring-boot-4/1] (lines 76-79)
- Reactor 3.8 declares `Mono`'s type parameter non-null with JSpecify annotations, and the Kotlin compiler reports
  violations as errors by default. The Reactor-based API still uses nullable type arguments such as
  `Mono<ReqShieldData<T>?>` (`AsyncCache.get`, `ReqShieldConfiguration.getCacheFunction`), so Kotlin code that
  implements or calls it fails to compile on Reactor 3.8.

[README.md#kotlin-with-reactor-3-8-spring-boot-4/2] (lines 80-81)
- Until the API changes, compile such Kotlin code with `-Xjspecify-annotations=warn`, as
  `req-shield-spring-boot4-webflux-example` does. Java callers and the coroutine modules are not affected.

### WebFlux null handling

[README.md#webflux-null-handling/1] (lines 85-87)
- `@ReqShieldCacheable(nullHandling = ...)` controls how `null` values are emitted in WebFlux:
    - `EMIT_EMPTY` (default): map `null` to `Mono.empty()`.
    - `ERROR`: throw an `IllegalStateException` if a `null` value is produced.

### Cache key layout

[README.md#cache-key-layout/1] (lines 91-93)
- The Spring adapters store every entry under `"{cacheName}::{key}"` (the same convention as Spring's `RedisCacheManager`),
  where `key` is the SpEL result or the `KeyGenerator` output. `@ReqShieldCacheEvict` applies the same rule, so an evict
  with the same `cacheName` and `key` always targets the entry written by `@ReqShieldCacheable`.

[README.md#cache-key-layout/2] (lines 94-95)
- Lock keys are derived from that namespaced key and prefixed with `reqshield:lock:`, so two caches that happen to use the
  same raw key never share a lock or an entry.

[README.md#cache-key-layout/3] (lines 96-96)
- If you call `ReqShield` directly (core / core-reactor / core-kotlin-coroutine), the key you pass is used as is.

### Global lock guidance

[README.md#global-lock-guidance/1] (lines 100-103)
- When `isLocalLock = false`, your cache bean must also implement the module's `GlobalLockSupport` interface
  (`com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport`, `...spring.webflux.cache.GlobalLockSupport`,
  `...spring.webflux.kotlin.coroutine.cache.GlobalLockSupport`). If it does not, the first call to the annotated method
  fails with an `IllegalArgumentException` instead of silently running without request collapsing.

[README.md#global-lock-guidance/2] (lines 104-105)
- Every lock acquisition carries an ownership token. Only the holder that acquired the lock can release it, so a slow
  holder whose lock already expired can no longer release the lock of the next holder.

[README.md#global-lock-guidance/3] (lines 106-109)
- Recommended Redis implementation:
    - Lock: `SET {lockKey} {token} NX PX {ttlMillis}` (atomic; never `SETNX` followed by a separate `PEXPIRE`)
    - Unlock: compare-and-delete in a Lua script, e.g.
      `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end`

[README.md#global-lock-guidance/4] (lines 110-111)
- The example modules contain working implementations for `RedisTemplate`, `ReactiveRedisTemplate` and the coroutine
  extensions.

### Local lock map size

[README.md#local-lock-map-size/1] (lines 115-117)
- The local lock (`isLocalLock = true`, the default) keeps one entry per `(cache key, lock type)` it is currently
  locking, in a map shared by the whole JVM. A cache key that is both created and refreshed therefore uses up to two
  entries.

[README.md#local-lock-map-size/2] (lines 118-121)
- An entry is dropped as soon as the lock is released; an entry whose holder never released it is dropped by the
  cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much.
  The map's size therefore tracks how many keys are being locked **at the same time**, not how many distinct keys the
  application sees. A service with 50 ms of supplier latency at 10k rps holds roughly 500 locks at once.

[README.md#local-lock-map-size/3] (lines 122-138)
- The map is uncapped by default. To bound it, set `req-shield.lock.max-entries`; `0` (the default) means uncapped.
  Size it against concurrent lock ownership, not key cardinality.

  ```yaml
  # Spring modules: read from the Environment, so application.yml works
  req-shield:
    lock:
      max-entries: 2000
  ```

  ```bash
  # core / core-reactor / core-kotlin-coroutine used directly: system property
  java -Dreq-shield.lock.max-entries=2000 -jar app.jar
  ```

  The Spring modules read the same key from the `Environment`, so a `-D` override still outranks the yml entry there,
  and `REQ_SHIELD_LOCK_MAX_ENTRIES` works as an environment variable.

[README.md#local-lock-map-size/4] (lines 139-142)
- Once the map is full, a request for a **new** key is handed a permit that no map entry backs. It then runs exactly as
  if it had taken the lock - it calls the supplier and writes the cache - so what the cap costs is request collapsing
  for that key, and nothing else: no added latency, and the cache still gets populated. Keys whose entry is already in
  the map are not subject to the cap at all.

[README.md#local-lock-map-size/5] (lines 143-144)
- The cap is a soft one. The size check is an estimate and is not atomic with the insertion it guards, so concurrent
  callers can push the map slightly past the configured number.

[README.md#local-lock-map-size/6] (lines 145-145)
- The first refusal and every 1000th after it are logged at WARN.

[README.md#local-lock-map-size/7] (lines 146-149)
- A value that cannot be read as a non-negative number is logged at WARN and ignored, leaving the current cap
  unchanged. This is deliberate in both directions: a class initializer that throws would poison the library for the
  whole JVM, and an application context should not fail to start over a tuning knob typo. Setting the cap
  programmatically (`LocalLockLimit.maxEntries = -1`) still fails fast, because there the stack trace is actionable.

### Cache eviction semantics

[README.md#cache-eviction-semantics/1] (lines 153-154)
- `@ReqShieldCacheEvict` evicts **after** the annotated method completes successfully (Spring's `@CacheEvict` default).
  If the method throws, nothing is evicted; if the eviction itself fails, that failure propagates to the caller.

[README.md#cache-eviction-semantics/2] (lines 155-155)
- In WebFlux the eviction also runs when the method completes empty (for example a `Mono<Void>` handler).

### Waiting for another request (lock not acquired)

[README.md#waiting-for-another-request-lock-not-acquired/1] (lines 159-159)
- A request that loses the lock polls the cache every 50 ms, up to `maxAttemptGetCache` times (default 60).

[README.md#waiting-for-another-request-lock-not-acquired/2] (lines 160-161)
- A cache read failure while polling is logged and counted as a failed attempt. Three consecutive failures are treated as a
  cache outage and the request falls back to calling the supplier itself right away.

[README.md#waiting-for-another-request-lock-not-acquired/3] (lines 162-163)
- When the attempts are exhausted the request calls the supplier once. A supplier failure is propagated as a
  `ClientException(SUPPLIER_ERROR)` with the original exception as `cause`; it is never turned into a cached `null`.

### Thread pools and schedulers

[README.md#thread-pools-and-schedulers/1] (lines 167-171)
- `core` accepts any `java.util.concurrent.Executor` for its background cache writes; only `execute` is called and the
  library never shuts a caller-supplied pool down. The default is a shared daemon pool. The Spring adapter runs the
  writes on a daemon pool of its own that is shut down with the context, and registers no `Executor` bean, so Spring
  Boot keeps its `applicationTaskExecutor`. To use your own pool, define an `Executor` bean named `reqShieldExecutor`;
  its lifecycle stays yours, and like any `Executor` bean it makes Spring Boot skip `applicationTaskExecutor`.

[README.md#thread-pools-and-schedulers/2] (lines 172-173)
- `core-reactor` accepts a `Scheduler` (default `boundedElastic`). The Spring WebFlux adapter uses the shared
  `boundedElastic` too and never disposes it; to use your own, define a `Scheduler` bean named `reqShieldScheduler`.

[README.md#thread-pools-and-schedulers/3] (lines 174-176)
- `core-kotlin-coroutine` accepts a `CoroutineScope` for background cache writes (default: a shared supervisor scope on
  `Dispatchers.IO`). The coroutine Spring adapter runs them on a supervisor scope of its own on `Dispatchers.IO` and
  cancels it on context shutdown; to use your own, define a `CoroutineScope` bean named `reqShieldCoroutineScope`.

[README.md#thread-pools-and-schedulers/4] (lines 177-179)
- The Spring adapters register none of these beans themselves, so an application bean of the same name replaces the
  default instead of clashing with it, and its lifecycle stays with the application. A bean of one of these names
  that is not of the expected type fails the application startup instead of being ignored.

### WebFlux adapter

[README.md#webflux-adapter/1] (lines 183-187)
- `@ReqShieldCacheable` / `@ReqShieldCacheEvict` from `core-spring-webflux` require methods that return `Mono`. The
  aspect hands back a lazy `Mono`, and other return types are not rejected up front:
    - On a `void` (`Unit`) method Spring discards that `Mono`, so the method body never runs and nothing is evicted,
      without any error. Return `Mono<Void>` from eviction methods instead.
    - Any other return type fails with a `ClassCastException` at the call site.

[README.md#webflux-adapter/2] (lines 188-188)
- Use `core-spring` for blocking methods and `core-spring-webflux-kotlin-coroutine` for `suspend` functions.

### Kotlin coroutine adapter

[README.md#kotlin-coroutine-adapter/1] (lines 192-194)
- `@ReqShieldCacheable` / `@ReqShieldCacheEvict` from `core-spring-webflux-kotlin-coroutine` require `suspend`
  functions. Annotating a regular function fails fast with an `IllegalArgumentException`; use `core-spring` or
  `core-spring-webflux` for blocking or `Mono`-returning methods.

### Contributing

[README.md#contributing/1] (lines 198-200)
Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to
change.
Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contributing to Req-Shield.

### LICENSE

[README.md#license/1] (lines 204-204)
Apache License 2.0

### How to reach us

[README.md#how-to-reach-us/1] (lines 208-209)
- File an issue in [the issue tracker](https://github.com/line/req-shield/issues) to report a bug or suggest an
  idea.


## CLAUDE.md

### CLAUDE.md

[CLAUDE.md#claude-md/1] (lines 3-3)
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

### Project Overview

[CLAUDE.md#project-overview/1] (lines 7-7)
Req-Shield is a Kotlin library that provides request-collapsing functionality for cache-based applications. It prevents the thundering herd problem by ensuring only one request for the same cache key is processed at a time, while subsequent concurrent requests wait for the result.

### Core Architecture

[CLAUDE.md#core-architecture/1] (lines 11-11)
The library is organized into several core modules:

[CLAUDE.md#core-architecture/2] (lines 13-13)
- **core**: Base implementation with synchronous operations

[CLAUDE.md#core-architecture/3] (lines 14-14)
- **core-reactor**: Reactive implementation using Project Reactor

[CLAUDE.md#core-architecture/4] (lines 15-15)
- **core-kotlin-coroutine**: Coroutine-based implementation

[CLAUDE.md#core-architecture/5] (lines 16-16)
- **core-spring**: Spring integration with traditional caching

[CLAUDE.md#core-architecture/6] (lines 17-17)
- **core-spring-webflux**: Spring WebFlux integration

[CLAUDE.md#core-architecture/7] (lines 18-18)
- **core-spring-webflux-kotlin-coroutine**: Spring WebFlux + Kotlin Coroutines integration

[CLAUDE.md#core-architecture/8] (lines 19-19)
- **support**: Shared utilities, models, and constants

### Key Components

[CLAUDE.md#key-components/1] (lines 23-23)
1. **ReqShield**: Main orchestrator that manages cache operations and request collapsing

[CLAUDE.md#key-components/2] (lines 24-24)
2. **KeyLock**: Locking mechanism (local or global) to prevent concurrent cache operations. `tryLock` returns an ownership token and `unLock` only releases when the token matches, so a slow holder cannot release someone else's lock

[CLAUDE.md#key-components/3] (lines 25-25)
3. **ReqShieldConfiguration**: Configuration object that defines cache functions, locking behavior, and timeouts

[CLAUDE.md#key-components/4] (lines 26-26)
4. **ReqShieldData**: Wrapper for cached data with metadata (creation time, TTL)

[CLAUDE.md#key-components/5] (lines 27-27)
5. **Spring Aspects**: AOP-based implementations that provide annotation-driven caching. One `ReqShield` instance per annotated method; cache and lock keys are namespaced as `"{cacheName}::{key}"`; eviction happens after the method returns successfully

[CLAUDE.md#key-components/6] (lines 28-28)
6. **GlobalLockSupport**: Optional interface a cache bean implements to enable `isLocalLock = false` (Redis `SET NX PX` + compare-and-delete). Without it, `isLocalLock = false` fails fast

### Design Patterns

[CLAUDE.md#design-patterns/1] (lines 32-32)
- **Template Method**: Core ReqShield logic is template-based with pluggable cache and lock functions

[CLAUDE.md#design-patterns/2] (lines 33-33)
- **Strategy Pattern**: Different locking strategies (local vs global) and work modes

[CLAUDE.md#design-patterns/3] (lines 34-34)
- **Aspect-Oriented Programming**: Spring modules use AOP for transparent caching

[CLAUDE.md#design-patterns/4] (lines 35-35)
- **Factory Pattern**: Configuration objects create appropriate lock implementations

### Building the Project

[CLAUDE.md#building-the-project/1] (lines 40-44)
```bash
./gradlew build                    # Build all modules
./gradlew :core:build             # Build specific module
./gradlew clean build             # Clean and build
```

### Running Tests

[CLAUDE.md#running-tests/1] (lines 47-51)
```bash
./gradlew test                    # Run all tests
./gradlew :core:test              # Run tests for specific module
./gradlew test --tests "*ReqShieldTest*"  # Run specific test pattern
```

### Code Quality

[CLAUDE.md#code-quality/1] (lines 54-58)
```bash
./gradlew ktlintCheck             # Check Kotlin code style
./gradlew ktlintFormat            # Format Kotlin code
./gradlew jacocoTestReport        # Generate test coverage report
```

### Running Examples

[CLAUDE.md#running-examples/1] (lines 61-68)
```bash
./gradlew :req-shield-spring-boot3-example:bootRun                       # Spring Boot 3.5 (the supported minimum)
./gradlew :req-shield-spring-boot3-webflux-example:bootRun
./gradlew :req-shield-spring-boot3-webflux-kotlin-coroutine-example:bootRun
./gradlew :req-shield-spring-boot4-example:bootRun                       # Spring Boot 4.1
./gradlew :req-shield-spring-boot4-webflux-example:bootRun
./gradlew :req-shield-spring-boot4-webflux-kotlin-coroutine-example:bootRun
```

### Core Modules

[CLAUDE.md#core-modules/1] (lines 73-73)
Each core module follows the same package structure:

[CLAUDE.md#core-modules/2] (lines 74-74)
- `com.linecorp.cse.reqshield.{variant}/` - Main classes (ReqShield, KeyLock implementations)

[CLAUDE.md#core-modules/3] (lines 75-75)
- `com.linecorp.cse.reqshield.{variant}/config/` - Configuration classes

### Spring Integration Modules

[CLAUDE.md#spring-integration-modules/1] (lines 78-78)
Spring modules add:

[CLAUDE.md#spring-integration-modules/2] (lines 79-79)
- `annotation/` - Cache annotations (@ReqShieldCacheable, @ReqShieldCacheEvict)

[CLAUDE.md#spring-integration-modules/3] (lines 80-80)
- `aspect/` - AOP implementation for intercepting annotated methods

[CLAUDE.md#spring-integration-modules/4] (lines 81-81)
- `cache/` - Cache interface implementations

[CLAUDE.md#spring-integration-modules/5] (lines 82-82)
- `config/` - Auto-configuration for Spring Boot

### Support Module

[CLAUDE.md#support-module/1] (lines 85-85)
Contains shared:

[CLAUDE.md#support-module/2] (lines 86-86)
- `constant/` - Configuration constants and defaults

[CLAUDE.md#support-module/3] (lines 87-87)
- `exception/` - Custom exceptions and error codes

[CLAUDE.md#support-module/4] (lines 88-88)
- `model/` - Data models (ReqShieldData)

[CLAUDE.md#support-module/5] (lines 89-89)
- `utils/` - Utility functions for cache decisions

### ReqShieldConfiguration Parameters

[CLAUDE.md#reqshieldconfiguration-parameters/1] (lines 94-94)
- `isLocalLock`: Use local vs distributed locking (default: true)

[CLAUDE.md#reqshieldconfiguration-parameters/2] (lines 95-95)
- `globalLockFunction` / `globalUnLockFunction`: `(lockKey, token, ttlMillis) -> Boolean` / `(lockKey, token) -> Boolean`, required when `isLocalLock = false`

[CLAUDE.md#reqshieldconfiguration-parameters/3] (lines 96-96)
- `executor` (core) / `scheduler` (reactor) / `scope` (coroutine): where background cache writes run; defaults are shared. The Spring adapters register none of them as beans (an `Executor` bean would make Spring Boot drop its `applicationTaskExecutor`, and any library bean would clash with an application bean of the same name): each aspect uses an application bean named `reqShieldExecutor` / `reqShieldScheduler` / `reqShieldCoroutineScope` if one exists (a bean of that name with another type fails the context refresh), otherwise its own default (an aspect-owned pool / the shared `boundedElastic` / an aspect-owned scope), and only shuts down what it owns

[CLAUDE.md#reqshieldconfiguration-parameters/4] (lines 97-97)
- `lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)

[CLAUDE.md#reqshieldconfiguration-parameters/5] (lines 98-98)
- `decisionForUpdate`: Percentage of TTL after which to trigger async cache refresh (default: 80)

[CLAUDE.md#reqshieldconfiguration-parameters/6] (lines 99-99)
- `maxAttemptGetCache`: Max retry attempts when waiting for cache (default: 60, 50ms apart). Three consecutive cache-read failures while waiting fall back to the supplier immediately; supplier failures propagate as `ClientException(SUPPLIER_ERROR)`

[CLAUDE.md#reqshieldconfiguration-parameters/7] (lines 100-100)
- `reqShieldWorkMode`: CREATE_AND_UPDATE_CACHE | ONLY_CREATE_CACHE | ONLY_UPDATE_CACHE

### Work Modes

[CLAUDE.md#work-modes/1] (lines 103-103)
- **CREATE_AND_UPDATE_CACHE**: Full functionality (default)

[CLAUDE.md#work-modes/2] (lines 104-104)
- **ONLY_CREATE_CACHE**: Request collapsing (lock) applies only when a cache entry is being created; refreshes of existing entries still happen but without a lock, so every request past the `decisionForUpdate` threshold may trigger a refresh

[CLAUDE.md#work-modes/3] (lines 105-105)
- **ONLY_UPDATE_CACHE**: Request collapsing (lock) applies only when an existing entry is being refreshed; on a cache miss every request calls the supplier and writes the cache without a lock

### Test Infrastructure

[CLAUDE.md#test-infrastructure/1] (lines 110-110)
- Uses JUnit 5 platform

[CLAUDE.md#test-infrastructure/2] (lines 111-111)
- MockK for Kotlin mocking

[CLAUDE.md#test-infrastructure/3] (lines 112-112)
- Testcontainers for integration tests (Redis); set `TEST_REDIS_HOST`/`TEST_REDIS_PORT` to use an external Redis instead (CI does this)

[CLAUDE.md#test-infrastructure/4] (lines 113-113)
- Awaitility for asynchronous testing

[CLAUDE.md#test-infrastructure/5] (lines 114-114)
- Separate test fixtures in `support` module

### Test Coverage Requirements

[CLAUDE.md#test-coverage-requirements/1] (lines 117-117)
- **Minimum test coverage**: 80% line coverage for every library module (example modules are exempt)

[CLAUDE.md#test-coverage-requirements/2] (lines 118-118)
- Coverage reports generated via `./gradlew jacocoTestReport`

[CLAUDE.md#test-coverage-requirements/3] (lines 119-119)
- Enforced by `jacocoTestCoverageVerification`, which runs as part of `./gradlew check` / `./gradlew build`

### Code Quality Requirements

[CLAUDE.md#code-quality-requirements/1] (lines 122-122)
- **Lint validation**: All code must pass ktlint checks before completion

[CLAUDE.md#code-quality-requirements/2] (lines 123-123)
- Run `./gradlew ktlintCheck` to validate code style

[CLAUDE.md#code-quality-requirements/3] (lines 124-124)
- Fix any lint issues with `./gradlew ktlintFormat`

[CLAUDE.md#code-quality-requirements/4] (lines 125-125)
- Lint validation is mandatory for all code changes

### Test Categories

[CLAUDE.md#test-categories/1] (lines 128-128)
- **Unit Tests**: Test individual components in isolation

[CLAUDE.md#test-categories/2] (lines 129-129)
- **Integration Tests**: Test Spring integration with real Redis containers

[CLAUDE.md#test-categories/3] (lines 130-130)
- **Base Test Classes**: Located in `support/src/testFixtures/` for reuse across modules

### Java/Kotlin Compatibility

[CLAUDE.md#java-kotlin-compatibility/1] (lines 135-135)
- **All modules**: Java 17+; library consumers need Kotlin 1.9+

[CLAUDE.md#java-kotlin-compatibility/2] (lines 136-137)
- **Example applications**: `req-shield-spring-boot3-*` run on Spring Boot 3.5 (the supported minimum),
  `req-shield-spring-boot4-*` run the same code on Spring Boot 4.1

### Kotlin Toolchain

[CLAUDE.md#kotlin-toolchain/1] (lines 140-140)
- The build uses the Kotlin 2.4 compiler (K2), set by `kotlin` in `libs.versions.toml`.

[CLAUDE.md#kotlin-toolchain/2] (lines 141-147)
- Library modules compile with `languageVersion`/`apiVersion` 2.0 and publish `kotlin-stdlib`/`kotlin-reflect` at
  `kotlinApi` (2.0.21) instead of the compiler's version (`kotlin.stdlib.default.dependency=false`). This keeps the
  published metadata and stdlib readable by consumers on Kotlin 1.9+, the default Kotlin of Spring Boot 3.5. Kotlin
  documents that 1.9 reads most 2.0 binaries; kotlinc 1.9.25 and 2.0.21 consumers were checked (1.8.22 also worked,
  but no Kotlin guarantee covers it, so 1.8 is not supported).
  Raising either value raises the minimum Kotlin version of consumers. Kotlin 2.4 already reports language version
  2.0 as deprecated, so a later Kotlin upgrade may force a higher value and a higher consumer minimum.

[CLAUDE.md#kotlin-toolchain/3] (lines 148-148)
- Example applications are not published and use the compiler's stdlib.

[CLAUDE.md#kotlin-toolchain/4] (lines 149-149)
- The stdlib is an `api` dependency of the library modules, so it stays in the POM's compile scope for Java callers.

### Framework Support

[CLAUDE.md#framework-support/1] (lines 152-152)
- Spring Boot 3.5+ (Spring Framework 6.2+)

[CLAUDE.md#framework-support/2] (lines 153-153)
- Project Reactor 3.7+

[CLAUDE.md#framework-support/3] (lines 154-154)
- Kotlin Coroutines 1.8+

[CLAUDE.md#framework-support/4] (lines 155-156)
- The library compiles and runs its tests against these minimums. The boot3 examples run it on Spring Boot 3.5 and
  the boot4 examples on Spring Boot 4.1; keep the two sets in sync when changing one.


## KeyLock.kt(core)

[KeyLock.kt(core)#KeyLock] (lines 19-25, KDoc of: interface KeyLock {)
Lock used to collapse concurrent requests for the same cache key.

Ownership is represented by an opaque token: only the holder that acquired the lock can
release it, so a holder whose lock already expired and was taken over by someone else
cannot release the new owner's lock.

[KeyLock.kt(core)#tryLock] (lines 27-31, KDoc of: fun tryLock()
Acquires the lock for [key] + [lockType].

@return an opaque ownership token, or null when another holder owns the lock.

[KeyLock.kt(core)#unLock] (lines 37-41, KDoc of: fun unLock()
Releases the lock for [key] + [lockType] only if [token] matches the current owner.

@return false when the lock is not held or the token does not match the current owner.


## KeyLock.kt(reactor)

[KeyLock.kt(reactor)#tryLock] (lines 22-27, KDoc of: fun tryLock()
Tries to acquire the lock for [key] and [lockType].

Emits an opaque ownership token when the lock was acquired, and completes EMPTY
when another holder currently owns the lock.

[KeyLock.kt(reactor)#unLock] (lines 33-38, KDoc of: fun unLock()
Releases the lock for [key] and [lockType] only if [token] matches the current owner.

Emits false when the lock is not held or the token does not match, so an expired lock
that was already handed to another holder can never be released by a stale owner.


## KeyLock.kt(coroutine)

[KeyLock.kt(coroutine)#tryLock] (lines 22-22, KDoc of: suspend fun tryLock()
Returns an opaque ownership token when acquired, or null when another holder owns the lock.

[KeyLock.kt(coroutine)#unLock] (lines 28-28, KDoc of: suspend fun unLock()
Releases only if [token] matches the current owner; false when not held or token mismatch.

[KeyLock.kt(coroutine)#lockKeyOf] (lines 41-41, KDoc of: internal fun lockKeyOf()
Lock keys are prefixed so that a lock entry can never collide with a cache entry.
