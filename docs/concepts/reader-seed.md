# Reader

Senior Android and Kotlin Multiplatform developer. New to backend work, operations and this codebase.

`/brief-pr` copies this file to `.claude/reader.local.md` (git-ignored) the first time it runs. Edit that copy: it is
the list the explainer and the cold reader go by. **A concept that is not listed below counts as unknown**, however
common it is, and must be explained before its first use.

## Known (baseline)
- Kotlin: the language, coroutines, `Flow`, structured concurrency
- Threads, locks, atomics, race conditions
- HTTP: methods, status codes, headers; REST; JSON
- An HTTP client: interceptors, timeouts, retries, caching with ETag (OkHttp, Ktor client)
- Tokens from the client's side: storing them, sending them, refreshing them
- Dependency injection (Koin, Hilt)
- App architecture: layers, repositories, use cases, ViewModel and state
- A local database: SQL basics, Room or SQLDelight, migrations on the device
- Serialization (kotlinx.serialization)
- Gradle: modules, version catalog, convention plugins, build variants
- Tests: unit tests, fakes and mocks, running tests on an emulator
- Git, pull requests, CI as a user (GitHub Actions)
- The product terms in the `tastebuds_spec` glossary (media, take, vibe, match, pick, anon session, ...)

## Known (cards passed)
<!-- `/brief-pr` adds a line here after a teach-back answer is right: `- <card slug> (<YYYY-MM-DD>)` -->

## Did not land
<!-- `/brief-pr` adds a line here when an explanation failed:
     `- <term>: <what was tried>; <what worked, if anything>` -->
