# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Public release: anyone who installs Spectra from GitHub or another open-source store. Most are people who listen every day: on commutes, at work, at night with headphones, and with friends. Many arrive from Spotify or YouTube Music and expect that level of polish on day one, with no setup or manual. The current owner (who took over the Velune codebase with the original developer's permission) is the first user and tests on a mid-range ARM64 Android 14 phone.

## Product Purpose

Spectra plays the full YouTube Music catalogue for free, with no ads, and turns a phone into a personal music library that works offline and that you can share live with friends. It succeeds when every song plays immediately and sounds great, when the library feels like yours (ordered the way you want), and when listening with someone far away feels as easy as sharing earbuds.

## Positioning

- **Listen Together:** one host phone leads; friends in other cities join with a code or QR code and hear the same song in sync on their own phones and headphones. Saved people (with nicknames) can be invited directly, always with their permission.
- **Smart offline:** an on-device taste model scores songs and downloads only the ones you're very likely to love (≥ ~75% confidence). Downloads keep a custom order you arrange by dragging.
- **Audio quality:** picks the best available stream, with equalizer, loudness normalisation, volume boost and crossfade.
- **Free and open:** no subscription and no ads; open source (GPL-3.0).

## Operating Context

One-handed use on a phone, often while walking or with the screen glanced at briefly. Long sessions with the screen off (lock-screen and notification controls, Bluetooth headsets, Android Auto). Offline use on downloaded songs. A user can sign in with their own YouTube account to sync likes, library and playlists.

## Capabilities and Constraints

- Native Android, Kotlin + Jetpack Compose (Material 3), Media3/ExoPlayer, Room. minSdk 26, targetSdk 36.
- Existing features to preserve: home feed, search, library (songs, albums, artists, playlists, downloads), player with lyrics, queue, equalizer, sleep timer, Listen Together (LAN + online), smart downloads, YouTube account sync, backup/restore, Discord presence, Last.fm/ListenBrainz scrobbling, widget, theme and palette options.
- User data must survive every update: same applicationId (`com.nikhil.yt`) and signing key; database changes only via migrations.
- Licensed GPL-3.0: stays open source; original copyright notices kept alongside the new owner's.
- No backend owned by the project: anything online must use free, accountless public infrastructure.

## Brand Commitments

- Name: **Spectra** (rebranded from Velune).
- Icon: white line-art ghost wearing headphones on pure black (source: `C:\Users\src\Downloads\download.jpg`, to be vectorised).
- Dark-first: dark theme is the primary experience, light theme secondary.
- Screens and the player tint from the current song's album art.
- Users keep the existing theme and palette choices in Settings.

## Evidence on Hand

No testimonials, user counts, press or benchmarks exist; do not invent any. Real assets: the new icon, the app's own screens, and the user's library on the test phone.

## Product Principles

1. Every song should play: speed and reliability come before features.
2. Your library, your order: never lose or reorder user data without being asked.
3. Together, with consent: sharing is easy, but nobody joins or is joined without permission.
4. Dead simple on the surface, deep underneath: power features stay one level down, never in the way.
5. Free means free: no ads, no paywalls, no accounts required to use the app.
