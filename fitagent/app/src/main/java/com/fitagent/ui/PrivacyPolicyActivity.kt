package com.fitagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.ui.theme.FitAgentTheme

/**
 * Shown by Health Connect from its permission screen (required for the permission dialog to appear),
 * and from You → Privacy policy.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FitAgentTheme { PrivacyPolicy(onClose = ::finish) } }
    }
}

private val SECTIONS = listOf(
    "What we read" to "With your permission, FitAgent reads steps, distance, active calories, exercise sessions, " +
        "heart rate, resting heart rate and sleep from Health Connect. It writes only the workouts you record in FitAgent.",
    "Why" to "To show your daily rings, compute your readiness score, track streaks and give coaching suggestions. " +
        "Nothing is used for advertising and nothing is sold.",
    "Where it's stored" to "By default, everything stays on this phone. If you create an account, daily summaries " +
        "(not raw sensor samples), workouts, your goals and coach conversations are sent over HTTPS to the FitAgent " +
        "server so the AI coach and challenges can work.",
    "AI coach" to "Coach messages and a summary of your recent activity are processed by the AI model configured on the " +
        "server. Messages that mention medical emergencies, injuries or self-harm are never sent to the AI — you get " +
        "safety guidance instead.",
    "Challenges" to "Other members of a challenge see your display name and your total for that challenge's metric only.",
    "Your control" to "Revoke Health Connect access any time in system settings. Delete your cloud account and all its " +
        "data from You → Cloud account → Delete account. Uninstalling the app removes all local data.",
    "Not medical advice" to "FitAgent is a fitness and wellbeing tool. It does not diagnose, treat or prevent any " +
        "condition. Talk to a doctor before starting a new training programme if you have health concerns.",
)

@Composable
private fun PrivacyPolicy(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("Privacy policy", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            SECTIONS.forEach { (title, body) ->
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
