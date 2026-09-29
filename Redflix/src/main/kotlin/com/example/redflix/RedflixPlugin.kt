package com.example.redflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RedflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Redflix())
    }
}
