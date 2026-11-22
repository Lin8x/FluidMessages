# Contributing to FluidMessages

FluidMessages is an open-source Android messaging app with an optional LAN companion. The phone owns carrier messaging. The browser displays synced conversations and queues work for the phone. The primary hardware target is a dual-SIM Pixel 7 on GrapheneOS in the US.

## Design and interaction

Use the same UI and color logic for live and sample conversations. Keep the sample mode useful before SMS setup and safe for visual review.

- **Inbox.** Keep Settings at the leading edge, collection filters at the upper trailing edge, and Compose with the selected search layout. Verify Inbox, Archived, and Blocked in the sample inbox. Use a visible accent tint for actionable navigation icons.
- **Themes.** Put named themes first in the appearance screen. Place message colors under **Customize colors** and leave the optional green SMS and blue RCS preset in setup. Use the shared UI palette for settings cards, fields, buttons, dialogs, and companion surfaces. Give conversation bubbles their own color roles. Apply the selected theme’s defaults when switching or resetting a theme. Treat sound, haptic, and motion choices independently.
- **Contrast.** Use white text on colored bubbles and shade bright fills for readable contrast. Select pure black or white for neutral fills. Apply the rule to the live thread, sample threads, and animated appearance preview. Keep touch targets comfortable and check light mode, dark mode, and reduced motion when changing visuals.
- **References.** Translate the requested spacing, hierarchy, and interaction details into FluidMessages’ original UI. Use fictional names and message content in samples and test screenshots.
- **Protocol labels.** Report which capabilities work. Carrier SMS is distinct from the RCS appearance preview, and queued browser submissions are distinct from carrier delivery.

## Development and testing

Android lives in `app/`. Use Kotlin and Compose for UI and reuse the Java role, telephony, SIM, backup, and sync interfaces. The browser and Python service live in `server/`.

Use JDK 17, Android SDK 35, and the bundled Gradle wrapper. Run from the checkout as your regular user.

```sh
./scripts/test-emulator.sh --headless
./scripts/test-emulator.sh --open
./scripts/test-local
```

The emulator smoke test covers setup, the theme dropdown, sample inbox filters, and a synthetic send. `--open` leaves the app ready for manual inspection. Pass `--apk PATH` to review a known build. Set `ANDROID_HOME` when the SDK is outside the standard location. `./scripts/test-local --demo` starts the browser companion with fictional data. When rclone is installed, the local smoke test checks uploads and newest-file downloads against three temporary directories.

In a UI PR, give the path through the app, attach a screenshot when placement or color matters, and say which actions worked. Check the expected visual result directly. Build and install success only establish that the APK runs.

## Data and device checks

- Use fictional samples and treat message history, backups, credentials, and signing material as private.
- Preserve backup compatibility. Exercise encrypted export, wrong-password handling, category-level restore, and repeated restore when touching backup code.
- Preserve the companion’s persisted queue and at-most-once claiming. Verify sync, scheduling, cancellation, and phone submission when touching that contract.
- For telephony changes, exercise role setup and simulated SMS first. With authorization, verify incoming and outgoing SMS and both SIMs on a test phone. Identify those results separately from emulator checks.

See [README.md](README.md) for supported features, setup, and self-hosting.
