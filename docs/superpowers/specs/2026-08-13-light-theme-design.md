# Design: Light theme / system color scheme (TASK-312)

## Context

GitHub issue #22 (@PasqualePerilli): a user wants to choose a light/white theme so the app matches the rest of their system. TASK-20 (Done) added three brand themes (Default, WhatsApp, Telegram) but deliberately dark-only: `AntiVocaleTheme` always resolves to one of three `darkColorScheme()` values. There is no light scheme at all, and the theme system uses Material3 cleanly: every screen reads `MaterialTheme.colorScheme.*` (the Model/Logs/Settings tabs have zero hardcoded colors). The one known exception is `BenchmarkDialog`, which uses a few intentional hardcoded status-badge accent colors plus white badge text; these are saturated accents that render acceptably on a light background but sit outside the colorScheme, so they go in the light-mode contrast check.

Goal: add a Light/Dark/System mode toggle layered on the existing brand themes, so the user can follow the system setting (their actual ask: "looks like the rest of my system") or force a mode. Each brand keeps its identity (green for WhatsApp, blue for Telegram) in both palettes.

## Design

### Brand + mode (two independent dimensions)

- **Brand** (existing, unchanged): Default / WhatsApp / Telegram, stored in `themePreference`.
- **Mode** (new): SYSTEM / DARK / LIGHT, stored in a new `themeMode` preference, default SYSTEM.

The (brand, mode) pair selects the exact scheme. No `*_LIGHT` enum entries needed: the pair already disambiguates.

### Scheme resolution (6 schemes)

Each brand gets a light `ColorScheme` alongside its existing dark one (3 dark already exist, 3 light to add). `AntiVocaleTheme` resolves:

```
val isDark = when (mode) {
    SYSTEM -> isSystemInDarkTheme()
    DARK   -> true
    LIGHT  -> false
}
val scheme = when (brand) {
    DEFAULT  -> if (isDark) DefaultDarkColorScheme else DefaultLightColorScheme
    WHATSAPP -> if (isDark) WhatsAppDarkColorScheme else WhatsAppLightColorScheme
    TELEGRAM -> if (isDark) TelegramDarkColorScheme else TelegramLightColorScheme
}
```

Light palettes are proper Material3 light schemes: light backgrounds/surfaces with dark readable text, brand-tinted primaries kept but with appropriate on-colors and containers. WhatsApp light keeps green accents; Telegram light keeps blue accents. Not inverted dark values.

### Preference + state

- New `themeMode: Flow<String>` in `PreferencesManager` / `PreferencesManagerImpl`, default `SYSTEM`. Mirrors the existing `themePreference` pattern (key + flow + save + cache field).
- New `ThemeMode` enum (SYSTEM / DARK / LIGHT) in the theme package.

### AntiVocaleTheme signature

`AntiVocaleTheme(brand: ThemeType, mode: ThemeMode, content)`. `MainActivity` reads both `themePreference` (brand) and `themeMode` (mode) and passes both.

### UI

A second `SettingsDropdown` in `SettingsTab`, directly under the existing brand selector, labeled "Theme mode" with options System / Dark / Light. The brand selector stays as-is. Strings in en + it.

### Backward compatibility

Default mode = SYSTEM. Existing users (brand set, no mode) get system-following behavior. On a dark-system device this is visually identical to today; on a light-system device the app finally renders light. No migration needed.

## Files

- `ui/theme/Theme.kt`: add 3 light ColorSchemes, `ThemeMode` enum, change `AntiVocaleTheme` to take (brand, mode) and resolve via `isSystemInDarkTheme()` (import from `androidx.compose.foundation`, not material3).
- `data/PreferencesManager.kt` + `PreferencesManagerImpl.kt`: `themeMode` flow + save + key + cache field + default constant.
- `MainActivity.kt`: read both prefs, pass brand + mode to `AntiVocaleTheme`.
- `ui/viewmodel/SettingsViewModel.kt`: expose `themeMode` state + `themeModeOptions` + setter.
- `ui/tabs/SettingsTab.kt`: second dropdown for mode.
- `res/values/strings.xml` + `res/values-it/strings.xml`: mode selector title + System/Dark/Light labels.

## Verification

1. Build both flavors.
2. Device: toggle mode System/Dark/Light for each brand; confirm correct scheme applies immediately (no restart) and persists across relaunch.
3. System-follow: with mode = System, change the device's system dark setting; the app follows.
4. Contrast check: every screen readable in light mode (Model, Logs, Settings, in-app result, BenchmarkDialog's hardcoded status-badge accents).
5. Backward compat: fresh install (no mode set) defaults to System and renders correctly on a dark-system device (identical to today).
6. No em-dashes in prose/strings (project rule).
