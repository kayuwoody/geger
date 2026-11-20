package com.geger.trial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geger.positioning.config.TrialConfig

/**
 * The app's single screen in milestone M1.
 *
 * Concept: this placeholder exists to prove the build works end to end — Compose renders, and the
 * positioning-core library (plain Kotlin) runs on the phone. Later milestones replace it with the
 * session setup, AR and map screens described in SPEC.md, "App screens and AR overlay".
 */
class MainActivity : ComponentActivity() {

    /** Android calls this when the screen is created; we hand the UI to Compose. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = TrialConfig.defaults()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SkeletonScreen(particleCount = config.filter.particleCount)
                }
            }
        }
    }
}

/**
 * Shows the app name, the milestone and one value read from positioning-core's default config,
 * which confirms the core library is linked into the app.
 */
@Composable
private fun SkeletonScreen(particleCount: Int) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Geger positioning trial", style = MaterialTheme.typography.headlineSmall)
        Text("Milestone M1 skeleton", style = MaterialTheme.typography.bodyLarge)
        Text("positioning-core linked: default particle count = $particleCount", style = MaterialTheme.typography.bodyMedium)
    }
}
