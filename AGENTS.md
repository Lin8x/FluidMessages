# FluidMessages agent guide

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing the app. Treat the current request and its acceptance details as the task. Inspect the existing behavior and fix the failed interaction at its real entry point.

## Product decisions to preserve

- Android uses Kotlin and Compose for messaging and appearance UI. Use the existing Java SMS role, provider, SIM, backup, and sync integration where appropriate. The LAN companion queues work for the phone.
- Place inbox filters at the upper trailing edge, including in the sample inbox. Use the active control tint for navigation icons and a contrasting colored fallback when needed.
- In **Settings → Themes and appearance**, show **Color theme** first and list every named theme. Keep message colors in **Customize colors** and the optional green SMS and blue RCS preset in setup. Selecting or resetting a theme restores its colors, wallpaper, and layout. Preserve sound, haptic, and motion choices.
- Use white text on colored bubbles and shade bright fills for readable contrast. Choose black or white for neutral fills, including during preview animations. Use the same rule in sample and live conversations. Keep RCS labeled as a preview until carrier support works.
- Use screenshots as references for the requested design qualities. Use original visuals and fictional message fixtures. Exclude conversation contents, contact details, pairing tokens, and keys from logs and deliverables.

## Verification

- Run `./scripts/test-emulator.sh --headless` for adjacent UI flows. Review the rendered screen for visual work. A passing build alone is insufficient evidence for placement, colors, or contrast.
- For inbox changes, verify filter position and behavior in the sample inbox. For theme changes, open the full theme dropdown and verify bright and dark bubble fills. Report the actual observed coverage.
- Keep real SMS sends, SMS role changes on a personal device, and publishing within the user’s authorization. Keep backup format compatibility, encryption defaults, and durable server queue behavior intact.
- Run `./scripts/test-local` for the companion and APK smoke checks. Describe any test that could not run and distinguish emulator coverage from carrier tests.
