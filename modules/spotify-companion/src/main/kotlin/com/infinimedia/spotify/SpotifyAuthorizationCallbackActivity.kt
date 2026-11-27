package com.infinimedia.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import java.util.concurrent.Executors

/** Receives only the registered Spotify OAuth redirect and exchanges its code off the UI thread. */
class SpotifyAuthorizationCallbackActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpotifyOAuthCallback").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStatus("Finishing Spotify sign-in…")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(source: Intent?) {
        val callback = source?.data ?: run {
            finishWith("Spotify did not return a sign-in response.", false)
            return
        }
        if (handling) return
        handling = true
        executor.execute {
            val outcome = runCatching { SpotifyOAuthManager(applicationContext).completeAuthorization(callback) }
            main.post {
                if (outcome.isSuccess) finishWith(outcome.getOrThrow(), true)
                else finishWith(outcome.exceptionOrNull()?.message ?: "Spotify sign-in failed.", false)
            }
        }
    }

    private fun finishWith(message: String, success: Boolean) {
        val target = Intent(this, SpotifyWorkspaceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SpotifyWorkspaceActivity.EXTRA_AUTH_RESULT, message)
            .putExtra(SpotifyWorkspaceActivity.EXTRA_AUTH_SUCCEEDED, success)
        runCatching { startActivity(target) }
        finish()
    }

    private fun showStatus(message: String) {
        setContentView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
