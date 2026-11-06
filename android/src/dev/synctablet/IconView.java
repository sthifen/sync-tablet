package dev.synctablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Botón con icono de trazo (estilo de línea, 24 unidades), tocable en
 * 44 dp. Activo: círculo del color de acento con el icono en blanco.
 */
final class IconView extends View {
    static final int BACK = 0, PEN = 1, HIGHLIGHTER = 2, ERASER = 3, SHAPES = 4, UNDO = 5, REDO = 6,
            PREV = 7, NEXT = 8, PLUS = 9, PAGE = 10, HAND = 11, WIFI = 12, LASSO = 13, TEXT = 14, TAPE = 15,
            LASER = 16, IMAGE = 17, PAGES = 18, EXPORT = 19, COPY = 20, TRASH = 21, CHECK = 22, SEARCH = 23, STAR = 24, DOTS = 25, MORE = 26, HOME = 27, MOON = 28, SIDEBAR = 29,
            S_LINE = 30, S_ARROW = 31, S_ARROW2 = 32, S_RECT = 33, S_ELLIPSE = 34, S_TRIANGLE = 35, S_AXES = 36, S_CROSS = 37, WAND = 38, PLAY = 39, SLIDERS = 40, NOTE = 41, CHIP = 42, BACK_ROW = 43, FLIP = 44, CAMERA = 45, FILE = 46, TABLE = 47, PASTE = 48, SLIDES = 49, GRID_DOC = 50;

    private final int icon;
    private final Path path;
    private boolean active;
    private int color;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);

    IconView(Context c, int icon, int color, String label) {
        super(c);
        this.icon = icon;
        this.color = color;
        this.path = build(icon);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.8f);
        disc.setColor(Ui.ACCENT_DEEP);
        setContentDescription(label);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(Ui.dp(c, 44));
        setMinimumHeight(Ui.dp(c, 44));
    }

    void setActive(boolean a) {
        if (active != a) {
            active = a;
            invalidate();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.35f);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Ui.dp(getContext(), 44);
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (active) c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2 - Ui.dp(getContext(), 3), disc);
        float s = Ui.dp(getContext(), 22) / 24f;
        c.save();
        c.translate((w - 24 * s) / 2, (h - 24 * s) / 2);
        c.scale(s, s);
        stroke.setColor(active ? 0xFFFFFFFF : color);
        c.drawPath(path, stroke);
        c.restore();
    }

    private static Path build(int icon) {
        Path p = new Path();
        switch (icon) {
            case BACK:
            case PREV:
                p.moveTo(15, 5); p.lineTo(8, 12); p.lineTo(15, 19);
                break;
            case NEXT:
                p.moveTo(9, 5); p.lineTo(16, 12); p.lineTo(9, 19);
                break;
            case PEN:
                p.moveTo(4, 20); p.lineTo(5.2f, 15.2f); p.lineTo(15.6f, 4.8f);
                p.cubicTo(16.6f, 3.8f, 18.2f, 3.8f, 19.2f, 4.8f);
                p.cubicTo(20.2f, 5.8f, 20.2f, 7.4f, 19.2f, 8.4f);
                p.lineTo(8.8f, 18.8f); p.close();
                p.moveTo(14, 6.5f); p.lineTo(17.5f, 10);
                break;
            case HIGHLIGHTER:
                p.moveTo(9, 13); p.lineTo(15, 4); p.lineTo(20, 7.5f); p.lineTo(14, 16.5f); p.close();
                p.moveTo(9, 13); p.lineTo(7.5f, 17); p.lineTo(11, 18.5f); p.lineTo(14, 16.5f);
                p.moveTo(4, 21); p.lineTo(12, 21);
                break;
            case ERASER:
                p.moveTo(8, 20); p.lineTo(4.5f, 16.5f); p.lineTo(14, 7); p.lineTo(20, 13); p.lineTo(13, 20); p.close();
                p.moveTo(9.3f, 11.7f); p.lineTo(15.3f, 17.7f);
                p.moveTo(13, 20); p.lineTo(20, 20);
                break;
            case SHAPES:
                p.addCircle(8.5f, 8.5f, 4.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(12.5f, 12.5f, 20.5f, 20.5f), 1.5f, 1.5f, Path.Direction.CW);
                p.moveTo(4, 20.5f); p.lineTo(7.5f, 14); p.lineTo(11, 20.5f); p.close();
                break;
            case UNDO:
                p.moveTo(9, 13); p.lineTo(4, 8); p.lineTo(9, 3);
                p.moveTo(4, 8); p.lineTo(14, 8); p.cubicTo(20.5f, 8, 20.5f, 19, 14, 19); p.lineTo(10, 19);
                break;
            case REDO:
                p.moveTo(15, 13); p.lineTo(20, 8); p.lineTo(15, 3);
                p.moveTo(20, 8); p.lineTo(10, 8); p.cubicTo(3.5f, 8, 3.5f, 19, 10, 19); p.lineTo(14, 19);
                break;
            case PLUS:
                p.moveTo(12, 5); p.lineTo(12, 19); p.moveTo(5, 12); p.lineTo(19, 12);
                break;
            case PAGE:
                p.addRoundRect(new RectF(5, 3, 19, 21), 2, 2, Path.Direction.CW);
                p.moveTo(8, 8); p.lineTo(16, 8); p.moveTo(8, 12); p.lineTo(16, 12); p.moveTo(8, 16); p.lineTo(13, 16);
                break;
            case HAND:
                p.moveTo(9, 13); p.lineTo(9, 5.5f); p.cubicTo(9, 3.8f, 11.5f, 3.8f, 11.5f, 5.5f); p.lineTo(11.5f, 11);
                p.moveTo(11.5f, 10); p.cubicTo(11.5f, 8.5f, 14, 8.5f, 14, 10); p.lineTo(14, 11.5f);
                p.moveTo(14, 11); p.cubicTo(14, 9.5f, 16.5f, 9.5f, 16.5f, 11); p.lineTo(16.5f, 15);
                p.cubicTo(16.5f, 19, 14, 21, 11.5f, 21); p.cubicTo(9, 21, 7.5f, 20, 6.3f, 18);
                p.lineTo(4.3f, 14.6f); p.cubicTo(3.6f, 13.4f, 5.2f, 12.2f, 6.3f, 13.2f); p.lineTo(9, 15.5f);
                break;
            case LASSO:
                p.moveTo(7, 17.5f); p.cubicTo(2, 15, 3, 5, 12, 4.5f); p.cubicTo(20, 4, 22.5f, 11, 17, 14.5f);
                p.cubicTo(14, 16.5f, 9.5f, 16, 8, 15);
                p.addCircle(7.5f, 18.5f, 1.8f, Path.Direction.CW);
                p.moveTo(7.5f, 20.3f); p.cubicTo(7, 22, 9, 22.5f, 10, 21.5f);
                break;
            case TEXT:
                p.moveTo(5, 7); p.lineTo(5, 4.5f); p.lineTo(19, 4.5f); p.lineTo(19, 7);
                p.moveTo(12, 4.5f); p.lineTo(12, 19.5f); p.moveTo(9, 19.5f); p.lineTo(15, 19.5f);
                break;
            case TAPE:
                p.moveTo(3, 15); p.lineTo(15, 3); p.lineTo(21, 9); p.lineTo(9, 21); p.close();
                p.moveTo(6, 12); p.lineTo(12, 18); p.moveTo(12, 6); p.lineTo(18, 12);
                break;
            case LASER:
                p.addCircle(12, 12, 3, Path.Direction.CW);
                p.moveTo(12, 3); p.lineTo(12, 5.5f); p.moveTo(12, 18.5f); p.lineTo(12, 21);
                p.moveTo(3, 12); p.lineTo(5.5f, 12); p.moveTo(18.5f, 12); p.lineTo(21, 12);
                p.moveTo(5.6f, 5.6f); p.lineTo(7.4f, 7.4f); p.moveTo(16.6f, 16.6f); p.lineTo(18.4f, 18.4f);
                p.moveTo(18.4f, 5.6f); p.lineTo(16.6f, 7.4f); p.moveTo(7.4f, 16.6f); p.lineTo(5.6f, 18.4f);
                break;
            case IMAGE:
                p.addRoundRect(new RectF(3.5f, 4.5f, 20.5f, 19.5f), 2.5f, 2.5f, Path.Direction.CW);
                p.addCircle(9, 9.5f, 1.6f, Path.Direction.CW);
                p.moveTo(4, 17); p.lineTo(9.5f, 12.5f); p.lineTo(13, 15.5f); p.lineTo(16, 13); p.lineTo(20, 16.5f);
                break;
            case PAGES:
                p.addRoundRect(new RectF(4, 4, 11, 11), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(13, 4, 20, 11), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(4, 13, 11, 20), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(13, 13, 20, 20), 1.5f, 1.5f, Path.Direction.CW);
                break;
            case EXPORT:
                p.moveTo(12, 15); p.lineTo(12, 3.5f); p.moveTo(8, 7.5f); p.lineTo(12, 3.5f); p.lineTo(16, 7.5f);
                p.moveTo(8, 10.5f); p.lineTo(6, 10.5f); p.lineTo(6, 20); p.lineTo(18, 20); p.lineTo(18, 10.5f); p.lineTo(16, 10.5f);
                break;
            case COPY:
                p.addRoundRect(new RectF(8, 8, 19, 19), 2, 2, Path.Direction.CW);
                p.moveTo(16, 8); p.lineTo(16, 6); p.cubicTo(16, 5, 15.5f, 5, 15, 5); p.lineTo(6, 5);
                p.cubicTo(5.5f, 5, 5, 5.5f, 5, 6); p.lineTo(5, 15); p.cubicTo(5, 15.5f, 5.5f, 16, 6, 16); p.lineTo(8, 16);
                break;
            case TRASH:
                p.moveTo(4, 7); p.lineTo(20, 7); p.moveTo(9, 7); p.lineTo(9, 4.5f); p.lineTo(15, 4.5f); p.lineTo(15, 7);
                p.moveTo(6, 7); p.lineTo(7, 20); p.lineTo(17, 20); p.lineTo(18, 7);
                p.moveTo(10, 11); p.lineTo(10, 16.5f); p.moveTo(14, 11); p.lineTo(14, 16.5f);
                break;
            case CHECK:
                p.moveTo(5, 12.5f); p.lineTo(10, 17.5f); p.lineTo(19, 7);
                break;
            case SEARCH:
                p.addCircle(10.5f, 10.5f, 6, Path.Direction.CW);
                p.moveTo(15, 15); p.lineTo(20, 20);
                break;
            case DOTS: // asa de un bloque: dos columnas de puntos
                for (int r = 0; r < 3; r++) for (int col = 0; col < 2; col++) p.addCircle(9.5f + 5 * col, 6.5f + 5.5f * r, 0.9f, Path.Direction.CW);
                break;
            case MORE:
                for (int col = 0; col < 3; col++) p.addCircle(6 + 6 * col, 12, 1f, Path.Direction.CW);
                break;
            case HOME:
                p.moveTo(4, 11); p.lineTo(12, 4); p.lineTo(20, 11);
                p.moveTo(6.5f, 9.5f); p.lineTo(6.5f, 20); p.lineTo(17.5f, 20); p.lineTo(17.5f, 9.5f);
                p.moveTo(10, 20); p.lineTo(10, 14.5f); p.lineTo(14, 14.5f); p.lineTo(14, 20);
                break;
            case MOON:
                p.moveTo(19.5f, 14.5f);
                p.cubicTo(15.5f, 16.5f, 9, 13.5f, 9.5f, 7.5f);
                p.cubicTo(9.6f, 6.2f, 10, 5, 10.6f, 4);
                p.cubicTo(6.6f, 5, 4, 8.5f, 4.3f, 12.6f);
                p.cubicTo(4.7f, 17.2f, 8.6f, 20.6f, 13.2f, 20.3f);
                p.cubicTo(16, 20.1f, 18.4f, 18.6f, 19.5f, 14.5f);
                break;
            case S_LINE:
                p.moveTo(5, 19); p.lineTo(19, 5);
                break;
            case S_ARROW:
                p.moveTo(5, 19); p.lineTo(19, 5); p.moveTo(11.5f, 5); p.lineTo(19, 5); p.lineTo(19, 12.5f);
                break;
            case S_ARROW2:
                p.moveTo(5, 19); p.lineTo(19, 5); p.moveTo(13, 5); p.lineTo(19, 5); p.lineTo(19, 11);
                p.moveTo(5, 13); p.lineTo(5, 19); p.lineTo(11, 19);
                break;
            case S_RECT:
                p.addRect(new RectF(4, 6.5f, 20, 17.5f), Path.Direction.CW);
                break;
            case S_ELLIPSE:
                p.addOval(new RectF(3.5f, 6, 20.5f, 18), Path.Direction.CW);
                break;
            case S_TRIANGLE:
                p.moveTo(12, 5); p.lineTo(20.5f, 19); p.lineTo(3.5f, 19); p.close();
                break;
            case S_AXES:
                p.moveTo(2.5f, 7); p.lineTo(5, 4); p.lineTo(7.5f, 7); p.moveTo(5, 4); p.lineTo(5, 19); p.lineTo(20, 19);
                p.moveTo(17, 16.5f); p.lineTo(20, 19); p.lineTo(17, 21.5f);
                break;
            case S_CROSS:
                p.moveTo(12, 21); p.lineTo(12, 3); p.moveTo(9.5f, 5.5f); p.lineTo(12, 3); p.lineTo(14.5f, 5.5f);
                p.moveTo(3, 12); p.lineTo(21, 12); p.moveTo(18.5f, 9.5f); p.lineTo(21, 12); p.lineTo(18.5f, 14.5f);
                break;
            case NOTE:
                p.moveTo(5, 4.5f); p.lineTo(19, 4.5f); p.lineTo(19, 14.5f); p.lineTo(14, 19.5f); p.lineTo(5, 19.5f); p.close();
                p.moveTo(19, 14.5f); p.lineTo(14, 14.5f); p.lineTo(14, 19.5f);
                break;
            case CHIP: // una resistencia
                p.moveTo(2.5f, 12); p.lineTo(6, 12); p.lineTo(7.7f, 7); p.lineTo(11, 17); p.lineTo(14, 7); p.lineTo(17, 17); p.lineTo(18.3f, 12); p.lineTo(21.5f, 12);
                break;
            case CAMERA:
                p.addRoundRect(new RectF(3.5f, 7, 20.5f, 19), 2.5f, 2.5f, Path.Direction.CW);
                p.addCircle(12, 13, 3.4f, Path.Direction.CW);
                p.moveTo(8.5f, 7); p.lineTo(10, 4.5f); p.lineTo(14, 4.5f); p.lineTo(15.5f, 7);
                break;
            case FILE:
                p.moveTo(6, 3.5f); p.lineTo(14, 3.5f); p.lineTo(18.5f, 8); p.lineTo(18.5f, 20.5f); p.lineTo(6, 20.5f); p.close();
                p.moveTo(14, 3.5f); p.lineTo(14, 8); p.lineTo(18.5f, 8);
                p.moveTo(9, 13); p.lineTo(15.5f, 13); p.moveTo(9, 16.5f); p.lineTo(15.5f, 16.5f);
                break;
            case TABLE:
                p.addRoundRect(new RectF(3.5f, 5, 20.5f, 19), 1.5f, 1.5f, Path.Direction.CW);
                p.moveTo(3.5f, 9.7f); p.lineTo(20.5f, 9.7f); p.moveTo(3.5f, 14.3f); p.lineTo(20.5f, 14.3f);
                p.moveTo(9.2f, 5); p.lineTo(9.2f, 19); p.moveTo(14.8f, 5); p.lineTo(14.8f, 19);
                break;
            case PASTE:
                p.addRoundRect(new RectF(5, 5, 19, 20.5f), 2, 2, Path.Direction.CW);
                p.addRoundRect(new RectF(9, 3, 15, 7), 1, 1, Path.Direction.CW);
                break;
            case SLIDES:
                p.addRoundRect(new RectF(3.5f, 4.5f, 20.5f, 15.5f), 1.5f, 1.5f, Path.Direction.CW);
                p.moveTo(12, 15.5f); p.lineTo(12, 19); p.moveTo(8.5f, 20); p.lineTo(15.5f, 20);
                break;
            case GRID_DOC:
                p.moveTo(6, 3.5f); p.lineTo(18, 3.5f); p.lineTo(18, 20.5f); p.lineTo(6, 20.5f); p.close();
                p.moveTo(9, 8); p.lineTo(15, 8); p.moveTo(9, 12); p.lineTo(15, 12); p.moveTo(9, 16); p.lineTo(13, 16);
                break;
            case FLIP:
                p.moveTo(12, 3.5f); p.lineTo(12, 20.5f);
                p.moveTo(9, 7); p.lineTo(4, 17); p.lineTo(9, 17); p.close();
                p.moveTo(15, 7); p.lineTo(20, 17); p.lineTo(15, 17); p.close();
                break;
            case BACK_ROW:
                p.addCircle(8.5f, 8.5f, 4.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(12.5f, 12.5f, 20.5f, 20.5f), 1.5f, 1.5f, Path.Direction.CW);
                break;
            case SLIDERS:
                p.moveTo(4, 7); p.lineTo(20, 7); p.moveTo(4, 12); p.lineTo(20, 12); p.moveTo(4, 17); p.lineTo(20, 17);
                p.addCircle(9, 7, 2, Path.Direction.CW);
                p.addCircle(15, 12, 2, Path.Direction.CW);
                p.addCircle(8, 17, 2, Path.Direction.CW);
                break;
            case PLAY:
                p.moveTo(7, 4.5f); p.lineTo(19.5f, 12); p.lineTo(7, 19.5f); p.close();
                break;
            case WAND:
                p.moveTo(4, 20); p.lineTo(15, 9);
                p.moveTo(17.5f, 3); p.lineTo(17.5f, 7); p.moveTo(15.5f, 5); p.lineTo(19.5f, 5);
                p.moveTo(20, 11); p.lineTo(20, 14); p.moveTo(18.5f, 12.5f); p.lineTo(21.5f, 12.5f);
                break;
            case SIDEBAR:
                p.addRoundRect(new RectF(3.5f, 5, 20.5f, 19), 2.5f, 2.5f, Path.Direction.CW);
                p.moveTo(9.5f, 5); p.lineTo(9.5f, 19);
                break;
            case STAR:
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 8.5f : 3.6f;
                    float x = 12 + r * (float) Math.cos(a), y = 12.5f + r * (float) Math.sin(a);
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                p.close();
                break;
            case WIFI:
                p.arcTo(new RectF(2.5f, 8.5f, 21.5f, 27.5f), 225, 90, true);
                p.arcTo(new RectF(6, 12, 18, 24), 225, 90, true);
                p.arcTo(new RectF(9.5f, 15.5f, 14.5f, 20.5f), 225, 90, true);
                p.addCircle(12, 20.5f, 0.6f, Path.Direction.CW);
                break;
        }
        return p;
    }
}
