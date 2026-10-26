package com.example.cinehdlive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineHdLivePlugin : Plugin() {
    override fun load(context: Context) {
        CineHdLiveProvider.cacheDir = context.cacheDir
        registerMainAPI(CineHdLiveProvider())
    }
}
