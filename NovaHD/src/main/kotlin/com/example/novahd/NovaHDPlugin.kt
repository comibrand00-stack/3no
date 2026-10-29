package com.example.novahd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NovaHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NovaHD())
    }
}
