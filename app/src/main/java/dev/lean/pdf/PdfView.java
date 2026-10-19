package dev.lean.pdf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;

/**
 * Continuous vertical PDF view: fit-to-width pages, pinch/double-tap zoom, fling.
 * All PdfRenderer access happens on one background thread (PdfRenderer isn't thread-safe).
 */
public final class PdfView extends View {

    public interface Listener {
        void onLoaded();
        void onError(String message);
    }

    private static final float MAX_ZOOM = 6f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final float gap;
    private final Paint blankPaint = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final HandlerThread thread = new HandlerThread("pdf-render");
    private final Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> cache;
    private final int maxBitmapPixels;
    private final HashSet<Integer> pending = new HashSet<>();

    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private ValueAnimator zoomAnim;

    // Render-thread state
    private PdfRenderer renderer;
    private ParcelFileDescriptor fd;
    private File tempFile;

    // UI-thread state
    private volatile int generation;
    private volatile int visFirst, visLast;
    private float[] aspect;      // page height / width
    private float[] tops;        // page tops at zoom 1, in px
    private float pageW, contentH;
    private int insetL, insetT, insetR, insetB;
    private float zoom = 1f, sx, sy;
    private float lastFx, lastFy;
    private boolean scaling;
    private float pendingProgress = -1f;
    private long indicatorUntil;
    private final Runnable hideIndicator = this::invalidate;

    // Load timer: set when a file starts opening, cleared once its first page is on screen.
    private long openStartedAt;
    private String notice;
    private long noticeUntil;

    public PdfView(Context ctx) {
        super(ctx);
        float dp = getResources().getDisplayMetrics().density;
        gap = 6 * dp;
        blankPaint.setColor(Color.WHITE);
        pillPaint.setColor(0xCC000000);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);

        thread.start();
        bg = new Handler(thread.getLooper());

        int cacheBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 256L << 20);
        maxBitmapPixels = Math.min(8_000_000, cacheBytes / 4 / 3); // at least 3 pages always fit
        cache = new LruCache<Integer, Bitmap>(cacheBytes) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
        };

        scroller = new OverScroller(ctx);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling) return true;
                sx += dx;
                sy += dy;
                clamp();
                showIndicator();
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling) return true;
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy,
                        0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.05f ? 1f : DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                return true;
            }
        });
        scaler = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                if (zoomAnim != null) zoomAnim.cancel();
                scroller.forceFinished(true);
                scaling = true;
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float nz = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                float fx = d.getFocusX(), fy = d.getFocusY();
                // Keep the content point under the previous focus under the new focus (zoom + pan).
                sx = (sx + lastFx) * nz / zoom - fx;
                sy = (sy + lastFy) * nz / zoom - fy;
                zoom = nz;
                lastFx = fx;
                lastFy = fy;
                clamp();
                invalidate();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                invalidate(); // triggers sharper re-render at the new zoom
            }
        });

        setOnApplyWindowInsetsListener((v, in) -> {
            insetL = in.getSystemWindowInsetLeft();
            insetT = in.getSystemWindowInsetTop();
            insetR = in.getSystemWindowInsetRight();
            insetB = in.getSystemWindowInsetBottom();
            float p = progress();
            relayout();
            setProgress(p);
            invalidate();
            return in;
        });
    }

    // ---------------------------------------------------------------- opening / closing

    public void open(ContentResolver cr, Uri uri, File temp, Listener listener) {
        final int gen = ++generation;
        openStartedAt = SystemClock.uptimeMillis();
        cache.evictAll();
        pending.clear();
        aspect = null;
        tops = null;
        zoom = 1f;
        sx = sy = 0f;
        invalidate();

        bg.post(() -> {
            closeRenderer();
            try {
                try {
                    fd = cr.openFileDescriptor(uri, "r");
                    renderer = new PdfRenderer(fd);
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception notSeekable) {
                    // Some providers hand out pipes; PdfRenderer needs a seekable file. Copy it.
                    closeRenderer();
                    try (InputStream in = cr.openInputStream(uri);
                         OutputStream out = new FileOutputStream(temp)) {
                        if (in == null) throw notSeekable;
                        byte[] buf = new byte[64 * 1024];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                    tempFile = temp;
                    fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                    renderer = new PdfRenderer(fd);
                }
                int n = renderer.getPageCount();
                if (n == 0) throw new IllegalStateException("empty");
                final float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    PdfRenderer.Page p = renderer.openPage(i);
                    a[i] = (float) p.getHeight() / p.getWidth();
                    p.close();
                }
                ui.post(() -> {
                    if (gen != generation) return;
                    aspect = a;
                    relayout();
                    if (pendingProgress >= 0) setProgress(pendingProgress);
                    pendingProgress = -1f;
                    invalidate();
                    listener.onLoaded();
                });
            } catch (Exception e) {
                closeRenderer();
                final String msg = e instanceof SecurityException
                        ? "Can't open this PDF (it may be password-protected)"
                        : "Can't open this PDF";
                ui.post(() -> { if (gen == generation) listener.onError(msg); });
            }
        });
    }

    /** Call from Activity.onDestroy. */
    public void close() {
        generation++;
        bg.post(this::closeRenderer);
        thread.quitSafely();
        cache.evictAll();
    }

    private void closeRenderer() { // render thread
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
        try { if (fd != null) fd.close(); } catch (Exception ignored) { }
        renderer = null;
        fd = null;
        if (tempFile != null) { tempFile.delete(); tempFile = null; }
    }

    // ---------------------------------------------------------------- position save/restore

    /** Scroll position as a 0..1 fraction of the document. */
    public float progress() {
        return contentH > 0 ? sy / (contentH * zoom) : 0f;
    }

    public void setPendingProgress(float p) { pendingProgress = p; }

    private void setProgress(float p) {
        sy = p * contentH * zoom;
        clamp();
    }

    // ---------------------------------------------------------------- layout

    private void relayout() {
        if (aspect == null || getWidth() == 0) return;
        pageW = getWidth() - insetL - insetR - 2 * gap;
        tops = new float[aspect.length];
        float y = insetT + gap;
        for (int i = 0; i < aspect.length; i++) {
            tops[i] = y;
            y += pageW * aspect[i] + gap;
        }
        contentH = y + insetB;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float p = progress();
        relayout();
        setProgress(p);
    }

    private float maxX() { return Math.max(0, getWidth() * zoom - getWidth()); }
    private float maxY() { return Math.max(0, contentH * zoom - getHeight()); }

    private void clamp() {
        sx = Math.max(0, Math.min(sx, maxX()));
        sy = Math.max(0, Math.min(sy, maxY()));
    }

    private int pageAt(float y) { // largest i with tops[i] <= y
        int lo = 0, hi = tops.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tops[mid] <= y) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        if (tops == null) return;
        int first = pageAt(sy / zoom);
        int last = pageAt((sy + getHeight()) / zoom);
        visFirst = first;
        visLast = last;

        c.save();
        c.translate(-sx, -sy);
        c.scale(zoom, zoom);
        float left = insetL + gap;
        for (int i = first; i <= last; i++) {
            rect.set(left, tops[i], left + pageW, tops[i] + pageW * aspect[i]);
            Bitmap b = cache.get(i);
            if (b != null) {
                c.drawBitmap(b, null, rect, bitmapPaint);
                if (openStartedAt > 0) showLoadTime();
            } else {
                c.drawRect(rect, blankPaint);
            }
            int want = targetWidth(i, zoom);
            if (b == null || (!scaling && b.getWidth() < want * 0.9f)) request(i, want);
        }
        c.restore();

        // Prefetch the next page at base resolution so scrolling down never shows blanks.
        if (!scaling && last + 1 < tops.length && cache.get(last + 1) == null) {
            request(last + 1, targetWidth(last + 1, 1f));
        }

        // Bottom pill: the load-time notice wins while it's up, otherwise the page number.
        long now = SystemClock.uptimeMillis();
        if (now < noticeUntil) {
            drawPill(c, notice);
        } else if (now < indicatorUntil && tops.length > 1) {
            drawPill(c, (pageAt((sy + getHeight() / 2f) / zoom) + 1) + " / " + tops.length);
        }
    }

    private void drawPill(Canvas c, String s) {
        float tw = textPaint.measureText(s), th = textPaint.getTextSize();
        float cx = getWidth() / 2f, by = getHeight() - insetB - th * 1.5f;
        rect.set(cx - tw / 2 - th, by - th * 1.4f, cx + tw / 2 + th, by + th * 0.6f);
        c.drawRoundRect(rect, th, th, pillPaint);
        c.drawText(s, cx, by, textPaint);
    }

    /** Called on the first frame that shows a rendered page after open(). */
    private void showLoadTime() {
        long ms = SystemClock.uptimeMillis() - openStartedAt;
        openStartedAt = 0;
        notice = "Opened in " + ms + " ms";
        noticeUntil = SystemClock.uptimeMillis() + 2000;
        removeCallbacks(hideIndicator);
        postDelayed(hideIndicator, 2050);
    }

    private int targetWidth(int i, float z) {
        float w = pageW * z;
        float cap = (float) Math.sqrt(maxBitmapPixels / aspect[i]);
        return Math.max(1, Math.round(Math.min(w, cap)));
    }

    private void request(final int i, final int w) {
        if (!pending.add(i)) return;
        final int gen = generation;
        final int h = Math.max(1, Math.round(w * aspect[i]));
        bg.post(() -> {
            Bitmap b = null;
            // Skip work for pages that scrolled out of view before we got to them.
            if (gen == generation && renderer != null && i >= visFirst - 1 && i <= visLast + 1) {
                PdfRenderer.Page p = null;
                try {
                    b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    p = renderer.openPage(i);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Throwable t) {
                    b = null;
                } finally {
                    if (p != null) p.close();
                }
            }
            final Bitmap result = b;
            ui.post(() -> {
                if (gen != generation) return;
                pending.remove(i);
                if (result != null) {
                    cache.put(i, result);
                    invalidate();
                }
            });
        });
    }

    private void showIndicator() {
        indicatorUntil = SystemClock.uptimeMillis() + 1200;
        removeCallbacks(hideIndicator);
        postDelayed(hideIndicator, 1250);
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.getCurrX();
            sy = scroller.getCurrY();
            clamp();
            showIndicator();
            postInvalidateOnAnimation();
        }
    }

    private void animateZoom(float target, final float fx, final float fy) {
        if (zoomAnim != null) zoomAnim.cancel();
        scroller.forceFinished(true);
        zoomAnim = ValueAnimator.ofFloat(zoom, target).setDuration(200);
        zoomAnim.addUpdateListener(a -> {
            float nz = (float) a.getAnimatedValue();
            sx = (sx + fx) * nz / zoom - fx;
            sy = (sy + fy) * nz / zoom - fy;
            zoom = nz;
            clamp();
            invalidate();
        });
        zoomAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator a) { scaling = true; }
            @Override public void onAnimationEnd(Animator a) { scaling = false; invalidate(); }
        });
        zoomAnim.start();
    }
}
