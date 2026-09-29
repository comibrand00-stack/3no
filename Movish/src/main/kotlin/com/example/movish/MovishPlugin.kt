package com.example.movish

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovishPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movish())
    }
}
