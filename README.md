# Raknetify2

<img src="icon.png" alt="Raknetify2" width="256">

Raknetify2 is a Minecraft networking mod that uses RakNet over UDP to help keep multiplayer interactions responsive during packet loss and large world transfers.

[Downloads](https://github.com/pjscruggs/raknetify2/releases) · [Wiki and setup guides](https://github.com/pjscruggs/raknetify2/wiki) · [Choose your language (145 languages)](https://github.com/pjscruggs/raknetify2/wiki/Languages)

## How it works

Minecraft normally uses TCP, which delivers data in one ordered stream. A missing piece can delay everything behind it. Raknetify2 separates traffic into ordering channels, so a delay in one channel need not hold up unrelated traffic in another.

Raknetify2 changes the network transport, not Minecraft's game protocol. Connecting different Minecraft versions still requires a compatible protocol translation setup.

## Downloads

Download from [GitHub Releases](https://github.com/pjscruggs/raknetify2/releases). Choose the file for your platform and check its supported Minecraft versions before installing.

| Platform | File | Install location |
| --- | --- | --- |
| Fabric client or server | `raknetify2-fabric-*.jar` | `mods` |
| Forge client or server | `raknetify2-forge-*.jar` | `mods` |
| NeoForge client or server | `raknetify2-neoforge-*.jar` | `mods` |
| Spigot or Paper server | `raknetify2-spigot-*.jar` | `plugins` |
| Velocity proxy | `raknetify2-velocity-*.jar` | `plugins` |
| BungeeCord proxy | `raknetify2-bungee-*.jar` | `plugins` |

The included `compatibility.json` lists supported Minecraft versions and runtime requirements for Fabric. For Forge, NeoForge and Spigot/Paper, check the Minecraft version declared by that JAR.

Development builds are available from [GitHub Actions](https://github.com/pjscruggs/raknetify2/actions/workflows/build.yml). Open a successful run for `main` and download its `Artifacts` archive. A GitHub account is required to download these builds.

## Direct server setup

1. Install the matching Raknetify2 mod on the client. Install the matching mod or Spigot/Paper plugin on the server using the table above.
2. Allow UDP traffic on the server's Minecraft port, using the same port number as TCP. Configure both the firewall and port forwarding when applicable.
3. Connect using an address such as `raknet;example.com` or `raknet;example.com:25565`.

Install Raknetify2 in place of Raknetify. Do not install both. Normal server addresses continue to use TCP.

Encrypted RakNet connections require the authenticated encryption protocol on both ends. Original Raknetify and older Raknetify2 releases cannot connect to updated clients or servers over encrypted RakNet.

## Proxy setup

Install the matching plugin on Velocity or BungeeCord and the matching client mod on each connecting client. Allow UDP on the proxy's listening port. Clients use the `raknet;` prefix with the proxy address. Backend servers do not need Raknetify2 for this setup. Both proxy plugins forward backend keepalive requests to the client in both delivery modes.

## Ordered mode

Normally, Raknetify2 lets independent packet channels continue while one channel waits for missing data. Ordered mode puts all Minecraft packets on one channel, so a missing packet holds up every later packet in that direction until it is retransmitted. This preserves the order expected by some anti-cheat software, but gives up Raknetify2’s ability to keep unrelated game traffic moving during that wait.

Minecraft uses clientbound packet bundles to apply a group of updates in the same client tick. With updated endpoints, multichannel mode delivers each bundle together. A bundle containing packets from multiple ordering channels waits for earlier writes, and later writes wait for that bundle. Bundles confined to one channel use that channel normally. Older peers retain per-packet multichannel delivery. Ordered mode preserves vanilla bundles on its single channel.

Create `config/raknetify2-compatibility.json` in the working directory of the server or Minecraft proxy accepting RakNet connections:

```json
{"mode": "ordered"}
```

All Minecraft packets use one reliable ordered channel, including bundle delimiters and world transitions. Configure this at the proxy even if the anti-cheat runs on a backend. The policy applies across backend switches.

Keep anti-cheat checks enabled and validate your particular setup.

Older clients can use the ordinary TCP address without `raknet;`. An absent file or `"mode": "multichannel"` retains ordinary behavior. Invalid settings reject RakNet connections.

## Connection diagnostics

The HUD follows the language selected in Minecraft. Raknetify2 uses the JVM’s display language for its operator messages. To select a specific Minecraft locale on a server or proxy, add a JVM option such as `-Draknetify.language=fr_ca` before `-jar`.

The client debug HUD shows RakNet latency, traffic and queue statistics. On Minecraft versions with configurable debug entries, enable `raknetify:raknetify_debug_info` in the debug screen options. The client requests peer statistics while that entry is displayed, including when set to Always On. Earlier versions show the lines in the F3 overlay.

Servers and proxies keep their measurements private by default. The client asks once when diagnostics are needed. If the server declines or the peer does not answer, the HUD continues showing local measurements. An accepting server can share its measurements by creating `config/raknetify2-diagnostics.json` in its working directory and restarting:

```json
{
  "shareServerMetrics": true,
  "disconnectOnRejectedRequests": true
}
```

`shareServerMetrics` defaults to `false`. `disconnectOnRejectedRequests` defaults to `true`. After the transport acknowledges a refusal, the server allows three seconds for requests already in flight and closes the connection after three further requests counted at least one second apart. Set `disconnectOnRejectedRequests` to `false` to ignore continued requests. Unsolicited reports from older clients do not count as requests.

Starting a Fabric, Forge or NeoForge endpoint with `-Draknetify.debug=true` also requests peer statistics for its own connection exception logs. Each endpoint can send at most one solicited sample per second.

## UDP proxies and PROXY protocol v2

To preserve client IP addresses behind a UDP proxy, create `config/raknetify2-proxy-protocol.json` on the server or Minecraft proxy receiving the forwarded traffic:

```json
{
  "trustedProxies": ["192.0.2.10"]
}
```

Replace the example with the UDP proxy's actual source IP address or CIDR range, then restart. Configure that proxy to send PROXY protocol v2 headers for UDP. Raknetify2 accepts forwarded client addresses only from the listed sources. Direct UDP connections are rejected while this configuration is enabled. The TCP listener keeps its existing configuration.

This setting trusts the listed proxies to identify clients. Use only addresses you control and restrict backend access in the firewall. An absent file leaves ordinary direct connections enabled. An invalid file prevents the RakNet listener from starting.

## Sharing a port with Simple Voice Chat

This integration supports Fabric, Forge, NeoForge, Spigot and Paper servers. Install [Simple Voice Chat](https://modrepo.de/minecraft/voicechat/wiki/server_setup) on the server and on each client that wants voice chat.

1. Create `config/raknetify2-voicechat.json` in the server directory containing `{"enabled": true}`.
2. Set `port=-1` in Simple Voice Chat's server configuration. The file is `config/voicechat/voicechat-server.properties` on modded servers or `plugins/voicechat/voicechat-server.properties` on Spigot/Paper.
3. Leave `bind_address` empty unless you need a specific interface. An explicit address must match the RakNet listener. If RakNet uses a different port from Minecraft, set the voice `port` to that UDP port instead of `-1`.
4. Restart the server and run `/voicechat test <PLAYERNAME>` to check a connected player's voice connection.

Raknetify2 supplies the shared UDP socket through Simple Voice Chat's API. Simple Voice Chat still authenticates players and encrypts voice traffic. Clients can use voice chat with either TCP or RakNet gameplay connections.

Keep Query and Geyser on separate UDP ports. This integration shares the server socket with Simple Voice Chat only. It does not share the voice-proxy socket on Velocity or BungeeCord. For those networks, follow [Simple Voice Chat's proxy setup](https://modrepo.de/minecraft/voicechat/wiki/proxy_setup) and give the voice proxy its own UDP port. For a generic UDP tunnel, set `voice_host` to the public hostname and UDP port that forwards to this server.

## Automatic transfers

Fabric servers can select RakNet automatically when transferring players to configured destinations. Create `config/raknetify2-transfers.json` on the server that sends the transfer:

```json
{
  "destinations": [
    {
      "host": "play.example.com",
      "tcpPort": 25565,
      "udpPort": 25570
    }
  ]
}
```

Restart the server after editing this file. Transfer players to the ordinary hostname and TCP port. Clients that advertise support for automatic RakNet transfers use the configured UDP port. Other clients and unmatched destinations keep the original transfer address.

The destination must already accept RakNet on that UDP port. This file does not open a listener or configure a firewall. Hostnames and TCP ports must match an entry exactly, apart from hostname capitalization and equivalent IP spelling.

## License and credits

Raknetify2 is an independently maintained fork of [Raknetify](https://github.com/RelativityMC/raknetify), originally developed by ishland. It retains Raknetify's MIT license and original copyright notices. The networking implementation builds on ishland's Raknetify and [netty-raknet](https://github.com/RelativityMC/netty-raknet).
