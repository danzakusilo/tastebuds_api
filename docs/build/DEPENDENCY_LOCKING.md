# Dependency locking

Every Gradle project (root, `libs/observability`, `services/core`, and the included `build-logic` build) locks all
configurations. Resolution is therefore reproducible, and Trivy's filesystem scan in CI reads `gradle.lockfile` to see
the real dependency graph (without lockfiles it sees nothing).

Lock mode is Gradle's default: a resolved version that differs from the lockfile fails the build, and a configuration
without lock state is allowed.

## Lockfiles
- `gradle.lockfile`, `libs/observability/gradle.lockfile`, `services/core/gradle.lockfile`
- `build-logic/gradle.lockfile`
- `settings-gradle.lockfile` and `build-logic/settings-gradle.lockfile`: written by Gradle for the settings-level
  configuration that resolves the `libs` version catalog (`incomingCatalogForLibs0`). They are nearly empty
  (`empty=incomingCatalogForLibs0`) but Gradle creates them whenever locking is on, so they are committed.

## Refreshing the locks
```bash
./gradlew resolveAndLockAll --write-locks --no-configuration-cache
./gradlew -p build-logic resolveAndLockAll --write-locks --no-configuration-cache
```
`resolveAndLockAll` resolves every resolvable configuration at execution time, which the configuration cache does not
support. It is marked incompatible, so without `--no-configuration-cache` the build still succeeds but prints a wall of
configuration cache problems; the flag silences them.

Run both commands after any change to `gradle/libs.versions.toml`, to build scripts, or to plugin versions, and commit
the changed lockfiles. Renovate PRs already include refreshed lockfiles.

## Stale lockfile
If the catalog or a plugin version changes without refreshing the locks, what you see depends on the change:

- **Upgrade:** `./gradlew check` fails during dependency resolution, naming the module and configuration, for example
  ```
  Could not resolve io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.29.0-alpha.
  > Cannot find a version of '...:opentelemetry-logback-appender-1.0' that satisfies the version constraints:
     Dependency path: project ':libs:observability' (compileClasspath) --> '...:2.29.0-alpha'
     Constraint path: project ':libs:observability' (compileClasspath) --> '...:{strictly 2.28.0-alpha}'
     because of the following reason: Dependency version enforced by Dependency Locking
  ```
- **New dependency:** resolution fails with "... which is not part of the dependency lock state".
- **Downgrade:** the build passes silently and stays on the locked (higher) version until you refresh the locks, so
  always run the commands above after touching the catalog.

## Configurations excluded from locking
None.
