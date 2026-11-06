package dev.synctablet;
import java.util.*;
public class SelectionTest {
    static int fails = 0;
    static void expect(String name, boolean ok, String got) { if (!ok) fails++; System.out.printf("%s %-36s %s%n", ok ? "✓" : "✗", name, got); }
    static boolean same(float[] a, float... b) { for (int i = 0; i < 4; i++) if (Math.abs(a[i] - b[i]) > 0.01f) return false; return true; }
    static String s(float[] a) { return Arrays.toString(a); }
    public static void main(String[] x) {
        float[] box = {100, 100, 300, 200}; // 200 × 100
        float[] r;
        r = SelectionMath.resize(SelectionMath.RIGHT, box, 50, 30); expect("derecha sólo ensancha", same(r, 100,100,350,200), s(r));
        r = SelectionMath.resize(SelectionMath.LEFT, box, -40, 9); expect("izquierda ancla la derecha", same(r, 60,100,300,200), s(r));
        r = SelectionMath.resize(SelectionMath.BOTTOM, box, 7, 50); expect("abajo sólo alarga", same(r, 100,100,300,250), s(r));
        r = SelectionMath.resize(SelectionMath.TOP, box, 0, -30); expect("arriba ancla abajo", same(r, 100,70,300,200), s(r));
        r = SelectionMath.resize(SelectionMath.MOVE, box, 10, -20); expect("mover", same(r, 110,80,310,180), s(r));
        r = SelectionMath.resize(SelectionMath.BOTTOM_RIGHT, box, 100, 0);
        expect("esquina guarda proporción", Math.abs((r[2]-r[0])/(r[3]-r[1]) - 2) < 0.001f && r[0] == 100 && r[1] == 100, s(r));
        r = SelectionMath.resize(SelectionMath.TOP_LEFT, box, -100, 0);
        expect("esquina opuesta queda fija", r[2] == 300 && r[3] == 200 && Math.abs((r[2]-r[0])/(r[3]-r[1]) - 2) < 0.001f, s(r));
        r = SelectionMath.resize(SelectionMath.RIGHT, box, -500, 0); expect("no se invierte ni desaparece", r[2]-r[0] >= SelectionMath.MIN_SIZE, s(r));
        r = SelectionMath.resize(SelectionMath.BOTTOM_RIGHT, box, -900, -900); expect("esquina con tamaño mínimo", r[3]-r[1] >= SelectionMath.MIN_SIZE - 0.01f, s(r));
        r = SelectionMath.resize(SelectionMath.RIGHT, new float[]{0,0,100,200}, 98, 0); expect("casi cuadrado → cuadrado", same(r, 0,0,200,200), s(r));
        r = SelectionMath.resize(SelectionMath.RIGHT, new float[]{0,0,100,200}, 50, 0); expect("lejos de cuadrado no ajusta", same(r, 0,0,150,200), s(r));
        expect("ángulo casi 45", Math.abs(Math.toDegrees(SelectionMath.snapAngle(Math.toRadians(43.5))) - 45) < 0.01, "");
        expect("ángulo libre", Math.abs(Math.toDegrees(SelectionMath.snapAngle(Math.toRadians(37))) - 37) < 0.01, "");
        float[] m = SelectionMath.affine(box, new float[]{100,100,500,200}, 0);
        expect("afín estira", Math.abs(m[0]*300+m[2]*200+m[4] - 500) < 0.01 && Math.abs(m[1]*300+m[3]*200+m[5] - 200) < 0.01, Arrays.toString(m));
        m = SelectionMath.affine(box, box, (float) (Math.PI/2)); // centro (200,150)
        float px = m[0]*300+m[2]*150+m[4], py = m[1]*300+m[3]*150+m[5];
        expect("afín gira alrededor del centro", Math.abs(px-200) < 0.01 && Math.abs(py-250) < 0.01, px + "," + py);
        float cx = m[0]*200+m[2]*150+m[4], cy = m[1]*200+m[3]*150+m[5];
        expect("el centro no se mueve", Math.abs(cx-200) < 0.01 && Math.abs(cy-150) < 0.01, cx + "," + cy);
        expect("uniforme sí", SelectionMath.uniform(box, new float[]{100,100,500,300}), "");
        expect("uniforme no", !SelectionMath.uniform(box, new float[]{100,100,500,200}), "");
        int h;
        h = SelectionMath.hit(box, 300, 200, 20, 12, true, true, 40); expect("esquina", h == SelectionMath.BOTTOM_RIGHT, ""+h);
        h = SelectionMath.hit(box, 305, 150, 20, 12, true, true, 40); expect("borde derecho", h == SelectionMath.RIGHT, ""+h);
        h = SelectionMath.hit(box, 200, 198, 20, 12, true, true, 40); expect("borde de abajo", h == SelectionMath.BOTTOM, ""+h);
        h = SelectionMath.hit(box, 200, 198, 20, 12, false, true, 40); expect("texto: sin borde de abajo", h == SelectionMath.MOVE, ""+h);
        h = SelectionMath.hit(box, 200, 60, 20, 12, true, true, 40); expect("giro", h == SelectionMath.ROTATE, ""+h);
        h = SelectionMath.hit(box, 200, 60, 20, 12, true, false, 40); expect("sin giro con imágenes", h == SelectionMath.NONE, ""+h);
        h = SelectionMath.hit(box, 200, 150, 20, 12, true, true, 40); expect("dentro mueve", h == SelectionMath.MOVE, ""+h);
        h = SelectionMath.hit(box, 800, 800, 20, 12, true, true, 40); expect("fuera", h == SelectionMath.NONE, ""+h);
        System.out.println(fails == 0 ? "TODO BIEN" : fails + " FALLOS");
    }
}
