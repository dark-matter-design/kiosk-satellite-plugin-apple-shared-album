# Privacy

The plugin sends the public album token or opaque ID only to Apple's public iCloud API and downloads selected image bytes only from validated Apple iCloud media hosts. It sends nothing to this project, its maintainers, analytics providers, or Home Assistant.

The album URL is an ordinary Kiosk Satellite SDK 1 string setting, not a masked secret field. Anyone who can view plugin settings or an included backup may be able to read it. Revoke the album's public website in Apple Photos when access is no longer required.

Album lists, short-lived anonymous CloudKit grants, signed image URLs, compressed display images, capture dates, GPS coordinates, and rendered HTML stay in process memory. The plugin writes no cache or log containing them. Disabling, reconfiguring, replacing, or uninstalling the plugin cancels work and drops its in-memory references; Kiosk Satellite controls deletion of stored plugin settings on uninstall.

Clock, current date, photo capture date, photo location, and overlay pixel shift are all off by default. Current date means today's device-local date; it is not inferred from the photo. Photo capture date uses Apple's bounded item metadata when present, then local JPEG EXIF. Photo location first uses only explicit IPTC/XMP location fields from image bytes already downloaded from Apple; captions, titles, and descriptions are not location evidence. If no explicit place field exists, bounded EXIF GPS is displayed as source-labeled coordinates. Reverse geocoding would disclose coordinates to a provider, so this plugin makes no such request. Apple often strips metadata from shared renditions, in which case the metadata line stays hidden.

Pixel shift changes only the in-memory inline document's text-overlay transform by at most 10 px per axis once per minute. It does not move, crop, zoom, reload, or otherwise alter the displayed photo.

The plugin uses `host.read` only to query `isScreensaverActive` and observe `screensaver.state`. It subscribes before the initial read, gives newer events precedence over a late read, and does not fetch album metadata or photos until active. Dismissal cancels requests and scheduled work; refresh actions while idle are deferred until activation. No host state is sent to Apple.
