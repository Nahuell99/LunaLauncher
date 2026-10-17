# <img src="docs/images/icon.png" alt="" height="36"> LunaLauncher

[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84?logo=android&logoColor=white)](#install)

[Features](#features) · [Install](#install) · [Search](#search) · [Privacy](#privacy) · [Development](#development) · [Credits](#credits) · [License](#license)

LunaLauncher es un launcher minimalista para Android: por defecto se ve como una pantalla de
texto limpia, y todo lo demás (íconos, grilla, dock, widgets, estilos de reloj) se activa solo
si lo querés. Software libre (GPL-3.0), sin anuncios y **sin permiso de Internet**.

Es un fork de [Olauncher](https://github.com/tanujnotes/olauncher) que incorpora las funciones
de [BetterLauncher](https://github.com/pierspad/BetterLauncher). Ver [Credits](#credits).

## Features

**Home screen**
- Text-based home with adjustable font, size, alignment and background opacity.
- Up to eight home apps and eight shortcut icons; drag to reorder.
- Optional free-form grid: apps, shortcuts, folders and widgets on any cell; drag, resize and edit in place.
- Optional dock: a fixed row of icons at the bottom.
- Clock styles: a gallery of 10 styles (thin, bold, stacked, in words, binary, terminal, serif…) and a visual editor.

**Icons & colours**
- Icon sources: system, themed (Android 13+), bundled Lawnicons or any installed ADW/Nova icon pack.
- Icon shape, size and tint, and per-app icon picks from any pack.
- Material You: text and accent colours from the wallpaper (Android 12+).
- Notification dots or counts on the home list, grid, dock and drawer (optional notification access).

**Widgets**
- Several Android widgets on the grid.
- Custom JSON widgets (clocks, date, battery, alarm, calendar, music, notes, countdown, screen time) that you can export, edit and import. See [docs/widgets.md](docs/widgets.md).

**App drawer & search**
- Three search modes; optional contact and Android settings search; `!` searches the web.
- Calculator and unit conversion inline (`12*3+4`, `10 km a mi`), and `>` commands (`> wifi`, `> linterna`, `> alarma 7:30`, `> timer 10m`…).
- Order A–Z, by use or by recent use; optional grouping by category, with your own categories.
- App folders, hidden apps, renaming.

**Wellbeing & security**
- App opening protected by device credentials or biometrics within the launcher.
- Optional app limits with progressive cooldowns.
- Optional double tap to lock the screen.

**Settings**
- Import/export of every setting (grid, dock, widgets, styles, icon picks) as a JSON file or through the clipboard.
- Custom TrueType/OpenType fonts and an app language independent of the system.

## Install

Descargá el APK desde [Releases](https://github.com/Nahuell99/olauncher/releases) o compilalo
(ver [Development](#development)) e instalalo en el teléfono. Después elegí LunaLauncher como
app de inicio en Ajustes de Android → Apps predeterminadas.

## Search

| Mode | Behavior | Example |
| :--- | :--- | :--- |
| Smart | Word prefixes, initials and fuzzy single-word matching | `pm` → Proton Mail |
| Word prefix | Each term must start a word in the app label | `p m` → Proton Mail |
| Loose fuzzy | Query characters may occur anywhere, in order | `rdd` → Reddit |

Calculator, unit conversion and `>` commands are enabled in Settings → App drawer & search.

## Privacy

LunaLauncher has no Internet permission and sends nothing anywhere. Optional permissions
(contacts, calendar, usage access, notification access, accessibility) are requested only
when you turn on the feature that needs them and are processed on the device. Details in
[docs/privacy.md](docs/privacy.md).

App lock protects launches through LunaLauncher; it does not prevent another application or
Android itself from opening the target app.

## Development

Use JDK 17, Android SDK 36 and the included Gradle wrapper:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug build installs as `app.nahuell.launcher.debug`, next to any release build. See
[CONTRIBUTING.md](CONTRIBUTING.md) for emulator regression checks.

## Credits

LunaLauncher would not exist without:

- **[Olauncher](https://github.com/tanujnotes/olauncher)** by Tanuj Notes — the original
  minimal launcher this project is forked from (GPL-3.0).
- **[BetterLauncher](https://github.com/pierspad/BetterLauncher)** by pierspad — shortcuts,
  widgets, folders, biometric lock, app limits, custom fonts and settings backup (GPL-3.0).
- **[Lawnicons](https://github.com/LawnchairLauncher/lawnicons)** — the bundled monochrome icons
  (Apache-2.0). App icons and brand names belong to their respective owners.

Olauncher and BetterLauncher are projects of their respective authors. LunaLauncher is an independent fork and is not
affiliated with or endorsed by Olauncher or BetterLauncher.

## AI disclosure

Development and documentation have been assisted by language models. Contributions still require review and verification.

## License

[GNU GPL v3](LICENSE). Copyright of the original code belongs to its authors; modifications
in this fork are released under the same license.
