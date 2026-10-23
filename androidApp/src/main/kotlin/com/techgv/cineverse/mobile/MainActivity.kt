package com.techgv.cineverse.mobile

import android.os.Bundle
import android.content.Intent
import com.techgv.cineverse.auth.NativeApprovalHost
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.techgv.cineverse.smoke.App

class MainActivity : ComponentActivity() {
    private val approval by lazy { NativeApprovalHost() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        approval.attach(this)
        intent?.dataString?.let(approval::onCallback)
        setContent { App(nativeReadToken = BuildConfig.TMDB_READ_ACCESS_TOKEN.ifBlank { null }) }
    }

    override fun onStart() {
        super.onStart()
        approval.attach(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(approval::onCallback)
    }

    override fun onStop() {
        approval.stop()
        super.onStop()
    }

    override fun onDestroy() {
        approval.detach(this)
        super.onDestroy()
    }
}
