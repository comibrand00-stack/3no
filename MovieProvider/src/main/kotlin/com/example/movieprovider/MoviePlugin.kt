package com.example.movieprovider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Qesset())
    }
}
