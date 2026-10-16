package app.olauncher.ui.grid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import app.olauncher.data.GridItem
import app.olauncher.data.HomeGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Free-form home grid: lays children out on a [columns]×[rows] cell grid, like a
 * classic launcher workspace, and lets the user drag them around in edit mode.
 *
 * Touch model (normal mode):
 *  - empty cells consume nothing, so taps/swipes fall through to the home screen below;
 *  - icon items are handled here (tap, long-press, fling → swipe gestures);
 *  - widget items receive their own touches, but a long-press is intercepted so every
 *    item, widgets included, can open edit mode the same way.
 * Edit mode consumes everything: drag to move, tap an item or an empty cell.
 */
class HomeGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    interface Listener {
        fun onItemClick(item: GridItem)
        fun onItemLongClick(item: GridItem)
        fun onSwipe(direction: Int)
        fun onEditItemTap(item: GridItem)
        fun onEditEmptyCellTap(col: Int, row: Int)
        fun onItemMoved(item: GridItem, col: Int, row: Int)
    }

    var listener: Listener? = null

    var columns = 5
        private set
    var rows = 8
        private set
    private var items: List<GridItem> = emptyList()
    private val views = LinkedHashMap<String, View>()

    var editMode = false
        set(value) {
            field = value
            cancelPendingLongPress()
            invalidate()
        }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setGridColor(color: Int) {
        cellPaint.color = (color and 0x00FFFFFF) or 0x55000000
        targetPaint.color = (color and 0x00FFFFFF) or 0x22000000
        invalidate()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        setGridColor(-0x1)
    }

    val cellWidth: Float get() = (width - paddingLeft - paddingRight).toFloat() / columns
    val cellHeight: Float get() = (height - paddingTop - paddingBottom).toFloat() / rows

    /**
     * Replaces the grid content. [viewFor] builds (or reuses, keyed by item id) the view
     * for each item; views of items that disappeared are removed.
     */
    fun setItems(columns: Int, rows: Int, newItems: List<GridItem>, viewFor: (GridItem, View?) -> View) {
        this.columns = columns.coerceIn(HomeGrid.MIN_COLUMNS, HomeGrid.MAX_COLUMNS)
        this.rows = rows.coerceIn(HomeGrid.MIN_ROWS, HomeGrid.MAX_ROWS)
        items = newItems.filter { it.col + it.spanX <= this.columns && it.row + it.spanY <= this.rows }
        val keep = items.map { it.id }.toSet()
        views.keys.filter { it !in keep }.forEach { id -> views.remove(id)?.let(::removeView) }
        for (item in items) {
            val old = views[item.id]
            val view = viewFor(item, old)
            if (view !== old) {
                old?.let(::removeView)
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view)
                views[item.id] = view
            }
        }
        requestLayout()
        invalidate()
    }

    fun viewFor(itemId: String): View? = views[itemId]

    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (cellWidth <= 0f || cellHeight <= 0f) return null
        val col = ((x - paddingLeft) / cellWidth).toInt()
        val row = ((y - paddingTop) / cellHeight).toInt()
        if (col !in 0 until columns || row !in 0 until rows) return null
        return col to row
    }

    private fun itemAt(x: Float, y: Float): GridItem? {
        val (col, row) = cellAt(x, y) ?: return null
        return HomeGrid.itemAt(items, col, row)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cw = (width - paddingLeft - paddingRight).toFloat() / columns
        val ch = (height - paddingTop - paddingBottom).toFloat() / rows
        for ((id, view) in views) {
            val item = items.firstOrNull { it.id == id } ?: continue
            view.measure(
                MeasureSpec.makeMeasureSpec((cw * item.spanX).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((ch * item.spanY).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((id, view) in views) {
            val item = items.firstOrNull { it.id == id } ?: continue
            val left = (paddingLeft + cellWidth * item.col).toInt()
            val top = (paddingTop + cellHeight * item.row).toInt()
            view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!editMode) return
        val inset = 3f * resources.displayMetrics.density
        val radius = 10f * resources.displayMetrics.density
        for (row in 0 until rows) for (col in 0 until columns) {
            val rect = cellRect(col, row, 1, 1).apply { inset(inset, inset) }
            canvas.drawRoundRect(rect, radius, radius, cellPaint)
        }
        dropTarget?.let { (col, row, item) ->
            val rect = cellRect(col, row, item.spanX, item.spanY).apply { inset(inset, inset) }
            canvas.drawRoundRect(rect, radius, radius, targetPaint)
        }
    }

    private fun cellRect(col: Int, row: Int, spanX: Int, spanY: Int) = RectF(
        paddingLeft + cellWidth * col,
        paddingTop + cellHeight * row,
        paddingLeft + cellWidth * (col + spanX),
        paddingTop + cellHeight * (row + spanY),
    )

    // ---------------------------------------------------------------------------------
    // Normal-mode touch handling
    // ---------------------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var touchedItem: GridItem? = null
    private var touchedIcon = false
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        val item = touchedItem ?: return@Runnable
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onItemLongClick(item)
    }

    private fun cancelPendingLongPress() = removeCallbacks(longPressRunnable)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!longPressFired) touchedItem?.let { listener?.onItemClick(it) }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val dx = e2.x - (e1?.x ?: downX)
            val dy = e2.y - (e1?.y ?: downY)
            val threshold = 100
            val direction = when {
                abs(dx) > abs(dy) && abs(dx) > threshold && abs(velocityX) > threshold ->
                    if (dx > 0) SWIPE_RIGHT else SWIPE_LEFT

                abs(dy) > threshold && abs(velocityY) > threshold -> if (dy < 0) SWIPE_UP else SWIPE_DOWN
                else -> return false
            }
            cancelPendingLongPress()
            listener?.onSwipe(direction)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editMode || longPressFired) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressFired = false
                touchedItem = itemAt(ev.x, ev.y)
                touchedIcon = touchedItem?.isIcon == true
                if (touchedItem != null) postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                // Icons are ours from the first event; widgets keep their own touches.
                return touchedIcon
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) cancelPendingLongPress()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPendingLongPress()
        }
        return longPressFired
    }

    @SuppressLint("ClickableViewAccessibility") // Items expose their own click actions.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The rest of a gesture whose long-press already fired (and possibly switched
        // to edit mode) must not also count as a tap or a drag.
        if (longPressFired) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                longPressFired = false
                touchedItem = null
                touchedIcon = false
            }
            return true
        }
        if (editMode) return onEditTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && touchedItem == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) cancelPendingLongPress()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPendingLongPress()
        }
        if (touchedIcon) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchedItem = null
            touchedIcon = false
        }
        return true
    }

    // ---------------------------------------------------------------------------------
    // Edit mode: drag & drop
    // ---------------------------------------------------------------------------------

    private var dragItem: GridItem? = null
    private var dragView: View? = null
    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dropTarget: Triple<Int, Int, GridItem>? = null

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                dragItem = itemAt(event.x, event.y)
                dragView = dragItem?.let { views[it.id] }
                dragView?.let {
                    dragOffsetX = event.x - it.left
                    dragOffsetY = event.y - it.top
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val item = dragItem ?: return true
                val view = dragView ?: return true
                if (!dragging && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    view.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.85f).setDuration(120).start()
                    view.bringToFront()
                }
                if (dragging) {
                    view.translationX = event.x - dragOffsetX - view.left
                    view.translationY = event.y - dragOffsetY - view.top
                    // Snap preview: the cell under the dragged view's top-left corner.
                    val col = ((event.x - dragOffsetX - paddingLeft) / cellWidth).roundToInt()
                    val row = ((event.y - dragOffsetY - paddingTop) / cellHeight).roundToInt()
                    val free = HomeGrid.isFree(items, columns, rows, col, row, item.spanX, item.spanY, item.id)
                    dropTarget = if (free) Triple(col, row, item) else null
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val item = dragItem
                if (dragging && item != null) {
                    val target = dropTarget
                    dragView?.let { resetDragView(it) }
                    if (target != null && (target.first != item.col || target.second != item.row)) {
                        listener?.onItemMoved(item, target.first, target.second)
                    }
                } else if (item != null) {
                    listener?.onEditItemTap(item)
                } else {
                    cellAt(event.x, event.y)?.let { (col, row) -> listener?.onEditEmptyCellTap(col, row) }
                }
                endDrag()
            }

            MotionEvent.ACTION_CANCEL -> {
                dragView?.let { resetDragView(it) }
                endDrag()
            }
        }
        return true
    }

    private fun resetDragView(view: View) {
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f).translationY(0f).setDuration(120).start()
    }

    private fun endDrag() {
        dragging = false
        dragItem = null
        dragView = null
        dropTarget = null
        invalidate()
    }

    companion object {
        const val SWIPE_LEFT = 0
        const val SWIPE_RIGHT = 1
        const val SWIPE_UP = 2
        const val SWIPE_DOWN = 3
    }
}
