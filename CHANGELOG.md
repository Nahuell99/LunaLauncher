# Changelog

## 2.0.0 — LunaLauncher (unreleased)

First version under the LunaLauncher name.

### Features

- Icon engine: system, themed (Android 13+), bundled Lawnicons or any ADW/Nova icon pack; icon shape, size and tint; per-app icon picks.
- Free-form home grid with drag & drop, resize and an edit mode; pinned shortcuts and widgets requested by apps land on it.
- Dock: a fixed row of icons at the bottom.
- Several Android widgets on the grid, plus declarative JSON widgets with export/import (`docs/widgets.md`).
- Clock styles for the home clock: 10 bundled styles, a visual editor, time in words and binary tokens.
- Drawer: order by use or recent use, grouping by category (Android's or your own).
- Search: calculator, unit conversion and `>` commands.
- Material You colours from the wallpaper (Android 12+).
- Notification dots or counts on the home list, grid, dock and drawer.
- Backups include the grid, dock, widgets, clock styles and icon picks, and are validated before import.

### Changes

- Renamed to LunaLauncher; removed the upstream promotional texts (Pro version, wallpapers, affiliate link) and the BetterLauncher release pipeline.
- Widget files use the `lunalauncher-widget` format id; files with the old `olauncher-widget` id still import.

## Earlier history

LunaLauncher is a fork of [Olauncher](https://github.com/tanujnotes/olauncher) v6.9.17 with the
features of [BetterLauncher](https://github.com/pierspad/BetterLauncher) up to v1.5.0. Their
change history is available in those repositories.
