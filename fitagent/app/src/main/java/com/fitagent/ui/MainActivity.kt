package com.fitagent.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitagent.data.health.HealthAvailability
import com.fitagent.data.health.HealthConnectSource
import com.fitagent.domain.WorkoutType
import com.fitagent.ui.screens.ActiveWorkoutScreen
import com.fitagent.ui.screens.CoachScreen
import com.fitagent.ui.screens.MiniSessionBar
import com.fitagent.ui.screens.OnboardingScreen
import com.fitagent.ui.screens.SignInScreen
import com.fitagent.ui.screens.SquadScreen
import com.fitagent.ui.screens.TodayScreen
import com.fitagent.ui.screens.TrainScreen
import com.fitagent.ui.screens.WorkoutSummaryDialog
import com.fitagent.ui.screens.YouScreen
import com.fitagent.ui.theme.FitAgentTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Rounded.Today),
    TRAIN("Train", Icons.Rounded.FitnessCenter),
    COACH("Coach", Icons.Rounded.AutoAwesome),
    SQUAD("Squad", Icons.Rounded.Groups),
    YOU("You", Icons.Rounded.Person),
}

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val cloud: CloudViewModel by viewModels()

    private val healthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { main.refresh() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FitAgentTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        main.refresh() // picks up new steps and permission changes made in system settings
    }

    private fun connectHealth() {
        val source = main.healthSource
        when (source.availability()) {
            HealthAvailability.AVAILABLE ->
                if (main.state.value?.health?.connected == true) openHealthSettings()
                else healthPermissions.launch(source.allPermissions)
            else -> openPlayStore()
        }
    }

    private fun openHealthSettings() {
        runCatching { startActivity(HealthConnectClient.getHealthConnectManageDataIntent(this)) }
            .onFailure { healthPermissions.launch(main.healthSource.allPermissions) }
    }

    private fun openPlayStore() {
        val pkg = HealthConnectSource.PROVIDER_PACKAGE
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding"))
                    .setPackage("com.android.vending")
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    private fun App() {
        val home by main.state.collectAsStateWithLifecycle()
        val profile by main.profile.collectAsStateWithLifecycle()
        val session by main.session.collectAsStateWithLifecycle()
        val finished by main.finished.collectAsStateWithLifecycle()
        val cloudState by cloud.state.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
        var showSignIn by rememberSaveable { mutableStateOf(false) }
        var playerOpen by rememberSaveable { mutableStateOf(true) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { main.events.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(Unit) { cloud.events.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(cloudState.account) { if (cloudState.account != null) showSignIn = false }
        LaunchedEffect(session?.startedAtMs) { if (session != null) playerOpen = true }

        if (!profile.onboarded) {
            OnboardingScreen(profile, onConnectHealth = ::connectHealth) {
                main.completeOnboarding(it)
                askNotifications()
            }
            return
        }
        val state = home
        if (state == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val start: (WorkoutType) -> Unit = { main.startWorkout(it) }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        session?.let { s -> if (!playerOpen) MiniSessionBar(s) { playerOpen = true } }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                when (tab) {
                    Tab.TODAY -> TodayScreen(state, padding, main::refresh, ::connectHealth, start)
                    Tab.TRAIN -> TrainScreen(state, padding, onStart = main::startWorkout, onLog = main::logPastWorkout,
                        onDelete = { main.deleteWorkout(it) })
                    Tab.COACH -> CoachScreen(state, cloudState, padding, onSignIn = { showSignIn = true },
                        onLoad = cloud::loadCoach, onSend = cloud::send, onClear = { cloud.clearChat() },
                        onGeneratePlan = cloud::generatePlan, onStart = start)
                    Tab.SQUAD -> SquadScreen(cloudState, padding, onSignIn = { showSignIn = true },
                        onLoad = cloud::loadChallenges,
                        onCreate = { n, m, d, done -> cloud.createChallenge(n, m, d, done) },
                        onJoin = { code, done -> cloud.joinChallenge(code, done) },
                        onLeave = { cloud.leaveChallenge(it) })
                    Tab.YOU -> YouScreen(state, cloudState, padding, onUpdateProfile = main::updateProfile,
                        onManageHealth = ::connectHealth, onSignIn = { showSignIn = true }, onSignOut = cloud::signOut,
                        onDeleteAccount = { cloud.deleteAccount() }, onServerUrl = cloud::setServerUrl,
                        onPrivacy = { startActivity(Intent(this@MainActivity, PrivacyPolicyActivity::class.java)) })
                }
            }

            AnimatedVisibility(session != null && playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                session?.let { s ->
                    ActiveWorkoutScreen(s, profile.weightKg, onTogglePause = main::togglePause, onFinish = main::finishWorkout,
                        onDiscard = main::discardWorkout, onMinimize = { playerOpen = false })
                }
            }
            if (showSignIn) {
                SignInScreen(cloudState, profile.name, onSignIn = cloud::signIn, onRegister = cloud::register,
                    onClose = { showSignIn = false; cloud.clearAuthError() })
            }
            finished?.let { WorkoutSummaryDialog(it, main::dismissSummary) }
        }
    }
}
