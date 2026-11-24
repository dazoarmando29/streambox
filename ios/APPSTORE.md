# App Store / TestFlight Submission Preparation

Status: **prepared, not submitted.** Submitting requires an Apple Developer
Program membership (USD 99/year), which this project does not have. This
document exists so that, when the membership exists, submission is a
mechanical step - and so reviewers can see the app is not hiding anything.

## App identity (as configured in `project.yml` / `Info.plist`)

| Field | Value |
| --- | --- |
| Bundle identifier | `com.streambox.ios` |
| Display name | StreamBox |
| Version (marketing) | 2.36 |
| Build | 238 (= repo `version.txt`) |
| Devices | Universal (iPhone + iPad) |
| Minimum OS | iOS 16.0 |
| Orientation | iPhone: portrait + both landscapes; iPad: all four |
| Encryption | HTTPS only; `ITSAppUsesNonExemptEncryption = NO` |

## App metadata to prepare in App Store Connect

- **Name**: StreamBox - Movies & TV Shows
- **Subtitle**: Fast free streaming catalog
- **Category**: Entertainment (primary), Photo & Video (secondary)
- **Age rating**: 17+ (unrestricted web content is possible through player
  embeds; the app ships a parental 18+ gate locked by default). Answer the
  questionnaire honestly: frequent/intense mature themes possible via
  third-party catalog content.
- **Privacy policy URL**: https://dazoarmando29.github.io/streambox/privacy.html
- **Support URL**: https://github.com/dazoarmando29/streambox (issues)
- **Marketing URL**: https://dazoarmando29.github.io/streambox/
- **Keywords**: movies, tv shows, anime, watch party, subtitles, catalog

## Privacy disclosures (App Privacy section)

Declare, matching `PrivacyInfo.xcprivacy`:
- Data NOT collected: no contact info, no identity, no usage data, no
  diagnostics, no location, no purchases, no other linked data.
- Tracking: NO.
- The only required-reason API used is UserDefaults (CA92.1 - app settings
  accessed by the app itself).

## Screenshots required (produce on device or simulator)

- 6.9" iPhone (portrait): home/hero, details page, player with controls,
  Watch Party room, install/About screen.
- 13" iPad (portrait + landscape): home grid, details, player.
- No real-person imagery; use catalog artwork as the app itself does.

## Review notes (App Review)

Suggested honest text:

> StreamBox is a catalog browser and player shell. Metadata and artwork
> come from TMDB via our own API proxy. Playback uses third-party embed
> providers selected by the user inside the app (like a browser). We do not
> host media. The app includes an 18+ content gate locked behind a PIN, on
> by default. If any provider fails on iOS, the app shows a clear fallback
> message and suggests switching sources. No account is required; no user
> data leaves the device except the catalog/subtitle lookups described in
> our privacy policy.

Include a demo path: open app -> tap a trending title -> press Play ->
switch a mirror -> open Watch Party -> create a room.

## Content licensing documentation

- Catalog metadata/artwork: TMDB API terms (attribution shown in-app
  footer "Catalog: TMDB").
- Media: not hosted or licensed by StreamBox; playback is delegated to
  third-party embeds whose terms bind the user. This must be stated plainly
  in the review notes (do NOT conceal it). If App Review requires first-
  party or licensed media, the app supports playing direct authorized URLs
  natively (AVKit path) and can ship with licensed/public-domain streams
  (e.g. Apple's HLS test streams) as its featured content.

## TestFlight plan (when membership exists)

- Internal testing: immediate for up to 100 testers.
- External testing: requires Beta App Review; keep a fixed feedback URL
  (GitHub issues). Builds expire after 90 days; the release train (2.36.x)
  renews them.
- Public invitation link is displayed on `install.html` only when real.

## CI integration (already implemented)

`.github/workflows/ios.yml` already contains the full signed path:
- activates when `IOS_SIGNING_*` secrets exist (certificate p12, profile,
  team id, identity, profile name),
- exports `streambox-ios-signed.ipa` with `app-store` export options,
- uploads to TestFlight via `xcrun altool` when `APP_STORE_CONNECT_API_KEY_*`
  secrets exist,
- writes `install-metadata.json` describing exactly which artifacts are
  signed and how they may be installed.

Required GitHub Actions secrets (never commit these to the repository):

| Secret | Contents |
| --- | --- |
| `IOS_SIGNING_CERTIFICATE_P12_BASE64` | base64 of the distribution .p12 |
| `IOS_SIGNING_CERTIFICATE_PASSWORD` | p12 password |
| `IOS_SIGNING_IDENTITY` | e.g. "Apple Distribution: Name (TEAMID)" |
| `IOS_PROVISIONING_PROFILE_BASE64` | base64 of the .mobileprovision |
| `IOS_PROVISIONING_PROFILE_NAME` | profile name in the portal |
| `IOS_TEAM_ID` | 10-char Team ID |
| `APP_STORE_CONNECT_API_KEY_ID` | Key ID |
| `APP_STORE_CONNECT_API_KEY_P8` | contents of the AuthKey .p8 |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer UUID |

## What this project will never do

- Publish an unsigned or simulator IPA as if it were installable.
- Use enterprise certificates or re-signing bypasses.
- Hide provider behavior from App Review.
- Claim TestFlight/App Store availability before a real link exists.
