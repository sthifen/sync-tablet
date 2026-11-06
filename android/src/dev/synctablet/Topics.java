package dev.synctablet;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconoce de qué materia es un texto, sin internet: un diccionario de
 * términos por materia y, sobre todo, lo que el usuario ya ordenó en sus
 * carpetas. Nada sale de la tablet. Java puro para probarlo en el PC.
 */
final class Topics {
    /** Lo que se sabe de un documento. `subject` es null si no hay pruebas suficientes. */
    static final class Guess {
        final String subject;
        final int confidence;
        final boolean known;

        Guess(String subject, int confidence, boolean known) {
            this.subject = subject;
            this.confidence = confidence;
            this.known = known;
        }
    }

    private static final String[][] SUBJECTS = {
            {"Electromagnetismo", "electromagnetica electromagnetico electromagnetismo maxwell faraday gauss ampere onda plana polarizacion permitividad permeabilidad campo electrico magnetico poynting linea transmision reflexion dielectrico coaxial impedancia guia smith"},
            {"Circuitos", "circuito circuitos resistencia capacitor inductor thevenin norton kirchhoff nodal malla fasor impedancia voltaje corriente rlc transitorio potencia"},
            {"Electrónica", "transistor mosfet bjt diodo amplificador operacional opamp polarizacion semiconductor rectificador ganancia emisor colector compuerta drenador"},
            {"Sistemas digitales", "digital flip flop compuerta logica booleana karnaugh fpga verilog vhdl contador multiplexor registro combinacional secuencial microcontrolador"},
            {"Señales y sistemas", "senal senales fourier laplace convolucion transformada muestreo nyquist espectro filtro discreta sistema lti"},
            {"Control", "control lazo realimentacion pid estabilidad nyquist bode routh planta controlador funcion transferencia"},
            {"Máquinas eléctricas", "transformador motor generador sincrono induccion devanado estator rotor par deslizamiento"},
            {"Sistemas de potencia", "potencia generacion transmision distribucion subestacion fotovoltaico inversor flujo cortocircuito trifasico red electrica"},
            {"Comunicaciones", "modulacion antena radio demodulacion portadora ruido canal codificacion ofdm"},
            {"Programación", "programacion algoritmo funcion clase objeto variable python java rust compilador puntero bucle recursion"},
            {"Estructuras de datos", "estructura datos arbol grafo pila cola lista hash ordenamiento complejidad"},
            {"Bases de datos", "base datos sql tabla consulta relacional normalizacion transaccion"},
            {"Redes", "red protocolo tcp udp router enrutamiento ethernet subred dns firewall"},
            {"Cálculo", "calculo derivada integral limite serie convergencia diferencial gradiente multivariable taylor"},
            {"Álgebra lineal", "matriz vector espacio vectorial determinante autovalor eigenvalor lineal base rango"},
            {"Ecuaciones diferenciales", "ecuacion diferencial edo homogenea wronskiano laplace frobenius"},
            {"Física", "fisica cinematica dinamica newton fuerza energia momento inercia oscilador optica"},
            {"Termodinámica", "termodinamica entropia entalpia calor temperatura ciclo carnot presion gas"},
            {"Mecánica", "estatica esfuerzo deformacion viga torsion cortante momento flector armadura"},
            {"Química", "quimica reaccion molecula enlace acido base mol estequiometria oxidacion equilibrio"},
            {"Probabilidad y estadística", "probabilidad estadistica varianza distribucion muestra hipotesis regresion normal media"},
            {"Economía", "economia oferta demanda mercado costo inversion interes flujo caja presupuesto"},
            {"Inglés", "english grammar vocabulary reading writing listening speaking present past tense"},
            {"Biología", "biologia celula adn proteina organismo genetica ecosistema"},
            {"Historia", "historia siglo revolucion guerra colonia independencia sociedad"},
    };

    private static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "para como este esta estos estas pero porque cuando donde entre sobre desde hasta durante mediante segun cada todo toda todos todas "
                    + "tiene tienen puede pueden debe deben hacer hace sera sido siendo tambien solo aunque mientras ademas luego entonces "
                    + "the and for with that this from are was were have has not your you their which when will can "
                    + "que los las del una uno unos unas por con sin mas muy ese esa eso sus ser son hay fue han nos "
                    + "pagina figura tabla ejemplo capitulo seccion tema clase guia notas apuntes parte primero segundo tercero ejercicio solucion").split(" ")));

    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    private Topics() {
    }

    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Palabras con significado, en minúscula y sin tildes. */
    static List<String> tokens(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : plain(text).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w) && !w.matches("\\d+")) out.add(w);
        }
        return out;
    }

    private static Map<String, Integer> counts(List<String> words) {
        HashMap<String, Integer> m = new HashMap<>();
        for (String w : words) m.merge(w, 1, Integer::sum);
        return m;
    }

    /** Las palabras que mejor resumen un texto, las más repetidas primero. */
    static List<String> keywords(String text, int max) {
        ArrayList<Map.Entry<String, Integer>> list = new ArrayList<>(counts(tokens(text)).entrySet());
        list.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : list) {
            if (out.size() == max) break;
            out.add(e.getKey());
        }
        return out;
    }

    private static double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            na += e.getValue() * (double) e.getValue();
            Integer o = b.get(e.getKey());
            if (o != null) dot += e.getValue() * (double) o;
        }
        for (int v : b.values()) nb += v * (double) v;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /**
     * Materia de un documento. `learned`: carpeta → el texto de lo que ya hay
     * dentro. Lo aprendido pesa más que el diccionario, porque es del usuario.
     * El título cuenta triple.
     */
    static Guess classify(String title, String text, Map<String, String> learned) {
        ArrayList<String> words = new ArrayList<>(tokens(text));
        for (int i = 0; i < 3; i++) words.addAll(tokens(title));
        if (words.size() < 3) return new Guess(null, 0, false);
        Map<String, Integer> doc = counts(words);

        String bestFolder = null;
        double folderScore = 0;
        if (learned != null) {
            for (Map.Entry<String, String> e : learned.entrySet()) {
                double c = cosine(doc, counts(tokens(e.getValue())));
                if (c > folderScore) {
                    folderScore = c;
                    bestFolder = e.getKey();
                }
            }
        }

        String best = null;
        int bestHits = 0, second = 0, bestDistinct = 0;
        for (String[] s : SUBJECTS) {
            int hits = 0, distinct = 0;
            for (String term : s[1].split(" ")) {
                Integer n = doc.get(term);
                if (n != null) {
                    hits += Math.min(n, 6);
                    distinct++;
                }
            }
            if (hits > bestHits) {
                second = bestHits;
                bestHits = hits;
                best = s[0];
                bestDistinct = distinct;
            } else if (hits > second) {
                second = hits;
            }
        }
        if (bestFolder != null && folderScore >= 0.3) {
            return new Guess(bestFolder, (int) Math.min(97, 45 + folderScore * 70), true);
        }
        // Una palabra suelta no basta: «potencia» sale en varias materias.
        if (best == null || bestDistinct < 3) return new Guess(null, 0, false);
        int confidence = (int) Math.min(95, 100.0 * bestHits / (bestHits + second + 1));
        String own = sameSubject(best, learned);
        return new Guess(own != null ? own : best, confidence, own != null);
    }

    /** Si ya hay una carpeta que se llama como la materia, se usa esa. */
    private static String sameSubject(String subject, Map<String, String> learned) {
        if (learned == null) return null;
        String want = plain(subject);
        for (String folder : learned.keySet()) {
            String have = plain(folder);
            if (have.contains(want) || want.contains(have)) return folder;
        }
        return null;
    }

    /** «Noviembre 2026»: la fecha que traiga el texto si es creíble, si no la de edición. */
    static String month(String text, long millis) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(millis);
        int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH);
        if (text != null) {
            Matcher m = Pattern.compile("\\b(" + String.join("|", MONTHS) + ")\\s+(?:de(?:l)?\\s+)?(20\\d{2})\\b").matcher(plain(text));
            if (m.find()) {
                int y = Integer.parseInt(m.group(2));
                if (Math.abs(y - year) <= 2) {
                    year = y;
                    month = Arrays.asList(MONTHS).indexOf(m.group(1));
                }
            } else {
                m = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})\\b").matcher(text);
                if (m.find()) {
                    int mm = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3));
                    if (mm >= 1 && mm <= 12 && Math.abs(y - year) <= 2) {
                        year = y;
                        month = mm - 1;
                    }
                }
            }
        }
        return Character.toUpperCase(MONTHS[month].charAt(0)) + MONTHS[month].substring(1) + " " + year;
    }

    /** Sigla del curso («EL2114») o periodo («II semestre 2026»); "" si el texto no lo dice. */
    static String course(String text) {
        if (text == null) return "";
        Matcher code = Pattern.compile("(?i)\\b(?:curso|asignatura|materia|c[oó]digo)\\s*[:.\\-]?\\s*([A-Za-z]{2,5}[- ]?\\d{3,5})\\b").matcher(text);
        if (code.find()) return code.group(1).toUpperCase(java.util.Locale.ROOT).replaceAll("[- ]", "");
        code = Pattern.compile("\\b([A-Z]{2,4})-?(\\d{4})\\b").matcher(text);
        while (code.find()) {
            // «ISO-9001» o «IEEE-1547» no son cursos.
            if (!code.group(1).matches("ISO|IEEE|ANSI|IEC|RFC")) return code.group(1) + code.group(2);
        }
        Matcher term = Pattern.compile("(?i)\\b(I{1,3}|[123])\\s*(?:er|do|ro)?\\s*(semestre|cuatrimestre|trimestre|ciclo)\\s*(?:de(?:l)?\\s*)?(20\\d{2})?").matcher(text);
        if (term.find()) {
            String n = term.group(1).toUpperCase(java.util.Locale.ROOT).replace("1", "I").replace("2", "II").replace("3", "III");
            return n + " " + term.group(2).toLowerCase(java.util.Locale.ROOT) + (term.group(3) == null ? "" : " " + term.group(3));
        }
        return "";
    }
}
