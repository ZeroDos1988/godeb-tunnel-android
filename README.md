# GoDeb Tunnel (Android)

Custom fork of the [official WireGuard Android client](https://github.com/WireGuard/wireguard-android) for the [GoDeb](https://github.com/ZeroDos1988/GoDeb) Debian admin stack.

| | Official WireGuard | This fork |
|---|---|---|
| Application ID | `com.wireguard.android` | `de.godeb.wireguard` |
| Theme | Material default | GoDeb light / dark / system |
| Trusted network | — | Auto-disconnect via HTTP probe + optional LAN scan |
| Distribution | Play Store | GitHub Releases / GoDeb web UI |

## Download APK

See **[Releases](https://github.com/ZeroDos1988/godeb-tunnel-android/releases)** → `godeb-tunnel.apk`.

Sideload only (local signing key, not Play Store).

## Features

1. **Smart auto-disconnect** – when the phone can reach a trusted endpoint (`GET …/api/trusted` with `X-GoDeb-Trusted: 1`), the VPN is turned off; when leaving that network, it is restored.
2. **LAN scan** – Settings → Trusted network → Scan local network, then pick a discovered GoDeb host.
3. **Manual endpoint** – e.g. `http://172.16.1.20:8080/api/trusted`.
4. Works with **any** standard WireGuard server; GoDeb extras are optional.

## Build

```bash
git clone --recurse-submodules https://github.com/ZeroDos1988/godeb-tunnel-android.git
cd godeb-tunnel-android
# needs Android SDK + NDK; set sdk.dir in local.properties
./gradlew assembleRelease
```

Details: [GODEB.md](./GODEB.md)

## Remotes

- `origin` → this GoDeb fork  
- `upstream` → official WireGuard Android  

## License

Apache-2.0, like the upstream WireGuard Android client this app is based on (see `COPYING`). The app also contains `wireguard-tools` (GPL-2.0, submodule `tunnel/tools/wireguard-tools`) and `wireguard-go` (MIT); their sources are part of this repository and its submodules.

## Trademark

GoDeb Tunnel is an independent project and is not affiliated with or endorsed by the WireGuard project. "WireGuard" and the "WireGuard" logo are registered trademarks of Jason A. Donenfeld. This app does not use the WireGuard name or logo as its product name or icon.
