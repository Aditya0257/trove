# Trove — Mobile (Flutter)

Camera-first client over the Trove API. Single codebase (Android + iOS). Built around
the **Notice System** (see `DECISIONS.md` → D23): every request is legible — two-channel
toasts (user message + expandable developer note) and an in-app **Developer drawer**.

## First-time setup

This repo tracks the source (`lib/`, `pubspec.yaml`) but **not** the generated platform
folders. On a machine with the Flutter SDK (≥ 3.27):

```bash
cd mobile
flutter create .            # generates android/ ios/ (and web/) around the existing lib/ — non-destructive
flutter pub get
flutter analyze             # sanity-check types/lints before running
```

Grant camera/photo permissions when prompted (image_picker adds the entries; on iOS you
may need to add NSCameraUsageDescription / NSPhotoLibraryUsageDescription to Info.plist).

## Run

```bash
# Android emulator → host machine's backend is reachable at 10.0.2.2
flutter run --dart-define=TROVE_API_BASE=http://10.0.2.2:8080

# iOS simulator → use localhost
flutter run --dart-define=TROVE_API_BASE=http://localhost:8080

# Against a deployed API
flutter run --dart-define=TROVE_API_BASE=https://api.trove-sync.duckdns.org
```

Dev login (matches the backend seed): `dev@trove.local` / `devpassword`.

## Structure

```
lib/
  core/
    config.dart                 # API base (dart-define), tunables
    api/api_client.dart         # Dio wrapper — the Notice System hook (times, logs, parses notices)
    api/api_exception.dart
    notice/                     # Notice model, NoticeCenter (toasts), DeveloperLog (drawer)
    auth/                       # AuthStore (secure storage), AuthController
    models/                     # user, space, category, document (+ extractionNotice)
    providers.dart              # Riverpod wiring
  ui/
    theme.dart                  # Material 3, light+dark
    widgets/notice_toast.dart   # two-channel toast
    widgets/dev_drawer.dart     # in-app "inspect" surface
  features/
    auth/login_screen.dart
    home/home_shell.dart        # spaces + capture FAB + Developer drawer
    documents/                  # capture → confirm (upload + human review)
  main.dart                     # bootstrap, go_router, global NoticeHost overlay
```

## Notes

- Uses `Color.withValues(...)` → **Flutter ≥ 3.27**.
- No codegen: models use hand-written `fromJson` (no build_runner needed).
- Errors never hide: the ApiClient toasts failures and logs every call to the
  Developer drawer with the server's `X-Trove-Request-Id`.

## Reminder notifications (on-device, free)

Reminders (7/1/0 days before a due/renewal/warranty date) are scheduled on-device via
`flutter_local_notifications` — no server, no cost. After `flutter create .`, add the
platform bits the plugin needs:

**Android** (`android/app/src/main/AndroidManifest.xml`, inside `<manifest>`):
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
```
And enable core-library desugaring in `android/app/build.gradle` (the plugin uses Java 8
time APIs):
```gradle
android { compileOptions { coreLibraryDesugaringEnabled true } }
dependencies { coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.2' }
```
Scheduling is **inexact** (`AndroidScheduleMode.inexactAllowWhileIdle`), so the
restricted `SCHEDULE_EXACT_ALARM` permission is intentionally not required.

**iOS** — permission is requested at runtime by `NotificationService.init()`; no
Info.plist key is needed for local notifications. If you want alerts while the app is
foregrounded, set the `UNUserNotificationCenter` delegate in `AppDelegate.swift` per the
plugin's README.
