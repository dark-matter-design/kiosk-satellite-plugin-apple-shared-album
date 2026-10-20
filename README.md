# Apple Shared Album for Kiosk Satellite

Display still images from a **public** Apple Shared Album in the stock [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) screensaver. The plugin uses no Apple ID, password, cookie, API key, Home Assistant integration, or companion server.

## Requirements

- **Kiosk Satellite 2026.10.18 or newer**, with SDK 1 plugin screensavers.
- Android 7.0 (API 24) or newer.
- An Apple Shared Album with its public website enabled.
- One of these complete HTTPS links:

  ```text
  https://www.icloud.com/sharedalbum/#TOKEN
  https://photos.icloud.com/shared/album/OPAQUE_ID
  ```

The first form uses Apple's legacy `sharedstreams` service. The second uses anonymous public CloudKit. Invite links, approval flows, iCloud Links (`share.icloud.com/photos/...`), private albums, and anything requiring sign-in are deliberately rejected.

## Install and configure

1. In Kiosk Satellite, enable **Plugin Manager**.
2. Add this GitHub repository, review the source and manifest, and install a published stable release. For development, use **Install from ZIP** with the package from `dist/`.
3. Enable **Apple Shared Album** and paste the complete public album link.
4. Under **Screensaver**, select **Apple Shared Album (Apple Shared Album)**.

The renderer is registered as soon as the plugin starts, before it contacts Apple. On Kiosk Satellite 2026.10.18, the **on-device** Screensaver page refreshes its mode list when a plugin registers a renderer. Remote Admin does not invalidate an already loaded settings schema for that catalog change. If Remote Admin shows only built-in modes, confirm the plugin row says **Running**, then reload Remote Admin (or select the mode once on the kiosk itself) and reopen **Screensaver > Screensaver mode**. Album validation and photo count do not control whether the mode is listed. A Kiosk Satellite app restart is not normally required; disable/re-enable is useful only if the plugin is not running.

The settings also control display duration (5 seconds–1 hour in 5-second steps, 30 seconds by default), order, fit versus fill, and an optional readable corner overlay. Existing settings remain unchanged after updating. **Reconnect and refresh album** discards the current list and reconnects immediately. The normal list refresh interval is 15 minutes.

### Optional photo overlay

All overlay content is off by default. Enable any combination of:

- **Clock:** current device-local time, with 12/24-hour and seconds options.
- **Current date:** today's device-local date. This follows the stock Kiosk Satellite Clock behavior and is deliberately separate from photo metadata.
- **Photo capture date:** the capture date from Apple's item metadata, falling back to EXIF in the already downloaded image when available.
- **Photo location:** a bounded place name from explicit IPTC/XMP location fields already embedded in the downloaded JPEG, otherwise source-labeled GPS coordinates from local EXIF. Titles, captions, and descriptions are never treated as locations. Apple commonly strips this metadata, so the line is omitted when unavailable. The plugin never reverse-geocodes coordinates or sends them to another service.
- **Shift overlay pixels:** moves only the complete text overlay through a bounded deterministic pattern (at most 10 px per axis, once per minute) inside the overlay's safe inset. The photo remains completely stationary. This is off by default, matching Kiosk Satellite's Pixel shift default.

The combined overlay can sit at top left, top right, bottom left, or bottom right. It also offers WebView-safe font family, weight, color, and 50–150% size controls. Text uses a dark vignette and shadow for contrast without changing fit/fill behavior. Clock, current date, capture date, and location remain disabled unless explicitly enabled; location is off by default for privacy.

**Stationary-photo requirement:** use the plugin's **Shift overlay pixels** setting and leave Kiosk Satellite's separate, host-wide **Screensaver > Pixel shift** setting off. At the pinned host version, that global setting applies an outer transform to the complete plugin WebView, which the SDK cannot inspect or cancel, so it can move both photo and overlay. The plugin setting itself never transforms the image or document body.

## Privacy and security

The kiosk connects directly to Apple. The plugin has no analytics, telemetry, account login, cookies, disk photo cache, or third-party service. Images and album metadata remain in memory and are discarded as sessions change. Optional EXIF inspection is local and bounded; no metadata is logged or sent anywhere.

The album URL grants public read access and should be treated as sensitive. **SDK 1 only offers a regular string setting for this value:** Kiosk Satellite stores it as an ordinary plugin setting and displays it in the plugin UI. Protect Remote Admin and backups containing plugin settings. Disable the album's public website in Photos to revoke access.

All requests require HTTPS and an Apple-controlled `icloud.com` or `icloud-content.com` host. Every API base, media URL, and redirect hop is validated. Requests have connection/read timeouts and strict bounds on redirects, per-response bytes, JSON depth/nodes, pages, per-page records, total records, per-batch items, total images, rendition candidates, image bytes/dimensions/pixels, rendered image bytes, and the SDK's 512 KiB inline HTML limit. Lifecycle changes cancel active connections; stale workers cannot publish to a replacement session. User-visible errors distinguish metadata, image download, decoded-image, and display-output limits without including exceptions, request URLs, album tokens/IDs, server bodies, or album metadata.

See [SECURITY.md](SECURITY.md) and [docs/privacy.md](docs/privacy.md) for the threat model and disclosure guidance.

## Protocol and rendering architecture

`AppleAlbumClient` parses the supplied link and chooses one implementation behind a common interface:

- `LegacyProtocol`: `webstream` → bounded 25-photo `webasseturls` batches, including Apple's validated HTTP 330 partition handoff, a 250-item response ceiling, and response filtering to at most four safe checksums selected for each requested photo.
- `CloudKitProtocol`: public `records/resolve` → anonymous shared-zone `records/query`, requesting 100 records per page and accepting at most 200, with a 25-page/2,500-record ceiling, a bounded reconstructed zone ID, repeated-marker rejection, current `CPLAsset`/`CPLMaster` pairing, edited-photo rendition support, fixed filename-template expansion, video exclusion, and validated web-safe still-image rendition selection.

Both produce validated Apple image references and bounded capture dates when the payload supplies one. Renditions with a declared size over 8 MiB are excluded; up to four remaining candidates are ordered around the 1920 px display target, preferring a 1280–2560 px rendition instead of an unnecessary full/original. If an undeclared candidate still breaches the bounded download, the next lower safe candidate is tried. Before conversion, `ExifMetadata` locally inspects bounded JPEG EXIF, IPTC, and XMP segments for capture date, GPS, and explicit location fields only. It ignores caption/description fields. `SafeHttp` performs native Android HTTPS transport. `PhotoEncoder` retains the 20,000 px edge and 64-megapixel decoded-source limits, scales to at most 1920 px on the longest edge, and emits a JPEG no larger than 360 KiB. `SlideshowHtml` base64-embeds that JPEG with the sanitized overlay in an isolated inline screensaver document and verifies the complete UTF-8 document stays below 512 KiB. Videos and unsupported image renditions are skipped.

Metadata is read and parsed one response at a time. The per-response byte ceiling is 4 MiB and the parse-tree ceiling is 50,000 values. At those simultaneous maxima, the bounded transient inputs are approximately 4 MiB of UTF-8 bytes plus up to 8 MiB of Java UTF-16 source text. A conservative planning allowance of 256 bytes per parsed value is another 12.2 MiB; allowing one additional source-sized character copy for parsed strings and protocol collections keeps the metadata working-set estimate below roughly 40 MiB. This is an upper-bound analysis, not a JVM heap measurement; page, batch, record, and item caps normally keep use much lower. The former 2 MiB/20,000-value ceilings could reject a normal 70-photo paired CloudKit response containing many long signed rendition URLs. Smaller CloudKit pages and legacy asset batches reduce the usual working set rather than relying only on the raised ceilings.

## Build and test

The release toolchain is pinned to Ubuntu 24.04, Temurin 21, Android platform 35, Android build-tools 35.0.0, Java `--release 8`, and Python 3 as supplied by the runner. Locally, set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/check_upstream_screensaver.py /path/to/kiosk-satellite
python3 tools/inspect_package.py
python3 tools/audit_repository.py
```

The build writes a deterministic versioned ZIP such as `dist/apple-shared-album-0.1.6.zip`, matching release manifest, SHA-256 file, and compile-time SDK JAR. The ZIP contains exactly the Plugin Manager root files (`kiosk-satellite-plugin.json`, `plugin.jar`, and `LICENSE`) plus any supported `assets/` files; `NOTICE` remains in the repository and is linked below for upstream attribution. ZIP entries have fixed timestamps and ordering. Toolchain changes can still alter DEX bytes, so release assets must come from one workflow run.

Tests use only synthetic JSON, fake transport responses, and reserved fake paths. They never contact an album. See [docs/development.md](docs/development.md) for the verification matrix and [docs/research.md](docs/research.md) for source/version/license provenance.

## Limitations and device validation

- Apple's public album protocols are undocumented and may change.
- Still images only; videos and Live Photo motion are skipped.
- Albums are capped at 1,000 displayable images; CloudKit traversal is capped at 25 pages and 2,500 records. The page cap is 25 because the request size is now 100 records and 2,500 remains the independent total-record ceiling.
- No image is cached on disk. Restarting or refreshing needs network access.
- Capture dates depend on Apple returning `dateCreated` (legacy) or `assetDate` (CloudKit), or on usable JPEG EXIF. Place names require explicit IPTC/XMP location fields in the downloaded JPEG; otherwise GPS display requires usable JPEG EXIF. The maintained protocol references inspected do not expose a stable Apple payload place field. True names for coordinate-only photos require an explicit privacy/provider choice for reverse geocoding.
- Automated tests prove parsing, protocol shaping, a 70-photo legacy album in three bounded asset batches, a 70-photo paired CloudKit response larger than the former 2 MiB ceiling, safe rendition preference/fallback, distinct size failures, decoded-pixel protection, pagination, overlay rendering, text-only pixel shift, local EXIF/IPTC/XMP parsing, packaging, and compilation with synthetic fixtures. **On 20 October 2026, the maintainer confirmed that the device checks were completed and both supported public album URL formats were working.** Never put a live album URL, photo, coordinate, or metadata in an issue, fixture, CI variable, commit, or log.

## License

[Apache-2.0](LICENSE). See [NOTICE](NOTICE) for upstream attribution.
