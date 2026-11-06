package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;

/**
 * Presentar el cuaderno a pantalla completa, con lo escrito encima. De una
 * página con diapositiva sólo se ve la diapositiva. Se avanza tocando a la
 * derecha, deslizando, o con las teclas de un mando de presentaciones.
 */
public final class PresentActivity extends Activity {
    private final ArrayList<Ink.Page> pages = new ArrayList<>();
    private int index;
    private Stage stage;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Store store = Store.get(this);
        Ink.Notebook notebook = store.notebook(getIntent().getStringExtra("notebook"));
        if (notebook == null) {
            finish();
            return;
        }
        for (String id : notebook.pages) {
            Ink.Page p = store.page(id);
            if (p != null) pages.add(p);
        }
        if (pages.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(pages.size() - 1, getIntent().getIntExtra("index", 0)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stage = new Stage(this);
        setContentView(stage);
        stage.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        Ui.toast(this, "Toca a la derecha para avanzar y a la izquierda para volver");
    }

    private void go(int step) {
        int next = index + step;
        if (next < 0) return;
        if (next >= pages.size()) {
            finish();
            return;
        }
        index = next;
        stage.invalidate();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                go(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                go(-1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private final class Stage extends View {
        private final PageRenderer renderer;
        private final Paint counter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector gestures;

        Stage(Context c) {
            super(c);
            renderer = new PageRenderer(c);
            counter.setColor(0x99FFFFFF);
            counter.setTextSize(Ui.dp(c, 13));
            counter.setTextAlign(Paint.Align.RIGHT);
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    go(e.getX() < getWidth() / 3f ? -1 : 1);
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (Math.abs(vx) < Math.abs(vy)) return false;
                    go(vx < 0 ? 1 : -1);
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return gestures.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            Ink.Page page = pages.get(index);
            RectF area = renderer.contentRect(page);
            float k = Math.min(getWidth() / area.width(), getHeight() / area.height());
            float w = area.width() * k, h = area.height() * k;
            c.save();
            c.translate((getWidth() - w) / 2, (getHeight() - h) / 2);
            c.clipRect(0, 0, w, h);
            c.scale(k, k);
            c.translate(-area.left, -area.top);
            renderer.drawPage(c, page, k, null, null);
            c.restore();
            c.drawText((index + 1) + " / " + pages.size(), getWidth() - Ui.dp(getContext(), 16),
                    getHeight() - Ui.dp(getContext(), 14), counter);
        }
    }
}
