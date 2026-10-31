# etc hosts

A tiny Android app that gives your phone an `/etc/hosts` file, without root.

It runs a local, DNS-only VPN. Names in your hosts list are answered on the
phone; every other query is forwarded to the upstream resolver you pick. Only
DNS packets enter the VPN, so all other traffic keeps using your normal
network untouched.

Use it to point a phone at a test server, a staging box or a machine on your
LAN by name, the same way you would edit `/etc/hosts` on a laptop.

## Install

Download the APK from the [Releases](../../releases) page on the phone and
open it (allow "install unknown apps" for your browser when asked), or build
it yourself (below).

Release APKs are debug-signed by CI with a throwaway key, so a newer release
may refuse to install over an older one. Uninstall the old version first.

## Use

1. Type or paste your hosts list, in plain `/etc/hosts` syntax:

   ```
   # IP          hostname [hostname...]
   192.0.2.10    app.example.test api.example.test
   2001:db8::10  app.example.test
   192.0.2.20    *.dev.example.test
   ```

   Or tap **Import file** to load one (it replaces the text box).
2. Pick an upstream for every other name: Cloudflare or Google, over DoH
   (HTTPS) or plain UDP port 53.
3. Turn the switch on and accept the VPN prompt (once). A Quick Settings tile
   ("etc hosts") toggles it too; add it from the tile editor.

Edits to the hosts list and the upstream apply **live** while it is running.
The status line shows how many queries were answered locally, forwarded and
failed, and lists the line numbers of hosts lines it could not parse.

Matching rules:

- Names are matched case-insensitively and exactly. `*.example.test` matches
  any name under `example.test` (not `example.test` itself); an exact entry
  wins over a wildcard and the most specific wildcard wins.
- A queries get the name's IPv4 addresses, AAAA queries its IPv6 addresses.
  If a listed name has no address of the asked type (or the query is another
  type, such as HTTPS), the answer is empty, so the phone does not fall back
  to the real public address. Answers carry a 60 s TTL.
- `0.0.0.0 ads.example.test` works as a simple blocker.

## Traps (read these if it "does not work")

- **Chrome "Use secure DNS".** If set to a specific provider, Chrome sends
  DNS over HTTPS itself and never asks the system. Set it to the
  automatic/default option or turn it off (Settings > Privacy and security >
  Use secure DNS). Other browsers with their own DoH setting behave the same.
- **Android Private DNS.** If set to a provider hostname, the system resolver
  uses that provider and bypasses this app. Set it to Automatic or Off
  (Settings > Network & internet > Private DNS).
- **One VPN at a time.** Android allows a single active VPN. Starting another
  VPN app stops this one, and vice versa.
- **Battery optimisation.** Some vendors kill foreground services
  aggressively. If it switches itself off, exempt the app from battery
  optimisation (Settings > Apps > etc hosts > Battery > Unrestricted).
- **Caches.** Apps and the system cache DNS answers. After changing an entry,
  wait for the TTL (60 s for local answers, longer for upstream ones) or
  restart the app you are testing.
- **Plain DNS may be blocked.** Some networks only allow HTTPS out; pick a
  DoH upstream there. Answers bigger than one UDP packet that need a retry
  over TCP are not supported.

## How it works

The VPN interface gets `10.255.255.1/32` and `fd00:e7c:4057::1/128`, and
announces two resolvers that do not exist, `10.255.255.2` and
`fd00:e7c:4057::2`. The only routes added are those two resolver addresses,
so the tun receives DNS and nothing else. The app reads each UDP packet,
answers from the hosts table or forwards the raw query upstream (UDP sockets
are `protect()`ed, and the app itself is excluded from its own VPN so DoH
lookups never loop), then writes the reply back as an IPv4/IPv6 UDP packet
with fresh checksums. At most 16 queries are in flight; beyond that, and on
upstream failure or timeout (4 s), the client gets SERVFAIL.

No analytics, no other network traffic. Permissions: internet, foreground
service, notifications (the required "running" notification).

## Build

Needs JDK 17 and the Android SDK (platform 35).

```
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # or set ANDROID_HOME
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. CI builds every
push and attaches the APK to a GitHub Release for each `v*` tag.

Debug logging is off by default. To see per-query lines:

```
adb shell setprop log.tag.etchosts DEBUG
adb logcat -s etchosts
```

## License

MIT
