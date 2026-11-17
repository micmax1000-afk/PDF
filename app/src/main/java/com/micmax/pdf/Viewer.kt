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
    private val cache = object : LruCache<Int, Bitmap>(4) {}
    val count get() = renderer.pageCount
    /** width / height of every page */
    val ratios: List<Float> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
    @Volatile private var closed = false

    fun cached(i: Int): Bitmap? = cache.get(i)

    fun render(i: Int, width: Int, done: (Bitmap?) -> Unit, post: (Runnable) -> Unit) {
        exec.execute {
            if (closed) return@execute
            val b = cache.get(i) ?: runCatching {
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
    override fun getItemCount() = pr.count
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(ImageView(p.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.WHITE)
        layoutParams = RecyclerView.LayoutParams(-1, 100).apply { bottomMargin = p.context.dp(6) }
    })
    override fun onBindViewHolder(h: H, pos: Int) {
        val w = viewW().coerceAtLeast(300)
        h.img.layoutParams = (h.img.layoutParams as RecyclerView.LayoutParams).apply { height = (w / pr.ratios[pos]).toInt() }
        h.img.tag = pos
        val c = pr.cached(pos)
        if (c != null) { h.img.setImageBitmap(c); return }
        h.img.setImageDrawable(null)
        pr.render(pos, (w * 1.6f).toInt().coerceAtMost(1700), { b -> if (h.img.tag == pos && b != null) h.img.setImageBitmap(b) }, { r -> h.img.post(r) })
    }
}

/** A list of pages you can zoom with two fingers or a double tap, and drag sideways when zoomed. */
class ZoomRecycler(c: Context) : RecyclerView(c) {
    private var zoom = 1f
    private var tx = 0f
    private var lastX = 0f
    private var panning = false
    private val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor, d.focusX); return true }
    })
    private val taps = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(if (zoom > 1.2f) 1f else 2.5f, e.x); return true }
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

    fun goToPage(i: Int) { (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0) }
    fun currentPage(): Int = (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
}
