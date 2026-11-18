# Forwarded headers

The API runs behind a TLS-terminating ingress (docs/PLAN.md §3, §8.1, D16). The app sees plain HTTP from the ingress,
so the real client address and scheme must be recovered from `X-Forwarded-For` and `X-Forwarded-Proto`, and only when
the request really came through a trusted proxy.

## Decision
`server.forward-headers-strategy: native`: Tomcat's `RemoteIpValve`, configured through `server.tomcat.remoteip.*`
(`remote-ip-header: x-forwarded-for`, `protocol-header: x-forwarded-proto`). The valve honours the headers only when
the immediate peer matches `internal-proxies`, and it resolves the client address by walking `X-Forwarded-For` from the
right past trusted proxies only. Given the conditions under "What is trusted", a client-supplied prefix cannot choose
the address.

Not `framework`: it installs Spring's `ForwardedHeaderFilter`, which honours `Forwarded` and `X-Forwarded-*` from any
peer and has no trusted-proxy setting. With `native` the trust boundary is configurable and can be narrowed to the
ingress; see the caveat below about the default range.

## What is trusted
- Default `internal-proxies` (Boot's): a CIDR list covering loopback, 10/8, 172.16/12, 192.168/16, 169.254/16,
  100.64/10 and IPv6 loopback, link-local and ULA. It is deliberately not set in `application.yaml`.
- **Caveat:** until the range is narrowed, any peer in those ranges (in a typical cluster, every pod) is fully trusted.
  Such a peer can choose its client address (any string, not necessarily a valid IP) and its scheme.
- Override per environment with `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`: a comma-separated CIDR list (preferred, e.g.
  `10.1.0.0/16`) or, if the value contains no `/`, a single Java regex. The regex must match the whole peer IP, and IPv6
  peers appear uncompressed (`0:0:0:0:0:0:0:1`), which is why CIDR is preferred. Setting the value replaces the default
  entirely, so loopback stops being trusted unless it is listed.
- `X-Forwarded-For` is read right to left, skipping trusted proxies; the first untrusted address is `request.remoteAddr`.
  `X-Forwarded-Proto: https` marks the request secure.
- The prefix guarantee holds only if the address the ingress appends is outside `internal-proxies`, i.e. the ingress
  must see the real client IP. If a load balancer or node SNATs the client into a trusted private range, the walk
  skips it and may return a client-supplied address instead.
- `X-Forwarded-Host` and `X-Forwarded-Port` are also honoured from internal proxies (Boot's defaults for
  `host-header` and `port-header`). They change `serverName`, `serverPort` and any absolute URL built from the request.
  Disabling them is tracked as a backlog item.

## Operator checklist
- The ingress must set `X-Forwarded-Proto` and append the real peer address to `X-Forwarded-For`.
- The ingress must set or overwrite `X-Forwarded-Host` and `X-Forwarded-Port`, and never pass client-supplied values
  through.
- The ingress must see the real client IP (no SNAT into the trusted ranges).
- Once NetworkPolicies exist, narrow `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to the ingress pod CIDR.

## Consumers
- Rate limiting and any per-client logic use `request.remoteAddr` only and never parse `X-Forwarded-For` themselves.
- HSTS is written by Spring Security only for secure requests, so it depends on `request.isSecure`.
- The client address is personal data and is not added to the wide event.

## Phase 6 TODO
Cloudflare: add its ranges to `server.tomcat.remoteip.trusted-proxies`, or have the ingress restore the client IP from
`CF-Connecting-IP`.
