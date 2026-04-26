package com.securevaultoffline.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class AlphaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    if (SessionGate.shouldAutolockNow()) {
                        SessionGate.clear()
                    }
                    PreviewCache.wipe(this@AlphaApp)
                }
            },
        )
    }
}
