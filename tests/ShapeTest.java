package dev.synctablet;
import java.util.*;
public class ShapeTest {
    static Random r = new Random(Long.getLong("seed", 7));
    static float[] poly(float[][] v, float noise) { // contorno por vértices, con temblor
        List<Float> o = new ArrayList<>();
        for (int i = 0; i + 1 < v.length; i++) for (int s = 0; s < 20; s++) {
            float t = s / 20f;
            o.add(v[i][0] + (v[i+1][0]-v[i][0])*t + (float) r.nextGaussian()*noise);
            o.add(v[i][1] + (v[i+1][1]-v[i][1])*t + (float) r.nextGaussian()*noise);
        }
        o.add(v[v.length-1][0]); o.add(v[v.length-1][1]);
        float[] a = new float[o.size()]; for (int i = 0; i < a.length; i++) a[i] = o.get(i); return a;
    }
    static float[] ell(float cx, float cy, float rx, float ry, float noise, double sweep) {
        int n = 80; float[] a = new float[2*n];
        for (int i = 0; i < n; i++) { double t = sweep*i/(n-1); a[2*i] = cx + rx*(float)Math.cos(t) + (float) r.nextGaussian()*noise; a[2*i+1] = cy + ry*(float)Math.sin(t) + (float) r.nextGaussian()*noise; }
        return a;
    }
    static int fails = 0;
    static void check(String name, float[] xy, String want) { check(name, xy, want, false); }
    /** orNone: no convertirlo también vale (en la duda, mejor no tocar el trazo). */
    static void check(String name, float[] xy, String want, boolean orNone) {
        ShapeRecognizer.Shape s = ShapeRecognizer.recognize(xy, xy.length/2);
        String got = s == null ? null : s.kind;
        boolean ok = Objects.equals(got, want) || (orNone && got == null);
        if (!ok) fails++;
        System.out.printf("%s %-28s → %s%n", ok ? "✓" : "✗", name, got == null ? "(nada)" : got);
    }
    static void cir(String name, float[] xy, int want) {
        ShapeTools.Spec s = ShapeRecognizer.circuit(xy, xy.length/2);
        expect(name, (s == null ? -1 : s.kind) == want, s == null ? "(nada)" : s.name());
    }
    static void expect(String name, boolean ok, String got) {
        if (!ok) fails++;
        System.out.printf("%s %-28s %s%n", ok ? "✓" : "✗", name, got);
    }
    /** Largo de la barba: del final del trazo a la punta (x, y). */
    static float barb(float[] xy, float x, float y) { return (float) Math.hypot(xy[xy.length-2]-x, xy[xy.length-1]-y); }
    static float extent(float[] xy, int axis) { float lo = 1e9f, hi = -1e9f; for (int i = axis; i < xy.length; i += 2) { lo = Math.min(lo, xy[i]); hi = Math.max(hi, xy[i]); } return hi - lo; }
    static double angle(float[] xy) { return Math.toDegrees(Math.atan2(xy[xy.length-1]-xy[1], xy[xy.length-2]-xy[0])); }
    /** ¿Las esquinas de este contorno forman ángulos rectos? */
    static boolean square(float[] xy) {
        List<float[]> c = new ArrayList<>();
        for (int i = 2; i + 3 < xy.length; i += 2) {
            double a = Math.atan2(xy[i+1]-xy[i-1], xy[i]-xy[i-2]), b = Math.atan2(xy[i+3]-xy[i+1], xy[i+2]-xy[i]);
            double t = Math.abs(Math.toDegrees(Math.atan2(Math.sin(b-a), Math.cos(b-a))));
            if (t > 30) c.add(new float[]{(float) t});
        }
        for (float[] t : c) if (Math.abs(t[0] - 90) > 0.5) return false;
        return c.size() >= 3;
    }
    public static void main(String[] a) {
        check("círculo", ell(300,300,100,100,3,2*Math.PI*1.03), ShapeRecognizer.CIRCLE);
        check("elipse", ell(300,300,180,90,3,2*Math.PI*1.02), ShapeRecognizer.ELLIPSE);
        check("rectángulo", poly(new float[][]{{100,100},{400,100},{400,300},{100,300},{102,104}},2.5f), ShapeRecognizer.RECTANGLE);
        check("cuadrado girado 30°", poly(new float[][]{{200,100},{373,200},{273,373},{100,273},{198,103}},2.5f), ShapeRecognizer.RECTANGLE);
        check("triángulo", poly(new float[][]{{100,400},{250,120},{400,400},{104,398}},2.5f), ShapeRecognizer.TRIANGLE);
        check("línea", poly(new float[][]{{100,100},{500,180}},2f), ShapeRecognizer.LINE);
        check("línea casi horizontal", poly(new float[][]{{100,300},{600,318}},2f), ShapeRecognizer.LINE);
        check("rombo", poly(new float[][]{{250,100},{400,250},{250,400},{100,250},{248,104}},2f), ShapeRecognizer.RECTANGLE);
        check("trapecio", poly(new float[][]{{150,100},{350,100},{450,300},{50,300},{148,104}},2f), ShapeRecognizer.QUAD, true);
        check("arco en C (abierto)", ell(300,300,100,100,2,Math.PI*1.3), null);
        check("letra S", poly(new float[][]{{300,100},{150,120},{160,220},{300,250},{310,360},{150,380}},2f), null);
        check("zigzag", poly(new float[][]{{100,100},{150,200},{200,100},{250,200},{300,100},{350,200}},1f), null);
        check("garabato", ell(300,300,60,40,25,2*Math.PI*3), null);
        float[] tiny = poly(new float[][]{{100,100},{108,104}},0.3f);
        check("punto/trazo diminuto", tiny, null);
        // Nuevas: elipse inclinada, flechas, polígonos, varios trazos, ángulos exactos.
        double c30 = Math.cos(Math.PI/6), s30 = Math.sin(Math.PI/6);
        float[] tilted = ell(0,0,180,80,2.5f,2*Math.PI*1.03);
        for (int i = 0; i < tilted.length; i += 2) { float x = tilted[i], y = tilted[i+1]; tilted[i] = 400 + (float)(x*c30 - y*s30); tilted[i+1] = 400 + (float)(x*s30 + y*c30); }
        check("elipse inclinada 30°", tilted, ShapeRecognizer.ELLIPSE);
        ShapeRecognizer.Shape te = ShapeRecognizer.recognize(tilted, tilted.length/2);
        if (te != null) { float bw = extent(te.xy, 0), bh = extent(te.xy, 1); expect("la elipse sigue inclinada", bw > 305 && bw < 340 && bh > 210 && bh < 245, bw + "×" + bh); }
        check("flecha un trazo", poly(new float[][]{{100,300},{500,300},{460,270},{500,300},{462,332}},1.2f), ShapeRecognizer.ARROW);
        check("flecha cabeza triángulo", poly(new float[][]{{100,120},{420,350},{365,345},{400,295}},1.2f), ShapeRecognizer.ARROW, true);
        check("círculo tembloroso", ell(300,300,120,120,5,2*Math.PI*1.03), ShapeRecognizer.CIRCLE, true);
        check("círculo pequeño", ell(300,300,45,45,2,2*Math.PI*1.03), ShapeRecognizer.CIRCLE, true);
        check("pentágono", poly(new float[][]{{300,100},{490,238},{418,462},{182,462},{110,238},{298,104}},2f), ShapeRecognizer.POLYGON, true);
        check("hexágono", poly(new float[][]{{200,100},{400,100},{500,273},{400,446},{200,446},{100,273},{202,102}},2f), ShapeRecognizer.POLYGON, true);
        // Rectángulo girado: lados perpendiculares de verdad.
        ShapeRecognizer.Shape rr = ShapeRecognizer.recognize(poly(new float[][]{{200,100},{450,244},{350,417},{100,273},{198,103}},2.5f), 81);
        expect("rectángulo girado exacto", rr != null && square(rr.xy), rr == null ? "nada" : rr.kind);
        // Línea a 45°, exacta.
        ShapeRecognizer.Shape l45 = ShapeRecognizer.recognize(poly(new float[][]{{100,100},{400,405}},1.5f), 21);
        expect("línea a 45° exacta", l45 != null && Math.abs(angle(l45.xy) - 45) < 0.2, l45 == null ? "nada" : "" + angle(l45.xy));
        // Trazos sueltos.
        float[][] box = {{100,100,300,104},{302,100,298,300},{300,302,102,298},{98,300,102,98}};
        ShapeRecognizer.Shape cl = ShapeRecognizer.closeLines(box);
        expect("4 líneas → rectángulo", cl != null && cl.kind.equals(ShapeRecognizer.RECTANGLE), cl == null ? "nada" : cl.kind);
        float[][] shuffled = {box[2], box[0], box[3], box[1]};
        cl = ShapeRecognizer.closeLines(shuffled);
        expect("4 líneas desordenadas", cl != null && cl.kind.equals(ShapeRecognizer.RECTANGLE), cl == null ? "nada" : cl.kind);
        cl = ShapeRecognizer.closeLines(new float[][]{{100,400,400,400},{400,402,250,120},{252,118,98,398}});
        expect("3 líneas → triángulo", cl != null && cl.kind.equals(ShapeRecognizer.TRIANGLE), cl == null ? "nada" : cl.kind);
        expect("3 líneas abiertas no cierran", ShapeRecognizer.closeLines(new float[][]{{0,0,100,0},{300,0,400,0},{0,300,100,300}}) == null, "");
        float[] v = poly(new float[][]{{460,262},{500,300},{458,338}},1f);
        ShapeRecognizer.Shape h = ShapeRecognizer.arrowHead(100,300,500,300,v,v.length/2);
        expect("línea + V → flecha", h != null && h.kind.equals(ShapeRecognizer.ARROW), "");
        float[] far = poly(new float[][]{{260,262},{300,300},{258,338}},1f);
        expect("V en medio no es flecha", ShapeRecognizer.arrowHead(100,300,500,300,far,far.length/2) == null, "");
        float[] curve = ell(500,300,40,40,1,Math.PI);
        expect("curva no es cabeza", ShapeRecognizer.arrowHead(100,300,500,300,curve,curve.length/2) == null, "");
        // Herramientas de arrastrar.
        float[] sq = ShapeTools.build(ShapeTools.RECT, 100,100,300,306,0);
        expect("casi cuadrado → cuadrado", Math.abs(extent(sq,0) - extent(sq,1)) < 0.5f, extent(sq,0) + "×" + extent(sq,1));
        float[] g = ShapeTools.build(ShapeTools.LINE, 103,118,297,283,ShapeTools.GRID);
        expect("cuadrícula", g[0] == 120 && g[1] == 120 && g[g.length-2] == 280 && g[g.length-1] == 280, g[0] + "," + g[1]);
        float[] la = ShapeTools.build(ShapeTools.LINE, 100,100,400,412,0);
        expect("ángulo de arrastre a 45°", Math.abs(angle(la) - 45) < 0.2, "" + angle(la));
        for (int k = 0; k <= ShapeTools.CROSS_AXES; k++) { float[] o = ShapeTools.build(k, 200,200,420,380,0); boolean fin = o.length >= 4; for (float x : o) fin &= !Float.isNaN(x); expect("herramienta " + k, fin, ""); }
        expect("arrastre sin mover no falla", ShapeTools.build(ShapeTools.ARROW,50,50,50,50,0).length >= 4, "");
        // Figuras editables.
        ShapeTools.Spec ar = ShapeTools.spec(ShapeTools.ARROW, 100,100,300,100,0);
        float[] stretch = {3,0,0,1,-200,0}; // el doble y algo a lo ancho
        float[] st = ar.mapped(stretch, 0).points();
        float before = barb(ar.points(), 300, 100), after = barb(st, 700, 100);
        expect("estirar flecha: punta igual", before > 10 && Math.abs(before - after) < 0.5f, before + " → " + after);
        float[] naive = ar.points().clone();
        for (int i = 0; i < naive.length; i += 2) naive[i] = 3 * naive[i] - 200;
        expect("sin Spec se deformaría", Math.abs(barb(naive, 700, 100) - before) > 5, "" + barb(naive, 700, 100));
        ShapeTools.Spec rc = ShapeTools.spec(ShapeTools.RECT, 100,100,300,200,0);
        ShapeTools.Spec rc2 = rc.mapped(new float[]{2,0,0,1,0,0}, 0);
        expect("rectángulo estirado sigue siéndolo", rc2 != null && rc2.x1 == 600 && rc2.y1 == 200, rc2 == null ? "nada" : rc2.x0 + "," + rc2.x1);
        float c90 = (float) Math.toRadians(90);
        float[] rot = SelectionMath.affine(new float[]{100,100,300,200}, new float[]{100,100,300,200}, c90);
        ShapeTools.Spec rr2 = rc.mapped(rot, c90);
        float[] pts = rr2.points();
        expect("rectángulo girado 90°: alto y ancho cambian", Math.abs(extent(pts,0) - 100) < 0.5f && Math.abs(extent(pts,1) - 200) < 0.5f, extent(pts,0) + "×" + extent(pts,1));
        expect("caja girada estirada: dibujo libre", rr2.mapped(new float[]{3,0,0,1,0,0}, 0) == null, "");
        ShapeTools.Spec moved = rr2.mapped(new float[]{1,0,0,1,50,20}, 0);
        expect("caja girada se mueve y sigue", moved != null && moved.angle == rr2.angle, "");
        ShapeTools.Spec dia = ShapeTools.spec(ShapeTools.LINE, 0,0,100,0,0).mapped(rot, c90);
        expect("una línea se gira por sus puntas", Math.abs(dia.angle) < 0.001f, "");
        ShapeTools.Spec end = ar.withEnd(false, 300, 300);
        expect("mover una punta", end.x0 == 100 && end.y1 == 300, "");
        expect("línea → flecha → doble → línea", ShapeTools.nextLineKind(ShapeTools.nextLineKind(ShapeTools.nextLineKind(ShapeTools.LINE))) == ShapeTools.LINE, "");
        expect("nombres de ida y vuelta", ShapeTools.Spec.kindOf(ShapeTools.spec(ShapeTools.CROSS_AXES,0,0,1,1,0).name()) == ShapeTools.CROSS_AXES && ShapeTools.Spec.kindOf("futuro") == -1, "");
        expect("anclas de una caja", rc.anchors().length == 16 && rc.anchors()[0] == 100 && rc.anchors()[1] == 100, "");
        ShapeRecognizer.Shape rl = ShapeRecognizer.recognize(poly(new float[][]{{100,100},{500,180}},1f), 21);
        expect("línea reconocida editable", rl != null && rl.spec != null && rl.spec.isLine(), "");
        ShapeRecognizer.Shape rq = ShapeRecognizer.recognize(poly(new float[][]{{200,100},{450,244},{350,417},{100,273},{198,103}},1.5f), 81);
        expect("rectángulo girado editable", rq != null && rq.spec != null && rq.spec.angle != 0, rq == null ? "nada" : "" + (rq.spec == null ? "sin" : rq.spec.angle));
        if (rq != null && rq.spec != null) { float[] sp = rq.spec.points(); expect("sus puntos coinciden", Math.abs(extent(sp,0) - extent(rq.xy,0)) < 2 && Math.abs(extent(sp,1) - extent(rq.xy,1)) < 2, extent(sp,0) + " vs " + extent(rq.xy,0)); }
        ShapeRecognizer.Shape re = ShapeRecognizer.recognize(tilted, tilted.length/2);
        if (re != null) { expect("elipse inclinada editable", re.spec != null, ""); if (re.spec != null) { float[] sp = re.spec.points(); expect("sus puntos coinciden", Math.abs(extent(sp,0) - extent(re.xy,0)) < 3, extent(sp,0) + " vs " + extent(re.xy,0)); } }
        // Circuitos.
        cir("zigzag → resistencia", poly(new float[][]{{100,300},{130,270},{170,330},{210,270},{250,330},{290,270},{330,330},{360,300}},1.5f), ShapeTools.RESISTOR);
        cir("zigzag vertical", poly(new float[][]{{300,100},{270,130},{330,170},{270,210},{330,250},{270,290},{300,330}},1.5f), ShapeTools.RESISTOR);
        cir("círculo → fuente", ell(300,300,70,70,2,2*Math.PI*1.03), ShapeTools.VOLTAGE);
        cir("línea → cable", poly(new float[][]{{100,200},{500,210}},1.5f), ShapeTools.LINE);
        float[] coil = new float[2*90];
        for (int i = 0; i < 90; i++) { double t = 2*Math.PI*4.2*i/89; coil[2*i] = 100 + 3.4f*i + 30*(float)Math.sin(t); coil[2*i+1] = 300 - 30*(float)Math.cos(t) + (float) r.nextGaussian(); }
        cir("espiral → bobina", coil, ShapeTools.INDUCTOR);
        cir("garabato → nada", ell(300,300,60,40,25,2*Math.PI*3), -1);
        ShapeTools.Spec tri = ShapeRecognizer.circuit(poly(new float[][]{{200,150},{520,300},{200,450},{202,152}},2f), 61);
        expect("triángulo → operacional", tri != null && tri.kind == ShapeTools.OPAMP, tri == null ? "(nada)" : tri.name());
        if (tri != null) expect("su salida es la punta", tri.x1 > tri.x0 && Math.abs(tri.y1 - 300) < 16, tri.x0 + "→" + tri.x1 + "," + tri.y1);
        ShapeTools.Spec triL = ShapeRecognizer.circuit(poly(new float[][]{{520,150},{200,300},{520,450},{518,152}},2f), 61);
        expect("triángulo al revés", triL != null && triL.kind == ShapeTools.OPAMP && triL.x1 < triL.x0, triL == null ? "(nada)" : triL.x0 + "→" + triL.x1);
        cir("círculo sigue siendo fuente", ell(300,300,90,90,2,2*Math.PI*1.03), ShapeTools.VOLTAGE);
        ShapeTools.Spec q = new ShapeTools.Spec(ShapeTools.NPN, 100,300,300,300,0);
        float[] tq = q.terminals();
        expect("transistor: base, colector arriba, emisor abajo", tq.length == 6 && tq[0] == 100 && tq[3] < 300 && tq[5] > 300, Arrays.toString(tq));
        float[] tf = q.flipped().terminals();
        expect("volteado: colector abajo", tf[3] > 300 && tf[5] < 300 && tf[0] == 100, Arrays.toString(tf));
        expect("sus anclas son los terminales", q.anchors().length == 6 && q.anchors()[3] == tq[3], "");
        float[] to = new ShapeTools.Spec(ShapeTools.OPAMP, 100,300,300,300,0).terminals();
        expect("operacional: dos entradas y salida", to[0] == 100 && to[2] == 100 && to[1] < 300 && to[3] > 300 && to[4] == 300 && to[5] == 300, Arrays.toString(to));
        expect("volteo sobrevive a moverlo", q.flipped().mapped(new float[]{1,0,0,1,9,9},0).mirror && q.flipped().withEnd(true,1,1).mirror, "");
        expect("transistores en ciclo", ShapeTools.nextTransistor(ShapeTools.nextTransistor(ShapeTools.nextTransistor(ShapeTools.nextTransistor(ShapeTools.NPN)))) == ShapeTools.NPN, "");
        for (int k = ShapeTools.AND; k <= ShapeTools.XNOR; k++) {
            float[] tg = new ShapeTools.Spec(k, 100,300,300,300,0).terminals();
            expect("compuerta: dos entradas y salida " + k, tg[0] == 100 && tg[2] == 100 && tg[1] < 300 && tg[3] > 300 && tg[4] == 300, Arrays.toString(tg));
        }
        int gate = ShapeTools.AND; for (int i = 0; i < 6; i++) gate = ShapeTools.nextGate(gate);
        expect("compuertas en ciclo", gate == ShapeTools.AND && ShapeTools.nextGate(ShapeTools.nextGate(ShapeTools.NOT)) == ShapeTools.NOT, "");
        expect("NOT tiene dos terminales", !new ShapeTools.Spec(ShapeTools.NOT,0,0,9,0,0).isThreeTerminal() && new ShapeTools.Spec(ShapeTools.NOT,0,0,9,0,0).isComponent(), "");
        for (int k = ShapeTools.NOT; k <= ShapeTools.BUFFER; k++) {
            float[] o = new ShapeTools.Spec(k, 100, 300, 300, 300, 0).points();
            float lo = 1e9f, hi = -1e9f; for (int i = 0; i < o.length; i += 2) if (!Float.isNaN(o[i])) { lo = Math.min(lo, o[i]); hi = Math.max(hi, o[i]); }
            expect("una entrada llega de punta a punta " + k, lo <= 100.01f && hi >= 299.9f, lo + "–" + hi);
        }
        ShapeTools.Spec upGate = ShapeTools.spec(ShapeTools.NOR, 100, 100, 350, 340, 0);
        expect("compuerta en diagonal queda derecha", upGate.y0 == upGate.y1, "");
        for (int k = ShapeTools.NPN; k <= ShapeTools.XNOR; k++) {
            ShapeTools.Spec sp = new ShapeTools.Spec(k, 100,300,300,300,0);
            float[] o = sp.points(); int real = 0; for (float x2 : o) if (!Float.isNaN(x2)) real++;
            boolean near = true; float[] tt = sp.terminals();
            for (int i = 0; i < 6; i += 2) { float best = 1e9f; for (int j = 0; j + 1 < o.length; j += 2) if (!Float.isNaN(o[j])) best = Math.min(best, (float) Math.hypot(o[j]-tt[i], o[j+1]-tt[i+1])); near &= best < 0.5f; }
            expect("el dibujo llega a sus terminales: " + sp.name(), real >= 20 && near, "");
        }
        for (int k = ShapeTools.RESISTOR; k <= ShapeTools.SWITCH; k++) {
            float[] o = new ShapeTools.Spec(k, 100, 100, 300, 100, 0).points();
            boolean nums = o.length >= 8; int real = 0; for (float x2 : o) if (!Float.isNaN(x2)) real++;
            float lo = 1e9f, hi = -1e9f; for (int i = 0; i < o.length; i += 2) if (!Float.isNaN(o[i])) { lo = Math.min(lo, o[i]); hi = Math.max(hi, o[i]); }
            expect("componente " + new ShapeTools.Spec(k,0,0,1,1,0).name(), nums && real >= 8 && lo <= 100.01f && hi >= 299.9f, lo + "–" + hi);
        }
        float[] cp = new ShapeTools.Spec(ShapeTools.CAPACITOR,0,0,200,0,0).points(); boolean jumps = false; for (float pv : cp) jumps |= Float.isNaN(pv);
        expect("condensador levanta el lápiz", jumps, "");
        float[] lb = new ShapeTools.Spec(ShapeTools.RESISTOR,100,300,300,300,0).labelPosition(); expect("valor encima", lb[1] < 300 && Math.abs(lb[0]-200) < 1, lb[0] + "," + lb[1]);
        ShapeTools.Spec lab = new ShapeTools.Spec(ShapeTools.RESISTOR,0,0,10,0,0).withLabel("a");
        expect("el valor sigue al componente", "a".equals(lab.mapped(new float[]{1,0,0,1,5,5},0).label) && "a".equals(lab.withEnd(true,1,1).label), "");
        ShapeTools.Spec up = ShapeTools.spec(ShapeTools.NPN, 100, 100, 360, 360, 0);
        expect("transistor en diagonal queda derecho", up.y0 == up.y1 && up.x1 == 360, up.x1 + "," + up.y1);
        ShapeTools.Spec tall = ShapeTools.spec(ShapeTools.OPAMP, 100, 100, 150, 400, 0);
        expect("operacional casi vertical queda vertical", tall.x0 == tall.x1 && tall.y1 == 400, tall.x1 + "," + tall.y1);
        ShapeTools.Spec onGrid = ShapeTools.spec(ShapeTools.NMOS, 41, 39, 322, 300, ShapeTools.GRID);
        expect("transistor derecho también en cuadrícula", onGrid.y0 == onGrid.y1 && onGrid.x0 == 40 && onGrid.x1 == 320, onGrid.x0 + "," + onGrid.x1);
        ShapeTools.Spec src = ShapeTools.spec(ShapeTools.VOLTAGE, 100, 100, 350, 350, 0);
        expect("fuente en diagonal queda derecha", src.y0 == src.y1, src.x1 + "," + src.y1);
        ShapeTools.Spec tie = ShapeTools.spec(ShapeTools.VOLTAGE, 100, 100, 349, 350, 0);
        expect("diagonal casi exacta: horizontal", tie.y0 == tie.y1, tie.x1 + "," + tie.y1);
        ShapeTools.Spec off = ShapeTools.spec(ShapeTools.RESISTOR, 100, 100, 300, 130, 0);
        expect("resistencia casi horizontal", Math.abs(off.y1 - 100) < 0.01f, "" + off.y1);
        ShapeTools.Spec cross = ShapeTools.spec(ShapeTools.RESISTOR, 100, 100, 300, 280, 0);
        expect("resistencia a 45°", Math.abs((cross.x1 - 100) - (cross.y1 - 100)) < 0.01f, cross.x1 + "," + cross.y1);
        System.out.println(fails == 0 ? "TODO BIEN" : fails + " FALLOS");
    }
}
