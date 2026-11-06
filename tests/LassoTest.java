package dev.synctablet;
import java.util.*;
public class LassoTest {
    static int fails = 0;
    static void expect(String name, boolean[] got, boolean... want) {
        boolean ok = Arrays.equals(got, want);
        if (!ok) fails++;
        System.out.printf("%s %-34s %s%n", ok ? "✓" : "✗", name, Arrays.toString(got));
    }
    /** Una «letra»: garabato en la caja (x, y, w, h). */
    static float[] letter(float x, float y, float w, float h) {
        float[] o = new float[40];
        for (int i = 0; i < 20; i++) { double t = i / 19.0; o[2*i] = x + (float)(w*t); o[2*i+1] = y + h/2 + (float)(h/2*Math.sin(t*12)); }
        return o;
    }
    static float[] ring(float cx, float cy, float rx, float ry) {
        float[] o = new float[120];
        for (int i = 0; i < 60; i++) { double t = 2*Math.PI*i/59; o[2*i] = cx + rx*(float)Math.cos(t); o[2*i+1] = cy + ry*(float)Math.sin(t); }
        return o;
    }
    public static void main(String[] a) {
        // «hola» (4 letras juntas), «mundo» lejos, y otro renglón debajo.
        List<float[]> s = List.of(letter(100,100,30,40), letter(135,100,30,40), letter(170,100,30,40), letter(205,100,30,40),
                letter(400,100,40,40), letter(100,240,40,40));
        expect("toque agarra la palabra", LassoPicker.pick(new float[]{145,120,146,121}, s), true,true,true,true,false,false);
        expect("toque en la otra palabra", LassoPicker.pick(new float[]{420,120}, s), false,false,false,false,true,false);
        expect("toque en lo vacío", LassoPicker.pick(new float[]{700,700}, s), false,false,false,false,false,false);
        expect("contorno amplio", LassoPicker.pick(ring(170,120,110,60), s), true,true,true,true,false,false);
        // Contorno justo: corta la primera y la última letra.
        expect("contorno justo", LassoPicker.pick(ring(167,120,62,40), s), true,true,true,true,false,false);
        float[] open = {90,120,115,121,150,119,180,120,240,120};
        expect("línea por encima", LassoPicker.pick(open, s), true,true,true,true,false,false);
        // Contorno cerrado en un hueco, rozando una letra: agarra lo que toca.
        expect("contorno vacío que roza", LassoPicker.pick(ring(300,120,62,30), s), false,false,false,true,false,false);
        expect("sin trazos", LassoPicker.pick(ring(100,100,50,50), List.of()));
        // Figuras grandes junto a la palabra: ni se suman ni sirven de puente.
        float[] axis = {50,120,700,120};
        float[] tall = {240,0,240,500};
        List<float[]> f = List.of(letter(100,100,30,40), letter(135,100,30,40), axis, tall, letter(400,100,40,40));
        expect("palabra junto a figuras", LassoPicker.pick(new float[]{145,120,146,121}, List.of(letter(100,100,30,40), letter(135,100,30,40), tall, letter(260,100,40,40))), true,true,false,false);
        expect("tocar figura agarra sólo esa", LassoPicker.pick(new float[]{240,400}, f), false,false,false,true,false);
        System.out.println(fails == 0 ? "TODO BIEN" : fails + " FALLOS");
    }
}
