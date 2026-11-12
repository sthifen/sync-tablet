package android.app;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Pruebas sin manos: con -Dsynctablet.script=archivo se siguen sus órdenes,
 * una por línea: "wait 500", "click x y", "drag x1 y1 x2 y2", "wheel x y n",
 * "type texto", "key ESCAPE", "shot archivo.png", "size w h", "quit".
 */
final class Automation {
    static void start(Desktop d) {
        String script = System.getProperty("synctablet.script");
        if (script == null) return;
        Thread t = new Thread(() -> {
            try {
                List<String> lines = Files.readAllLines(new File(script).toPath());
                Thread.sleep(1500);
                for (String raw : lines) {
                    String l = raw.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String[] p = l.split("\\s+", 2);
                    String[] a = p.length > 1 ? p[1].split("\\s+") : new String[0];
                    switch (p[0]) {
                        case "pick": FileDialogs.nextPick = new File(p[1]); break;
                        case "wait": Thread.sleep(Long.parseLong(a[0])); break;
                        case "size": SwingUtilities.invokeAndWait(() -> { d.frame().setExtendedState(0); d.frame().setSize(Integer.parseInt(a[0]), Integer.parseInt(a[1])); }); Thread.sleep(400); break;
                        case "click": mouse(d, Integer.parseInt(a[0]), Integer.parseInt(a[1]), a.length > 2 && a[2].equals("right") ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1); Thread.sleep(250); break;
                        case "drag": drag(d, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])); Thread.sleep(250); break;
                        case "wheel": wheel(d, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), a.length > 3 && a[3].equals("ctrl")); Thread.sleep(300); break;
                        case "type": type(d, p.length > 1 ? p[1] : ""); Thread.sleep(150); break;
                        case "key": key(d, a[0], a.length > 1 ? a[1] : ""); Thread.sleep(200); break;
                        case "shot": {
                            final java.awt.image.BufferedImage[] img = new java.awt.image.BufferedImage[1];
                            SwingUtilities.invokeAndWait(() -> img[0] = d.snapshot());
                            ImageIO.write(img[0], "png", new File(a[0]));
                            System.err.println("shot " + a[0]);
                            break;
                        }
                        case "quit": SwingUtilities.invokeLater(() -> d.frame().dispatchEvent(new java.awt.event.WindowEvent(d.frame(), java.awt.event.WindowEvent.WINDOW_CLOSING))); return;
                        default: System.err.println("orden desconocida: " + l);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "automatización");
        t.setDaemon(true);
        t.start();
    }

    private static void post(Desktop d, java.awt.AWTEvent e) throws Exception {
        SwingUtilities.invokeAndWait(() -> d.panel().dispatchEvent(e));
    }

    private static void mouse(Desktop d, int x, int y, int button) throws Exception {
        java.awt.Component c = d.panel();
        long t = System.currentTimeMillis();
        int mask = button == MouseEvent.BUTTON3 ? MouseEvent.BUTTON3_DOWN_MASK : MouseEvent.BUTTON1_DOWN_MASK;
        post(d, new MouseEvent(c, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
        post(d, new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, mask, x, y, 1, false, button));
        Thread.sleep(60);
        post(d, new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 60, 0, x, y, 1, false, button));
    }

    private static void drag(Desktop d, int x1, int y1, int x2, int y2) throws Exception {
        java.awt.Component c = d.panel();
        long t = System.currentTimeMillis();
        post(d, new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, MouseEvent.BUTTON1_DOWN_MASK, x1, y1, 1, false, MouseEvent.BUTTON1));
        for (int i = 1; i <= 20; i++) {
            int x = x1 + (x2 - x1) * i / 20, y = y1 + (y2 - y1) * i / 20;
            post(d, new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, t + i * 12, MouseEvent.BUTTON1_DOWN_MASK, x, y, 0, false));
            Thread.sleep(12);
        }
        post(d, new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 300, 0, x2, y2, 1, false, MouseEvent.BUTTON1));
    }

    private static void wheel(Desktop d, int x, int y, int n, boolean ctrl) throws Exception {
        java.awt.Component c = d.panel();
        for (int i = 0; i < Math.abs(n); i++) {
            post(d, new java.awt.event.MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), ctrl ? java.awt.event.InputEvent.CTRL_DOWN_MASK : 0, x, y, 0, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, n > 0 ? 1 : -1));
            Thread.sleep(30);
        }
    }

    private static void type(Desktop d, String s) throws Exception {
        java.awt.Component c = d.panel();
        for (char ch : s.toCharArray()) {
            post(d, new KeyEvent(c, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch));
            Thread.sleep(10);
        }
    }

    private static void key(Desktop d, String name, String mods) throws Exception {
        java.awt.Component c = d.panel();
        int vk = (Integer) KeyEvent.class.getField("VK_" + name.toUpperCase()).get(null);
        int m = 0;
        if (mods.contains("ctrl")) m |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
        if (mods.contains("shift")) m |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        if (mods.contains("alt")) m |= java.awt.event.InputEvent.ALT_DOWN_MASK;
        long t = System.currentTimeMillis();
        post(d, new KeyEvent(c, KeyEvent.KEY_PRESSED, t, m, vk, KeyEvent.CHAR_UNDEFINED));
        post(d, new KeyEvent(c, KeyEvent.KEY_RELEASED, t + 30, m, vk, KeyEvent.CHAR_UNDEFINED));
    }

    private Automation() {}
}
