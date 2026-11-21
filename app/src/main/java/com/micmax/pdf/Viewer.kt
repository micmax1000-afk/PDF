package com.micmax.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/** Keeps one PDF open and draws its pages on demand (one at a time: PdfRenderer allows only that). */
class PageRenderer(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val exec = Executors.newSingleThreadExecutor()
    /** Pictures of the pages, counted in bytes (a page drawn sharp for the zoom is big). */
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).coerceAtMost(160L shl 20).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    val count get() = renderer.pageCount
    /** width / height of every page */
    val ratios: List<Float> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
    @Volatile private var closed = false

    fun cached(i: Int): Bitmap? = cache.get(i)

    fun render(i: Int, width: Int, done: (Bitmap?) -> Unit, post: (Runnable) -> Unit) {
        exec.execute {
            if (closed) return@execute
            val b = cache.get(i)?.takeIf { it.width >= width * 0.95f } ?: runCatching {
                renderer.openPage(i).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()?.also { cache.put(i, it) }
            post(Runnable { done(b) })
        }
    }

    fun close() {
        closed = true
        exec.execute { runCatching { renderer.close() }; runCatching { fd.close() } }
        exec.shutdown()
    }
}

class PageAdapter(private val pr: PageRenderer, private val viewW: () -> Int) : RecyclerView.Adapter<PageAdapter.H>() {
    class H(val img: ImageView) : RecyclerView.ViewHolder(img)
    /** Zoomed in: the pages are drawn again, sharper. */
    var sharp = false
    /** Night reading: white pages become dark. */
    var night = false
    override fun getItemCount() = pr.count
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(ImageView(p.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = RecyclerView.LayoutParams(-1, 100).apply { bottomMargin = p.context.dp(6) }
    })
    override fun onBindViewHolder(h: H, pos: Int) {
        val w = viewW().coerceAtLeast(300)
        h.img.layoutParams = (h.img.layoutParams as RecyclerView.LayoutParams).apply { height = (w / pr.ratios[pos]).toInt() }
        h.img.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        h.img.colorFilter = if (night) INVERT else null
        h.img.tag = pos
        val target = if (sharp) (w * 2.6f).toInt().coerceAtMost(2600) else (w * 1.6f).toInt().coerceAtMost(1700)
        val c = pr.cached(pos)
        if (c != null) h.img.setImageBitmap(c) else h.img.setImageDrawable(null)
        if (c != null && c.width >= target * 0.95f) return
        pr.render(pos, target, { b -> if (h.img.tag == pos && b != null) h.img.setImageBitmap(b) }, { r -> h.img.post(r) })
    }

    companion object {
        /** Negative colours (white paper → black, black ink → white), hue kept roughly as it is. */
        val INVERT = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
}

/** A list of pages you can zoom with two fingers or a double tap, and drag sideways when zoomed. */
class ZoomRecycler(c: Context) : RecyclerView(c) {
    private var zoom = 1f
    private var tx = 0f
    private var lastX = 0f
    private var panning = false
    /** Called when a zoom ends, with the new zoom. */
    var onZoomed: ((Float) -> Unit)? = null
    private val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor, d.focusX); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { onZoomed?.invoke(zoom) }
    })
    /** A long press on a page: page and where, as fractions of the page (0..1 from the top left). */
    var onLongPage: ((page: Int, fx: Float, fy: Float) -> Unit)? = null
    private val taps = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(if (zoom > 1.2f) 1f else 2.5f, e.x); onZoomed?.invoke(zoom); return true }
        override fun onLongPress(e: MotionEvent) {
            val x = (e.x - tx) / zoom; val y = e.y / zoom
            val child = findChildViewUnder(x, y) ?: return
            val pos = getChildAdapterPosition(child).takeIf { it >= 0 } ?: return
            onLongPage?.invoke(pos, ((x - child.left) / child.width).coerceIn(0f, 1f), ((y - child.top) / child.height).coerceIn(0f, 1f))
        }
    })

    private fun setZoom(z: Float, fx: Float) {
        val nz = z.coerceIn(1f, 4f)
        val cx = (fx - tx) / zoom
        zoom = nz; tx = fx - cx * nz
        clampTx(); invalidate()
    }
    private fun clampTx() { tx = tx.coerceIn(width - width * zoom, 0f) }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save(); canvas.translate(tx, 0f); canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e); taps.onTouchEvent(e)
        if (scale.isInProgress || e.pointerCount > 1) { panning = false; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; panning = true }
            MotionEvent.ACTION_MOVE -> if (panning && zoom > 1f) { tx += e.x - lastX; lastX = e.x; clampTx(); invalidate() } else lastX = e.x
        }
        val m = MotionEvent.obtain(e)
        m.setLocation((e.x - tx) / zoom, e.y / zoom)
        val r = super.onTouchEvent(m)
        m.recycle()
        return r || true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = false

    /** Draws the pages on screen again (after a zoom or the night switch), without jumping. */
    fun refreshVisible() {
        val lm = layoutManager as LinearLayoutManager
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a >= 0 && b >= a) adapter?.notifyItemRangeChanged(a, b - a + 1)
    }

    fun goToPage(i: Int) { (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0) }
    fun currentPage(): Int = (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
}
