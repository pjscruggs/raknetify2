# Security policy

Security fixes are maintained on `main`. Use the latest Raknetify2 build for
your platform and keep Minecraft, your mod loader, and any server or proxy
software updated independently.

## Host dependencies

Raknetify2 scans dependencies bundled in its release JARs. The compatibility
inventory also lists libraries supplied by supported hosts, including Netty,
LZ4, Guava, and Log4j. A Raknetify2 update cannot patch the copies loaded by
Minecraft, a loader, or a proxy. A compatible host version can still contain
libraries with open advisories.

Before exposing a server or proxy to untrusted clients, check that its vendor
has fixed the advisories that apply to that installation. If a legacy version
has no vendor fix, keep it on a private network with access limited to trusted
players. Use a dedicated, unprivileged account for each server or proxy.

## Reporting a vulnerability

[Report a vulnerability privately](https://github.com/pjscruggs/raknetify2/security/advisories/new).
Include the Raknetify2 build, Minecraft version, loader or proxy, and the
observed impact. Report issues in host-supplied libraries to the appropriate
platform maintainer as well.
