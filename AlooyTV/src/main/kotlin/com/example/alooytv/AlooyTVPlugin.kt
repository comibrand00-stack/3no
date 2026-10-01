package com.example.alooytv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AlooyTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AlooyTV())
    }
}
