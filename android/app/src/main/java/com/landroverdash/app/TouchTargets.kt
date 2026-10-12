package com.landroverdash.app

import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View

/**
 * Grows a small control's touch area by [extraDp] on every side without
 * changing how it looks or moving anything else — the dashboard layout is
 * tuned to the millimetre, but RESET TRIP and the gear were too small to hit
 * reliably on a moving car's screen.
 *
 * A parent can hold only one delegate, so use this on controls that don't
 * share a parent. Recomputed on every layout pass so it follows the control
 * if it moves. Touches still go to any sibling they land on first.
 */
fun View.expandTouchArea(extraDp: Int) {
    val parentView = parent as? View ?: return
    val px = (extraDp * resources.displayMetrics.density).toInt()
    val update = {
        val r = Rect()
        getHitRect(r)
        r.inset(-px, -px)
        parentView.touchDelegate = TouchDelegate(r, this)
    }
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    if (isLaidOut) update()
}
