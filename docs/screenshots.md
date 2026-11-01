# CI screenshots

`scripts/ci/screenshots.sh` runs at the end of `scripts/ci/build.sh` on GitHub Actions (skip it with
`MAWJA_SCREENSHOTS=0`). It boots a headless emulator (API 34, falling back to 31 and 30), installs the
`githubDebug` app plus the `:admin` and `:downloader` debug APKs when they were built, and captures every
v1.0 screen in **Arabic and English, dark and light**. The result never fails the build.

## Output

| Path | What |
| --- | --- |
| `build/screens/<screen>_<lang>_<mode>.png` | one capture, e.g. `home_ar_dark.png`, `equalizer_en_light.png` |
| `build/screens/index.json` | manifest: `sha`, `api`, `variants`, `counts` per status, `screens` (unique names), and `shots[]` with `screen`, `lang`, `mode`, `file`, `status` (`captured`, `blank`, `skipped`, `failed`), `bytes`, `note` |
| ntfy log topic | `screens src=<sha> count=N zip=<url> index=<url> home-en=<url> ...`: the zip holds every PNG and the manifest; the README set is linked one by one under its legacy names (`home-en`, `themes-ar`, `onboarding-en`, ...; all from the dark captures) |
| build summary | the `screens:` line adds `zip=... index=... counts={...}` |

`blank` is a size heuristic (a single-colour 1080x2400 PNG deflates more than 200x): a `FLAG_SECURE` window
or a screen that never rendered. Check those by eye before committing anything.

## Screens

Pass 1 (every variant, in this order): `home`, `search`, `player` (video), `music_player`, `shorts`,
`library`, `history` (smart lists appear once the player shot has added history), `downloads`,
`subscriptions`, `music_home`, `notifications_inbox`, `settings`, `appearance`, `theme`, `theme_premium`
(theme picker scrolled to Mawja's premium palettes, Nile), `app_icon` (dialog), `player_appearance`,
`accessibility`, `accessibility_large` + `home_large` (Mawja largest text, high contrast, red-green
colour-blind mode, reduce motion, and system font scale 200%), `equalizer` (presets), `subtitles`
(Playback page highlighted on Second subtitle language), `offline` + `offline_downloads` (airplane mode
on), `islamic` (prayer times, Cairo), `qibla`, `notifications` (smart notifications settings), `account`
(guest), `upgrade`, `codes_redeem` (upgrade page scrolled to "Activate a code"), `background_jobs`,
`battery_saver`, `widgets`, `language`, `admin_main`, `downloader_main`. Plus `onboarding_en_dark` once,
on first launch.

Pass 2 (as long as the time budget allows): `music_player_bg_<style>` for every
`MusicPlayerBackgroundStyle` except the default `BLUR_GRADIENT` (read from the source, so new styles are
picked up), `music_player_live_<id>` for every `LiveBackgroundStyle` except `off` when that enum exists
in the build, `theme_nile_home` + `theme_nile_picker` (Nile stored as the theme), `widget_picker` (launcher
> Widgets > Mawja), `music_search`.

## How each screen is reached

No test APIs: only intents, prefs and adb.

- **Routes**: `am start -n <pkg>/app.mawja.android.MainActivity --ez app.mawja.android.extra.BENCHMARK_BYPASS_ONBOARDING true --es widget_route '<route>'`.
  `MainActivity.handleIntent` hands `widget_route` (`WidgetDeepLink.EXTRA_WIDGET_ROUTE`) to
  `IncomingLinks.openRoute`, so any `FlowNavigation` route works: `home`, `search`, `library`, `history`,
  `downloads`, `subscriptions`, `music`, `notifications`, `shorts`, `musicPlayer/<id>`,
  `musicSearch?query=<q>`, `settings?target=<id>`.
- **Settings pages**: `settings?target=<SettingsDestination.id>[~tab][%23highlight]`
  (`SettingsTarget.encode`; `#` goes as `%23` so it is not read as a URI fragment). Ids:
  `home`, `account`, `upgrade`, `appearance`, `theme`, `player_appearance`, `accessibility`, `equalizer`,
  `battery_saver`, `playback`, `islamic`, `qibla`, `notifications`, `widgets`, `language_region`,
  `background_jobs`, ... Highlights are settings-index keys, e.g. `appearance.app_icon`,
  `playback.secondary_subtitle_language`.
- **Video player**: `--es video_id <id>`. **Full music player**: play through `musicPlayer/<id>`, then
  `--ez open_music_player true`.
- **Dialogs and scrolled spots**: `uiautomator dump` + tap on the matching text (`App Icon` /
  `أيقونة التطبيق`), `input swipe` to scroll.
- **App state**, written with `run-as` while the app is stopped (debug builds are debuggable):
  - SharedPreferences XML: `mawja_islamic` (enabled, city `cairo`), `mawja_accessibility` (`text_size`,
    `high_contrast`, `reduce_motion`, `color_vision`), `mawja_visual_player` (`live_background`),
    `flow_language_prefs` (`app_language_tag`, used below API 33).
  - Preferences DataStore: one protobuf map entry appended to `files/datastore/<store>.preferences_pb`
    (a repeated key parses last-wins): `player_preferences/music_player_background_style`,
    `flow_preferences/theme_mode`.
- **Language**: `cmd locale set-app-locales <pkg> --locales ar-EG|en-US` for the app, admin and downloader
  (API 33+); below 33 only the main app switches (its own language pref).
- **Light/dark**: `cmd uimode night yes|no` (the app's theme defaults to following the system).
- **Offline**: `cmd connectivity airplane-mode enable|disable` (fallback: `settings put global airplane_mode_on`).
- **Text 200%**: `settings put system font_scale 2.0`, reset to 1.0 after.

## Knobs (environment)

| Variable | Default | Use |
| --- | --- | --- |
| `MAWJA_SCREEN_VARIANTS` | `en:dark ar:dark en:light ar:light` | which language/mode pairs, in order |
| `MAWJA_SCREEN_ONLY` | all | comma list of step names (`home,equalizer,music_player_bg_immersive`) |
| `MAWJA_SCREEN_BUDGET_SEC` | `1440` | captures stop this many seconds after the script starts (boot included); the rest is recorded as `skipped: time budget` |
| `MAWJA_PLAYER_BACKGROUNDS` / `MAWJA_LIVE_BACKGROUNDS` | from source | override the style lists |
| `MAWJA_SCREEN_VIDEO` / `MAWJA_SCREEN_MUSIC` | `jNQXAC9IVRw` / `kJQP7kiw5Fk` | video and song used by the players |
| `MAWJA_SCREEN_APIS` | `34 31 30` | emulator API levels to try |
| `STEP_T` | `100` | hard timeout per step, seconds |

Every step is independent: it launches what it needs, and a timeout, crash or missing screen is logged
(`skip ...`, `step ... timed out`) and written to the manifest without stopping the run. Pass 1 covers all
variants before pass 2 starts, so a short budget drops the extras first.

## Known limits

- **Admin app** sets `FLAG_SECURE` and asks for the device unlock, and needs a staff sign-in: its capture is
  the lock/sign-in screen at best and usually lands as `blank`. Real admin screenshots need a debug-only
  switch for `FLAG_SECURE` and a signed-in staff session.
- **Premium looks for a guest**: Nile and the live backgrounds are locked without a membership, so
  `theme_nile_*` and `music_player_live_*` show what a guest sees (fallback palette / no live background,
  or the upsell), not the unlocked look.
- **Widget picker** is the launcher's own UI: its language follows the device (English), and finding the
  Mawja row depends on the launcher version.
- Network screens (home, search, players, shorts, music) depend on YouTube answering the emulator.
- Runtime: boot takes 5-9 minutes; pass 1 is about 4-5 minutes per variant. Use `MAWJA_SCREEN_VARIANTS` or
  `MAWJA_SCREEN_ONLY` for a narrower run.
