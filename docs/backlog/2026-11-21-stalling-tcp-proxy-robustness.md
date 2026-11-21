# StallingTcpProxy stops on one failed connect and leaves half-closed pairs
- **Found during:** 012 (review round 1)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/StallingTcpProxy.kt
- **Severity:** low
- **What:** One failed upstream connect ends the accept loop, and EOF on one side does not close the other socket.
  This is fine for the current tests (one connection, explicit shutdown).
- **Why it matters:** A future test that reuses the helper with reconnects or several connections would hang or see
  a dead proxy without a clear error.
- **Suggested fix:** Catch the connect failure per connection and keep accepting; close the peer socket when one side
  reaches EOF.
