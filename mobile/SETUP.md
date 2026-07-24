# Running the Trove mobile app (Flutter)

This guide gets the Flutter app running on a **real phone over USB**, which is the
right way to test it (the camera/scan flow needs a physical device; emulators fake
the camera). Everything here is **no-admin** where possible.

> Why a real device and not Docker: Docker Desktop on macOS runs containers in a Linux
> VM with **no USB passthrough**, so a container cannot see a plugged-in Android phone,
> and iOS builds only run on macOS (never in a Linux container). Docker is fine for
> headless `flutter analyze` / `flutter test`, not for running the app on a phone.

---

## 1. Flutter SDK (already installed here)

The SDK lives at `~/development/flutter` (installed via `git clone`, no admin). Put it
on your PATH so `flutter` works in any terminal:

```bash
echo 'export PATH="$HOME/development/flutter/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
flutter --version   # Flutter 3.44.x, Dart 3.12.x
```

Then fetch the app's packages:

```bash
cd <repo>/mobile
flutter pub get
```

## 2. VSCode extensions

Install **Flutter** and **Dart** (the Flutter one pulls in Dart). Reload VSCode. You'll
get device selection in the status bar, Run/Debug, and hot reload.

## 3. Pick your phone

### Android (any Android phone) — simplest
1. Install the Android toolchain (SDK + platform-tools). Easiest no-fuss option:
   ```bash
   brew install --cask android-commandlinetools   # or Android Studio if you prefer a GUI
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   flutter config --android-sdk "$(brew --prefix)/share/android-commandlinetools"
   flutter doctor --android-licenses   # accept all
   ```
2. On the phone: **Settings -> About -> tap Build number 7x** to unlock Developer
   options, then **Developer options -> enable USB debugging**.
3. Plug in via USB-C, tap **Allow** on the "trust this computer" prompt.
4. Confirm it's seen:
   ```bash
   flutter devices        # your phone should be listed
   ```

### iPhone — needs Xcode (you're on a Mac)
1. Install **Xcode** from the App Store, then:
   ```bash
   sudo xcodebuild -license accept
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   brew install cocoapods
   ```
2. Open `mobile/ios/Runner.xcworkspace` in Xcode once -> select the **Runner** target
   -> **Signing & Capabilities** -> pick your personal **Apple ID team** (a free Apple ID
   works for running on your own device). Set a unique bundle id if prompted.
3. Plug in the iPhone, unlock it, tap **Trust**. First run also asks you to trust the
   developer profile on the phone: **Settings -> General -> VPN & Device Management**.

## 4. Point the app at your backend (important)

On a phone, `localhost` is the **phone**, not your laptop. The app reads its API base
from a `--dart-define`:

- **Android over USB (recommended):** map the phone's localhost to your laptop, then the
  built-in default works:
  ```bash
  adb reverse tcp:8080 tcp:8080
  flutter run --dart-define=TROVE_API_BASE=http://localhost:8080
  ```
- **iPhone (or Android on Wi-Fi):** use your laptop's LAN IP (same Wi-Fi network).
  Find it with `ipconfig getifaddr en0`, then:
  ```bash
  flutter run --dart-define=TROVE_API_BASE=http://192.168.1.42:8080
  ```
  Make sure the backend is reachable on the LAN (it listens on `:8080`).

The backend must be running (`java -jar ...` against Neon, as in dev) before you sign in.

## 5. Run + hot reload

```bash
cd <repo>/mobile
flutter run --dart-define=TROVE_API_BASE=<see above>
```
While it runs: press **r** for hot reload, **R** for hot restart, **q** to quit. In
VSCode, just press **F5** (add the dart-define to `.vscode/launch.json`, see below).

`.vscode/launch.json`:
```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Trove (device)",
      "request": "launch",
      "type": "dart",
      "program": "mobile/lib/main.dart",
      "args": ["--dart-define=TROVE_API_BASE=http://localhost:8080"]
    }
  ]
}
```

## 6. Sanity checks

```bash
flutter doctor        # green ticks for the platform you chose
flutter analyze       # static analysis (should be clean)
flutter test          # unit/widget tests, if any
```

## Troubleshooting

- **`flutter devices` doesn't list the phone (Android):** re-plug, tap Allow on the
  phone, `adb kill-server && adb start-server`, try a different cable (some are
  charge-only).
- **App loads but every request fails:** the API base is wrong. Android USB -> did you run
  `adb reverse tcp:8080 tcp:8080`? Wi-Fi/iPhone -> is the laptop IP right and on the same
  network, and is the backend actually up on `:8080`?
- **iOS "Untrusted Developer":** trust the profile under Settings -> General -> VPN &
  Device Management.
- **HTTP (not HTTPS) blocked:** dev uses plain HTTP; the app is configured to allow
  cleartext to your dev host. For production use the HTTPS URL of your deployed backend.
