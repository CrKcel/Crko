package com.crko

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crko.state.entry.EntryInbox
import com.crko.state.entry.EntryTarget
import com.crko.state.EXTRA_REMINDER_RECORD_ID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidAppContext.install(this)
        ActivityHolder.attach(this)
        offerEntry(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerEntry(intent)
    }

    /**
     * Deep links and notification actions queue before Compose exists, so a cold start lands on the
     * same page a warm one would. A "开始" action carries the todo id; every other link is the
     * page-only form.
     */
    private fun offerEntry(intent: Intent?) {
        val uri = intent?.data ?: return
        val recordId = intent.getStringExtra(EXTRA_REMINDER_RECORD_ID)
        if (recordId != null && uri.toString().startsWith("crko://start", ignoreCase = true)) {
            EntryInbox.offer(EntryTarget.Focus(recordId))
        } else {
            EntryInbox.offerDeepLink(uri.toString())
        }
    }
}
