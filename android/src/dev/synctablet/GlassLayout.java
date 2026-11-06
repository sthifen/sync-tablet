package dev.synctablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

/**
 * Panel de cristal líquido: difumina lo que tiene detrás, lo tiñe, le da un
 * brillo arriba y un borde de luz, y flota con sombra.
 *
 * Android no deja difuminar lo que hay detrás de una vista dentro de la
 * misma ventana, así que se hace a mano: se dibuja la vista de detrás
 * (`source`) en un bitmap 12 veces más pequeño y se estira con filtrado
 * bilineal, que es un desenfoque barato.
 */
final class GlassLayout extends LinearLayout {
    private static final int DOWN = 12;

    private final View source;
    private final int tint;
    private final float radius;
    int maxWidth = Integer.MAX_VALUE;

    private Bitmap small;
    private Canvas smallCanvas;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path edge = new Path();
    private final RectF box = new RectF();
    private final int[] me = new int[2], src = new int[2];
    private static boolean drawingSource;
    /** Android 12 en adelante: el desenfoque lo hace la GPU sobre las texturas de la hoja. */
    private android.graphics.RenderNode blurred;

    /** `tint`: color del cristal con su opacidad (p. ej. blanco al 55 %). */
    GlassLayout(Context c, View source, int tint, float radiusPx) {
        super(c);
        this.source = source;
        this.tint = tint;
        this.radius = radiusPx;
        setWillNotDraw(false);
        fill.setColor(tint);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(c, 1.2f));
        setElevation(Ui.dp(c, 12));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                o.setAlpha(0.3f);
            }
        });
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        if (maxWidth < Integer.MAX_VALUE && (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED || size > maxWidth)) {
            w = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        }
        super.onMeasure(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        box.set(0, 0, w, h);
        shape.reset();
        shape.addRoundRect(box, radius, radius, Path.Direction.CW);
        float half = rim.getStrokeWidth() / 2;
        edge.reset();
        edge.addRoundRect(new RectF(half, half, w - half, h - half), radius - half, radius - half, Path.Direction.CW);
        boolean light = ((tint >>> 24) & 0xFF) > 0x60 && (tint & 0xFF) > 0xC0;
        sheen.setShader(new LinearGradient(0, 0, 0, h, new int[] {light ? 0x66FFFFFF : 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, 0, h, light ? 0xF2FFFFFF : 0x80FFFFFF, light ? 0x55FFFFFF : 0x14FFFFFF,
                Shader.TileMode.CLAMP));
        if (android.os.Build.VERSION.SDK_INT >= 29) return;
        if (small != null) small.recycle();
        small = Bitmap.createBitmap(Math.max(1, w / DOWN), Math.max(1, h / DOWN), Bitmap.Config.ARGB_8888);
        smallCanvas = new Canvas(small);
    }

    private void drawBlurred(Canvas c) {
        if (blurred == null) {
            blurred = new android.graphics.RenderNode("glass");
            float r = Ui.dp(getContext(), 12);
            blurred.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP));
        }
        int w = getWidth(), h = getHeight();
        blurred.setPosition(0, 0, w, h);
        Canvas r = blurred.beginRecording(w, h);
        try {
            getLocationInWindow(me);
            source.getLocationInWindow(src);
            r.translate(src[0] - me[0], src[1] - me[1]);
            ((InkView) source).drawGlassSource(r);
        } finally {
            blurred.endRecording();
        }
        c.drawRenderNode(blurred);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        c.save();
        c.clipPath(shape);
        // Un cristal dentro de la vista de detrás no se dibuja a sí mismo.
        if (android.os.Build.VERSION.SDK_INT >= 31 && source instanceof InkView && c.isHardwareAccelerated()) {
            drawBlurred(c);
        } else if (source != null && small != null && !drawingSource) {
            getLocationInWindow(me);
            source.getLocationInWindow(src);
            small.eraseColor(0);
            smallCanvas.save();
            smallCanvas.scale(1f / DOWN, 1f / DOWN);
            smallCanvas.translate(src[0] - me[0], src[1] - me[1]);
            drawingSource = true;
            try {
                source.draw(smallCanvas);
            } finally {
                drawingSource = false;
            }
            smallCanvas.restore();
            c.drawBitmap(small, null, box, blit);
        }
        c.drawRect(box, fill);
        c.drawRect(box, sheen);
        c.restore();
        c.drawPath(edge, rim);
        super.dispatchDraw(c);
    }
}
