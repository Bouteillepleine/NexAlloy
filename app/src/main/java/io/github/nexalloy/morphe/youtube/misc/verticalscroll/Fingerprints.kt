package io.github.nexalloy.morphe.youtube.misc.verticalscroll

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.fingerprint
import org.luckypray.dexkit.query.enums.StringMatchType

val canScrollVerticallyFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("Z")
    parameters()
    methodMatcher {
        invokeMethods {
            add {
                name = "canScrollVertically"
                declaredClass = "android.view.View"
            }
        }
    }
    classMatcher { className(".SwipeRefreshLayout", StringMatchType.EndsWith) }
}