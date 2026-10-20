# GoDeb WireGuard Android Client

Fork of the [official WireGuard Android client](https://github.com/WireGuard/wireguard-android) with:

- **Smart Auto-Disconnect** on trusted networks (HTTP endpoint check, optional BSSID fallback)
- **GoDeb UI theme** (light / dark / system)
- Application ID `de.godeb.wireguard` (installs alongside the stock app)

## Build APK

```bash
git clone --recurse-submodules https://git.zx2c4.com/wireguard-android
# or use this fork tree
cd wireguard-android
./gradlew assembleRelease
```

Release APK:

```
ui/build/outputs/apk/release/ui-release-unsigned.apk
```

Sign with your release keystore (`apksigner` / `jarsigner`) before distributing from the GoDeb web UI.

Debug APK:

```bash
./gradlew assembleDebug
# ui/build/outputs/apk/debug/ui-debug.apk  (applicationId de.godeb.wireguard.debug)
```

Gradle properties (`gradle.properties`):

| Property | Purpose |
|----------|---------|
| `wireguardApplicationId` | `de.godeb.wireguard` |
| `wireguardPackageName` | Java namespace (`com.wireguard.android`) |
| `wireguardVersionCode` / `wireguardVersionName` | Versioning |

## Smart Auto-Disconnect

1. Enable **Auto-disconnect on trusted network** on the main screen (or in Settings).
2. Set **Trusted endpoint URL** to your GoDeb probe, e.g. `http://192.168.1.1:8080/api/trusted` (LAN IP/hostname of the Debian host).
3. Optionally add trusted Wi-Fi BSSIDs (comma-separated). BSSID is only used if the HTTP check fails.

### Endpoint contract

The monitor binds the HTTP GET to the active Wi-Fi `Network` (bypasses VPN).

Trusted if **HTTP 200** and either:

- Response header `X-GoDeb-Trusted: 1`, or
- Body is `trusted`, `ok`, or contains `"trusted":true`

Unreachable / timeout / wrong payload → network is **not** trusted → VPN is brought **up** (restoring previously auto-managed tunnels).

### Recommended GoDeb probe route

Serve a tiny authenticated-or-LAN-only handler that returns:

```http
HTTP/1.1 200 OK
X-GoDeb-Trusted: 1
Content-Type: text/plain

trusted
```

Do **not** expose this on the public internet without additional auth; the Android client treats reachability as proof of being on the home/company LAN.

## Injecting WireGuard configurations

Configs are standard `wg-quick` `.conf` files stored in the app private dir.

### 1. QR code / file import (runtime)

GoDeb already generates peer configs / QR codes. In the app:

- FAB → **Scan QR code**, or
- FAB → **Import from file** (`.conf` or `.zip` of `.conf` files)

Uses `TunnelImporter` → `FileConfigStore` → `TunnelManager.create()`.

### 2. Pre-seed configs in a custom APK build

Place one or more `*.conf` files under:

```
ui/src/main/assets/preconfigured/
```

Then load them on first launch (example hook in `Application.onCreate` / a one-shot importer):

```kotlin
// Pseudocode — copy assets/*.conf into FileConfigStore via TunnelManager.create()
assets.list("preconfigured")?.filter { it.endsWith(".conf") }?.forEach { name ->
    val config = Config.parse(assets.open("preconfigured/$name"))
    tunnelManager.create(name.removeSuffix(".conf"), config)
}
```

Build a per-tenant APK in CI by copying the peer `.conf` from GoDeb into `assets/preconfigured/` before `./gradlew assembleRelease`.

### 3. MDM / managed configuration

Trusted endpoints are configured **per tunnel** in the tunnel detail screen (`tunnel_trusted_endpoint_<name>`). Extend `res/xml/app_restrictions.xml` if you want to push those via Android Enterprise.

### 4. Programmatic control intents

With **Allow remote control apps** enabled, external apps may send:

- `com.wireguard.android.action.SET_TUNNEL_UP` / `SET_TUNNEL_DOWN`
- Extra: `tunnel` = tunnel name

Permission: `${applicationId}.permission.CONTROL_TUNNELS`  
(with `applicationId` = `de.godeb.wireguard`).

## Hosting the APK from GoDeb

1. Build and sign the APK.
2. Install on the Debian host:

```bash
sudo mkdir -p /etc/godeb/android
sudo install -m 0644 ui-release.apk /etc/godeb/android/godeb-wireguard.apk
echo '1.0.0' | sudo tee /etc/godeb/android/version.txt
```

3. The WireGuard plugin serves:
   - `GET /api/plugins/wireguard/android` — metadata + trusted endpoint hint
   - `GET /api/plugins/wireguard/android/apk` — APK download (no login required)
4. In the GoDeb UI → WireGuard → **Android client** card: download APK, copy trusted endpoint, then import peer via QR/`.conf`.

## Permissions added by this fork

- `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS` (Android 13+)
