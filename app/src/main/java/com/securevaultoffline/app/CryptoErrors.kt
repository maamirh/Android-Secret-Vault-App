package com.securevaultoffline.app

import android.security.keystore.UserNotAuthenticatedException

fun Throwable.isUserAuthRequired(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is UserNotAuthenticatedException) return true
        if (t.javaClass.name.contains("UserNotAuthenticatedException", ignoreCase = true)) return true
        t = t.cause
    }
    return false
}
