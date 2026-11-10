package dev.synctablet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Plantillas para empezar: hojas de cálculo y documentos (notas que se exportan a Word). */
final class Templates {
    private Templates() {}

    static final String[] SHEETS = {"blank", "budget", "grades", "lab", "schedule", "tasks"};
    static final String[] SHEET_NAMES = {"En blanco", "Presupuesto", "Calificaciones", "Laboratorio", "Horario", "Tareas"};
    static final String[] SHEET_ICONS = {"▦", "💰", "🎓", "🧪", "🗓️", "✅"};

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
