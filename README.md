# One (Android)

This project contains an Android app "One" (notes, vault, files) with Drive appData backup/restore.

Quick setup for Google Drive appData backups (on-device sign-in)

1) Generate SHA-1 for your debug keystore

- Run in project root (preferred):

```
./gradlew signingReport
```

Find the entries under `Variant: debug` -> `SigningReport` -> `SHA1` for the `com.rohit.one` applicationId.

Alternatively, run keytool:

```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

2) Create OAuth 2.0 Client ID in Google Cloud Console

- Open https://console.developers.google.com
- Select your project (create one if necessary).
- APIs & Services -> OAuth consent screen: configure test app, add your email and app name.
- APIs & Services -> Library -> enable "Google Drive API"
- Credentials -> Create Credentials -> OAuth client ID -> "Android"
  - Package name: `com.rohit.one`
  - SHA-1 certificate fingerprint: paste the SHA-1 from step 1
  - Save; note the client ID if needed.

3) (Optional) If you use AppAuth or a web client flow, create a Web OAuth client and register redirect URI.

4) Put client ID / redirect into `local.properties` for local runs

```
OAUTH_CLIENT_ID=YOUR_ANDROID_CLIENT_ID.apps.googleusercontent.com
OAUTH_REDIRECT_URI=com.googleusercontent.apps.YOUR_CLIENT_ID:/oauth2redirect
```

- The build reads these values at build time and populates `BuildConfig.OAUTH_CLIENT_ID` and `BuildConfig.OAUTH_REDIRECT_URI`.

5) Build and run

```
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb -s emulator-5554 shell am start -n com.rohit.one/.MainActivity
adb -s emulator-5554 logcat OneApp:* IdentityAuthProvider:* BackupRepository:* *:S
```

6) Test manual flows

- Open the app, go to Backup screen.
- Tap "Sign in to Google" and complete consent.
- Tap "Backup now" and verify snackbar "Backup uploaded" and logs.
- To inspect files in Drive `appDataFolder` with curl or OAuth playground, obtain an access token and call:

```
curl -H "Authorization: Bearer <ACCESS_TOKEN>" "https://www.googleapis.com/drive/v3/files?spaces=appData"
```

Notes

- AppAuth PKCE fallback has been removed as requested. This build assumes Play Services / GoogleSignIn is available on devices.
- Tokens are stored securely with Jetpack Security `EncryptedSharedPreferences` via `AuthTokenStore`.
- If you need to support non-Play Services devices, consider re-adding AppAuth PKCE fallback.

If you want, I can now run a live e2e sign-in & backup on your emulator while streaming `adb logcat`. Say "Go live" and I will start streaming logs and guide you through the interaction (or attempt to complete it automatically if you allow UI automation).
