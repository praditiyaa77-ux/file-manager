# File Manager Pro v2 — Kotlin + Shizuku

Android native file manager foundation, configured with Android SDK 36 / Android 16 compile compatibility.

## What's new
- Shizuku API 12.2.0 and provider integration.
- Shizuku binder availability and permission checks.
- Advanced browser for `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb`.
- Shell-based directory listing, create folder, rename and delete.
- Normal storage browser using SAF.
- GitHub Actions builds a debug APK artifact.

## Important limitations
- This is **ADB/Shizuku mode**, not root. Shizuku must be running and the user must grant this app permission.
- On non-root devices, Shizuku must be started again after reboot, commonly through Wireless Debugging pairing/start steps in the Shizuku app.
- Android 16 and OEM firmware (including Infinix XOS) may restrict shell access to specific paths or operations. The app reports shell errors; it cannot guarantee access to every app directory or every file operation.
- Shizuku's `newProcess` API is deprecated/being phased out in favor of UserService in upstream guidance. This v2 uses it for a compact demonstrator; for a production release, migrate the shell backend to Shizuku UserService and test on the target XOS build.
- Delete is permanent. Directory deletion uses recursive removal after explicit user action. Verify the selected path before confirming.
- This project has not been built or device-tested in this environment. GitHub Actions is the build verification step.

## Setup on Infinix / XOS
1. Install Shizuku from the official site: https://shizuku.rikka.app/
2. Open Shizuku and choose Start via Wireless Debugging.
3. Follow its pairing flow in Developer Options > Wireless debugging > Pair device with pairing code.
4. Start Shizuku service.
5. Open File Manager Pro, choose Android/data tab, tap Minta izin, and approve the permission in Shizuku.
6. Browse Android/data or Android/obb. If XOS returns Permission denied, the firmware is restricting that operation; this app does not bypass it.

## Build APK using GitHub Actions
1. Upload all project files to a GitHub repository. Keep `.github/workflows/build-apk.yml`.
2. Open Actions > Build File Manager Pro v2.
3. Run workflow (or push to main/master).
4. Download artifact `FileManagerPro-v2-debug`.
5. Extract the ZIP and install `app-debug.apk`.

## Official Shizuku developer guide
https://github.com/RikkaApps/Shizuku-API
