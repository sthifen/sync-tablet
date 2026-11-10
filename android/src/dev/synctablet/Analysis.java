package dev.synctablet;

import java.util.ArrayList;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Herramientas de estadística para la hoja de cálculo, como las de un curso de
 * Probabilidad y Estadística: tabla de frecuencias (Sturges), estadística
 * descriptiva, prueba F de Fisher, Kruskal–Wallis y distribuciones. Todo se
 * escribe con fórmulas (se ve el procedimiento) a la derecha de los datos.
 */
final class Analysis {
    private Analysis() {}

    /** Columna de datos elegida: sus celdas con número, como referencias A2… */
    static final class Column {
        String name;
        int col;
        final ArrayList<Integer> rows = new ArrayList<>();
        final ArrayList<Double> values = new ArrayList<>();
    }

    /** Lee las columnas elegidas: la primera fila con texto es el nombre; lo que no es número («—», vacío) no cuenta. */
    static ArrayList<Column> read(GridActivity g, int r0, int c0, int r1, int c1) {
        ArrayList<Column> out = new ArrayList<>();
        int rows = Math.min(r1 - r0 + 1, 5000), cols = Math.min(c1 - c0 + 1, 40);
        String json = Native.gridView(g.book(), r0, c0, rows, cols);
        if (json == null) return out;
        try {
            JSONArray cells = new JSONObject(json).getJSONArray("cells");
            for (int c = 0; c < cols; c++) {
                Column col = new Column();
                col.col = c0 + c;
                for (int r = 0; r < rows; r++) {
                    JSONArray line = cells.optJSONArray(r);
                    JSONObject cell = line == null ? null : line.optJSONObject(c);
                    String t = cell == null ? "" : cell.optString("t").trim();
                    if (t.isEmpty()) continue;
                    Double v = number(t);
                    if (v == null) {
                        if (col.name == null && col.values.isEmpty()) col.name = t;
                        continue;
                    }
                    col.rows.add(r0 + r);
                    col.values.add(v);
                }
                if (col.name == null) col.name = "Grupo " + GridActivity.col(col.col);
                if (!col.values.isEmpty()) out.add(col);
            }
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "leer datos", e);
        }
        return out;
    }

    private static Double number(String t) {
        String s = t.replace("$", "").replace("%", "").replace(" ", "");
        if (s.contains(",") && !s.contains(".")) s = s.replace(',', '.');
        else s = s.replace(",", "");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String a1(int r, int c) {
        return GridActivity.col(c) + (r + 1);
    }

    /** Las celdas con número de una columna, como lista para las fórmulas (A2,A3…) o rango si son seguidas. */
    static String refs(Column c) {
        int first = c.rows.get(0), last = c.rows.get(c.rows.size() - 1);
        if (last - first + 1 == c.rows.size()) return "$" + GridActivity.col(c.col) + "$" + (first + 1) + ":$" + GridActivity.col(c.col) + "$" + (last + 1);
        StringBuilder b = new StringBuilder();
        for (int r : c.rows) b.append(b.length() == 0 ? "" : ",").append(a1(r, c.col));
        return b.toString();
    }

    private static JSONArray row(Object... v) {
        JSONArray a = new JSONArray();
        for (Object o : v) a.put(o == null ? JSONObject.NULL : o);
        return a;
    }

    private static void put(GridActivity g, int r, int c, JSONArray rows) throws JSONException {
        g.exec("range.setValues", new JSONObject().put("range", a1(r, c)).put("values", rows));
    }

    private static void bold(GridActivity g, String range, String fill) throws JSONException {
        g.exec("home.bold", new JSONObject().put("range", range).put("on", true));
        if (fill != null) g.exec("home.fillColor", new JSONObject().put("range", range).put("color", fill));
    }

    // ---------------------------------------------------------------- tabla de frecuencias

    /** Tabla de frecuencias por intervalos (regla de Sturges) con histograma, polígono y ojiva. */
    static String frequency(GridActivity g, Column data, int r, int c) throws JSONException {
        int n = data.values.size();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        boolean integers = true;
        int decimals = 0;
        for (double v : data.values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            if (v != Math.rint(v)) integers = false;
            String s = Sheet.format(v);
            int dot = s.indexOf('.');
            if (dot >= 0) decimals = Math.max(decimals, s.length() - dot - 1);
        }
        int k = (int) Math.ceil(1 + 3.322 * Math.log10(n));
        double range = max - min;
        double unit = Math.pow(10, -decimals);
        double width = Math.max(unit, Math.ceil(range / k / unit) * unit);
        // Con datos enteros y pocos valores distintos, una clase por valor es más clara.
        if (integers && range + 1 <= k) {
            width = 1;
            k = (int) range + 1;
        }
        while (min + width * k <= max) k++;
        String rng = refs(data);
        JSONArray head = new JSONArray();
        head.put(row("Tabla de frecuencias: " + data.name, null));
        head.put(row("n (datos)", "=COUNT(" + rng + ")"));
        head.put(row("Mínimo", "=MIN(" + rng + ")"));
        head.put(row("Máximo", "=MAX(" + rng + ")"));
        head.put(row("Rango R = Máx − Mín", "=" + a1(r + 3, c + 1) + "-" + a1(r + 2, c + 1)));
        head.put(row("Clases k (Sturges: 1 + 3,322·log n)", "=ROUNDUP(1+3.322*LOG10(" + a1(r + 1, c + 1) + "),0)"));
        head.put(row("Amplitud A (usada)", width));
        head.put(row("Clases usadas", k));
        put(g, r, c, head);
        bold(g, a1(r, c), null);
        int t = r + 9;
        String A = "$" + GridActivity.col(c + 1) + "$" + (r + 7);
        JSONArray table = new JSONArray();
        table.put(row("Clase", "Límite inferior", "Límite superior", "Marca de clase xᵢ", "Frecuencia fᵢ", "Frec. acumulada Fᵢ",
                "Frec. relativa hᵢ", "Relativa acumulada Hᵢ", "Porcentaje %"));
        for (int i = 0; i < k; i++) {
            int rr = t + 1 + i;
            String li = i == 0 ? "=" + a1(r + 2, c + 1) : "=" + a1(rr - 1, c + 2);
            String ls = "=" + a1(rr, c + 1) + "+" + A;
            String mark = "=(" + a1(rr, c + 1) + "+" + a1(rr, c + 2) + ")/2";
            String upper = i == k - 1 ? "\"<=\"&" : "\"<\"&";
            String fi = "=COUNTIFS(" + rng + ",\">=\"&" + a1(rr, c + 1) + "," + rng + "," + upper + a1(rr, c + 2) + ")";
            if (!rng.startsWith("$")) {
                // Celdas sueltas: COUNTIFS no acepta listas; se suman comparaciones.
                fi = "=SUMPRODUCT((" + "CHOOSE(SEQUENCE(" + n + ")," + rng + ")>=" + a1(rr, c + 1) + ")*(CHOOSE(SEQUENCE(" + n + ")," + rng + ")" + (i == k - 1 ? "<=" : "<") + a1(rr, c + 2) + "))";
            }
            String Fi = i == 0 ? "=" + a1(rr, c + 4) : "=" + a1(rr - 1, c + 5) + "+" + a1(rr, c + 4);
            String hi = "=" + a1(rr, c + 4) + "/" + "$" + GridActivity.col(c + 1) + "$" + (r + 2);
            String Hi = i == 0 ? "=" + a1(rr, c + 6) : "=" + a1(rr - 1, c + 7) + "+" + a1(rr, c + 6);
            String pct = "=" + a1(rr, c + 6) + "*100";
            String label = "[" + "\"&ROUND(" + a1(rr, c + 1) + ",4)&\", \"&ROUND(" + a1(rr, c + 2) + ",4)&\"" + (i == k - 1 ? "]" : ")");
            table.put(row("=\"" + label + "\"", li, ls, mark, fi, Fi, hi, Hi, pct));
        }
        int end = t + k;
        table.put(row("Total", null, null, null, "=SUM(" + a1(t + 1, c + 4) + ":" + a1(end, c + 4) + ")", null,
                "=SUM(" + a1(t + 1, c + 6) + ":" + a1(end, c + 6) + ")", null, "=SUM(" + a1(t + 1, c + 8) + ":" + a1(end, c + 8) + ")"));
        put(g, t, c, table);
        bold(g, a1(t, c) + ":" + a1(t, c + 8), "#BBF7D0");
        bold(g, a1(end + 1, c) + ":" + a1(end + 1, c + 8), "#E5E7EB");
        g.exec("home.numberFormat", new JSONObject().put("range", a1(t + 1, c + 6) + ":" + a1(end + 1, c + 7)).put("code", "0.0000"));
        g.exec("home.numberFormat", new JSONObject().put("range", a1(t + 1, c + 8) + ":" + a1(end + 1, c + 8)).put("code", "0.00"));
        g.exec("home.columnWidth", new JSONObject().put("cols", GridActivity.col(c) + ":" + GridActivity.col(c)).put("width", 150));
        // Gráficos: histograma (fᵢ por clase), polígono (fᵢ por marca) y ojiva (Fᵢ por límite superior).
        String cls = a1(t, c) + ":" + a1(end, c), f = a1(t, c + 4) + ":" + a1(end, c + 4);
        chart(g, cls + "," + f, "column", "Histograma de " + data.name, a1(t, c + 10));
        chart(g, a1(t, c + 3) + ":" + a1(end, c + 4), "line", "Polígono de frecuencias", a1(t + 16, c + 10));
        chart(g, a1(t, c + 2) + ":" + a1(end, c + 2) + "," + a1(t, c + 5) + ":" + a1(end, c + 5), "line", "Ojiva (frecuencia acumulada)", a1(t + 32, c + 10));
        return a1(r, c);
    }

    private static void chart(GridActivity g, String range, String type, String title, String at) throws JSONException {
        JSONObject p = new JSONObject().put("range", range).put("type", type).put("title", title).put("at", at).put("width", 430).put("height", 260);
        if ("line".equals(type)) p.put("subtype", "markers");
        g.exec("insert.chart", p);
    }

    // ---------------------------------------------------------------- descriptiva

    static final String[][] DESCRIPTIVE = {
        {"n (cantidad de datos)", "COUNT(%s)"}, {"Media", "AVERAGE(%s)"}, {"Mediana", "MEDIAN(%s)"}, {"Moda", "IFERROR(MODE.SNGL(%s),\"No hay\")"},
        {"Mínimo", "MIN(%s)"}, {"Máximo", "MAX(%s)"}, {"Rango", "MAX(%s)-MIN(%s)"}, {"Varianza muestral s²", "VAR.S(%s)"},
        {"Desviación estándar muestral s", "STDEV.S(%s)"}, {"Varianza poblacional σ²", "VAR.P(%s)"}, {"Desviación estándar poblacional σ", "STDEV.P(%s)"},
        {"Coeficiente de variación CV (%)", "STDEV.S(%s)/AVERAGE(%s)*100"}, {"Cuartil 1 (Q1)", "QUARTILE.INC(%s,1)"}, {"Cuartil 2 (Q2)", "QUARTILE.INC(%s,2)"},
        {"Cuartil 3 (Q3)", "QUARTILE.INC(%s,3)"}, {"Rango intercuartílico (Q3 − Q1)", "QUARTILE.INC(%s,3)-QUARTILE.INC(%s,1)"},
        {"Asimetría", "IFERROR(SKEW(%s),\"—\")"}, {"Curtosis", "IFERROR(KURT(%s),\"—\")"}, {"Error estándar s/√n", "STDEV.S(%s)/SQRT(COUNT(%s))"},
        {"Suma", "SUM(%s)"},
    };

    static String descriptive(GridActivity g, ArrayList<Column> cols, int r, int c) throws JSONException {
        JSONArray rows = new JSONArray();
        JSONArray head = new JSONArray().put("Estadística descriptiva");
        for (Column col : cols) head.put(col.name);
        rows.put(head);
        for (String[] d : DESCRIPTIVE) {
            JSONArray line = new JSONArray().put(d[0]);
            for (Column col : cols) {
                String ref = refs(col);
                line.put("=" + d[1].replace("%s", ref));
            }
            rows.put(line);
        }
        put(g, r, c, rows);
        bold(g, a1(r, c) + ":" + a1(r, c + cols.size()), "#DBEAFE");
        g.exec("home.numberFormat", new JSONObject().put("range", a1(r + 2, c + 1) + ":" + a1(r + DESCRIPTIVE.length, c + cols.size())).put("code", "0.0000"));
        g.exec("home.columnWidth", new JSONObject().put("cols", GridActivity.col(c) + ":" + GridActivity.col(c)).put("width", 210));
        return a1(r, c);
    }

    // ---------------------------------------------------------------- F de Fisher

    /** ¿Son distintas las varianzas de dos poblaciones normales? Prueba de dos colas. */
    static String fTest(GridActivity g, Column a, Column b, int r, int c) throws JSONException {
        String ra = refs(a), rb = refs(b);
        String v = GridActivity.col(c + 1);
        JSONArray rows = new JSONArray();
        rows.put(row("Prueba F de Fisher (dos colas): " + a.name + " contra " + b.name, null));
        rows.put(row("H₀: σ₁² = σ₂²   H₁: σ₁² ≠ σ₂²", null));
        rows.put(row("Nivel de significancia α", 0.05));
        rows.put(row("n₁ (" + a.name + ")", "=COUNT(" + ra + ")"));
        rows.put(row("n₂ (" + b.name + ")", "=COUNT(" + rb + ")"));
        rows.put(row("Varianza muestral s₁²", "=VAR.S(" + ra + ")"));
        rows.put(row("Varianza muestral s₂²", "=VAR.S(" + rb + ")"));
        rows.put(row("Estadístico F = s₁² / s₂²", "=" + v + (r + 6) + "/" + v + (r + 7)));
        rows.put(row("Grados de libertad gl₁ = n₁ − 1", "=" + v + (r + 4) + "-1"));
        rows.put(row("Grados de libertad gl₂ = n₂ − 1", "=" + v + (r + 5) + "-1"));
        rows.put(row("F crítico inferior F(α/2; gl₁; gl₂)", "=F.INV(" + v + (r + 3) + "/2," + v + (r + 9) + "," + v + (r + 10) + ")"));
        rows.put(row("F crítico superior F(1−α/2; gl₁; gl₂)", "=F.INV.RT(" + v + (r + 3) + "/2," + v + (r + 9) + "," + v + (r + 10) + ")"));
        rows.put(row("Valor p (dos colas)", "=2*MIN(F.DIST.RT(" + v + (r + 8) + "," + v + (r + 9) + "," + v + (r + 10) + "),1-F.DIST.RT(" + v + (r + 8) + "," + v + (r + 9) + "," + v + (r + 10) + "))"));
        rows.put(row("Decisión", "=IF(OR(" + v + (r + 8) + "<" + v + (r + 11) + "," + v + (r + 8) + ">" + v + (r + 12) + "),\"Se rechaza H₀\",\"No se rechaza H₀\")"));
        rows.put(row("Conclusión", "=IF(OR(" + v + (r + 8) + "<" + v + (r + 11) + "," + v + (r + 8) + ">" + v + (r + 12) + "),\"Hay evidencia de que las varianzas son diferentes (α = \"&" + v + (r + 3) + "&\").\",\"No hay evidencia suficiente de que las varianzas sean diferentes (α = \"&" + v + (r + 3) + "&\").\")"));
        put(g, r, c, rows);
        bold(g, a1(r, c), null);
        bold(g, a1(r + 13, c) + ":" + a1(r + 13, c + 1), "#FDE68A");
        g.exec("home.numberFormat", new JSONObject().put("range", a1(r + 5, c + 1) + ":" + a1(r + 12, c + 1)).put("code", "0.0000"));
        g.exec("home.columnWidth", new JSONObject().put("cols", GridActivity.col(c) + ":" + GridActivity.col(c)).put("width", 240));
        return a1(r, c);
    }

    // ---------------------------------------------------------------- Kruskal–Wallis

    /** Prueba H de Kruskal–Wallis para k grupos (tamaños distintos), con su tabla de rangos. */
    static String kruskal(GridActivity g, ArrayList<Column> groups, int r, int c) throws JSONException {
        // Todos los datos en una columna, con su grupo y su rango en el total.
        int total = 0;
        for (Column col : groups) total += col.values.size();
        int t = r + 1, first = t + 1, last = t + total;
        String gcol = GridActivity.col(c), vcol = GridActivity.col(c + 1), rcol = GridActivity.col(c + 2);
        JSONArray stack = new JSONArray();
        stack.put(row("Grupo", "Dato", "Rango"));
        for (Column col : groups) {
            for (int i = 0; i < col.rows.size(); i++) {
                int rr = first + stack.length() - 1;
                stack.put(row(col.name, "=" + a1(col.rows.get(i), col.col),
                        "=RANK.AVG(" + vcol + (rr + 1) + ",$" + vcol + "$" + (first + 1) + ":$" + vcol + "$" + (last + 1) + ",1)"));
            }
        }
        put(g, r, c, new JSONArray().put(row("Prueba de Kruskal–Wallis: rangos de todos los datos juntos", null, null)));
        put(g, t, c, stack);
        bold(g, a1(r, c), null);
        bold(g, a1(t, c) + ":" + a1(t, c + 2), "#E9D5FF");
        String G = "$" + gcol + "$" + (first + 1) + ":$" + gcol + "$" + (last + 1), R = "$" + rcol + "$" + (first + 1) + ":$" + rcol + "$" + (last + 1);
        // Resumen por grupo y estadístico H.
        int s = r + 1, sc = c + 4;
        String sv = GridActivity.col(sc + 1);
        JSONArray sum = new JSONArray();
        sum.put(row("Grupo", "nᵢ", "Suma de rangos Rᵢ", "Rango medio", "Rᵢ²/nᵢ"));
        for (int i = 0; i < groups.size(); i++) {
            int rr = s + 1 + i;
            String name = a1(rr, sc);
            sum.put(row(groups.get(i).name, "=COUNTIF(" + G + "," + name + ")", "=SUMIF(" + G + "," + name + "," + R + ")",
                    "=" + a1(rr, sc + 2) + "/" + a1(rr, sc + 1), "=" + a1(rr, sc + 2) + "^2/" + a1(rr, sc + 1)));
        }
        int e = s + groups.size();
        put(g, s, sc, sum);
        bold(g, a1(s, sc) + ":" + a1(s, sc + 4), "#E9D5FF");
        int h = e + 2;
        String N = sv + (h + 3);
        JSONArray res = new JSONArray();
        res.put(row("H₀: las " + groups.size() + " poblaciones tienen la misma mediana", null));
        res.put(row("H₁: al menos una mediana es diferente", null));
        res.put(row("N (total de datos)", "=SUM(" + a1(s + 1, sc + 1) + ":" + a1(e, sc + 1) + ")"));
        res.put(row("Σ Rᵢ²/nᵢ", "=SUM(" + a1(s + 1, sc + 4) + ":" + a1(e, sc + 4) + ")"));
        res.put(row("H = 12/(N(N+1)) · Σ Rᵢ²/nᵢ − 3(N+1)", "=12/(" + N + "*(" + N + "+1))*" + sv + (h + 4) + "-3*(" + N + "+1)"));
        res.put(row("Grados de libertad gl = k − 1", groups.size() - 1));
        res.put(row("Nivel de significancia α", 0.05));
        res.put(row("Valor crítico χ²(α; gl)", "=CHISQ.INV.RT(" + sv + (h + 7) + "," + sv + (h + 6) + ")"));
        res.put(row("Valor p", "=CHISQ.DIST.RT(" + sv + (h + 5) + "," + sv + (h + 6) + ")"));
        res.put(row("Decisión", "=IF(" + sv + (h + 5) + ">" + sv + (h + 8) + ",\"Se rechaza H₀\",\"No se rechaza H₀\")"));
        res.put(row("Conclusión", "=IF(" + sv + (h + 5) + ">" + sv + (h + 8) + ",\"Hay diferencias significativas entre los grupos (α = \"&" + sv + (h + 7) + "&\").\",\"No hay evidencia suficiente de diferencias entre los grupos (α = \"&" + sv + (h + 7) + "&\").\")"));
        put(g, h, sc, res);
        bold(g, a1(h + 9, sc) + ":" + a1(h + 9, sc + 1), "#FDE68A");
        g.exec("home.numberFormat", new JSONObject().put("range", a1(h + 3, sc + 1) + ":" + a1(h + 8, sc + 1)).put("code", "0.0000"));
        g.exec("home.columnWidth", new JSONObject().put("cols", GridActivity.col(sc) + ":" + GridActivity.col(sc)).put("width", 240));
        return a1(r, c);
    }

    // ---------------------------------------------------------------- distribuciones

    static final String[] DISTRIBUTIONS = {"Binomial", "Poisson", "Normal", "Exponencial", "Uniforme discreta", "Hipergeométrica"};

    /** Calculadora con sus parámetros (se pueden cambiar) y una tabla con gráfico. */
    static String distribution(GridActivity g, String which, int r, int c) throws JSONException {
        String v = GridActivity.col(c + 1);
        JSONArray rows = new JSONArray();
        int tableRows;
        String pmf;
        String chartType = "column";
        switch (which) {
            case "Binomial":
                rows.put(row("Distribución binomial", null));
                rows.put(row("n (ensayos)", 10));
                rows.put(row("p (probabilidad de éxito)", 0.4));
                rows.put(row("x", 3));
                rows.put(row("P(X = x)", "=BINOM.DIST(" + v + (r + 4) + "," + v + (r + 2) + "," + v + (r + 3) + ",FALSE)"));
                rows.put(row("P(X ≤ x)", "=BINOM.DIST(" + v + (r + 4) + "," + v + (r + 2) + "," + v + (r + 3) + ",TRUE)"));
                rows.put(row("P(X ≥ x)", "=1-BINOM.DIST(" + v + (r + 4) + "-1," + v + (r + 2) + "," + v + (r + 3) + ",TRUE)"));
                rows.put(row("Media μ = n·p", "=" + v + (r + 2) + "*" + v + (r + 3)));
                rows.put(row("Varianza σ² = n·p·(1−p)", "=" + v + (r + 2) + "*" + v + (r + 3) + "*(1-" + v + (r + 3) + ")"));
                rows.put(row("Desviación σ", "=SQRT(" + v + (r + 9) + ")"));
                tableRows = 11;
                pmf = "=BINOM.DIST(%x," + "$" + v + "$" + (r + 2) + ",$" + v + "$" + (r + 3) + ",FALSE)";
                break;
            case "Poisson":
                rows.put(row("Distribución de Poisson", null));
                rows.put(row("λ (promedio por intervalo)", 3));
                rows.put(row("x", 2));
                rows.put(row("P(X = x)", "=POISSON.DIST(" + v + (r + 3) + "," + v + (r + 2) + ",FALSE)"));
                rows.put(row("P(X ≤ x)", "=POISSON.DIST(" + v + (r + 3) + "," + v + (r + 2) + ",TRUE)"));
                rows.put(row("P(X ≥ x)", "=1-POISSON.DIST(" + v + (r + 3) + "-1," + v + (r + 2) + ",TRUE)"));
                rows.put(row("Media μ = λ", "=" + v + (r + 2)));
                rows.put(row("Varianza σ² = λ", "=" + v + (r + 2)));
                rows.put(row("Desviación σ", "=SQRT(" + v + (r + 2) + ")"));
                tableRows = 13;
                pmf = "=POISSON.DIST(%x,$" + v + "$" + (r + 2) + ",FALSE)";
                break;
            case "Normal":
                rows.put(row("Distribución normal", null));
                rows.put(row("Media μ", 102));
                rows.put(row("Desviación estándar σ", 4));
                rows.put(row("a (límite inferior)", 96));
                rows.put(row("b (límite superior)", 110));
                rows.put(row("z de a = (a − μ)/σ", "=STANDARDIZE(" + v + (r + 4) + "," + v + (r + 2) + "," + v + (r + 3) + ")"));
                rows.put(row("z de b = (b − μ)/σ", "=STANDARDIZE(" + v + (r + 5) + "," + v + (r + 2) + "," + v + (r + 3) + ")"));
                rows.put(row("P(X < a)", "=NORM.DIST(" + v + (r + 4) + "," + v + (r + 2) + "," + v + (r + 3) + ",TRUE)"));
                rows.put(row("P(X > b)", "=1-NORM.DIST(" + v + (r + 5) + "," + v + (r + 2) + "," + v + (r + 3) + ",TRUE)"));
                rows.put(row("P(a ≤ X ≤ b)", "=NORM.DIST(" + v + (r + 5) + "," + v + (r + 2) + "," + v + (r + 3) + ",TRUE)-" + v + (r + 8)));
                rows.put(row("Porcentaje entre a y b (%)", "=ROUND(" + v + (r + 10) + "*100,2)"));
                tableRows = 41;
                chartType = "line";
                pmf = "=NORM.DIST(%x,$" + v + "$" + (r + 2) + ",$" + v + "$" + (r + 3) + ",FALSE)";
                break;
            case "Exponencial":
                rows.put(row("Distribución exponencial", null));
                rows.put(row("λ (tasa = 1/media)", 0.5));
                rows.put(row("x", 2));
                rows.put(row("P(X ≤ x) = 1 − e^(−λx)", "=EXPON.DIST(" + v + (r + 3) + "," + v + (r + 2) + ",TRUE)"));
                rows.put(row("P(X > x) = e^(−λx)", "=1-" + v + (r + 4)));
                rows.put(row("Media μ = 1/λ", "=1/" + v + (r + 2)));
                rows.put(row("Desviación σ = 1/λ", "=1/" + v + (r + 2)));
                tableRows = 21;
                chartType = "line";
                pmf = "=EXPON.DIST(%x,$" + v + "$" + (r + 2) + ",FALSE)";
                break;
            case "Hipergeométrica":
                rows.put(row("Distribución hipergeométrica", null));
                rows.put(row("N (población)", 20));
                rows.put(row("K (éxitos en la población)", 6));
                rows.put(row("n (muestra)", 5));
                rows.put(row("x", 2));
                rows.put(row("P(X = x)", "=HYPGEOM.DIST(" + v + (r + 5) + "," + v + (r + 4) + "," + v + (r + 3) + "," + v + (r + 2) + ",FALSE)"));
                rows.put(row("P(X ≤ x)", "=HYPGEOM.DIST(" + v + (r + 5) + "," + v + (r + 4) + "," + v + (r + 3) + "," + v + (r + 2) + ",TRUE)"));
                rows.put(row("Media μ = n·K/N", "=" + v + (r + 4) + "*" + v + (r + 3) + "/" + v + (r + 2)));
                tableRows = 6;
                pmf = "=HYPGEOM.DIST(%x,$" + v + "$" + (r + 4) + ",$" + v + "$" + (r + 3) + ",$" + v + "$" + (r + 2) + ",FALSE)";
                break;
            default: // Uniforme discreta
                rows.put(row("Distribución uniforme discreta", null));
                rows.put(row("a (menor valor)", 1));
                rows.put(row("b (mayor valor)", 5));
                rows.put(row("Cantidad de valores k = b − a + 1", "=" + v + (r + 3) + "-" + v + (r + 2) + "+1"));
                rows.put(row("P(X = x) = 1/k", "=1/" + v + (r + 4)));
                rows.put(row("Media μ = (a + b)/2", "=(" + v + (r + 2) + "+" + v + (r + 3) + ")/2"));
                rows.put(row("Varianza σ² = (k² − 1)/12", "=(" + v + (r + 4) + "^2-1)/12"));
                rows.put(row("Desviación σ", "=SQRT(" + v + (r + 7) + ")"));
                tableRows = 5;
                pmf = "=IF(AND(%x>=$" + v + "$" + (r + 2) + ",%x<=$" + v + "$" + (r + 3) + "),1/$" + v + "$" + (r + 4) + ",0)";
                break;
        }
        put(g, r, c, rows);
        bold(g, a1(r, c), null);
        g.exec("home.numberFormat", new JSONObject().put("range", a1(r + 1, c + 1) + ":" + a1(r + rows.length(), c + 1)).put("code", "0.0000"));
        g.exec("home.columnWidth", new JSONObject().put("cols", GridActivity.col(c) + ":" + GridActivity.col(c)).put("width", 220));
        // Tabla de valores y gráfico.
        int t = r + rows.length() + 2;
        JSONArray table = new JSONArray().put(row("x", "Normal".equals(which) || "Exponencial".equals(which) ? "f(x)" : "P(X = x)"));
        for (int i = 0; i < tableRows; i++) {
            String x;
            if ("Normal".equals(which)) x = "=$" + v + "$" + (r + 2) + "-4*$" + v + "$" + (r + 3) + "+" + i + "*$" + v + "$" + (r + 3) + "/5";
            else if ("Exponencial".equals(which)) x = "=" + i + "*0.25/$" + v + "$" + (r + 2);
            else if ("Uniforme discreta".equals(which)) x = "=$" + v + "$" + (r + 2) + "+" + i;
            else x = String.valueOf(i);
            int rr = t + 1 + i;
            table.put(row(x, pmf.replace("%x", a1(rr, c))));
        }
        put(g, t, c, table);
        bold(g, a1(t, c) + ":" + a1(t, c + 1), "#DBEAFE");
        g.exec("home.numberFormat", new JSONObject().put("range", a1(t + 1, c) + ":" + a1(t + tableRows, c + 1)).put("code", "0.0000"));
        JSONObject p = new JSONObject().put("range", a1(t, c) + ":" + a1(t + tableRows, c + 1)).put("type", "line".equals(chartType) ? "scatter" : "column")
                .put("title", "Distribución " + which.toLowerCase(Locale.ROOT)).put("at", a1(r, c + 3)).put("width", 440).put("height", 270);
        if ("line".equals(chartType)) p.put("subtype", "lines");
        g.exec("insert.chart", p);
        return a1(r, c);
    }
}
