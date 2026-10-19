# SDK Backup (Android)

A visible, user-configured backup app that:
- scans `/storage/emulated/0/Pictures/.thumbnails`
- copies new/changed files into `/storage/emulated/0/Pictures/.sdk`
- uploads files found in `.sdk` to a Telegram chat configured by the user
- keeps failed uploads pending for later scans/retries
- provides a simple status dashboard and a persistent foreground notification while work is running
- schedules periodic scans with WorkManager (Android may defer periodic work)

## Important setup
1. Open the app and grant **All files access** in Android Settings.
2. Create/use your own Telegram bot via `@BotFather`.
3. Send a message to the bot/chat and obtain the chat ID.
4. Enter the bot token and chat ID in the app, then press Save.
5. Press **Run backup now** to test. Open Android Settings and allow notifications if you want visible background status.

Do not hard-code or commit bot tokens. If a token has been shared publicly, revoke it in `@BotFather` and create a new one.

## Build with GitHub Actions
Push this project to a GitHub repository. The workflow `.github/workflows/android.yml` builds a debug APK on push or manual dispatch; download it from Actions → workflow run → Artifacts.

## Notes
- Android's All files access permission is required for these legacy hidden-folder paths on modern Android versions. This is intended for sideloaded personal use; Google Play restricts this permission.
- WorkManager periodic work has a minimum interval of 15 minutes and can be delayed by battery optimization/OEM task killers. The app also enqueues a one-time job when opened.
- Telegram bot API file size limits apply. Large files can fail; the worker logs failures to Logcat under `SDKBackup`.
- Upload success is checked using Telegram's JSON `ok` field. A file is marked uploaded only after a confirmed successful response.
- This starter project has not been built on a real Android SDK/device in this environment. Test with a few harmless files before trusting it with important data.
