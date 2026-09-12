# LATENT — Master Handoff

*Film camera for Android by Celestial. State as of 12 Sep 2026 (app step 7b, engine patch pending).*
*Purpose: everything a fresh session needs. Paste this at the start of a new conversation.*

---

## 1. What Latent is

A film camera, not an editor. Shoot RAW, the photo develops through spektrafilm's physically
based simulation (spectral exposure → dye density → print → scan). Free and open source
(GPLv3). Package `com.celestial.latent`, repo `github.com/itwasrajesh-jpg/latent` (public).

Required attribution under GPLv3 §7(b), both lines, clickable, in the About screen:
- **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
- Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm

## 2. Working method

- Phone-only development: GitHub web editor + Actions. No Codespaces, no PC.
- Code arrives as zip bundles → upload to repo root or `drop/` → `drop.yml` unpacks and commits.
- **Builds are manual**: Actions → "Build Latent APK" → Run workflow. APK on the Releases page.
- Workflow files are pasted by hand (a workflow cannot edit workflows).
- Engine + LibRaw are fetched at build time from the user's mirror at a pinned commit
  (`ENGINE_REPO` / `ENGINE_REF` in build.yml). arm64 only. JDK 21, NDK 27.0.12077973, CMake 3.22.1.
- Build errors: the `e: file:///…` lines are what's needed. Crashes: the app shows the trace
  on next launch with a Share button. Runtime behaviour: only the phone can verify.
- The user's preferences: plain language throughout, aesthetics considered in the first
  version rather than added later, ask before big or risky changes, small steps.

## 3. Device facts (Xiaomi 15 Ultra, Android 16)

- Camera2 LEVEL_3. Logical camera 0 with physical 2 (main 23 mm, 1-inch, f/1.63), 3 (UW 14 mm),
  4 (70 mm, ISO max 1119), 5 (100 mm). Extra logical cameras 6 and 7 exist.
- Third-party RAW: 12.5 MP, **10-bit** (white 1023, black 64) on every lens; no 50 MP for
  outside apps. Colour matrices, noise profile and shading map all present and matching
  Xiaomi's own Pro-mode RAW.
- RAW burst runs at a true 30 fps with no drops.
- Camera Extensions available: AUTOMATIC, BOKEH, NIGHT (front camera too).
- `JPEG_R` (Ultra HDR) is offered but **cannot share a session with RAW** — the driver throws a
  fatal error. Ultra HDR was removed.
- **Measured dead ends**: DCG, staggered HDR, multi-frame HDR, snapshot HDR and MFNR vendor keys
  are accepted but have *zero* measurable effect on the RAW (A/B tested at ISO 390 and 3112,
  ±0.01). The driver publishes no vendor result keys, so "echo=1" means "not rejected", nothing
  more. Ideal RAW times out in every stream layout. All those toggles were removed.
- **What is real**: in-sensor zoom on the JPEG path (main lens), `sensor_meta_data.current_mode`
  as a route to sensor-crop RAW on the telephotos (MotionCam uses mode 38 on lens 5), the
  Camera Extensions, and our own aligned stacking.
- Vendor codes are per-lens in the app; a mode valid on one sensor errors on another.

## 4. Engine facts

**The port ships `.claude/skills`, including `spectrafilm-dev` — read it before touching the
C++.** Its hard laws: parity with the Python oracle is the prime directive (max_abs ≤ 1e-4,
rms ≤ 1e-5, byte-identical across thread counts); any change under
`engine/spektra-core/src/main/cpp/**` must keep the host-parity suite green before it is done;
non-parity behaviour must default OFF; NDK r27 / CMake 3.22.1 / JDK 21 are hard pins;
`-fno-finite-math-only` must never be stripped (the scan stage relies on NaN propagation);
stochastic stages need fixed seeds; thread-invariance is mandatory; GPU never routes export or
parity; and never claim the parity gate passed without running it.

- `engine:spektra-core` (C++ + Kotlin facade) and `lib:libraw`, fetched from the mirror.
- API used: `simulate`, `simulatePreview` (the only path honouring the GPU preview flag),
  `bakeCubeLut`, `meterExposureEv`, `listProfiles`.
- Input must be **linear ProPhoto**: the engine ignores the colour-space label and always
  interprets input as ProPhoto. RAW arrives that way from LibRaw; JPEG sources are converted
  in `Develop.openImage` (sRGB curve removed, then sRGB→ProPhoto primaries).
- Output: the scan stage converts to the chosen space with correct matrices, CAT02 adaptation
  and per-space encoding. sRGB is correct for viewing; wide spaces are for onward grading and
  can band in an 8-bit JPEG.
- 20 filming profiles, 8 printing profiles. Each film declares its `target_print`: Kodak
  negatives → Portra Endura, Fuji → Crystal Archive II, Vision3/Verita → Kodak 2383. Slide
  films (Provia, Velvia, Ektachrome, Kodachrome) have none and need `scanFilm = true`.
  Using a printing profile as a film gives "internal error".
- **Performance**: the diffusion filter is a direct 2D convolution in float64 with radius
  = 8 × bloom λ (capped at half the image), so cost is O(pixels × radius²) — minutes at
  12.5 MP. It is already multithreaded. Everything else is seconds. Previews therefore drop
  the spatial stages (grain, halation, diffusion, glare) unless that tab is open.
- Known cost profile: 800 px preview ≈ 300–500 ms; full 12.5 MP ≈ 10–20 s without diffusion.
- GPU (Vulkan) covers the **scan stage only** and is **preview-only by the engine's own rule**
  (its float maths is not bit-reproducible across vendors, so export and the parity path must
  stay CPU). The app therefore exposes a GPU *preview* switch only, and `Develop.render` forces
  it off for full renders. Do not re-add a GPU export option. Measurements so far were taken on
  a throttled phone and are inconclusive; a guarded "Measure GPU vs CPU" button is in the
  ENGINE tab.
- **Local engine change (pending upload)**: the diffusion filter *family* was fixed to
  black_pro_mist in the C API, so Glimmerglass / Pro-Mist / Cinebloom were silently ignored.
  Patch bundle `engine-diffusion-family.zip` adds the field to `spektra.h`, honours it in
  `spektra.cpp` (with fallback), maps the Kotlin string in `spektra_jni.cpp`, and documents it
  in the mirror's NOTICE. After committing it, `ENGINE_REF` in build.yml must be updated.

## 5. App structure

```
app/src/main/java/com/celestial/latent/
  MainActivity.kt      navigation (camera / roll / darkroom / settings / about / report /
                       vendor / probe / logs / extension), permission, crash screen, volume keys
  CameraScreen.kt      viewfinder, lens row + ×2, control strip, focus+EV overlay, film strip,
                       shutter, thumbnail → roll, quick-settings drawer
  RollScreen.kt        contact sheet, filters, develop all, import, tap → darkroom
  DarkroomScreen.kt    draggable sheet, 10 tabs, live preview, compare, recipes, full develop
  ExtensionScreen.kt   Xiaomi Portrait / Night / Auto, capability-driven controls
  SettingsScreen.kt    anti-banding, gridlines, haptics, format, film/auto-develop, camera path,
                       lens defaults, links to vendor codes / probe / logs / report / about
  VendorScreen.kt      per-lens vendor codes and session opmode
  ProbeScreen.kt       key map, candidate tests, sensor-mode sweep, quality probe, A/B test
  AboutScreen.kt       required attribution, licences, engine commit, credits
  LogScreen.kt         in-app logcat with share
  develop/Develop.kt   decode (with self-enforced size cap), cache, render, save, film/paper pairing
  develop/DevelopQueue.kt  single-lane background developing, cancellable full develops
  develop/Recipe.kt    the full parameter set, JSON, named recipes
  camera/*.kt          Camera2 controller, controls, lens table, alignment, DNG writer, report, probe
```

## 6. Open items

- Engine patch not yet committed to the mirror; `ENGINE_REF` still points at upstream.
- Live film preview in the viewfinder (bake a LUT, GPU shader) — designed, not built.
- Diffusion speed-up — reconsidered after reading `spectrafilm-dev`: float32 would break
  bit-exactness with the oracle, so by their rules it must be opt-in and default OFF, and any
  C++ change needs their host-parity suite run. The safer subset (mirror symmetry, negligible-tail
  trim, cache-friendly ordering) is arithmetically identical but still needs the gate. Treat this
  as "patch + run their tests", not a small change.
- Latent Looks (own digital looks, HALD import of Lightroom presets) — planned.
- Own depth-based bokeh — planned, after the above.
- Lens discovery is still hard-coded to the 15 Ultra's IDs; a Pixel shows the wrong chips.
- Freeze-frame preview (real engine render of the current frame) — idea, not built.

## 7. Testing habits that work

- Settings → Logs → "Latent only" → Clear → do the thing → Refresh → Share.
- Measure, don't assume: the A/B test and the sensor-mode sweep settled questions that
  metadata could not.
- Nothing heavy runs twice at once: developing is single-lane. A hot phone throttles hard and
  makes every timing meaningless — measure cool.
