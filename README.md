# TestNav

An Android app that reads turn-by-turn navigation data from Google Maps (via Notification and Accessibility Service) and pushes it to a car head unit / dashboard display through MediaSession.

## Architecture

Navigation data comes from two sources that complement each other:

- **NotificationListenerService** — reads Google Maps' navigation notification (`com.google.android.apps.maps`), extracting next-turn distance, next road name, and remaining time/distance/ETA (parsed from `subText`). Works even when Maps is running in the background.
- **MapsAccessibilityService** — reads the Google Maps UI directly while the app is in the foreground, extracting the detailed turn instruction, speed, and remaining time/distance. Only works while Maps is in the foreground.

These two sources are merged in **NavStateManager**, which prefers Accessibility data when Maps is in the foreground and the data is still fresh, and automatically falls back to Notification-derived data when Maps is pushed to the background or the Accessibility data goes stale. When navigation ends (the notification is removed), the state is cleared automatically after a short debounce delay.

**MediaSessionService** exposes this data as a MediaSession (title/artist), so it displays on the car's screen the same way a music player would.

## File structure

| File | Role |
|---|---|
| `MainActivity.kt` | Main screen: enable Notification Access / Accessibility permissions, show debug state |
| `NotificationListenerService.kt` | Reads Google Maps' navigation notification |
| `MapsAccessibilityService.kt` | Reads the Google Maps UI via Accessibility |
| `NavState.kt` | Data class holding the current navigation state |
| `NavStateManager.kt` | Merges data from both sources, handles fallback/clear logic |
| `MediaSessionService.kt` | Pushes the state out as a MediaSession for the car display |
| `accessibility_service_config.xml` | Accessibility Service permission/event-type configuration |

## Setup & run

1. Clone the project:
   ```bash
   git clone git@github.com:ngohoang34/testnav.git
   ```
2. Open in Android Studio, build, and install on a device (Google Maps must already be installed).
3. Open the app and enable, in order:
   - **Open Notification Access** → grant TestNav permission to read notifications
   - **Open Accessibility Settings** → enable the Accessibility Service for TestNav
   - Tap **Start Media Service**
4. Open Google Maps and start navigation — data will appear on the main screen (debug) and via MediaSession (if connected to a car display / Android Auto / Bluetooth media display).

## Required permissions

- `BIND_NOTIFICATION_LISTENER_SERVICE` — read notifications
- `BIND_ACCESSIBILITY_SERVICE` — read the Google Maps UI
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — run MediaSessionService in the background

## Current limitations

- Only text data is read (distance, road name, instructions); the turn-direction **icon/image** is not read.
- Depends on Google Maps' internal view IDs — these may change with Google Maps updates, so check them again if Accessibility suddenly stops returning data.
- Google Maps' notification updates may be rate-limited by the OS/OEM, which can introduce some delay in data coming from that source.

## License

Personal project, for private use.
