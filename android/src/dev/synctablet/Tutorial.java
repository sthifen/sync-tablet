package dev.synctablet;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Un tutorial corto de cada cosa, en el mismo dock de cristal: pasos con un
 * dibujo, «Anterior» y «Siguiente». Se abre desde Inicio, la hoja y las hojas de
 * cálculo; la primera vez que se usa algo se ofrece solo.
 */
final class Tutorial {
    private Tutorial() {}

    /** Temas: clave, icono, título y pasos (título del paso · explicación). */
    private static final Object[][] TOPICS = {
        {"start", IconView.HOME, "Primeros pasos", new String[] {
            "Inicio|Aquí están tus notas recientes, tus cuadernos y carpetas. Toca un cuaderno para escribir a mano; una nota para escribir con teclado.",
            "Crear|«Nuevo cuaderno» crea uno con hojas para el lápiz. «Nueva carpeta» agrupa cuadernos por materia.",
            "Importar|El botón «Importar PDF, Word o Excel» convierte un PDF o PowerPoint en cuaderno, un Word en nota y un Excel en hoja de cálculo.",
            "Con el PC|Conecta la tablet por USB y el PC ve tus apuntes al instante. Por WiFi: menú ⋯ › Conectar por WiFi, con el código de 6 dígitos."}},
        {"pen", IconView.PEN, "Lápiz y goma", new String[] {
            "Lápiz|Toca el lápiz para escribir. Tócalo otra vez para elegir pluma, lápiz, pincel, caligrafía o un lápiz tuyo.",
            "Crea tu lápiz|En «Tipo de lápiz» › «Crear lápiz» eliges el trazo, el grosor, el color y la presión. Se guarda con su nombre y lo usas como cualquier otro.",
            "Colores|A la izquierda están los colores y grosores. «+» abre un color a medida.",
            "Goma|Borra trazos enteros, sólo lo que toca, sólo el resaltador o lo que rodees. El botón del lápiz también puede borrar."}},
        {"lasso", IconView.LASSO, "Lazo y selección", new String[] {
            "Rodear|Con el lazo, rodea lo que quieras mover: trazos, textos, imágenes y figuras.",
            "Mover y estirar|Arrastra por dentro para mover. Los lados estiran, las esquinas agrandan y el punto de arriba gira.",
            "Imágenes con el dedo|Con el lápiz activo, toca una imagen con el dedo para elegirla sin cambiar de herramienta."}},
        {"shapes", IconView.SHAPES, "Figuras y circuitos", new String[] {
            "Figuras|En Figuras dibuja a mano: una línea, un círculo o un rectángulo se vuelven perfectos. Toca otra vez para ver la galería.",
            "Circuitos|En el dock de figuras, «Circuito» reconoce resistencias, fuentes, condensadores, transistores y compuertas dibujadas de un trazo.",
            "Valores|Toca un componente para escribirle su valor, como 10 kΩ o 5 V."}},
        {"paper", IconView.PAGE, "Hojas y plantillas", new String[] {
            "Plantilla|En el título de la hoja › Plantilla eliges rayas, cuadros, puntos, Cornell, partida a la mitad, milimetrada, isométrica, ingeniería y más.",
            "Hoja oscura|«Hoja oscura» se ve oscura en la tablet; al exportar sale blanca y con tinta negra para imprimir.",
            "Páginas|«Ordenar páginas» mueve, duplica o borra páginas. Tira hacia abajo al final para añadir otra."}},
        {"stickers", IconView.STICKER, "Stickers", new String[] {
            "Poner un sticker|En la hoja, Insertar › Stickers. Toca uno y aparece en la página.",
            "Moverlo|Es una imagen más: con el lazo o con el dedo lo mueves, lo agrandas o lo giras."}},
        {"sheet", IconView.SHEET, "Hojas de cálculo", new String[] {
            "Celdas|Toca una celda para elegirla y escribe arriba, en la barra de fórmulas. ✓ o Intro guarda y baja a la siguiente.",
            "Varias celdas|Mantén el dedo en una celda y arrastra para elegir un rango. Toca una letra o un número para elegir la columna o la fila.",
            "Fórmulas|Empieza con =. Funcionan en español o inglés: =SUMA(A1:A5), =PROMEDIO(B2:B9), =SI(C2>60;\"Aprobado\";\"Reprobado\"). «fx» busca entre todas las funciones.",
            "Mientras escribes|Con una fórmula a medias, toca una celda para meterla en la fórmula.",
            "Gráficas|Elige los datos (una columna de nombres y otra de números) y toca el icono de gráfica: columnas, líneas, circular, dispersión y más. Mantén una gráfica para cambiarla o borrarla.",
            "Formato|La cinta tiene negrita, colores, alinear, formato de número, ordenar, filtros, insertar filas y más. Desliza la cinta para verla entera.",
            "Excel|Se guarda sola. ⋯ › Guardar como Excel exporta un .xlsx que abre Excel, LibreOffice o Google Sheets."}},
        {"doc", IconView.DOC, "Documentos Word", new String[] {
            "Nuevo documento|En Inicio, Crear › Documento (Word) y eliges plantilla: informe, carta, laboratorio, ensayo…",
            "Escribir|Escribe por bloques: «/» abre los tipos (títulos, listas, tablas, citas, código).",
            "Exportar|Menú ⋯ › Exportar a Word guarda un .docx."}},
        {"slides", IconView.SLIDES, "Presentaciones", new String[] {
            "Crear|En Inicio, Crear › Presentación y elige un estilo. Ya trae portada, agenda, secciones y cierre: toca un texto para cambiarlo.",
            "Editar|Arriba: texto, imagen, figuras, stickers y lápiz para dibujar. Toca algo para moverlo; la bolita de la esquina lo agranda. Doble toque edita un texto. «+ Diapositiva» trae diseños listos.",
            "Exportar|Exportar › PowerPoint guarda un .pptx con tus diapositivas."}},
        {"model", IconView.CUBE, "Modelos 3D", new String[] {
            "Abrir|En Inicio, Importar › Modelo 3D abre archivos STL u OBJ.",
            "Mirar|Arrastra con un dedo para girar, pellizca para acercar y arrastra con dos dedos para moverlo. Doble toque lo centra."}},
        {"drive", IconView.CLOUD, "Copia en Google Drive", new String[] {
            "Elegir dónde|Personalizar › Copia en Drive. Android te deja elegir Google Drive y el nombre del archivo.",
            "Automática|Desde entonces, la tablet actualiza esa copia sola al salir de un cuaderno (y como mucho cada 10 minutos).",
            "Recuperar|En otra tablet, Copia en Drive › Recuperar una copia, y eliges el archivo. Se agregan los cuadernos que falten, sin borrar nada."}},
        {"custom", IconView.WAND, "Personalizar", new String[] {
            "Fotos|Pon tus fotos de fondo, de portada de Inicio y detrás de las hojas, con difuminado.",
            "Tema|Modo claro u oscuro, sin perder la ventana en la que estabas.",
            "Pantalla completa|Esconde la barra de estado y la de apps de abajo. Desliza desde el borde para verlas un momento."}},
    };

    /** La lista de temas. */
    static void menu(Activity a, OptionDock dock) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (Object[] topic : TOPICS) {
            String key = (String) topic[0];
            tiles.add(new OptionDock.Tile(dock.glyph((Integer) topic[1]), (String) topic[2], false, () -> show(a, dock, key)).stays());
        }
        dock.tiles("Tutorial", tiles);
    }

    static void show(Activity a, OptionDock dock, String key) {
        for (Object[] topic : TOPICS) if (topic[0].equals(key)) step(a, dock, topic, 0);
    }

    /** La primera vez que se usa algo: se ofrece su tutorial una sola vez. */
    static void offerOnce(Activity a, OptionDock dock, String key) {
        android.content.SharedPreferences p = a.getSharedPreferences("ui", Activity.MODE_PRIVATE);
        if (p.getBoolean("tutorial:" + key, false)) return;
        p.edit().putBoolean("tutorial:" + key, true).apply();
        // Después de que la pantalla tenga tamaño: el dock se mide con ella.
        a.getWindow().getDecorView().postDelayed(() -> {
            if (!a.isFinishing() && !dock.isOpen()) show(a, dock, key);
        }, 700);
    }

    private static void step(Activity a, OptionDock dock, Object[] topic, int i) {
        String[] steps = (String[]) topic[3];
        String[] parts = steps[i].split("\\|", 2);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 8));
        box.setMinimumWidth(Ui.dp(a, 480));
        View art = dock.glyph((Integer) topic[1]);
        art.setScaleX(2.4f);
        art.setScaleY(2.4f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(a, 90), Ui.dp(a, 90));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(art, alp);
        TextView head = dock.message(parts[0]);
        head.setTextSize(19);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setGravity(Gravity.CENTER);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView body = dock.message(parts.length > 1 ? parts[1] : "");
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        // Puntos de avance.
        LinearLayout dots = new LinearLayout(a);
        dots.setGravity(Gravity.CENTER);
        for (int k = 0; k < steps.length; k++) {
            View d = new View(a);
            d.setBackground(Ui.round(k == i ? 0xFF0F766E : 0x40808080, Ui.dp(a, 4)));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, k == i ? 18 : 8), Ui.dp(a, 8));
            dlp.setMargins(Ui.dp(a, 3), Ui.dp(a, 6), Ui.dp(a, 3), 0);
            dots.addView(d, dlp);
        }
        box.addView(dots);
        String title = topic[2] + " · " + (i + 1) + " de " + steps.length;
        OptionDock.Action back = i == 0 ? new OptionDock.Action("Temas", false, () -> menu(a, dock)).stays()
                : new OptionDock.Action("Anterior", false, () -> step(a, dock, topic, i - 1)).stays();
        OptionDock.Action next = i == steps.length - 1 ? new OptionDock.Action("Entendido", true, null)
                : new OptionDock.Action("Siguiente", true, () -> step(a, dock, topic, i + 1)).stays();
        dock.show(title, box, back, next);
    }
}
