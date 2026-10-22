package com.techgv.cineverse.mobile

import android.app.Application
import com.techgv.cineverse.di.initKoin

class MobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this, BuildConfig.TMDB_READ_ACCESS_TOKEN)
    }
}
