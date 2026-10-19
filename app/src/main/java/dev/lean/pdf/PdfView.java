package dev.lean.pdf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Continuous vertical PDF view: fit-to-width pages, pinch/double-tap zoom, fling,
 * fast-scroll handle, night mode, remembered position.
 * All PdfRenderer access happens on one background thread (PdfRenderer isn't thread-safe).
 */
public final class PdfView extends View {

    public interface Listener {
        void onLoaded();
        void onError(String message);
        void onPageTap(int current, int count); // page pill tapped (0-based current page)
    }

    private static final float MAX_ZOOM = 6f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final int FAST_SCROLL_MIN_PAGES = 20;
    private static final int MEASURE_BATCH = 32; // pages measured per background step
    private static final int POOL_SIZE = 4;      // spare bitmaps kept for reuse

    private final float dp, gap;
    private final Paint blankPaint = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF pillRect = new RectF();
    private final SharedPreferences positions;

    private final HandlerThread thread = new HandlerThread("pdf-render");
    private final Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> cache;
    private final ArrayList<Bitmap> pool = new ArrayList<>(); // guarded by itself
    private final int maxBitmapPixels;
    private final HashSet<Integer> pending = new HashSet<>();

    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private ValueAnimator zoomAnim;
    private Listener listener;

    // Render-thread state
    private PdfRenderer renderer;
    private ParcelFileDescriptor fd;
    private File tempFile;
    private float[] measured;    // real page aspects, filled in batches after the first page shows

    // UI-thread state
    private volatile int generation;
    private volatile int visFirst, visLast;
    private float[] aspect;      // page height / width (pages not yet measured copy page 1)
    private float[] tops;        // page tops at zoom 1, in px
    private float pageW, contentH;
    private int insetL, insetT, insetR, insetB;
    private float zoom = 1f, sx, sy;
    private float lastFx, lastFy;
    private boolean scaling;
    private float pendingProgress = -1f;
    private String docKey;       // "name|size", used to remember the position per file
    private long indicatorUntil;
    private final Runnable hideIndicator = this::invalidate;

    // Load timer: set when a file starts opening, cleared once its first page is on screen.
    private long openStartedAt;
    private String notice;
    private long noticeUntil;

    // Fast-scroll handle
    private final float handleW, handleH, handleMargin, handleHitW;
    private boolean dragging;
    private float dragOffset;

    public PdfView(Context ctx) {
        super(ctx);
        dp = getResources().getDisplayMetrics().density;
        gap = 6 * dp;
        handleW = 6 * dp;
        handleH = 48 * dp;
        handleMargin = 4 * dp;
        handleHitW = 40 * dp;
        setKeepScreenOn(true);
        positions = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE); // starts loading now

        // Night mode follows the phone's dark theme: white paper becomes dark gray, black text light gray.
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            float k = -0.765f, o = 225f;
            bitmapPaint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    k, 0, 0, 0, o,
                    0, k, 0, 0, o,
                    0, 0, k, 0, o,
                    0, 0, 0, 1, 0 })));
            blankPaint.setColor(Color.rgb(30, 30, 30));
        } else {
            blankPaint.setColor(Color.WHITE);
        }
        pillPaint.setColor(0xCC000000);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);
        handlePaint.setColor(0xAA888888);

        thread.start();
        bg = new Handler(thread.getLooper());

        int cacheBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 256L << 20);
        maxBitmapPixels = Math.min(8_000_000, cacheBytes / 4 / 3); // at least 3 pages always fit
        cache = new LruCache<Integer, Bitmap>(cacheBytes) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getAllocationByteCount(); }
            @Override protected void entryRemoved(boolean evicted, Integer k, Bitmap old, Bitmap nu) {
                recycleBitmap(old);
            }
        };

        scroller = new OverScroller(ctx);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                boolean pillShowing = e.getEventTime() < Math.max(indicatorUntil, noticeUntil);
                float pad = 12 * dp;
                if (pillShowing && tops != null && tops.length > 1 && listener != null
                        && e.getX() > pillRect.left - pad && e.getX() < pillRect.right + pad
                        && e.getY() > pillRect.top - pad && e.getY() < pillRect.bottom + pad) {
                    listener.onPageTap(currentPage(), tops.length);
                }
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

    /** startedAt: SystemClock.uptimeMillis() of the user's tap, for the load timer. */
    public void open(ContentResolver cr, Uri uri, File temp, long startedAt, Listener l) {
        final int gen = ++generation;
        listener = l;
        openStartedAt = startedAt;
        cache.evictAll();
        pending.clear();
        aspect = null;
        tops = null;
        docKey = null;
        dragging = false;
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

                // Only measure page 1 now; every other page assumes the same shape until
                // measureFrom() fills in the real sizes after the first page is on screen.
                PdfRenderer.Page p = renderer.openPage(0);
                float first = (float) p.getHeight() / p.getWidth();
                p.close();
                measured = new float[n];
                Arrays.fill(measured, first);
                final float[] a = measured.clone();

                final String key = fileKey(cr, uri);
                final float saved = key != null ? positions.getFloat(key, -1f) : -1f;

                ui.post(() -> {
                    if (gen != generation) return;
                    aspect = a;
                    docKey = key;
                    if (pendingProgress < 0 && saved >= 0) pendingProgress = saved;
                    relayout();
                    setProgress(pendingProgress);
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

    /** "name|size" of the file, so the same PDF is recognized whichever app opened it. */
    private static String fileKey(ContentResolver cr, Uri uri) { // render thread
        String[] cols = { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        try (Cursor c = cr.query(uri, cols, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0)) return null;
            return c.getString(0) + "|" + (c.isNull(1) ? -1 : c.getLong(1));
        } catch (Exception e) {
            return null;
        }
    }

    /** Measures pages in batches, between page renders, then updates the layout in place. */
    private void measureFrom(final int gen, final int start) {
        bg.post(() -> {
            if (gen != generation || renderer == null) return;
            int n = measured.length, end = Math.min(n, start + MEASURE_BATCH);
            boolean changed = false;
            for (int i = start; i < end; i++) {
                try {
                    PdfRenderer.Page p = renderer.openPage(i);
                    float a = (float) p.getHeight() / p.getWidth();
                    p.close();
                    if (a != measured[i]) { measured[i] = a; changed = true; }
                } catch (Exception ignored) { }
            }
            if (changed) {
                final float[] a = measured.clone();
                ui.post(() -> {
                    if (gen != generation) return;
                    float pos = progress();
                    aspect = a;
                    relayout();
                    setProgress(pos);
                    invalidate();
                });
            }
            if (end < n) measureFrom(gen, end);
        });
    }

    /** Call from Activity.onPause. */
    public void savePosition() {
        if (docKey != null && tops != null) positions.edit().putFloat(docKey, progress()).apply();
    }

    /** Call from Activity.onDestroy. */
    public void close() {
        generation++;
        bg.post(this::closeRenderer);
        thread.quitSafely();
        cache.evictAll();
        synchronized (pool) { pool.clear(); }
    }

    private void closeRenderer() { // render thread
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
        try { if (fd != null) fd.close(); } catch (Exception ignored) { }
        renderer = null;
        fd = null;
        if (tempFile != null) { tempFile.delete(); tempFile = null; }
    }

    // ---------------------------------------------------------------- position

    /** Scroll position as page index + fraction through that page (e.g. 12.4). */
    public float progress() {
        if (tops == null) return Math.max(pendingProgress, 0f);
        float y = sy / zoom;
        int i = pageAt(y);
        float f = (y - tops[i]) / (pageW * aspect[i] + gap);
        return i + Math.max(0f, Math.min(0.999f, f));
    }

    public void setPendingProgress(float p) { pendingProgress = p; }

    private void setProgress(float p) {
        if (tops == null) return;
        pendingProgress = -1f;
        if (p <= 0) {
            sy = 0;
        } else {
            int i = Math.min((int) p, tops.length - 1);
            sy = (tops[i] + (p - i) * (pageW * aspect[i] + gap)) * zoom;
        }
        clamp();
    }

    public void goToPage(int i) {
        scroller.forceFinished(true);
        setProgress(i);
        showIndicator();
        invalidate();
    }

    private int currentPage() {
        return pageAt((sy + getHeight() / 2f) / zoom);
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
                if (openStartedAt > 0) onFirstPageShown();
            } else {
                c.drawRect(rect, blankPaint);
            }
            int want = targetWidth(i, zoom);
            // Re-render if missing, too blurry for this zoom, or drawn before the page's real size was known.
            boolean stale = b != null && Math.abs((float) b.getHeight() / b.getWidth() - aspect[i]) > 0.01f;
            if (b == null || stale || (!scaling && b.getWidth() < want * 0.9f)) request(i, want);
        }
        c.restore();

        // Prefetch the pages just above and below at base resolution so scrolling never shows blanks.
        if (!scaling) {
            if (last + 1 < tops.length && cache.get(last + 1) == null) {
                request(last + 1, targetWidth(last + 1, 1f));
            }
            if (first > 0 && cache.get(first - 1) == null) {
                request(first - 1, targetWidth(first - 1, 1f));
            }
        }

        // Bottom pill: the load-time notice wins while it's up, otherwise the page number.
        long now = SystemClock.uptimeMillis();
        if (now < noticeUntil) {
            drawPill(c, notice);
        } else if ((now < indicatorUntil || dragging) && tops.length > 1) {
            drawPill(c, (currentPage() + 1) + " / " + tops.length);
        }

        if (handleVisible()) {
            float x = getWidth() - insetR - handleMargin - handleW;
            float y = handleY();
            rect.set(x, y, x + handleW, y + handleH);
            c.drawRoundRect(rect, handleW / 2, handleW / 2, handlePaint);
        }
    }

    private void drawPill(Canvas c, String s) {
        float tw = textPaint.measureText(s), th = textPaint.getTextSize();
        float cx = getWidth() / 2f, by = getHeight() - insetB - th * 1.5f;
        pillRect.set(cx - tw / 2 - th, by - th * 1.4f, cx + tw / 2 + th, by + th * 0.6f);
        c.drawRoundRect(pillRect, th, th, pillPaint);
        c.drawText(s, cx, by, textPaint);
    }

    /** Called on the first frame that shows a rendered page after open(). */
    private void onFirstPageShown() {
        long ms = SystemClock.uptimeMillis() - openStartedAt;
        openStartedAt = 0;
        notice = "Opened in " + ms + " ms";
        noticeUntil = SystemClock.uptimeMillis() + 2000;
        removeCallbacks(hideIndicator);
        postDelayed(hideIndicator, 2050);
        if (aspect.length > 1) measureFrom(generation, 1);
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
                    b = obtainBitmap(w, h);
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
                pending.remove(i);
                if (gen != generation) { recycleBitmap(result); return; }
                if (result != null) {
                    cache.put(i, result);
                    invalidate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- bitmap reuse

    /** Reuses a spare bitmap that's big enough (but not wastefully big), else makes a new one. */
    private Bitmap obtainBitmap(int w, int h) { // render thread
        int need = w * h * 4;
        synchronized (pool) {
            for (int k = 0; k < pool.size(); k++) {
                Bitmap b = pool.get(k);
                int have = b.getAllocationByteCount();
                if (have >= need && have <= need * 2) {
                    pool.remove(k);
                    b.reconfigure(w, h, Bitmap.Config.ARGB_8888);
                    return b;
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private void recycleBitmap(Bitmap b) {
        if (b == null) return;
        synchronized (pool) {
            if (pool.size() >= POOL_SIZE) pool.remove(0); // drop the oldest; the GC frees it
            pool.add(b);
        }
    }

    // ---------------------------------------------------------------- indicator + fast scroll

    private void showIndicator() {
        indicatorUntil = SystemClock.uptimeMillis() + 1200;
        removeCallbacks(hideIndicator);
        postDelayed(hideIndicator, 1250);
    }

    private boolean handleVisible() {
        return tops != null && tops.length >= FAST_SCROLL_MIN_PAGES && maxY() > 0
                && (dragging || SystemClock.uptimeMillis() < indicatorUntil);
    }

    private float trackTop() { return insetT + handleMargin; }
    private float trackRange() { return Math.max(1, getHeight() - insetB - handleMargin - handleH - trackTop()); }
    private float handleY() { return trackTop() + sy / maxY() * trackRange(); }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        // Fast-scroll drag: grabbing the handle takes over the gesture until the finger lifts.
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && handleVisible()
                && e.getX() > getWidth() - insetR - handleHitW
                && e.getY() > handleY() - 24 * dp && e.getY() < handleY() + handleH + 24 * dp) {
            dragging = true;
            dragOffset = e.getY() - handleY();
            scroller.forceFinished(true);
        }
        if (dragging) {
            float f = (e.getY() - dragOffset - trackTop()) / trackRange();
            sy = Math.max(0f, Math.min(1f, f)) * maxY();
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dragging = false;
            showIndicator();
            invalidate();
            return true;
        }
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
