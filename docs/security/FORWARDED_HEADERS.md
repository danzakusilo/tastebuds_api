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
peer and has no trusted-proxy setting. With `native` the trust boundary is configurable; see the caveats below about
the default range and the operator checklist.

## What is trusted
- Default `internal-proxies` (Boot's): a CIDR list covering loopback, 10/8, 172.16/12, 192.168/16, 169.254/16,
  100.64/10 and IPv6 loopback, link-local and ULA. It is deliberately not set in `application.yaml`.
- **Caveat:** any peer in the trusted ranges (with the default, every pod of a typical cluster) is fully trusted. Such
  a peer can choose its client address (any string, not necessarily a valid IP) and its scheme, and it can make the pod
  resolve host names of its choice (next item).
- **DNS lookups:** with a CIDR list (Boot's default), Tomcat passes each `X-Forwarded-For` entry it walks to
  `InetAddress.getByName`. An entry that is a host name causes a DNS lookup on the request thread, before rate
  limiting. A name that resolves into a trusted range counts as a proxy and the walk goes on, so one header can cause
  one lookup per entry. The walk starts only when the peer is trusted and stops at the first untrusted entry: names to
  the left of an untrusted address, such as the real client address the ingress appends, are never resolved. A regex
  value never resolves names; a CIDR list in `trusted-proxies` resolves them in the same way. A fix is tracked as a
  backlog item.
- Override per environment with `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`: a comma-separated CIDR list (e.g.
  `10.1.0.0/16`) or, if the value contains no `/`, a single Java regex. The regex must match the whole peer IP, and IPv6
  peers appear uncompressed (`0:0:0:0:0:0:0:1`), which is why CIDR is preferred despite the DNS lookups above. Setting
  the value replaces the default entirely, so loopback stops being trusted unless it is listed.
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
- Do not try to narrow `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to the ingress pods: no address range holds only them.
  Pod addresses come from a per-node range that every pod on the node shares (k3s: `10.42.0.0/16`, one `/24` per
  node), and a recreated pod gets a new address, possibly on another node. A node range trusts every pod on that node.
  When the ingress runs outside the listed range, its requests come from an untrusted peer: every client gets the
  ingress address (one shared rate-limit bucket) and no request is secure (no HSTS).
- Limit who can connect instead: once NetworkPolicies exist, admit port 8080 of the api pods only from the ingress
  controller's pods.

## Consumers
- Rate limiting and any per-client logic use `request.remoteAddr` only and never parse `X-Forwarded-For` themselves.
- HSTS is written by Spring Security only for secure requests, so it depends on `request.isSecure`.
- The client address is personal data and is not added to the wide event.

## Phase 6 TODO
Cloudflare: add its ranges to `server.tomcat.remoteip.trusted-proxies`, or have the ingress restore the client IP from
`CF-Connecting-IP`.
