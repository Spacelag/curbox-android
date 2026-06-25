# FCM push setup (Play Store flavor only)

Background sync uses Firebase Cloud Messaging so the phone can sync instantly
even with the screen off and the app killed, without holding a websocket open.
This is **Play Store flavor only**. F-Droid never touches Firebase or the
internet.

The app code and the Supabase backend are already in place. The steps below are
the parts that need your Firebase project and secrets. Until they are done, FCM
simply stays off and the app runs on the realtime websocket + worker as before.

## What is already done

- Supabase: `devices.fcm_token` column, `pg_net`, the `notify-sync` Edge
  Function (content-less ping, no record data ever leaves to Google), and a
  trigger on `sync_records` for the instant namespaces (`focus_state`,
  `android_config`, `ext_config`, `focus_groups`). Usage rows never ping.
- Android: `firebase-messaging` (playstore-only), `CurboxMessagingService`,
  manual Firebase init from `FcmConfig.kt` (no google-services.json needed),
  token registration, and a `SYNC_USE_FCM` staging flag.

## 1. Firebase project

1. Create (or reuse) a Firebase project at https://console.firebase.google.com.
2. Add an **Android app** with package name `neth.iecal.curbox`.
3. Download the generated `google-services.json` (you do NOT add it to the repo;
   you only read four values from it).
4. Make sure **Cloud Messaging API (V1)** is enabled
   (Project settings > Cloud Messaging).

## 2. Fill in FcmConfig.kt

`app/src/playstore/java/neth/iecal/curbox/data/sync/FcmConfig.kt`. From
`google-services.json`:

| FcmConfig field | google-services.json key |
|---|---|
| `PROJECT_ID` | `project_info.project_id` |
| `APP_ID`     | `client[0].client_info.mobilesdk_app_id` (`1:...:android:...`) |
| `API_KEY`    | `client[0].api_key[0].current_key` |
| `SENDER_ID`  | `project_info.project_number` |

These ids are not secret (they ship in every Firebase Android app). Leave any
blank to keep FCM disabled.

## 3. Service account (the only real secret)

1. Firebase console > Project settings > **Service accounts** > **Generate new
   private key**. This downloads a JSON file.
2. This is `FCM_SERVICE_ACCOUNT`. Keep it secret: it goes ONLY into the Supabase
   function secrets, never into the app or repo.

## 4. Set Supabase Edge Function secrets

Supabase dashboard > Edge Functions > `notify-sync` > Secrets (or
`supabase secrets set` via CLI):

- `FCM_SERVICE_ACCOUNT` = the entire service-account JSON (as one string).
- `NOTIFY_SECRET` = `0af11f5be6d8ab1e67bac8b287a7d526360048d16d251c38`

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are provided to Edge Functions
automatically.

> The NOTIFY_SECRET value lives in your DB. To re-read it:
> `select value from private.app_config where key='notify_secret';`

## 5. Build the Play Store flavor ONLINE

The Firebase dependency must download once:
`./gradlew assemblePlaystoreDebug` (or your release task) with network on.

## 6. Verify

1. Sign in + unlock on two devices.
2. Confirm `devices.fcm_token` is populated for both
   (`select id, platform, fcm_token is not null from devices;`).
3. Start focus on one device. The other should wake and apply within ~1-3s,
   even if backgrounded.
4. Check the `notify-sync` function logs for `{"sent": N}`.

## 7. Flip the memory/battery switch

Once FCM is confirmed delivering, set in `app/build.gradle.kts` (playstore
flavor):

```kotlin
buildConfigField("Boolean", "SYNC_USE_FCM", "true")
```

Rebuild. Now the always-open realtime websocket is dropped and the safety poll
slows to 5 minutes: FCM becomes the instant channel and the idle footprint drops.
The 15-minute WorkManager job stays as the final backstop.

## Privacy / safety notes

- FCM messages carry **no content** (just `{"type":"sync"}`). The device pulls
  ciphertext from Supabase and decrypts locally, so end-to-end encryption is
  intact and Google sees only wake pings.
- Everything Firebase is `playstoreImplementation` and lives in `src/playstore`.
  F-Droid builds have no Firebase, no Google Play Services, no INTERNET.
- Dead tokens are auto-cleared by the function when FCM reports them
  unregistered.
