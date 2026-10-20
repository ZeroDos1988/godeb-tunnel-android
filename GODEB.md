# GoDeb WireGuard Android Client

Fork of the [official WireGuard Android client](https://github.com/WireGuard/wireguard-android) with:

- **Smart Auto-Disconnect** on trusted networks (signed GoDeb trusted endpoint, key pinned per tunnel)
- **GoDeb UI theme** (light / dark / system)
- Application ID `de.godeb.wireguard` (installs alongside the stock app)

## Build APK

```bash
git clone --recurse-submodules https://git.zx2c4.com/wireguard-android
# or use this fork tree
cd wireguard-android
./gradlew assembleRelease
```

The release build is signed automatically when a keystore description exists
outside the repository, by default `~/.config/godeb-android/keystore.properties`
(or `-PgodebKeystoreProperties=<file>` / `GODEB_KEYSTORE_PROPERTIES`):

```properties
storeFile=/home/<user>/.config/godeb-android/godeb-release.jks
storePasswordFile=/home/<user>/.config/godeb-android/password
keyAlias=godeb
```

The output is then `ui/build/outputs/apk/release/ui-release.apk`; without the
file it stays `ui-release-unsigned.apk`. Keep the keystore and its password
backed up: Android only installs updates signed with the same key. Losing
it means uninstalling the app (and its tunnels) on every phone.

Check the signature: `apksigner verify --print-certs ui/build/outputs/apk/release/ui-release.apk`.

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

Per tunnel. The tunnel goes down while the phone is on a network where the
GoDeb trusted endpoint answers with a valid signature, and comes back up
when it leaves that network.

Setup is automatic: a GoDeb hub writes the endpoint and its public key into
every client configuration, and importing it (QR code or file) turns
auto-disconnect on for that tunnel:

```
# GoDeb-Trusted-Endpoint: http://192.168.178.50:8787/godeb-trust
# GoDeb-Trusted-Key: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…
```

On the GoDeb side: `godeb trust enable` (or WireGuard → Trusted Endpoint in
the web UI) on the home server, then create or re-sync the phone peer on the
hub. Manual setup: paste URL and key in the tunnel detail view.

### Endpoint contract (godeb-trust-v1)

The monitor binds the request to the active Wi-Fi `Network`, so it never
goes through the tunnel.

```http
GET /godeb-trust?nonce=<32 random base64url chars>

200 {"v":1,"nonce":"<same nonce>","sig":"<base64 DER ECDSA>"}
```

Trusted only if `v` is 1, the nonce matches and `sig` verifies with
`SHA256withECDSA` over `"godeb-trust-v1\n" + nonce` against the pinned key
(base64 DER SubjectPublicKeyInfo, P-256). Reachability alone, headers or
body text do not count: any network could answer on the same address. No
key pinned → never trusted. The old header probe (`/api/trusted`) and the
LAN scan were removed for that reason.

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
