# Widgets personalizados

Los widgets propios de la grilla de inicio son archivos JSON **declarativos**: describen
líneas de texto, barras, espacios y botones de música. No llevan código ni se descargan de
ningún lado, así que importar uno es seguro. Se manejan en
**Ajustes → Grilla de inicio y widgets → Widgets personalizados**.

La forma más rápida de diseñar uno: exportá un widget integrado, editalo y volvé a importarlo.
Los integrados están en [`app/src/main/assets/widgets`](../app/src/main/assets/widgets).

## Ejemplo

```json
{
  "format": "olauncher-widget",
  "version": 1,
  "name": "Reloj y batería",
  "spanX": 4,
  "spanY": 2,
  "gravity": "start",
  "tap": "clock",
  "background": { "color": "primary", "alpha": 0.08, "radius": 18 },
  "lines": [
    { "text": "{time}", "size": 56, "font": "sans-serif-thin" },
    { "text": "{date:EEEE d MMMM} · {battery}%", "size": 14, "alpha": 0.7 },
    { "type": "bar", "value": "{battery}", "max": 100, "height": 3 }
  ]
}
```

## Campos del widget

| Campo | Valores | Por defecto |
| :--- | :--- | :--- |
| `format` | siempre `"olauncher-widget"` | obligatorio |
| `version` | `1` | `1` |
| `id` | texto; al importar se reasigna si choca con otro | automático |
| `name`, `name_es`, `name_<idioma>` | nombre visible | `id` |
| `spanX`, `spanY` | celdas que ocupa al agregarlo (1–10 / 1–14) | `4`, `1` |
| `gravity` | `start`, `center`, `end` | `start` |
| `vgravity` | `top`, `center`, `bottom` | `center` |
| `padding` | dp | `8` |
| `background` | `{ "color", "alpha", "radius", "stroke", "strokeWidth" }` | sin fondo |
| `tap` | `none`, `clock`, `calendar`, `edit`, `media`, `app:<paquete>` | `none` |
| `lines` | 1 a 24 líneas (ver abajo) | obligatorio |

`tap: "edit"` abre un cuadro de texto que guarda lo escrito en el elemento de la grilla; ese
texto se lee con `{data}` (notas, cuenta regresiva…).

## Tipos de línea

| `type` | Campos |
| :--- | :--- |
| `text` (por defecto) | `text`, `text_<idioma>`, `size` (sp), `font`, `bold`, `italic`, `color`, `alpha`, `letterSpacing`, `uppercase`, `maxLines`, `hideIfEmpty`, `shadow`, `gravity`, `marginTop` |
| `bar` | `value` (texto con tokens que da un número), `max`, `height`, `color`, `alpha`, `marginTop` |
| `space` | `height` (dp) |
| `media_controls` | `size` (dp), `color`, `gravity` |
| `row` | `items`: líneas puestas una al lado de la otra (no se pueden anidar), `gravity` |

**Colores:** `primary` (color del texto del launcher), `secondary`, `accent`, `inverse` o
`#RRGGBB` / `#AARRGGBB`.

**Fuentes:** `launcher` (la elegida en Ajustes, por defecto) o una familia de Android como
`sans-serif-thin`, `sans-serif-light`, `sans-serif-condensed`, `serif`, `monospace`, `casual`, `cursive`.

Si el contenido no entra en las celdas, el texto se achica solo para que quepa.

## Tokens

Se escriben dentro de `text` y `value`. `{token|alternativa}` muestra la alternativa cuando el
valor está vacío; `{token:argumento}` pasa un formato.

| Token | Valor |
| :--- | :--- |
| `{time}`, `{time:HH:mm}` | hora (respeta 12/24 h si no hay formato) |
| `{date}`, `{date:EEEE d MMMM}` | fecha con [patrón de `SimpleDateFormat`](https://developer.android.com/reference/java/text/SimpleDateFormat) |
| `{battery}` | porcentaje de batería |
| `{charging}`, `{charging:texto}` | `⚡` (o el texto) mientras carga |
| `{alarm}`, `{alarm:patrón}` | próxima alarma |
| `{greeting}` | buen día / buenas tardes / buenas noches |
| `{screentime}` | tiempo de pantalla de hoy (requiere el permiso de uso ya existente) |
| `{event}`, `{event_time}` | próximo evento del calendario (pide permiso de calendario al agregarlo) |
| `{media_title}`, `{media_artist}`, `{media_app}` | lo que está sonando (requiere acceso a notificaciones) |
| `{data}` | texto guardado con `tap: "edit"` |
| `{days_until}`, `{days_until:2026-12-25}` | días hasta una fecha; sin argumento usa la primera palabra de `{data}` |
| `{data_rest}` | `{data}` sin su primera palabra (el nombre en "2026-12-25 Navidad") |

Los tokens desconocidos se muestran tal cual, para que los errores de tipeo se vean al diseñar.

## Permisos

Todo funciona sin conexión. Solo dos tokens piden algo, y únicamente al agregar un widget
que los usa:

- `{event…}`: permiso de calendario.
- `{media…}` y `media_controls`: acceso a notificaciones, que se usa solo para leer la sesión
  de música activa. Las notificaciones nunca se leen ni se guardan.
