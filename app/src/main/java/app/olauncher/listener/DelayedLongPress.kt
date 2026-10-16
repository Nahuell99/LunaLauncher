package app.olauncher.listener

import android.view.View
import app.olauncher.data.Constants

/** Owns the delayed action so release, cancellation and detachment can cancel it. */
internal class DelayedLongPress : View.OnAttachStateChangeListener {
    private var target: View? = null
    private var pending: Runnable? = null

    fun schedule(view: View, action: () -> Unit) {
        cancel()
        target = view
        view.addOnAttachStateChangeListener(this)
        pending = Runnable {
            val attached = view.isAttachedToWindow
            cancel()
            if (attached) action()
        }.also { view.postDelayed(it, Constants.LONG_PRESS_DELAY_MS) }
    }

    fun cancel() {
        pending?.let { target?.removeCallbacks(it) }
        target?.removeOnAttachStateChangeListener(this)
        pending = null
        target = null
    }

    override fun onViewDetachedFromWindow(view: View) = cancel()
    override fun onViewAttachedToWindow(view: View) = Unit
}
