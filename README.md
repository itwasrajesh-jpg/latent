# Latent

A film camera for Android. Shoot RAW, and the photo develops through a physically based
film simulation.

## Attribution

> **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm

Both lines are required under GPLv3 §7(b) and are shown in the app's About screen with
clickable links. See NOTICE.md for the full terms.

## Building

**Actions → Build Latent APK → Run workflow.** The APK is attached to a GitHub Release;
install it over the previous build. The film engine and RAW decoder are fetched at build
time from a pinned commit of a mirror of the Android port, so they are never vendored here.

Development notes, device findings and the current state live in `LATENT_HANDOFF.md`.

## Licence

GPLv3. The film profiles and LUTs from spektrafilm are CC BY-SA 4.0; RAW decoding uses
LibRaw (LGPL-2.1 / CDDL-1.0).

Latent by Celestial.
