package dev.synctablet;

import java.util.HashMap;

public class TopicsTest {
    static int fails;
    static void expect(String n, boolean ok, String got) { if (!ok) { fails++; System.out.println("✗ " + n + " → " + got); } }
    static void subject(String want, String title, String text) {
        Topics.Guess g = Topics.classify(title, text, null);
        expect(want, want.equals(g.subject), g.subject + " " + g.confidence + "%");
    }
    public static void main(String[] a) {
        subject("Electromagnetismo", "Documento de Word", "TEORÍA ELECTROMAGNÉTICA II Guía Teórica Completa Onda Plana · Reflexión y Transmisión · Líneas de Transmisión · ecuaciones de Maxwell y vector de Poynting");
        subject("Circuitos", "Parcial 1", "Aplique Thevenin y Norton al circuito. Use análisis nodal y de malla, Kirchhoff, calcule la corriente en la resistencia y el voltaje en el capacitor");
        subject("Electrónica", "Lab 3", "Polarización del transistor BJT en emisor común. El MOSFET y el amplificador operacional. Ganancia del diodo rectificador");
        subject("Cálculo", "Tarea", "Calcule la derivada y la integral. Límite de la serie de Taylor y su convergencia, gradiente");
        subject("Programación", "Laboratorio", "Escriba un algoritmo en Python con una función recursiva. Clase, objeto y variable; compilador de Rust");
        subject("Química", "Quiz", "Balancee la reacción, calcule el mol por estequiometría y la oxidación del ácido y equilibrio de la molécula");
        subject("Probabilidad y estadística", "Taller", "Distribución normal, varianza, media de la muestra y prueba de hipótesis con regresión");
        expect("sin pruebas no inventa", Topics.classify("Lista", "comprar pan y leche mañana temprano para la casa", null).subject == null, "");
        expect("sin texto no inventa", Topics.classify("Cuaderno 6", "", null).subject == null, "");
        expect("una palabra suelta no basta", Topics.classify("Hoja", "potencia potencia potencia del motor", null).subject == null, Topics.classify("Hoja", "potencia potencia potencia del motor", null).subject + "");
        HashMap<String, String> mine = new HashMap<>();
        mine.put("Teoría EM 2", "onda plana maxwell reflexión transmisión coaxial poynting permitividad dieléctrico");
        Topics.Guess g = Topics.classify("Clase 8", "Onda plana en un dieléctrico, reflexión y Poynting", mine);
        expect("aprende de las carpetas", "Teoría EM 2".equals(g.subject) && g.known, g.subject + "");
        HashMap<String, String> same = new HashMap<>();
        same.put("Circuitos II", "xx yy zz");
        g = Topics.classify("Parcial", "thevenin norton kirchhoff nodal malla fasor", same);
        expect("usa la carpeta con ese nombre", "Circuitos II".equals(g.subject), g.subject + "");
        long nov = 1793000000000L;
        expect("mes del texto", "Marzo 2026".equals(Topics.month("Fecha: 12 de marzo de 2026", nov)), Topics.month("Fecha: 12 de marzo de 2026", nov));
        expect("mes con barras", "Agosto 2026".equals(Topics.month("Entregar 03/08/2026", nov)), Topics.month("Entregar 03/08/2026", nov));
        expect("fecha vieja no cuenta", !Topics.month("Copyright enero 2015", nov).contains("2015"), Topics.month("Copyright enero 2015", nov));
        expect("sigla", "EL2114".equals(Topics.course("Curso: EL-2114 Circuitos")), Topics.course("Curso: EL-2114 Circuitos"));
        expect("sigla suelta", "IE0305".equals(Topics.course("Programa IE-0305 Laboratorio")), Topics.course("Programa IE-0305 Laboratorio"));
        expect("norma no es curso", "".equals(Topics.course("según IEEE-1547 y ISO 9001")), Topics.course("según IEEE-1547 y ISO 9001"));
        expect("semestre", "II semestre 2026".equals(Topics.course("II Semestre 2026")), Topics.course("II Semestre 2026"));
        expect("palabras clave", Topics.keywords("onda onda onda plana plana maxwell y de la", 2).get(0).equals("onda"), "");
        System.out.println(fails == 0 ? "TODO BIEN" : fails + " FALLOS");
    }
}
