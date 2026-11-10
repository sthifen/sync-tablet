<p align="center">
  <img src="https://sthifen.github.io/apps/logos/synctablet.svg" width="112" height="112" alt="Sync Tablet" />
</p>

<h1 align="center">Sync Tablet</h1>

<p align="center">
  <strong>Apuntes a mano en tu tablet Android que aparecen en vivo en tu PC con Linux.</strong><br />
  Handwritten notes on your Android tablet that show up live on your Linux PC.
</p>

> 🚧 **Sync Tablet 2.0 en desarrollo.** Esta es la primera pieza (apuntes a mano).
> La versión 1 (Electron) está en la rama [`legacy-v1`](../../tree/legacy-v1).

## Qué hace

- **En la tablet:** cuadernos y páginas (A4) con fondo de rayas, cuadros o liso.
  El **lápiz escribe** (con presión) y **el dedo mueve la hoja y hace zoom**, así la
  palma no pinta. Pluma, resaltador, goma (también con la punta de goma o el botón
  del S Pen), deshacer y rehacer. Funciona sin el PC.
- **Herramientas como en Samsung Notes:** pluma, lápiz, pincel y caligrafía; resaltador;
  goma de trazo o parcial; **lazo** (mover, cambiar tamaño, recolorear, duplicar,
  borrar); texto; cinta para tapar respuestas (un toque la destapa); puntero láser
  (también se ve en el PC); colores a medida.
- **Hojas:** rayas, cuadros, puntos, Cornell, lista de tareas, pentagrama o lisa;
  páginas continuas (tira hacia arriba al final para una nueva); miniaturas para
  ordenar, duplicar y borrar.
- **Fotos y PDF:** inserta fotos y muévelas con el lazo; importa un PDF y escribe
  encima; exporta el cuaderno a PDF.
- **Portada:** carpetas, favoritos y búsqueda (títulos y textos de las páginas).
- **Figuras perfectas:** al terminar un trazo, deja el lápiz quieto medio segundo y se
  convierte en línea, círculo, elipse, rectángulo o triángulo. Con el botón de figuras
  activado se convierte cualquier trazo que lo parezca.
- **En el PC:** lo que escribes aparece al momento en `http://localhost:8788`, y cada
  página se guarda en `~/SyncTablet/<Cuaderno>/Página NN.svg` para abrirla en
  cualquier programa (Obsidian, el navegador, un visor de imágenes).
- **Notas en el PC, estilo cuaderno de bloques:** barra lateral con tus notas y los
  cuadernos de la tablet; escribe `/` para elegir el bloque (títulos, listas, tareas,
  citas, llamadas, separadores) o atajos como `# `, `- `, `[] `. El bloque **Página de
  la tablet** mete una página escrita a mano dentro de la nota y se actualiza sola.
  Búsqueda con `Ctrl+K` (títulos y texto), modo oscuro, y cada nota se guarda también
  como `~/SyncTablet/Notas/<Título>.md`.
- **Las notas también en la tablet:** sección «Notas» en la portada y el mismo editor
  de bloques (con `/`, atajos y páginas a mano dentro). Funcionan sin conexión y se
  sincronizan solas con el PC al conectar; un cambio en un lado aparece en el otro.
- **Vista en vivo:** «En vivo desde la tablet» muestra la página en la que escribes y
  la sigue sola si cambias de página o de cuaderno.
- **Sin conexión:** la tablet guarda todo y, al volver a conectar, el PC se pone al
  día solo.
- **Hojas de cálculo (0.3):** con el motor de [GridCraft](https://github.com/storytold/gridcraft)
  en la tablet: todas sus funciones (en español o inglés: `=SUMA`, `=SI`, `=BUSCARV`…),
  formato, ordenar, filtros, varias hojas, gráficas (columnas, líneas, circular,
  dispersión, cascada…), `.xlsx`/`.csv`/`.ods` de entrada y salida, plantillas
  (presupuesto, notas, laboratorio, graficar funciones) y gráficas que se pasan a un
  cuaderno o a una presentación. Las tablas dentro de las hojas usan el mismo motor.
- **Presentaciones al estilo Canva:** diez estilos con portada, agenda, secciones y
  cierre; textos, fotos, figuras, stickers y dibujo a mano; presentar a pantalla
  completa (pasa diapositivas con el S Pen) y exportar a PowerPoint o PDF.
- **Documentos:** plantillas de informe, laboratorio, carta, ensayo, resumen y acta,
  que se exportan a Word.
- **Más:** lápices propios (trazo, grosor, color y opacidad con nombre), regla,
  escuadras, transportador y compás, 18 hojas (partida a la mitad, milimetrada,
  ingeniería, isométrica…), stickers, visor de modelos 3D (STL y OBJ), importar
  Xournal++ (`.xopp`), seleccionar varios para mover o borrar, pantalla completa,
  tutorial de cada cosa, copia automática en Google Drive y **escribir desde el PC**
  (casilla «Escribir desde el PC» en el visor; la tablet guarda el trazo).

## Cómo se usa

1. En el PC: `./synctablet` (deja la ventana abierta).
2. En la tablet: instala `synctablet.apk` y conecta el cable USB con la
   *depuración USB* activada (como en [Encaje](https://github.com/sthifen/encaje)).
   Por WiFi: en la app, botón **WiFi**, con la IP del PC y, si la tablet nunca se conectó por cable, el código que sale en el visor (**Conectar por WiFi**).
3. Escribe. Abre `http://localhost:8788` en el PC para verlo en vivo.

## Compilar

```bash
cargo build --release          # PC: target/release/synctablet
android/build-apk.sh --install # tablet: sin Gradle ni Android Studio
cargo test
tests/shapes.sh                # reconocedor de figuras, 60 rondas
```

## Cómo funciona

```
Tablet (Java, sin dependencias)                PC (Rust, axum)
InkView ── trazo/borrado ──► WebSocket ──USB (adb reverse) / WiFi──► /ws/ink ──► ~/SyncTablet (JSON + SVG)
                                                                          └──► /ws/live ──► visor web
```

- La tablet es la dueña de los datos: cada cambio sube la versión de la página. Al
  conectar manda la versión de cada página y el PC pide las que tiene atrasadas.
- `src/ink.rs` (modelo y operaciones), `src/store.rs` (disco), `src/svg.rs`
  (exportar), `src/notes.rs` (notas del PC y su `.md`), `src/server.rs`
  (HTTP/WebSocket), `src/adb.rs` (`adb reverse`).
- `web/index.html`: el visor, un solo archivo (HTML, CSS y JavaScript sin
  frameworks) incluido en el binario; la fuente Inter (`web/fonts/`) también.
- `android/src/dev/synctablet/`: `InkView` (dibujo de baja latencia), `Store`
  (guardado local), `SyncClient` (conexión), `MainActivity` (portada con barra lateral) + `Notes` + `BlockEditor`
  (notas de bloques; se sincronizan por HTTP con `/api/notes`, avisadas por `/ws/ink`).

## Próximas piezas

1. Enlaces entre notas; PDFs y fotos dentro de las notas.
2. Phone Link: notificaciones, fotos y archivos de la tablet en el PC.

## Licencia

Sync Tablet se distribuye bajo la licencia [MIT](LICENSE). Los componentes de terceros
y sus licencias están en [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
