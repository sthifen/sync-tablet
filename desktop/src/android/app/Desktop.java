package android.app;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.MainQueue;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewRoot;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * La app en el escritorio: una ventana de Swing donde se dibuja la actividad
 * de arriba de la pila, como la pantalla de la tablet. Lleva la pila de
 * actividades, sus ciclos de vida, los selectores de archivos y Atrás (Esc).
 */
public final class Desktop {
    private static Desktop instance;

    public static Desktop get() { return instance; }

    private final JFrame frame;
    private final Panel panel;
    private final ViewRoot root;
    private final float density;
    private final ArrayList<Activity> stack = new ArrayList<>();
    private boolean fullscreen;
    private Rectangle windowedBounds;

    /** Arranca la app con la actividad `launcher`. */
    public static void launch(Class<? extends Activity> launcher, String title, Image icon) {
        SwingUtilities.invokeLater(() -> {
            instance = new Desktop(title, icon);
            instance.startActivity(null, new Intent(Application.get(), launcher).setAction(Intent.ACTION_MAIN), -1);
            instance.frame.setVisible(true);
            instance.panel.requestFocusInWindow();
        });
    }

    private Desktop(String title, Image icon) {
        density = pickDensity();
        Dimension screen = screenSize();
        Resources.setup(density, darkTheme(), screen.width, screen.height);
        frame = new JFrame(title);
        if (icon != null) frame.setIconImage(icon);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        panel = new Panel();
        root = new ViewRoot(panel, density);
        frame.setContentPane(panel);
        frame.getContentPane().setBackground(new Color(0x0F1417));
        Dimension size = new Dimension(Math.min(screen.width - 80, Math.round(1280 * density)), Math.min(screen.height - 80, Math.round(820 * density)));
        frame.setSize(size);
        frame.setMinimumSize(new Dimension(Math.round(560 * density), Math.round(420 * density)));
        frame.setLocationRelativeTo(null);
        if (screen.width <= 1600) frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        // Como en Android, las medidas de la pantalla son las de la ventana.
        Resources.resize(screen.width <= 1600 ? screen.width : size.width, screen.width <= 1600 ? screen.height - 64 : size.height);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
            @Override public void windowActivated(WindowEvent e) { focusChanged(true); }
            @Override public void windowDeactivated(WindowEvent e) { root.cancelPointer(); focusChanged(false); }
            @Override public void windowIconified(WindowEvent e) { Activity a = top(); if (a != null && a.resumed) { a.resumed = false; a.onPause(); } }
            @Override public void windowDeiconified(WindowEvent e) { Activity a = top(); if (a != null && !a.resumed) { a.resumed = true; a.onResume(); a.onPostResume(); } }
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { resized(); }
        });
        startThemeWatcher();
        Automation.start(this);
    }

    /** Para pruebas: la ventana tal como se ve, en una imagen. */
    public java.awt.image.BufferedImage snapshot() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.paintComponent(g);
        g.dispose();
        return img;
    }

    /** Para pruebas: eventos de ratón y teclado como si vinieran de la ventana. */
    public java.awt.Component panel() { return panel; }

    // ---------------------------------------------------------------- pantalla

    private static Dimension screenSize() {
        try {
            GraphicsDevice d = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return new Dimension(d.getDisplayMode().getWidth(), d.getDisplayMode().getHeight());
        } catch (Throwable t) {
            return new Dimension(1920, 1080);
        }
    }

    /** dp → px: el factor del sistema (GDK_SCALE y el tamaño del texto de GNOME) o SYNCTABLET_SCALE. */
    private static float pickDensity() {
        String env = System.getenv("SYNCTABLET_SCALE");
        if (env != null) {
            try { return Math.max(0.5f, Math.min(4f, Float.parseFloat(env.replace(',', '.')))); } catch (NumberFormatException ignored) {}
        }
        float d = 1f;
        String gdk = System.getenv("GDK_SCALE");
        if (gdk != null) { try { d = Float.parseFloat(gdk); } catch (NumberFormatException ignored) {} }
        else {
            Dimension s = screenSize();
            if (s.width >= 3200) d = 2f; else if (s.width >= 2400) d = 1.5f;
        }
        String text = run("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor");
        if (text != null) { try { d *= Float.parseFloat(text.trim()); } catch (NumberFormatException ignored) {} }
        return d;
    }

    private static boolean darkTheme() {
        String forced = System.getenv("SYNCTABLET_THEME");
        if ("dark".equalsIgnoreCase(forced)) return true;
        if ("light".equalsIgnoreCase(forced)) return false;
        String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (scheme != null) return scheme.contains("dark");
        String gtk = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
        return gtk != null && gtk.toLowerCase(java.util.Locale.ROOT).contains("dark");
    }

    /** Si cambia el tema del sistema, las actividades se recrean como en Android. */
    private void startThemeWatcher() {
        Thread t = new Thread(() -> {
            boolean last = darkTheme();
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                boolean now = darkTheme();
                if (now != last) {
                    last = now;
                    final boolean night = now;
                    SwingUtilities.invokeLater(() -> {
                        Resources.setNight(night);
                        for (Activity a : new ArrayList<>(stack)) recreate(a);
                    });
                }
            }
        }, "tema");
        t.setDaemon(true);
        t.start();
    }

    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private final Runnable configChanged = this::dispatchConfiguration;
    private int lastWidthDp = -1, lastHeightDp = -1;

    private void resized() {
        Resources.resize(panel.getWidth(), panel.getHeight());
        root.scheduleLayout();
        MainQueue.remove(this, configChanged, null);
        MainQueue.post(this, configChanged, 150, null);
    }

    private void dispatchConfiguration() {
        Configuration c = Resources.getSystem().getConfiguration();
        if (c.screenWidthDp == lastWidthDp && c.screenHeightDp == lastHeightDp) return;
        boolean first = lastWidthDp < 0;
        lastWidthDp = c.screenWidthDp;
        lastHeightDp = c.screenHeightDp;
        if (first) return;
        for (Activity a : new ArrayList<>(stack)) {
            a.onConfigurationChanged(new Configuration(c));
            a.getWindow().decor().dispatchConfigurationChangedPublic(new Configuration(c));
        }
    }

    public void setFullscreen(boolean on) {
        if (on == fullscreen) return;
        fullscreen = on;
        GraphicsDevice dev = frame.getGraphicsConfiguration().getDevice();
        if (on) {
            windowedBounds = frame.getBounds();
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            try { dev.setFullScreenWindow(frame); } catch (Throwable ignored) {}
        } else {
            try { dev.setFullScreenWindow(null); } catch (Throwable ignored) {}
            if (windowedBounds != null) frame.setBounds(windowedBounds);
        }
        panel.requestFocusInWindow();
    }

    public boolean isFullscreen() { return fullscreen; }

    void minimize() { frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED); }

    void titleChanged(Activity a) {
        if (a == top() && a.getTitle() != null) frame.setTitle(a.getTitle() + " — Sync Tablet");
    }

    private void focusChanged(boolean f) {
        root.setWindowFocused(f);
        Activity a = top();
        if (a != null) a.onWindowFocusChanged(f);
    }

    public ViewRoot root() { return root; }
    public float density() { return density; }
    public JFrame frame() { return frame; }

    // ---------------------------------------------------------------- pila de actividades

    Activity top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }
    boolean isRoot(Activity a) { return !stack.isEmpty() && stack.get(0) == a; }

    public void startActivity(Activity from, Intent intent, int request) {
        if (!MainQueue.isMain()) { MainQueue.post(this, () -> startActivity(from, intent, request), 0, null); return; }
        Class<?> cls = intent.componentClass();
        if (cls == null) { system(from, intent, request); return; }
        if (!Activity.class.isAssignableFrom(cls)) throw new ActivityNotFoundException("No es una actividad: " + cls);
        Activity top = top();
        boolean clearTop = (intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0;
        boolean singleTop = (intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0 || cls.getSimpleName().equals("MainActivity");
        if (clearTop) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).getClass() == cls) {
                    while (stack.size() - 1 > i) finishNow(top());
                    Activity a = stack.get(i);
                    a.setIntent(intent);
                    a.onNewIntent(intent);
                    show(a);
                    return;
                }
            }
        }
        if (top != null && singleTop && top.getClass() == cls) {
            boolean wasResumed = top.resumed;
            if (wasResumed) { top.resumed = false; top.onPause(); }
            top.setIntent(intent);
            top.onNewIntent(intent);
            if (wasResumed) { top.resumed = true; top.onResume(); top.onPostResume(); }
            return;
        }
        Activity a;
        try {
            a = (Activity) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ActivityNotFoundException(e.toString());
        }
        a.attach(intent);
        a.caller = request >= 0 ? from : null;
        a.requestCode = request;
        launch(a, null, null);
    }

    private void launch(Activity a, Bundle saved, Object nonConfig) {
        Activity prev = top();
        if (prev != null && prev.resumed) { prev.resumed = false; prev.onPause(); }
        stack.add(a);
        a.nonConfig = nonConfig;
        try {
            a.onCreate(saved);
            if (a.finishing) { finishNow(a); return; }
            a.started = true;
            a.onStart();
            if (saved != null) a.onRestoreInstanceState(saved);
            a.onPostCreate(saved);
            show(a);
        } catch (RuntimeException e) {
            android.util.Log.e("SyncTablet", "no se pudo abrir " + a.getClass().getSimpleName(), e);
            stack.remove(a);
            Activity back = top();
            if (back != null) show(back);
            throw e;
        }
        if (prev != null && prev != a && prev.started) { prev.started = false; prev.onStop(); }
    }

    private void show(Activity a) {
        if (a != top() || a.finishing) return;
        root.setDecor(a.getWindow().decor());
        if (!a.started) { a.started = true; a.onRestart(); a.onStart(); }
        if (!a.resumed) {
            a.resumed = true;
            a.onResume();
            a.onPostResume();
        }
        frame.setTitle(a.getTitle() != null ? a.getTitle() + " — Sync Tablet" : "Sync Tablet");
        MainQueue.post(this, () -> { if (a == top()) a.onWindowFocusChanged(frame.isActive()); }, 0, null);
        root.invalidate();
    }

    void finish(Activity a) {
        if (!MainQueue.isMain()) { MainQueue.post(this, () -> finish(a), 0, null); return; }
        if (a.finishing) return;
        a.finishing = true;
        // Como en Android: se cierra después de que vuelva el código que llamó a finish().
        MainQueue.post(this, () -> finishNow(a), 0, null);
    }

    private void finishNow(Activity a) {
        a.finishing = true;
        int i = stack.indexOf(a);
        if (i < 0) return;
        boolean wasTop = i == stack.size() - 1;
        if (a.resumed) { a.resumed = false; a.onPause(); }
        stack.remove(i);
        if (wasTop) {
            Activity next = top();
            if (next == null) {
                if (a.started) { a.started = false; a.onStop(); }
                destroy(a);
                quitNow();
                return;
            }
            root.setDecor(next.getWindow().decor());
            if (!next.started) { next.started = true; next.onRestart(); next.onStart(); }
            if (a.caller == next && a.requestCode >= 0) next.onActivityResult(a.requestCode, a.resultCode, a.resultData);
            show(next);
        }
        if (a.started) { a.started = false; a.onStop(); }
        destroy(a);
    }

    private void destroy(Activity a) {
        if (a.destroyed) return;
        a.destroyed = true;
        try { a.onDestroy(); } catch (RuntimeException e) { android.util.Log.e("SyncTablet", "onDestroy", e); }
    }

    void finishAll() { for (Activity a : new ArrayList<>(stack)) finish(a); }

    void recreate(Activity a) {
        if (!MainQueue.isMain()) { MainQueue.post(this, () -> recreate(a), 0, null); return; }
        int i = stack.indexOf(a);
        if (i < 0) return;
        Bundle saved = new Bundle();
        a.onSaveInstanceState(saved);
        Object keep = a.onRetainNonConfigurationInstance();
        boolean wasTop = a == top();
        if (a.resumed) { a.resumed = false; a.onPause(); }
        if (a.started) { a.started = false; a.onStop(); }
        destroy(a);
        Activity b;
        try {
            b = a.getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return;
        }
        b.attach(a.getIntent());
        b.caller = a.caller;
        b.requestCode = a.requestCode;
        stack.set(i, b);
        b.nonConfig = keep;
        b.onCreate(saved);
        b.started = true;
        b.onStart();
        b.onRestoreInstanceState(saved);
        b.onPostCreate(saved);
        if (wasTop) show(b); else { b.started = false; b.onStop(); }
    }

    /** Atrás: lo registrado con OnBackInvokedDispatcher primero; si no, onBackPressed. */
    void back(Activity a) {
        if (a != top()) return;
        if (a.backDispatcher.dispatch()) return;
        a.onBackPressed();
    }

    private boolean quitting;

    /** Tras actualizar: cierra con calma (se guarda todo) y abre el AppImage nuevo. */
    public void restart(File appImage) {
        toast("Actualizada. Volviendo a abrir…");
        MainQueue.post(this, () -> {
            relaunch = appImage;
            quit();
        }, 1200, null);
    }

    private File relaunch;

    private void quit() {
        if (quitting) return;
        quitting = true;
        for (int i = stack.size() - 1; i >= 0; i--) {
            Activity a = stack.get(i);
            try {
                if (a.resumed) { a.resumed = false; a.onPause(); }
                if (a.started) { a.started = false; a.onStop(); }
                destroy(a);
            } catch (RuntimeException e) {
                android.util.Log.e("SyncTablet", "al cerrar", e);
            }
        }
        stack.clear();
        quitNow();
    }

    private void quitNow() {
        frame.setVisible(false);
        android.content.Prefs.flush();
        // Lo que quede por guardar en otros hilos tiene un momento.
        Thread t = new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            android.content.Prefs.flush();
            if (relaunch != null) {
                try { new ProcessBuilder(relaunch.getPath()).inheritIO().start(); } catch (Exception e) { android.util.Log.e("SyncTablet", "no se pudo reabrir", e); }
            }
            System.exit(0);
        });
        t.start();
    }

    // ---------------------------------------------------------------- intents del sistema

    private void system(Activity from, Intent intent, int request) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        switch (action) {
            case Intent.ACTION_CHOOSER: {
                Intent inner = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (inner != null) system(from, inner, request);
                return;
            }
            case Intent.ACTION_OPEN_DOCUMENT:
            case Intent.ACTION_GET_CONTENT:
            case Intent.ACTION_PICK: {
                String[] mimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
                boolean multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                FileDialogs.open(frame, intent.getType(), mimes, multiple, files -> deliver(from, request, files));
                return;
            }
            case Intent.ACTION_CREATE_DOCUMENT: {
                String name = intent.getStringExtra(Intent.EXTRA_TITLE);
                FileDialogs.save(frame, name, intent.getType(), file -> deliver(from, request, file == null ? null : java.util.Collections.singletonList(file)));
                return;
            }
            case Intent.ACTION_OPEN_DOCUMENT_TREE:
                FileDialogs.folder(frame, dir -> deliver(from, request, dir == null ? null : java.util.Collections.singletonList(dir)));
                return;
            case MediaStore.ACTION_IMAGE_CAPTURE: {
                // Sin cámara: se elige una foto y se copia donde la app la esperaba.
                Uri out = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                toast("En el PC no hay cámara: elige una foto");
                FileDialogs.open(frame, "image/*", null, false, files -> {
                    if (files == null || files.isEmpty()) { deliver(from, request, null); return; }
                    if (out != null) {
                        File dst = ContentResolver.fileOf(out);
                        try {
                            if (dst != null) Files.copy(files.get(0).toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            else try (InputStream in = Files.newInputStream(files.get(0).toPath()); java.io.OutputStream o = Application.get().getContentResolver().openOutputStream(out)) { in.transferTo(o); }
                        } catch (Exception e) {
                            android.util.Log.w("SyncTablet", "foto", e);
                        }
                        if (from != null && request >= 0) from.onActivityResult(request, Activity.RESULT_OK, new Intent());
                    } else deliver(from, request, files);
                });
                return;
            }
            case Intent.ACTION_SEND:
            case Intent.ACTION_SEND_MULTIPLE:
            case Intent.ACTION_VIEW:
            case Intent.ACTION_EDIT: {
                Uri u = intent.getData();
                if (u == null) {
                    Object s = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (s instanceof Uri) u = (Uri) s;
                }
                if (u == null) {
                    String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                    if (text != null) {
                        android.content.ClipboardManager cm = Application.get().getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("texto", text));
                        toast("Copiado al portapapeles");
                    }
                    return;
                }
                File f = ContentResolver.fileOf(u);
                String target = f != null ? f.getPath() : u.toString();
                if (action.equals(Intent.ACTION_SEND) && f != null) toast("Guardado en " + shortPath(f));
                FileDialogs.openExternally(target);
                return;
            }
            default:
                if (action.startsWith("android.settings.")) { toast("En el PC no hay ajustes de Android"); return; }
                throw new ActivityNotFoundException("No Activity found to handle " + intent);
        }
    }

    private static String shortPath(File f) {
        String home = System.getProperty("user.home");
        String p = f.getPath();
        return p.startsWith(home) ? "~" + p.substring(home.length()) : p;
    }

    private void deliver(Activity from, int request, List<File> files) {
        SwingUtilities.invokeLater(() -> {
            if (from == null || request < 0 || from.destroyed) return;
            if (files == null || files.isEmpty()) {
                from.onActivityResult(request, Activity.RESULT_CANCELED, null);
                return;
            }
            Intent data = new Intent().setData(Uri.fromFile(files.get(0)));
            if (files.size() > 1) {
                android.content.ClipData clip = new android.content.ClipData("archivos", new String[] {"*/*"}, new android.content.ClipData.Item(Uri.fromFile(files.get(0))));
                for (int i = 1; i < files.size(); i++) clip.addItem(new android.content.ClipData.Item(Uri.fromFile(files.get(i))));
                data.setClipData(clip);
            }
            boolean wasResumed = from.resumed;
            if (from == top() && wasResumed) { from.resumed = false; from.onPause(); }
            from.onActivityResult(request, Activity.RESULT_OK, data);
            if (from == top() && wasResumed && !from.finishing) { from.resumed = true; from.onResume(); from.onPostResume(); }
        });
    }

    public void toast(String text) { root.toast(text, 2000); }

    // ---------------------------------------------------------------- la ventana

    private final class Panel extends JComponent implements ViewRoot.Host, InputMethodListener, InputMethodRequests {
        private boolean repaintQueued;

        Panel() {
            setOpaque(true);
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            enableInputMethods(true);
            enableEvents(AWTEvent.KEY_EVENT_MASK | AWTEvent.INPUT_METHOD_EVENT_MASK);
            addInputMethodListener(this);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); if (e.getButton() == 4 || e.getButton() == 6) { backKey(); return; } root.mousePressed(e, e.getX(), e.getY()); }
                @Override public void mouseReleased(MouseEvent e) { if (e.getButton() >= 4) return; root.mouseReleased(e, e.getX(), e.getY()); }
                @Override public void mouseDragged(MouseEvent e) { root.mouseDragged(e, e.getX(), e.getY()); }
                @Override public void mouseMoved(MouseEvent e) { root.mouseMoved(e, e.getX(), e.getY()); }
                @Override public void mouseExited(MouseEvent e) { root.mouseExited(e, e.getX(), e.getY()); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) { root.mouseWheel(e, e.getX(), e.getY()); }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        /**
         * Se dibuja en una imagen en memoria y se manda a la ventana de una vez:
         * dibujar forma a forma sobre la ventana (XRender, también en XWayland)
         * manda cada máscara suavizada al servidor gráfico y va a saltos.
         * -Dsynctablet.buffer=false vuelve a lo de antes, para comparar.
         */
        private final boolean buffered = !"false".equals(System.getProperty("synctablet.buffer"));
        private java.awt.image.BufferedImage back;

        @Override
        protected void paintComponent(Graphics g) {
            repaintQueued = false;
            long t0 = Stats.ON ? System.nanoTime() : 0;
            int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
            Graphics2D g2;
            java.awt.Rectangle dirty = root.beginFrame();
            if (buffered) {
                if (back == null || back.getWidth() != w || back.getHeight() != h) {
                    back = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    dirty = null;
                }
                g2 = back.createGraphics();
            } else {
                g2 = (Graphics2D) g.create();
                dirty = null;
            }
            try {
                if (dirty != null) {
                    // Sólo lo que cambió: el resto de la imagen sigue valiendo.
                    dirty = dirty.intersection(new java.awt.Rectangle(0, 0, w, h));
                    if (dirty.isEmpty()) { g2.dispose(); g.drawImage(back, 0, 0, null); if (Stats.ON) Stats.frame(System.nanoTime() - t0); return; }
                    g2.setClip(dirty);
                }
                java.awt.Rectangle area = dirty != null ? dirty : new java.awt.Rectangle(0, 0, w, h);
                g2.setColor(getBackground() == null ? Color.BLACK : getBackground());
                g2.fillRect(area.x, area.y, area.width, area.height);
                root.paint(g2);
            } catch (Throwable t) {
                android.util.Log.e("SyncTablet", "al dibujar", t);
            } finally {
                g2.dispose();
            }
            if (buffered) g.drawImage(back, 0, 0, null);
            if (Stats.ON) Stats.frame(System.nanoTime() - t0);
        }

        @Override public void repaint() {
            if (Stats.ON) Stats.asked();
            if (repaintQueued) return;
            repaintQueued = true;
            super.repaint();
        }

        @Override public int width() { return getWidth(); }
        @Override public int height() { return getHeight(); }
        @Override public Point screenOffset() { try { return getLocationOnScreen(); } catch (Exception e) { return new Point(); } }

        @Override public boolean dispatchTouch(MotionEvent e) { Activity a = top(); return a != null && a.dispatchTouchEvent(e); }
        @Override public boolean dispatchGeneric(MotionEvent e) { Activity a = top(); return a != null && a.dispatchGenericMotionEvent(e); }
        @Override public boolean dispatchKey(KeyEvent e) { Activity a = top(); return a != null && a.dispatchKeyEvent(e); }

        @Override
        public boolean unhandledKey(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_UP) return false;
            if (e.getKeyCode() == KeyEvent.KEYCODE_F11) { setFullscreen(!fullscreen); return true; }
            return false;
        }

        private void backKey() {
            Activity a = top();
            if (a == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
            if (!a.dispatchKeyEvent(down)) a.onKeyDown(KeyEvent.KEYCODE_BACK, down);
            if (!a.dispatchKeyEvent(up)) a.onKeyUp(KeyEvent.KEYCODE_BACK, up);
        }

        private final java.util.HashMap<Integer, Integer> repeats = new java.util.HashMap<>();
        private final java.util.HashMap<Integer, Long> downTimes = new java.util.HashMap<>();

        @Override
        protected void processKeyEvent(java.awt.event.KeyEvent e) {
            int id = e.getID();
            if (id == java.awt.event.KeyEvent.KEY_TYPED) {
                char ch = e.getKeyChar();
                int mods = e.getModifiersEx();
                boolean ctrl = (mods & (java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.META_DOWN_MASK)) != 0
                        && (mods & java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK) == 0;
                if (ch != java.awt.event.KeyEvent.CHAR_UNDEFINED && ch >= 0x20 && ch != 0x7F && !ctrl) root.typed(String.valueOf(ch));
                e.consume();
                return;
            }
            int vk = e.getKeyCode();
            int code = KeyEvent.fromAwt(vk);
            if (code == KeyEvent.KEYCODE_UNKNOWN) return;
            int meta = KeyEvent.metaFromAwt(e.getModifiersEx());
            long now = android.os.SystemClock.uptimeMillis();
            char kc = e.getKeyChar();
            int unicode = kc != java.awt.event.KeyEvent.CHAR_UNDEFINED && kc >= 0x20 ? kc : 0;
            if (id == java.awt.event.KeyEvent.KEY_PRESSED) {
                int rep = repeats.containsKey(vk) ? repeats.get(vk) + 1 : 0;
                repeats.put(vk, rep);
                if (rep == 0) downTimes.put(vk, now);
                KeyEvent k = new KeyEvent(downTimes.getOrDefault(vk, now), now, KeyEvent.ACTION_DOWN, code, rep, meta, unicode, null);
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    // Esc es Atrás, salvo que alguien lo quiera como Esc.
                    if (!root.key(k)) {
                        KeyEvent b = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, rep, meta);
                        if (rep == 0 && !root.key(b)) { Activity a = top(); if (a != null) a.onKeyDown(KeyEvent.KEYCODE_BACK, b); }
                    }
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && (meta & KeyEvent.META_ALT_ON) != 0) {
                    if (rep == 0) backKey();
                } else root.key(k);
                e.consume();
            } else if (id == java.awt.event.KeyEvent.KEY_RELEASED) {
                repeats.remove(vk);
                KeyEvent k = new KeyEvent(downTimes.getOrDefault(vk, now), now, KeyEvent.ACTION_UP, code, 0, meta, unicode, null);
                downTimes.remove(vk);
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    if (!root.key(k)) {
                        KeyEvent b = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, meta);
                        if (!root.key(b)) { Activity a = top(); if (a != null) a.onKeyUp(KeyEvent.KEYCODE_BACK, b); }
                    }
                } else if (code != KeyEvent.KEYCODE_DPAD_LEFT || (meta & KeyEvent.META_ALT_ON) == 0) root.key(k);
                e.consume();
            }
        }

        // ---- métodos de entrada (acentos con tecla muerta, IBus)

        @Override public InputMethodRequests getInputMethodRequests() { return this; }

        @Override
        public void inputMethodTextChanged(InputMethodEvent e) {
            AttributedCharacterIterator it = e.getText();
            int committed = e.getCommittedCharacterCount();
            StringBuilder done = new StringBuilder(), comp = new StringBuilder();
            if (it != null) {
                int i = 0;
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next(), i++) {
                    if (i < committed) done.append(c); else comp.append(c);
                }
            }
            if (done.length() > 0) root.typed(done.toString());
            root.composing(comp.toString());
            e.consume();
        }

        @Override public void caretPositionChanged(InputMethodEvent e) { e.consume(); }
        @Override public Rectangle getTextLocation(TextHitInfo offset) {
            Rectangle r = root.caretRect();
            Point p = screenOffset();
            r.translate(p.x, p.y);
            return r;
        }
        @Override public TextHitInfo getLocationOffset(int x, int y) { return null; }
        @Override public int getInsertPositionOffset() { return 0; }
        @Override public AttributedCharacterIterator getCommittedText(int b, int e, AttributedCharacterIterator.Attribute[] a) { return new AttributedString("").getIterator(); }
        @Override public int getCommittedTextLength() { return 0; }
        @Override public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] a) { return null; }
        @Override public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] a) { return new AttributedString("").getIterator(); }
    }
}
