package com.landroverdash.app

import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes

/**
 * Handle on one included [R.layout.item_stat_row]: title and caption on the
 * left, value on the right.
 */
class StatRow(root: View, title: CharSequence = "", sub: CharSequence = "") {
    val title: TextView = root.findViewById(R.id.tvRowTitle)
    val sub: TextView   = root.findViewById(R.id.tvRowSub)
    val value: TextView = root.findViewById(R.id.tvRowValue)

    init {
        this.title.text = title
        this.sub.text = sub
        this.sub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
    }

    fun sub(text: CharSequence, color: Int? = null) {
        sub.text = text
        sub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (color != null) sub.setTextColor(color)
    }

    companion object {
        fun of(activity: android.app.Activity, @IdRes id: Int,
               title: CharSequence, sub: CharSequence = ""): StatRow =
            StatRow(activity.findViewById(id), title, sub)
    }
}
