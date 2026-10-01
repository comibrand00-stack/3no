package com.example.cimaleek

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaleekPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cimaleek())
    }
}
