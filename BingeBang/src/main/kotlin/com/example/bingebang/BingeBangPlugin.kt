package com.example.bingebang

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeBangPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BingeBang())
    }
}
