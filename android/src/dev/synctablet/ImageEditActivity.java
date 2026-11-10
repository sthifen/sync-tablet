package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editar una imagen de la hoja: girar, recortar, ajustar y limpiar documentos.
 * La vista previa pasa por el mismo motor que el resultado (Native.imageEdit),
 * sobre una copia pequeña para que responda al mover los controles.
 */
public final class ImageEditActivity extends Activity {
    private static final int PREVIEW_SIDE = 900;
    private static final int BACKGROUND = 0xFF15181D, PANEL = 0xFF23272E, TEXT = 0xFFE7EAEE, ACCENT = 0xFF2DD4BF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refreshNow;
    private byte[] full, preview;
    private int turns;
    private boolean document;
    private SeekBar brightness, contrast, saturation;
    private TextView documentButton;
    private CropView crop;
    /** Sube cada vez que algo cambia: una vista previa vieja se descarta. */
    private volatile int generation;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String asset = getIntent().getStringExtra("asset");
        File file = asset == null ? null : Store.get(this).assetFile(asset);
        try {
            if (file == null || !Native.available()) throw new IOException("sin imagen");
            full = Files.readAllBytes(file.toPath());
            preview = smallCopy(full);
        } catch (IOException | OutOfMemoryError e) {
            Ui.toast(this, "No se pudo abrir la imagen");
            finish();
            return;
        }
        crop = new CropView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        // La pantalla llega bajo las barras del sistema: los controles no.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            @SuppressWarnings("deprecation") int top = in.getSystemWindowInsetTop();
            @SuppressWarnings("deprecation") int bottom = in.getSystemWindowInsetBottom();
            @SuppressWarnings("deprecation") int left = in.getSystemWindowInsetLeft();
            @SuppressWarnings("deprecation") int right = in.getSystemWindowInsetRight();
            v.setPadding(left, top, right, bottom);
            return in;
        });
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(controls(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        refreshNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    private static byte[] smallCopy(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > PREVIEW_SIDE * 2) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        float k = Math.min(1, PREVIEW_SIDE / (float) Math.max(b.getWidth(), b.getHeight()));
        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    private TextView button(String text, boolean strong, View.OnClickListener click) {
        TextView t = Ui.text(this, text, 15, strong ? BACKGROUND : TEXT, strong);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(this, 44));
        t.setMinWidth(Ui.dp(this, 96));
        t.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        t.setBackground(Ui.round(strong ? ACCENT : PANEL, Ui.dp(this, 12)));
        t.setOnClickListener(click);
        return t;
    }

    private View topBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, 0, pad, 0);
        row.addView(button("Cancelar", false, v -> finish()));
        TextView title = Ui.text(this, "Editar imagen", 16, TEXT, true);
        title.setGravity(Gravity.CENTER);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("Aplicar", true, v -> apply()));
        return row;
    }

    private View controls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, Ui.dp(this, 6), pad, pad);
        brightness = slider(box, "Brillo");
        contrast = slider(box, "Contraste");
        saturation = slider(box, "Color");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        row.addView(button("Girar", false, v -> {
            turns = (turns + 1) % 4;
            crop.reset();
            schedule();
        }), gap);
        documentButton = button("Documento", false, v -> {
            document = !document;
            paintDocument();
            schedule();
        });
        row.addView(documentButton, gap);
        row.addView(button("Restablecer", false, v -> {
            brightness.setProgress(100);
            contrast.setProgress(100);
            saturation.setProgress(100);
            turns = 0;
            document = false;
            paintDocument();
            crop.reset();
            schedule();
        }), gap);
        box.addView(row);
        return box;
    }

    private void paintDocument() {
        documentButton.setTextColor(document ? BACKGROUND : TEXT);
        documentButton.setBackground(Ui.round(document ? ACCENT : PANEL, Ui.dp(this, 12)));
    }

    /** Un control de −100 a 100, con el cero en medio. */
    private SeekBar slider(LinearLayout box, String name) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, name, 14, TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(this, 96), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setProgress(100);
        bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) schedule(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        box.addView(row);
        return bar;
    }

    private static float value(SeekBar bar) {
        return bar == null ? 0 : bar.getProgress() - 100;
    }

    private void schedule() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 40);
    }

    private void refreshNow() {
        int mine = ++generation;
        float b = value(brightness), c = value(contrast), s = value(saturation);
        int t = turns;
        boolean doc = document;
        worker.execute(() -> {
            if (mine != generation) return;
            byte[] out = Native.imageEdit(preview, b, c, s, t, 0, 0, 1, 1, doc);
            if (out == null || mine != generation) return;
            Bitmap shown = BitmapFactory.decodeByteArray(out, 0, out.length);
            main.post(() -> {
                if (mine == generation) crop.setImage(shown);
            });
        });
    }

    private void apply() {
        float b = value(brightness), c = value(contrast), s = value(saturation);
        RectF area = crop.area();
        int t = turns;
        boolean doc = document;
        Ui.toast(this, "Aplicando…");
        worker.execute(() -> {
            String id = null;
            int[] size = new int[2];
            try {
                byte[] out = Native.imageEdit(full, b, c, s, t, area.left, area.top, area.right, area.bottom, doc);
                if (out != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(out, 0, out.length, o);
                    size[0] = o.outWidth;
                    size[1] = o.outHeight;
                    id = Store.get(this).putAssetBytes(out);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "editar imagen", e);
            }
            String done = id;
            main.post(() -> {
                if (done == null) {
                    Ui.toast(this, "No se pudo editar la imagen");
                    return;
                }
                setResult(RESULT_OK, new Intent().putExtra("asset", done).putExtra("w", size[0]).putExtra("h", size[1]));
                finish();
            });
        });
    }

    /** La imagen con un marco de recorte: se arrastran sus lados y esquinas. */
    private static final class CropView extends View {
        private static final float MIN_SIZE = 0.05f;
        private final Paint dim = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float reach;
        private Bitmap image;
        private final RectF shown = new RectF();
        private final RectF area = new RectF(0, 0, 1, 1);
        private boolean left, top, right, bottom;

        CropView(Context c) {
            super(c);
            dim.setColor(0x99000000);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(Ui.dp(c, 2));
            frame.setColor(Color.WHITE);
            handle.setColor(ACCENT);
            reach = Ui.dp(c, 28);
        }

        void setImage(Bitmap b) {
            image = b;
            invalidate();
        }

        void reset() {
            area.set(0, 0, 1, 1);
            invalidate();
        }

        RectF area() {
            return new RectF(area);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (image == null) return;
            int pad = Ui.dp(getContext(), 20);
            float k = Math.min((getWidth() - 2f * pad) / image.getWidth(), (getHeight() - 2f * pad) / image.getHeight());
            float w = image.getWidth() * k, h = image.getHeight() * k;
            shown.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            c.drawBitmap(image, null, shown, null);
            float l = shown.left + area.left * w, t = shown.top + area.top * h, r = shown.left + area.right * w, b = shown.top + area.bottom * h;
            c.drawRect(shown.left, shown.top, shown.right, t, dim);
            c.drawRect(shown.left, b, shown.right, shown.bottom, dim);
            c.drawRect(shown.left, t, l, b, dim);
            c.drawRect(r, t, shown.right, b, dim);
            c.drawRect(l, t, r, b, frame);
            float dot = Ui.dp(getContext(), 7);
            for (float x : new float[] {l, r}) for (float y : new float[] {t, b}) c.drawCircle(x, y, dot, handle);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (shown.isEmpty()) return false;
            float x = (e.getX() - shown.left) / shown.width(), y = (e.getY() - shown.top) / shown.height();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float rx = reach / shown.width(), ry = reach / shown.height();
                left = Math.abs(x - area.left) < rx;
                right = !left && Math.abs(x - area.right) < rx;
                top = Math.abs(y - area.top) < ry;
                bottom = !top && Math.abs(y - area.bottom) < ry;
                return left || right || top || bottom;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (left) area.left = Math.max(0, Math.min(x, area.right - MIN_SIZE));
                if (right) area.right = Math.min(1, Math.max(x, area.left + MIN_SIZE));
                if (top) area.top = Math.max(0, Math.min(y, area.bottom - MIN_SIZE));
                if (bottom) area.bottom = Math.min(1, Math.max(y, area.top + MIN_SIZE));
                invalidate();
            }
            return true;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Ui.applyFullscreen(this);
    }
}
