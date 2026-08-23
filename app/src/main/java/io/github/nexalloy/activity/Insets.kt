package io.github.nexalloy.activity

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Pad a root view by the system bars.
 *
 * The layouts used to rely on `android:fitsSystemWindows="true"`. That is enough only
 * while the platform still lays content out inside the bars -- from Android 15 an app
 * targeting SDK 35+ (this one targets 37) is drawn edge to edge whether it asks or
 * not, and the flag no longer saves it: the first list row ends up under the status
 * bar and the last under the navigation bar.
 *
 * Deliberately uses the framework WindowInsets rather than AndroidX, because the module
 * has no AndroidX dependency and adding one for four lines is not worth it. On API < 30
 * the older systemWindowInset* accessors are the only ones available.
 */
@Suppress("DEPRECATION")
fun Activity.applySystemBarInsets(view: View?) {
    if (view == null) return
    view.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
        }
        insets
    }
    view.requestApplyInsets()
}
