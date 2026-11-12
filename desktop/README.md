# Sync Tablet para el escritorio (Linux)

La misma app de la tablet, con el **mismo código** (`android/src/dev/synctablet/`, sin
cambios), funcionando en una ventana de Linux. Sin webview: debajo hay una capa que
imita la API de Android (`desktop/src/android/…`) dibujando con Java2D/Swing.

```bash
desktop/build.sh              # compila la app y la librería nativa (Rust)
desktop/build.sh --install    # además, la añade al menú de aplicaciones
desktop/build/synctablet-escritorio
```

Necesita Java 21 o más nuevo (con `jdk.compiler`), `cargo` para la librería nativa
(PDF, hojas de cálculo, Word y PowerPoint) y, opcionalmente, `zenity` para los
selectores de archivos de GNOME.

## Cómo se usa con ratón y teclado

| En la tablet | En el PC |
|---|---|
| Lápiz | Botón izquierdo (o la tableta gráfica) |
| Botón del S Pen | Botón derecho |
| Dedo (mover la hoja) | Rueda, o arrastrar con el botón central |
| Pellizcar (zoom) | Ctrl + rueda |
| Atrás | Esc, Alt+←, o el botón «atrás» del ratón |
| Pantalla completa | F11 |

Los datos de la app de escritorio están en `~/.local/share/synctablet/`. Con el servidor
del PC abierto (`synctablet`) se conecta a `localhost:8788` como un aparato más, y la
tablet y el PC quedan **sincronizados en los dos sentidos y en vivo**: cuadernos,
páginas, trazos, borrados, imágenes y notas. Al conectarse, cada aparato recibe lo que
cambiaron los demás; lo que borró sin conexión no vuelve (el PC recuerda qué vio cada
uno en `~/SyncTablet/.data/seen.json`).

**Portapapeles compartido** (como en Encaje): lo que copias en el PC (texto o una captura)
queda copiado en la tablet al momento, y lo que copias dentro de la app en la tablet
pasa al PC. En la hoja, **Ctrl+V** pega la imagen o el texto copiado (también en el PC).

## AppImage y actualizaciones

```bash
desktop/build-appimage.sh     # desktop/build/synctablet-escritorio-<versión>-x86_64.AppImage
```

Lleva su propio Java dentro. Se sube al release junto al APK (y su línea en
`SHA256SUMS`): la app abierta como AppImage se actualiza sola desde «Buscar
actualización», igual que la de la tablet (comprueba la suma, reemplaza el AppImage y
se vuelve a abrir). Necesita `appimagetool`; `--appdir` deja sólo la carpeta para probar.

Para probar sin tocar los apuntes de verdad: `android/build-apk.sh --prueba` instala una
app aparte que usa el puerto 8789, y la del PC acepta `-Dsynctablet.port=8789`,
`-Dsynctablet.data=carpeta` y `-Dsynctablet.device=nombre`.

Variables: `SYNCTABLET_SCALE=1.5` (tamaño de la interfaz), `SYNCTABLET_THEME=dark|light`,
`SYNCTABLET_EMOJI_FONT=/ruta/letra-CBDT.ttf`, `SYNCTABLET_DEBUG=1`.

## Cómo está hecha

- `android/graphics`: Canvas, Paint, Path, Bitmap… sobre Java2D. `RenderNode` es una
  imagen; `PdfDocument` escribe PDF vectorial; los emoji en color se leen de una letra
  CBDT (Twemoji) porque Java no los dibuja.
- `android/view`, `android/widget`: medida, disposición, toques, foco y teclado como en
  Android (LinearLayout, FrameLayout, ScrollView, TextView/EditText, GridLayout…).
- `android/text`: spans, `StaticLayout` y `Html` con la misma salida que Android.
- `android/app/Desktop.java`: la ventana, la pila de actividades y sus ciclos de vida,
  los selectores de archivos y Atrás.
- `android/opengl`: OpenGL ES por software, lo justo para el visor 3D.
- Pruebas sin manos: `-Dsynctablet.script=archivo` (ver `android/app/Automation.java`).
