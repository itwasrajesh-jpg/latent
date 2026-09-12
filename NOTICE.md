# Notices

Latent is free software, released under the GNU General Public License v3.0
(see LICENSE).

## Required attribution (GPLv3 §7(b))

> **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm

Both lines are displayed in Latent's About screen with clickable links.

## Credits

Film modeling will be powered by **spektrafilm** — a physically based spectral
film simulation by Andrea Volpato
(https://github.com/andreavolpato/spektrafilm), GPLv3. Film profiles and LUTs
from that project are licensed CC BY-SA 4.0.

The Android engine port is **Spektrafilm-android** by thetechgeekko
(https://github.com/thetechgeekko/Spektrafilm-android), GPLv3.

Latent includes the engine module `engine:spektra-core` and the RAW decoder
module `lib:libraw` from the Android port. They are not committed to this
repository: the build workflow fetches them from a pinned commit of
https://github.com/itwasrajesh-jpg/Spektrafilm-android (an unmodified mirror of
the upstream port), currently:

    3c8080415d7bff2e915919e199a38ca40257eb4b

RAW decoding uses LibRaw (LGPL-2.1 / CDDL-1.0), vendored in that project's
`lib:libraw` module.


## Latent

Camera, develop flow and darkroom by Celestial — https://github.com/itwasrajesh-jpg/latent
Written with Anthropic's Claude, in conversation with the author.
