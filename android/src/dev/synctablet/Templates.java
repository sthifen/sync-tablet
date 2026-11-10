package dev.synctablet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Plantillas para empezar: hojas de cálculo y documentos (notas que se exportan a Word). */
final class Templates {
    private Templates() {}

    static final String[] SHEETS = {"blank", "loan", "depreciation", "money", "npv", "function", "budget", "grades", "lab", "schedule", "tasks"};
    static final String[] SHEET_NAMES = {"En blanco", "Préstamo y amortización", "Depreciación", "Valor del dinero", "VPN y TIR", "Graficar funciones",
            "Presupuesto", "Calificaciones", "Laboratorio", "Horario", "Tareas"};
    static final String[] SHEET_ICONS = {"▦", "🚗", "🏭", "⏳", "📊", "📈", "💰", "🎓", "🧪", "🗓️", "✅"};

    private static JSONArray rows(Object[][] data) {
        JSONArray a = new JSONArray();
        for (Object[] r : data) {
            JSONArray line = new JSONArray();
            for (Object v : r) line.put(v == null ? JSONObject.NULL : v);
            a.put(line);
        }
        return a;
    }

    private static void put(GridActivity g, String at, Object[][] data) throws JSONException {
        g.exec("range.setValues", new JSONObject().put("range", at).put("values", rows(data)));
    }

    private static void bold(GridActivity g, String range, String fill) throws JSONException {
        g.exec("home.bold", new JSONObject().put("range", range).put("on", true));
        if (fill != null) g.exec("home.fillColor", new JSONObject().put("range", range).put("color", fill));
    }

    private static void chart(GridActivity g, String range, String type, String title, String at) throws JSONException {
        g.exec("insert.chart", new JSONObject().put("range", range).put("type", type).put("title", title).put("at", at).put("width", 420).put("height", 260));
    }

    /** Llena la hoja recién creada con la plantilla. */
    static void sheet(GridActivity g, String name) {
        try {
            switch (name) {
                case "loan": {
                    // Financiamiento (caso del vehículo o del electrodoméstico): cuota y cuadro de amortización.
                    put(g, "A1", new Object[][] {
                        {"Financiamiento", null},
                        {"Precio del artículo", 15000000},
                        {"Prima (%)", 0.2},
                        {"Monto de la prima", "=B2*B3"},
                        {"Gastos de formalización", 350000},
                        {"Monto financiado", "=B2-B4+B5"},
                        {"Tasa anual nominal", 0.12},
                        {"Plazo (meses)", 60},
                        {"Tasa mensual", "=B7/12"},
                        {"Cuota mensual", "=PMT(B9,B8,-B6)"},
                        {"Tasa efectiva anual", "=EFFECT(B7,12)"},
                        {"Total pagado en cuotas", "=B10*B8"},
                        {"Total de intereses", "=B12-B6"},
                        {"Costo total (prima + cuotas)", "=B4+B12"}});
                    bold(g, "A1", null);
                    bold(g, "A10:B10", "#FDE68A");
                    g.exec("home.numberFormat", new JSONObject().put("range", "B2").put("format", "Currency"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B4:B6").put("format", "Currency"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B10").put("format", "Currency"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B12:B14").put("format", "Currency"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B3").put("format", "Percentage"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B7").put("format", "Percentage"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B9").put("code", "0.0000%"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B11").put("code", "0.00%"));
                    Object[][] t = new Object[61][];
                    t[0] = new Object[] {"Mes", "Saldo inicial", "Cuota", "Interés", "Amortización", "Saldo final"};
                    for (int m = 1; m <= 60; m++) {
                        int r = m + 1;
                        t[m] = new Object[] {m, m == 1 ? "=B6" : "=I" + (r - 1), "=IF(D" + r + "<=$B$8,$B$10,0)", "=E" + r + "*$B$9", "=F" + r + "-G" + r, "=E" + r + "-H" + r};
                    }
                    // Mes en D, saldo inicial E, cuota F, interés G, amortización H, saldo final I.
                    put(g, "D1", t);
                    bold(g, "D1:I1", "#DBEAFE");
                    g.exec("home.numberFormat", new JSONObject().put("range", "E2:I61").put("code", "#,##0.00"));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "A:A").put("width", 190));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "B:B").put("width", 120));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "E:I").put("width", 105));
                    g.exec("insert.chart", new JSONObject().put("range", "G1:H61").put("type", "column").put("subtype", "stacked")
                            .put("title", "Interés y amortización por mes").put("at", "K2").put("width", 460).put("height", 280));
                    break;
                }
                case "depreciation": {
                    put(g, "A1", new Object[][] {
                        {"Depreciación del activo", null},
                        {"Costo del activo (B)", 100000},
                        {"Valor de salvamento (VS)", 10000},
                        {"Vida útil n (años)", 5},
                        {"Tasa de impuestos", 0.3}});
                    bold(g, "A1", null);
                    Object[][] t = new Object[7][];
                    t[0] = new Object[] {"Año", "LR: depreciación", "LR: valor en libros", "SDD: depreciación", "SDD: valor en libros",
                            "SDA: depreciación", "SDA: valor en libros", "Ahorro fiscal (LR)"};
                    for (int y = 1; y <= 5; y++) {
                        int r = y + 7;
                        // «Año n» como texto: así sirve de categoría para el gráfico.
                        t[y] = new Object[] {"Año " + y, "=SLN($B$2,$B$3,$B$4)", y == 1 ? "=$B$2-B" + r : "=C" + (r - 1) + "-B" + r,
                                "=DDB($B$2,$B$3,$B$4," + y + ")", y == 1 ? "=$B$2-D" + r : "=E" + (r - 1) + "-D" + r,
                                "=SYD($B$2,$B$3,$B$4," + y + ")", y == 1 ? "=$B$2-F" + r : "=G" + (r - 1) + "-F" + r, "=B" + r + "*$B$5"};
                    }
                    t[6] = new Object[] {"Total", "=SUM(B8:B12)", null, "=SUM(D8:D12)", null, "=SUM(F8:F12)", null, "=SUM(H8:H12)"};
                    put(g, "A7", t);
                    bold(g, "A7:H7", "#FED7AA");
                    bold(g, "A13:H13", "#E5E7EB");
                    put(g, "A15", new Object[][] {{"LR = línea recta (SLN), SDD = saldo doble decreciente (DDB), SDA = suma de dígitos de los años (SYD)."}});
                    g.exec("home.numberFormat", new JSONObject().put("range", "B8:H13").put("code", "#,##0.00"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B5").put("format", "Percentage"));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "A:A").put("width", 170));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "B:H").put("width", 120));
                    g.exec("insert.chart", new JSONObject().put("range", "A7:A12,C7:C12,E7:E12,G7:G12").put("type", "line").put("subtype", "markers")
                            .put("title", "Valor en libros por método").put("at", "J2").put("width", 460).put("height", 280));
                    break;
                }
                case "money":
                    put(g, "A1", new Object[][] {
                        {"Valor del dinero en el tiempo", null, null},
                        {"Valor presente VP", 1000, null},
                        {"Tasa de interés por periodo i", 0.08, null},
                        {"Número de periodos n", 10, null},
                        {"Pago periódico (anualidad)", 0, null},
                        {null, null, null},
                        {"Resultado", "Valor", "Fórmula de Excel"},
                        {"Valor futuro VF = VP(1+i)^n", "=FV(B3,B4,-B5,-B2)", "VF(tasa; nper; pago; va)"},
                        {"Valor presente de VF", "=PV(B3,B4,-B5,-B8)", "VA(tasa; nper; pago; vf)"},
                        {"Interés compuesto ganado", "=B8-B2", null},
                        {"Interés simple (VP·i·n)", "=B2*B3*B4", null},
                        {"Tasa que lleva VP a VF", "=RATE(B4,-B5,-B2,B8)", "TASA(nper; pago; va; vf)"},
                        {"Periodos para duplicar", "=NPER(B3,0,-1,2)", "NPER(tasa; pago; va; vf)"},
                        {"Tasa efectiva anual (si i es mensual)", "=(1+B3)^12-1", null}});
                    bold(g, "A1", null);
                    bold(g, "A7:C7", "#DBEAFE");
                    g.exec("home.numberFormat", new JSONObject().put("range", "B3").put("format", "Percentage"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B8:B11").put("format", "Currency"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B12").put("code", "0.00%"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B14").put("code", "0.00%"));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "A:A").put("width", 240));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "C:C").put("width", 180));
                    break;
                case "npv": {
                    put(g, "A1", new Object[][] {
                        {"Evaluación de proyecto: VPN y TIR", null},
                        {"TMAR (tasa mínima aceptable)", 0.12},
                        {null, null},
                        {"Año", "Flujo neto de efectivo"},
                        {0, -500000}, {1, 150000}, {2, 160000}, {3, 170000}, {4, 180000}, {5, 200000},
                        {null, null},
                        {"VPN", "=NPV(B2,B6:B10)+B5"},
                        {"TIR", "=IRR(B5:B10)"},
                        {"Decisión", "=IF(B12>0,\"Se acepta: VPN > 0 y TIR > TMAR\",\"Se rechaza: VPN ≤ 0\")"},
                        {"Periodo de recuperación (años)", "=MATCH(TRUE,INDEX(C5:C10>=0,0),0)-1"}});
                    put(g, "C4", new Object[][] {{"Flujo acumulado"}, {"=B5"}, {"=C5+B6"}, {"=C6+B7"}, {"=C7+B8"}, {"=C8+B9"}, {"=C9+B10"}});
                    put(g, "D4", new Object[][] {{"Valor presente del flujo"}, {"=B5/(1+$B$2)^A5"}, {"=B6/(1+$B$2)^A6"}, {"=B7/(1+$B$2)^A7"}, {"=B8/(1+$B$2)^A8"},
                            {"=B9/(1+$B$2)^A9"}, {"=B10/(1+$B$2)^A10"}});
                    bold(g, "A1", null);
                    bold(g, "A4:D4", "#BBF7D0");
                    bold(g, "A12:B14", "#FDE68A");
                    g.exec("home.numberFormat", new JSONObject().put("range", "B2").put("format", "Percentage"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B13").put("code", "0.00%"));
                    g.exec("home.numberFormat", new JSONObject().put("range", "B5:D12").put("code", "#,##0.00"));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "A:A").put("width", 200));
                    g.exec("home.columnWidth", new JSONObject().put("cols", "B:D").put("width", 140));
                    g.exec("insert.chart", new JSONObject().put("range", "A4:B10").put("type", "column").put("title", "Flujos de efectivo")
                            .put("at", "F2").put("width", 420).put("height", 260));
                    break;
                }
                case "function": {
                    // x de −2π a 2π; cambia las fórmulas de B y C (y «Rellenar ↓») para graficar otra cosa.
                    int n = 41;
                    Object[][] rows = new Object[n + 1][];
                    rows[0] = new Object[] {"x", "f(x) = sen(x)", "g(x) = x²/10"};
                    for (int i = 1; i <= n; i++) {
                        int r = i + 1;
                        rows[i] = new Object[] {"=ROUND(-2*PI()+(" + (i - 1) + ")*PI()/10,4)", "=SIN(A" + r + ")", "=A" + r + "^2/10"};
                    }
                    put(g, "A1", rows);
                    bold(g, "A1:C1", "#DBEAFE");
                    g.exec("home.numberFormat", new JSONObject().put("range", "A2:C42").put("code", "0.00"));
                    g.exec("insert.chart", new JSONObject().put("range", "A1:C42").put("type", "scatter").put("subtype", "lines")
                            .put("title", "Funciones").put("at", "E2").put("width", 520).put("height", 340));
                    put(g, "E20", new Object[][] {{"Cambia la fórmula de B2 o C2 (por ejemplo =COS(A2) o =EXP(A2/3)),"},
                            {"elige la columna desde la fila 2 hasta la 42 y toca «Rellenar ↓»."}});
                    break;
                }
                case "budget":
                    put(g, "A1", new Object[][] {
                        {"Presupuesto del mes", null, null, null},
                        {"Concepto", "Presupuesto", "Gastado", "Diferencia"},
                        {"Comida", 120000, 98000, "=B3-C3"},
                        {"Transporte", 40000, 45500, "=B4-C4"},
                        {"Universidad", 60000, 52000, "=B5-C5"},
                        {"Internet y teléfono", 25000, 25000, "=B6-C6"},
                        {"Ocio", 30000, 18000, "=B7-C7"},
                        {"Ahorro", 50000, 50000, "=B8-C8"},
                        {"Total", "=SUM(B3:B8)", "=SUM(C3:C8)", "=SUM(D3:D8)"}});
                    bold(g, "A1", null);
                    bold(g, "A2:D2", "#BFDBFE");
                    bold(g, "A9:D9", "#E5E7EB");
                    g.exec("home.numberFormat", new JSONObject().put("range", "B3:D9").put("format", "Currency"));
                    chart(g, "A2:C8", "column", "Presupuesto y gasto", "F2");
                    break;
                case "grades":
                    put(g, "A1", new Object[][] {
                        {"Calificaciones", null, null, null, null, null},
                        {"Estudiante", "Parcial 1 (30 %)", "Parcial 2 (30 %)", "Proyecto (40 %)", "Nota final", "Estado"},
                        {"Ana", 85, 78, 92, "=ROUND(B3*0.3+C3*0.3+D3*0.4,1)", "=IF(E3>=70,\"Aprobado\",\"Reprobado\")"},
                        {"Luis", 62, 70, 75, "=ROUND(B4*0.3+C4*0.3+D4*0.4,1)", "=IF(E4>=70,\"Aprobado\",\"Reprobado\")"},
                        {"María", 95, 88, 90, "=ROUND(B5*0.3+C5*0.3+D5*0.4,1)", "=IF(E5>=70,\"Aprobado\",\"Reprobado\")"},
                        {"José", 55, 60, 68, "=ROUND(B6*0.3+C6*0.3+D6*0.4,1)", "=IF(E6>=70,\"Aprobado\",\"Reprobado\")"},
                        {"Promedio", "=AVERAGE(B3:B6)", "=AVERAGE(C3:C6)", "=AVERAGE(D3:D6)", "=AVERAGE(E3:E6)", "=COUNTIF(F3:F6,\"Aprobado\")&\" aprobados\""}});
                    bold(g, "A1", null);
                    bold(g, "A2:F2", "#BBF7D0");
                    bold(g, "A7:F7", "#E5E7EB");
                    g.exec("home.numberFormat", new JSONObject().put("range", "B7:E7").put("code", "0.0"));
                    chart(g, "A2:A6,E2:E6", "bar", "Nota final", "H2");
                    break;
                case "lab":
                    put(g, "A1", new Object[][] {
                        {"Práctica de laboratorio: ley de Ohm", null, null, null},
                        {"Tensión V (V)", "Corriente I (mA)", "Resistencia V/I (Ω)", "Potencia P (mW)"},
                        {1, 0.98, "=A3/(B3/1000)", "=A3*B3"},
                        {2, 2.01, "=A4/(B4/1000)", "=A4*B4"},
                        {3, 3.05, "=A5/(B5/1000)", "=A5*B5"},
                        {4, 3.96, "=A6/(B6/1000)", "=A6*B6"},
                        {5, 5.02, "=A7/(B7/1000)", "=A7*B7"},
                        {"Pendiente (kΩ⁻¹)", "=SLOPE(B3:B7,A3:A7)", "Media R", "=AVERAGE(C3:C7)"},
                        {"Correlación", "=CORREL(A3:A7,B3:B7)", "Desv. R", "=STDEV.S(C3:C7)"}});
                    bold(g, "A1", null);
                    bold(g, "A2:D2", "#FDE68A");
                    g.exec("home.numberFormat", new JSONObject().put("range", "C3:D9").put("code", "0.00"));
                    chart(g, "A2:B7", "scatter", "Corriente contra tensión", "F2");
                    break;
                case "schedule":
                    put(g, "A1", new Object[][] {
                        {"Hora", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes"},
                        {"7:30", "Cálculo", null, "Cálculo", null, "Laboratorio"},
                        {"9:30", "Física", "Programación", "Física", "Programación", "Laboratorio"},
                        {"11:30", null, "Química", null, "Química", null},
                        {"13:00", "Almuerzo", "Almuerzo", "Almuerzo", "Almuerzo", "Almuerzo"},
                        {"14:00", "Circuitos", "Inglés", "Circuitos", "Inglés", "Estudio"},
                        {"16:00", "Estudio", null, "Estudio", null, null}});
                    bold(g, "A1:F1", "#E9D5FF");
                    bold(g, "A2:A7", "#F3F4F6");
                    break;
                case "tasks":
                    put(g, "A1", new Object[][] {
                        {"Tarea", "Materia", "Entrega", "Estado", "Días que faltan"},
                        {"Informe de laboratorio", "Física", "=TODAY()+3", "Pendiente", "=C2-TODAY()"},
                        {"Lista de ejercicios 4", "Cálculo", "=TODAY()+5", "En curso", "=C3-TODAY()"},
                        {"Proyecto final", "Programación", "=TODAY()+20", "Pendiente", "=C4-TODAY()"},
                        {"Resumen capítulo 2", "Química", "=TODAY()-1", "Hecho", "=C5-TODAY()"},
                        {null, null, null, "Pendientes", "=COUNTIF(D2:D5,\"Pendiente\")"}});
                    bold(g, "A1:E1", "#FBCFE8");
                    g.exec("home.numberFormat", new JSONObject().put("range", "C2:C5").put("format", "Short Date"));
                    break;
                default:
                    break;
            }
            g.exec("selection.set", new JSONObject().put("range", "A1"));
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "plantilla de hoja", e);
        }
    }

    // ---- Documentos ----

    static final String[] DOCS = {"blank", "report", "lab", "letter", "essay", "summary", "minutes"};
    static final String[] DOC_NAMES = {"En blanco", "Informe", "Informe de laboratorio", "Carta formal", "Ensayo", "Resumen de estudio", "Acta de reunión"};
    static final String[] DOC_ICONS = {"📄", "📘", "🧪", "✉️", "🖋️", "🧠", "🗒️"};

    /** {título, icono, bloques como tipo|texto}. */
    static Object[] doc(String name) {
        switch (name) {
            case "report":
                return new Object[] {"Informe", "📘", new String[] {
                    "h1|Título del informe", "text|Autor · Curso · Fecha",
                    "h2|Resumen", "text|En pocas líneas: qué se hizo, cómo y qué se encontró.",
                    "h2|1. Introducción", "text|Contexto del tema y objetivo del informe.",
                    "h2|2. Desarrollo", "text|Explica el trabajo por partes.", "bullet|Primer punto", "bullet|Segundo punto",
                    "h2|3. Resultados", "text|Datos, tablas o figuras importantes.",
                    "h2|4. Conclusiones", "number|Primera conclusión", "number|Segunda conclusión",
                    "h2|Referencias", "text|[1] Autor, «Título», editorial, año."}};
            case "lab":
                return new Object[] {"Informe de laboratorio", "🧪", new String[] {
                    "h1|Práctica N.º 1: título", "text|Integrantes · Grupo · Fecha",
                    "h2|Objetivos", "bullet|Objetivo general", "bullet|Objetivo específico",
                    "h2|Marco teórico", "text|Las ecuaciones y conceptos que se usan.",
                    "h2|Materiales y equipo", "bullet|Fuente de tensión", "bullet|Multímetro", "bullet|Resistencias",
                    "h2|Procedimiento", "number|Montar el circuito.", "number|Medir y anotar.", "number|Repetir para cada valor.",
                    "h2|Datos y resultados", "callout|Pega aquí la tabla o una página de la tablet con las mediciones.",
                    "h2|Análisis", "text|Compara lo medido con lo teórico y explica el error.",
                    "h2|Conclusiones", "text|Lo aprendido.",
                    "h2|Bibliografía", "text|[1] …"}};
            case "letter":
                return new Object[] {"Carta formal", "✉️", new String[] {
                    "text|Ciudad, fecha", "text|Señor(a)\nNombre\nCargo\nInstitución",
                    "text|Asunto: motivo de la carta", "text|Estimado(a) señor(a):",
                    "text|Párrafo de presentación: quién escribe y para qué.",
                    "text|Párrafo con el detalle de la solicitud o la información.",
                    "text|Agradezco de antemano su atención.", "text|Atentamente,", "text|Nombre\nCarné o identificación\nCorreo"}};
            case "essay":
                return new Object[] {"Ensayo", "🖋️", new String[] {
                    "h1|Título del ensayo", "text|Autor · Curso",
                    "quote|Una cita o pregunta que abra el tema.",
                    "h2|Introducción", "text|Presenta el tema y tu tesis.",
                    "h2|Argumento 1", "text|Idea, evidencia y explicación.",
                    "h2|Argumento 2", "text|Idea, evidencia y explicación.",
                    "h2|Contraargumento", "text|Lo que otros dirían y por qué tu tesis se sostiene.",
                    "h2|Conclusión", "text|Retoma la tesis y cierra."}};
            case "summary":
                return new Object[] {"Resumen de estudio", "🧠", new String[] {
                    "h1|Tema", "callout|Idea principal en una frase.",
                    "h2|Conceptos clave", "bullet|Concepto: definición", "bullet|Concepto: definición",
                    "h2|Fórmulas", "text|Escribe o pega aquí las fórmulas.",
                    "h2|Ejemplo resuelto", "callout|Agrega una página de la tablet con «/» › Página de la tablet.",
                    "h2|Preguntas para repasar", "todo|¿…?", "todo|¿…?"}};
            case "minutes":
                return new Object[] {"Acta de reunión", "🗒️", new String[] {
                    "h1|Reunión del equipo", "text|Fecha · Lugar · Hora",
                    "h2|Asistentes", "bullet|Nombre", "bullet|Nombre",
                    "h2|Temas", "number|Tema 1", "number|Tema 2",
                    "h2|Acuerdos", "bullet|Acuerdo",
                    "h2|Pendientes", "todo|Tarea — responsable — fecha"}};
            default:
                return new Object[] {"", "📄", new String[] {"text|"}};
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /** La plantilla como HTML con formato, para el editor de documentos. */
    static String docHtml(String name) {
        StringBuilder b = new StringBuilder();
        boolean inList = false;
        int n = 0;
        for (String raw : (String[]) doc(name)[2]) {
            String[] p = raw.split("\\|", 2);
            String type = p[0], text = esc(p.length > 1 ? p[1] : "");
            boolean list = "bullet".equals(type);
            if (inList && !list) b.append("</ul>");
            if (list && !inList) b.append("<ul>");
            inList = list;
            if (!"number".equals(type)) n = 0;
            switch (type) {
                case "h1": b.append("<p><span style=\"font-size:1.80em;\"><b>").append(text).append("</b></span></p>"); break;
                case "h2": b.append("<p><span style=\"font-size:1.35em;\"><b>").append(text).append("</b></span></p>"); break;
                case "h3": b.append("<p><span style=\"font-size:1.15em;\"><b>").append(text).append("</b></span></p>"); break;
                case "bullet": b.append("<li>").append(text).append("</li>"); break;
                case "number": b.append("<p>").append(++n).append(". ").append(text).append("</p>"); break;
                case "todo": b.append("<p>☐ ").append(text).append("</p>"); break;
                case "quote": b.append("<p><i>“").append(text).append("”</i></p>"); break;
                case "callout": b.append("<p><font color=\"#0F766E\">").append(text).append("</font></p>"); break;
                case "divider": b.append("<p>―――――――――――――</p>"); break;
                default: b.append("<p>").append(text).append("</p>");
            }
        }
        if (inList) b.append("</ul>");
        return b.toString();
    }

    /** El JSON {title, blocks} que entiende Notes.createFrom. */
    static String docJson(String name) {
        Object[] d = doc(name);
        try {
            JSONArray blocks = new JSONArray();
            for (String b : (String[]) d[2]) {
                String[] p = b.split("\\|", 2);
                blocks.put(new JSONObject().put("type", p[0]).put("text", p.length > 1 ? p[1] : ""));
            }
            return new JSONObject().put("title", d[0]).put("icon", d[1]).put("blocks", blocks).toString();
        } catch (JSONException e) {
            return "{\"title\":\"\",\"blocks\":[]}";
        }
    }
}
