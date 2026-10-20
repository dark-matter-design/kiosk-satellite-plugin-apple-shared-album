# Development and verification

## Toolchain

The current upstream template workflow uses Ubuntu 24.04, `actions/checkout@v4`, `actions/setup-java@v4` with Temurin 21, `android-actions/setup-android@v4`, Android platform 35, and build-tools 35.0.0. The builder compiles Java with `javac --release 8`, targets manifest `minAndroidSdk` 24, and runs the exact build-tools 35.0.0 D8 with `--min-api 24`; `--build-tools VERSION` is available for intentional toolchain changes. Python 3 is required; upstream does not pin its patch version.

## Local verification

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/check_upstream_screensaver.py /path/to/kiosk-satellite
python3 tools/inspect_package.py
python3 tools/audit_repository.py
```

`tools/test.py` compiles the SDK, plugin, and tests and runs synthetic protocol tests plus the upstream Python tooling tests. It does not make network requests. Its lifecycle fixture proves that `start` publishes the `apple_album` renderer synchronously even with no album URL, and that `stop` removes it. The protocol fixtures cover legacy string/number derivative metadata and `dateCreated`, requested-checksum filtering and bounded Apple-only 330 handoff; a 70-photo legacy album split into three 25-photo asset batches; CloudKit zone reconstruction and bounds; `sharing_url_key`; paired `CPLAsset`/`CPLMaster` records and `assetDate`; a 70-photo paired response with realistic rich rendition records and long synthetic signatures (2,735,193 UTF-8 bytes and 25,202 parsed values) that exceeds both former 2 MiB/20,000-value caps; edited renditions stored on the asset; literal and percent-encoded `${f}` filename templates; `resJPEGScreenRes` and generic validated `resJPEG*Res` renditions; HEIC originals with JPEG derivatives; web-safe originals; safe rendition ordering/fallback; distinct bounded metadata/image reads; decoded dimension/pixel bombs; and missing `itemType`. Overlay tests cover privacy-off defaults, strict setting fallbacks, all four corners, formatting hooks, metadata absence, HTML escaping, bounded coordinates, the 360 KiB rendered JPEG budget, and the 512 KiB complete-document budget. Generated JPEG EXIF/IPTC/XMP fixtures contain no owner data and prove explicit place-field precedence, coordinate fallback, sanitization, and caption exclusion. Pixel-shift tests inspect image CSS independently from the overlay script and prove that only `.overlay` receives a transform, with a 10 px per-axis bound, one-minute cadence, 28 px inset, disabled state, and page-lifecycle timer cleanup. Duration tests cover SDK-style numeric edge cases. `check_upstream_screensaver.py` compares the vendored SDK byte-for-byte and checks the pinned application source paths for capability, registration, identity, settings exposure, and stock overlay behavior. It locks the stock small Clock defaults, four-corner vocabulary, minute alignment, 28 px safe inset, ±10 px small-overlay movement, one-minute plugin shift cadence, and off-by-default Pixel shift setting so drift is reviewed rather than silently approximated. `inspect_package.py` verifies package structure, deterministic metadata, checksum, DEX entry point, and limits. `audit_repository.py` rejects credential-like files and scans tracked text plus Git history for common secret markers and live Apple album URLs.

The metadata cap is intentionally per response rather than cumulative across an album. Its 4 MiB raw-byte limit implies at most about 8 MiB for the decoded Java UTF-16 JSON text, while the independent 50,000-value parser ceiling bounds collection/scalar growth. CloudKit's requested page size of 100, accepted page maximum of 200, 25-page limit, and 2,500-record total prevent the higher parser ceilings from removing traversal bounds. Legacy `webasseturls` responses remain 25-photo batches with at most 250 returned items. The 8 MiB image-download, 20,000 px edge, 64-megapixel decoded source, 360 KiB rendered JPEG, and 512 KiB inline HTML caps are unchanged.

### Kiosk Satellite 2026.10.18 selection semantics

The manifest capability permits `PluginHost.publishScreensaver`; it does not create a static dropdown entry. The enabled plugin must publish a renderer in its live session. Kiosk Satellite then derives the stable mode `plugin:PLUGIN_ID:KEY` and label `Title (Plugin Name)`. This plugin publishes `plugin:apple-shared-album:apple_album` before scheduling any album work, so invalid, empty, offline, or still-loading albums remain selectable and render a sanitized status document.

The native host batches publication for 250 ms and sends the live catalog to Flutter. The on-device Screensaver settings page listens directly to that catalog and rebuilds. In the same upstream commit, Remote Admin's settings schema refreshes on `SettingOptionsChanged`, but plugin renderer publication does not emit that event. A Remote Admin page whose settings schema was loaded before plugin initialization therefore keeps only the built-in options until the page refetches settings. Reload Remote Admin after the plugin row says **Running**, or select the mode on-device. Restart Kiosk Satellite only when the plugin row reports a failed/unclean session; ordinary enable and update paths start it without a restart.

SDK 1 number settings are finite JSON numbers constrained to the manifest's inclusive `min`/`max` and exact `step`. The display-duration manifest exposes 5–3,600 seconds in 5-second steps with a 30-second default (719 divisions, below SDK 1's 10,000-division limit). Runtime code independently rejects non-finite/non-numeric input, clamps finite values before arithmetic, rounds to the nearest 5-second step, clamps the normalized result again, and stores the scheduled delay as `long`. Every value accepted by the earlier 5–300 second range remains valid and unchanged.

## Manual device validation

On 20 October 2026, the maintainer confirmed completion of the device checks and working playback with both supported public album URL formats. No live album links, images or metadata are recorded here.

Retain the following as the regression checklist for future releases:

1. Install the local ZIP on Kiosk Satellite 2026.10.18 or newer.
2. Leave the host-wide **Screensaver > Pixel shift** off, then verify start, rotation, fit/fill, refresh, disable, re-enable, URL replacement, all four overlay positions, 12/24-hour and seconds modes, current date, missing metadata behavior, readable contrast on bright/dark fit/fill photos, text-only plugin pixel shift with a stationary photo, and uninstall. Separately confirm that enabling the host-wide setting moves the outer plugin WebView, as documented, rather than mistaking that host behavior for this plugin's overlay transform.
3. With owner permission, test one public album of each supported URL format without recording its link, traffic, metadata, or images.
4. Verify private, invite-only, approval-required, revoked, empty, oversized, slow, and offline states show only sanitized status text. With owner permission only, confirm capture date/location are shown when present and omitted when absent; do not record them.
5. Confirm disabling or replacing the plugin stops network traffic and a stale image never reappears.

The repository and automated suite must continue to contain synthetic fixtures only.

## Build and package reference

The release toolchain is pinned to Ubuntu 24.04, Temurin 21, Android platform 35, Android build-tools 35.0.0, Java `--release 8`, and Python 3 as supplied by the runner. Locally, set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/check_upstream_screensaver.py /path/to/kiosk-satellite
python3 tools/inspect_package.py
python3 tools/audit_repository.py
```

The build writes a deterministic versioned ZIP such as `dist/apple-shared-album-0.1.6.zip`, matching release manifest, SHA-256 file, and compile-time SDK JAR. The ZIP contains exactly the Plugin Manager root files (`kiosk-satellite-plugin.json`, `plugin.jar`, and `LICENSE`) plus any supported `assets/` files; `NOTICE` remains in the repository and is linked below for upstream attribution. ZIP entries have fixed timestamps and ordering. Toolchain changes can still alter DEX bytes, so release assets must come from one workflow run.

Tests use only synthetic JSON, fake transport responses, and reserved fake paths. They never contact an album. See [development notes](development.md) for the verification matrix and [research notes](research.md) for source/version/license provenance.
