# Latent

A film camera for Android. Pick a film, see its look in the viewfinder, shoot
RAW, and let the photo develop.

## Attribution

> **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm

Both lines are required under GPLv3 §7(b) and are shown in the app's About screen.
See NOTICE.md for the full terms and LICENSE for GPLv3.

## Status

Step 1 — project skeleton, CI build, signed APK. No camera yet.

## How it's built

Every push to `main` runs `.github/workflows/build.yml`, which produces
`latent-v<build>.apk` and attaches it to a GitHub Release. Install it over the
previous build; the committed `debug.keystore` keeps updates in place.

Code arrives in bundles: upload a zip to the `drop/` folder and
`.github/workflows/drop.yml` unpacks it into the repo and triggers a build.
Workflow files themselves are edited by hand (GitHub does not let a workflow
change other workflows).
