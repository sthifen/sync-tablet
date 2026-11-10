package dev.synctablet;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fórmulas de las hojas de cálculo (Java puro, se prueba en el PC):
 * números, texto y «=» con + − × ÷, paréntesis, celdas (A1) y funciones
 * sobre rangos (A1:B3): SUMA/SUM, PROMEDIO/AVERAGE, MIN, MAX, CONTAR/COUNT.
 * Errores: #ERROR, #CICLO (una celda que se usa a sí misma), #DIV/0.
 */
final class Sheet {
    private final String[] cells;
    private final int rows, cols;
    private final String[] values;
    private final Set<Integer> visiting = new HashSet<>();

    private Sheet(String[] cells, int rows, int cols) {
        this.cells = cells;
        this.rows = rows;
        this.cols = cols;
        this.values = new String[rows * cols];
    }

    /** Lo que se ve en cada celda, por filas. */
    static String[] evaluate(String[] cells, int rows, int cols) {
        // Con la librería nativa, todas las funciones de GridCraft (SI, BUSCARV, FECHA…).
        Engine e = engine;
        if (e != null) {
            try {
                String[] out = e.eval(cells, rows, cols);
                if (out != null && out.length == rows * cols) return out;
            } catch (UnsatisfiedLinkError err) {
                engine = null; // una librería vieja sin hojas de cálculo
            }
        }
        return evaluateJava(cells, rows, cols);
    }

    /** Quién calcula de verdad: GridCraft si está la librería (lo pone Native). */
    interface Engine {
        String[] eval(String[] cells, int rows, int cols);
    }

    static volatile Engine engine;

    static String[] evaluateJava(String[] cells, int rows, int cols) {
        Sheet s = new Sheet(cells, rows, cols);
        for (int i = 0; i < rows * cols; i++) s.value(i);
        return s.values;
    }

    private String raw(int i) {
        return i < cells.length && cells[i] != null ? cells[i].trim() : "";
    }

    private String value(int i) {
        if (values[i] != null) return values[i];
        String r = raw(i);
        if (!r.startsWith("=")) return values[i] = r;
        if (!visiting.add(i)) return "#CICLO";
        String v;
        try {
            Parser p = new Parser(r.substring(1));
            double d = p.expr();
            p.end();
            v = Double.isInfinite(d) || Double.isNaN(d) ? "#DIV/0" : format(d);
        } catch (Cycle c) {
            v = "#CICLO";
        } catch (RuntimeException e) {
            v = "#ERROR";
        }
        visiting.remove(i);
        return values[i] = v;
    }

    static String format(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = String.format(Locale.ROOT, "%.6f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static final class Cycle extends RuntimeException {
        Cycle() { super(null, null, false, false); }
    }

    /** Número de una celda (vacía = 0); el texto no es número. */
    private double number(int i) {
        String v = value(i);
        if (v.equals("#CICLO")) throw new Cycle();
        if (v.isEmpty()) return 0;
        return Double.parseDouble(v.replace(',', '.'));
    }

    private final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s.replace(" ", "").toUpperCase(Locale.ROOT);
        }

        void end() {
            if (pos != s.length()) throw new IllegalArgumentException();
        }

        private boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        double expr() {
            double v = term();
            while (true) {
                if (eat('+')) v += term();
                else if (eat('-')) v -= term();
                else return v;
            }
        }

        double term() {
            double v = factor();
            while (true) {
                if (eat('*')) v *= factor();
                else if (eat('/')) v /= factor();
                else return v;
            }
        }

        double factor() {
            if (eat('-')) return -factor();
            if (eat('(')) {
                double v = expr();
                if (!eat(')')) throw new IllegalArgumentException();
                return v;
            }
            int start = pos;
            if (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
                String name = s.substring(start, pos);
                if (eat('(')) return function(name);
                pos = start;
                return number(cell());
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == ',')) pos++;
            if (start == pos) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(start, pos).replace(',', '.'));
        }

        /** A1 → índice. */
        int cell() {
            int start = pos;
            int col = 0;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) col = col * 26 + (s.charAt(pos++) - 'A' + 1);
            int numStart = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (col == 0 || numStart == pos) {
                pos = start;
                throw new IllegalArgumentException();
            }
            int row = Integer.parseInt(s.substring(numStart, pos));
            if (row < 1 || row > rows || col > cols) throw new IllegalArgumentException();
            return (row - 1) * cols + (col - 1);
        }

        double function(String name) {
            double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int count = 0;
            do {
                int a = cell();
                int b = eat(':') ? cell() : a;
                int r0 = Math.min(a / cols, b / cols), r1 = Math.max(a / cols, b / cols);
                int c0 = Math.min(a % cols, b % cols), c1 = Math.max(a % cols, b % cols);
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        String v = value(r * cols + c);
                        if (v.equals("#CICLO")) throw new Cycle();
                        double d;
                        try {
                            d = Double.parseDouble(v.replace(',', '.'));
                        } catch (NumberFormatException e) {
                            continue; // texto o vacía: no cuenta
                        }
                        sum += d;
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                        count++;
                    }
                }
            } while (eat(';') || eat(','));
            if (!eat(')')) throw new IllegalArgumentException();
            switch (name) {
                case "SUM": case "SUMA": return sum;
                case "AVERAGE": case "PROMEDIO": return sum / count;
                case "MIN": return count == 0 ? 0 : min;
                case "MAX": return count == 0 ? 0 : max;
                case "COUNT": case "CONTAR": return count;
                default: throw new IllegalArgumentException();
            }
        }
    }

    private Sheet() {
        this(new String[0], 0, 0);
    }
}
