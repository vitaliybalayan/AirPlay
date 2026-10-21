# Play Console — answers for required forms

## App content
- **Privacy policy:** https://github.com/vitaliybalayan/AirPlay/blob/main/PRIVACY_POLICY.md
- **Ads:** No, the app does not contain ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating (IARC):** Category "Utility / Productivity / Communication / Other". No violence, sexual content, profanity, drugs, gambling. No user-to-user communication, no sharing of location, no purchases. → Expected rating: Everyone / 3+.
- **Target audience:** 18+ (not designed for children). Not appealing to children.
- **News app:** No.
- **COVID-19 / health:** No.
- **Government app:** No.
- **Financial features:** None.

## Data safety
- Does the app collect or share any required user data types? **No.**
  - Rationale: no account or personal data; screen/audio stream stays on the local network; for lyrics only the song title and artist (not user data, not linked to the user or device) are sent to the public LRCLIB API.
- Is all data encrypted in transit? LRCLIB requests use HTTPS.
- Can users request data deletion? Not applicable (no data stored).

## Foreground service declaration (FOREGROUND_SERVICE_SPECIAL_USE)
- Type: specialUse — "AirPlay receiver".
- Description: The app is a screen-mirroring receiver for Android TV. A foreground service keeps the receiver discoverable on the local network (mDNS) and ready to accept a connection from the user's iPhone at any time, including right after the TV starts. Without it, the TV would not appear in the iPhone's Screen Mirroring list until the user manually opens the app.
- User impact if deferred/interrupted: the TV disappears from the iPhone's list and an active mirroring session stops.

## Release
- Track: start with **Internal testing** (instant, add your own Google account as tester), then Production.
- Release name: 1.0 (1)
- Release notes RU: Первый выпуск: повтор экрана iPhone, iPad и Mac, музыка с текстами песен, управление громкостью ТВ с iPhone, работа в фоне.
- Release notes EN: First release: mirror iPhone, iPad and Mac, AirPlay music with live lyrics, TV volume from your iPhone, runs in the background.
- Device catalog: Android TV only (manifest requires android.software.leanback).
