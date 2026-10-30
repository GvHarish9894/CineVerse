package com.techgv.cineverse.mobile

import android.os.Bundle
import android.content.Intent
import com.techgv.cineverse.auth.NativeApprovalHost
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.techgv.cineverse.di.nativeMobileApplication
import com.techgv.cineverse.di.nativeMobileExternalActions
import com.techgv.cineverse.ui.mobile.NativeMobileApp

class MainActivity : ComponentActivity() {
    private val approval by lazy { NativeApprovalHost() }
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: the glass top bar and floating tab bar draw under the system bars and pad by their insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        approval.attach(this)
        intent?.dataString?.let(approval::onCallback)
        val graph = nativeMobileApplication()
        val external = nativeMobileExternalActions()
        setContent { NativeMobileApp(graph, external) }
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
