# Apple Shared Album for Kiosk Satellite

Turn a public Apple Shared Album into a photo screensaver for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite). Choose how photos are ordered and displayed, and optionally add a clock, date or photo details. No Apple account login, API key, companion server or Home Assistant integration is required.

## Requirements

- **Kiosk Satellite 2026.10.18 or newer**, with SDK 1 plugin screensavers.
- Android 7.0 (API 24) or newer.
- An Apple Shared Album with **Public Website** enabled and a complete HTTPS link in either supported format:

```text
https://www.icloud.com/sharedalbum/#TOKEN
https://photos.icloud.com/shared/album/OPAQUE_ID
```

Private albums, invitation or approval links, iCloud Links (`share.icloud.com/photos/...`) and links requiring sign-in are not supported.

## Installation

### Install button

Open the plugin in Kiosk Satellite, review it and choose **Trust and install**.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=dark-matter-design/kiosk-satellite-plugin-apple-shared-album)

The button uses the [Community Plugins](https://kiosksatellite.com/plugins/) installer. If the plugin is not available there yet, use the manual steps below. Once listed, you can also search for **Apple Shared Album** under **Settings > Plugin Manager > Browse plugins**.

### Manual installation

1. Open **Settings > Plugin Manager** and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste the repository URL below and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

```text
https://github.com/dark-matter-design/kiosk-satellite-plugin-apple-shared-album
```

For local development, use **Install from ZIP** with a built package from `dist/`.

## Set up the screensaver

1. Enable **Apple Shared Album** in Plugin Manager.
2. Paste the complete public album link into **Public album link**.
3. Select **Screensaver > Screensaver mode > Apple Shared Album (Apple Shared Album)**.

If Remote Admin does not show the screensaver, check that the plugin says **Running**, then reload Remote Admin and reopen **Screensaver mode**. You can also select the mode directly on the kiosk. An app restart is normally unnecessary.

## Settings

| Setting | Options and defaults |
| --- | --- |
| Public album link | Complete public URL in either supported format |
| Time per photo | 5 seconds to 1 hour, in 5-second steps; 30 seconds by default |
| Photo order | Shuffle (default), Newest first or Oldest first |
| Photo layout | Fit whole photo (default) or Fill screen |
| Clock | Optional local time, with 12/24-hour and seconds controls; off by default |
| Current date | Optional device-local date; off by default |
| Photo capture date | Optional date from Apple metadata or local JPEG EXIF; off by default |
| Photo location | Optional embedded place name or source-labelled GPS coordinates; off by default |
| Overlay position | Top right (default), Top left, Bottom left or Bottom right |
| Overlay appearance | Font family, weight, colour and 50-150% size; light sans serif, near-white and 100% by default |
| Shift overlay pixels | Moves the text overlay once per minute; off by default |

The album list refreshes every 15 minutes. Use **Reconnect and refresh album** to discard the current list and reconnect immediately. Compatible saved settings are retained after updates.

### Photo details and pixel shift

All overlay content is off by default. Capture dates and locations appear only when suitable metadata is available; Apple often removes it from shared photos. Location uses explicit embedded place fields or local EXIF GPS, never captions or reverse geocoding. The current-date overlay shows today's date separately from the photo's capture date.

For a stationary photo, leave Kiosk Satellite's **Screensaver > Pixel shift** off and use the plugin's **Shift overlay pixels** instead. The plugin moves only text, by at most 10 pixels per axis once per minute. The host-wide setting moves the complete screensaver, including the photo.

## Privacy and security

The kiosk sends the public album token or ID directly to Apple's iCloud services and downloads photos from validated Apple hosts over HTTPS. The plugin sends nothing to its maintainers, analytics providers, Home Assistant or other third-party services. It has no account login, telemetry or disk photo cache. Photos and album metadata stay in memory; optional metadata inspection happens locally.

**Treat the album link as sensitive:** it grants public read access. Kiosk Satellite stores and displays it as an ordinary plugin setting, so anyone with access to settings or backups may be able to read it. Protect Remote Admin and backups, and disable the album's Public Website in Photos to revoke access.

Requests, redirects, image decoding and rendered output have enforced limits. Errors omit album URLs, tokens, images and metadata. See the [privacy details](docs/privacy.md) and [security policy](SECURITY.md).

## Limitations

- Still images only; videos and Live Photo motion are skipped.
- Up to 1,000 displayable images, with bounded album traversal.
- Unsupported, oversized or undecodable images may be skipped.
- Network access is needed after a restart or refresh because photos are not cached on disk.
- Apple's public album protocols are undocumented and may change.

## Development and validation

See [development and verification](docs/development.md) for the toolchain, test commands, packaging and device checklist; [architecture](docs/architecture.md) for protocol and rendering details; and [research notes](docs/research.md) for source provenance. Tests use synthetic fixtures only.

The maintainer confirmed device testing with both supported public album URL formats on 20 October 2026. Do not include real album links, photos or metadata in issues, fixtures or logs.

## License

[Apache-2.0](LICENSE). See [NOTICE](NOTICE) for upstream attribution. This is an independent community plugin and is not affiliated with Apple.
