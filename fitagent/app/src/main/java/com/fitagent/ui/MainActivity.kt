package com.fitagent.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.core.content.ContextCompat
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
import com.fitagent.ui.screens.Detail
import com.fitagent.ui.screens.DevicesScreen
import com.fitagent.ui.screens.HeartScreen
import com.fitagent.ui.screens.ReadinessScreen
import com.fitagent.ui.screens.SleepScreen
import com.fitagent.ui.screens.BreatheScreen
import com.fitagent.ui.screens.MoodScreen
import com.fitagent.domain.Routines
import com.fitagent.ui.screens.FoodScreen
import com.fitagent.ui.screens.LibraryScreen
import com.fitagent.ui.screens.RecapScreen
import com.fitagent.ui.screens.RewardsScreen
import com.fitagent.ui.screens.RoutinePlayer
import com.fitagent.ui.screens.TimetableScreen
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
    private var pendingPhoto: java.io.File? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingPhoto ?: return@registerForActivityResult
        pendingPhoto = null
        if (saved) main.estimateFoodPhoto(file, "") else file.delete()
    }

    private fun launchFoodCamera() {
        val dir = java.io.File(cacheDir, "photos").apply { mkdirs() }
        val file = java.io.File(dir, "meal-${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        runCatching { takePhoto.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)) }
            .onFailure { pendingPhoto = null }
    }

    private val icsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            .getOrNull()?.let(main::importIcs)
    }
    /** Phone step sensor permission; asked right before Health Connect so both come from one "Connect" tap. */
    private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        main.refresh()
        connectHealth(askActivity = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleInvite(intent)
        setContent { FitAgentTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        main.refresh() // picks up new steps and permission changes made in system settings
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** https://<server>/j/CODE (App Link) → open Squad with the join dialog prefilled. */
    private fun handleInvite(intent: Intent?) {
        val data = intent?.data ?: return
        val segments = data.pathSegments
        if (segments.size == 2 && segments[0] == "j") {
            cloud.openInvite(segments[1])
            openSquad.value = true
        }
    }

    private val openSquad = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun connectHealth() = connectHealth(askActivity = true)

    private fun connectHealth(askActivity: Boolean) {
        if (askActivity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
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
        var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
        val squadRequested by openSquad.collectAsStateWithLifecycle()
        LaunchedEffect(squadRequested) { if (squadRequested) { tab = Tab.SQUAD; detail = null; openSquad.value = false } }
        var routineId by rememberSaveable { mutableStateOf<String?>(null) }
        // Keeps the closing screen rendered while it slides out.
        val shownDetail = remember { mutableStateOf<Detail?>(null) }
        if (detail != null) shownDetail.value = detail
        val hourly by main.hourlySteps.collectAsStateWithLifecycle()
        val lastNight by main.lastNight.collectAsStateWithLifecycle()
        val moods by main.moods.collectAsStateWithLifecycle()
        val foods by main.foods.collectAsStateWithLifecycle()
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
                    Tab.TODAY -> TodayScreen(state, padding, main::refresh, ::connectHealth, start, hourly, lastNight,
                        onOpen = { detail = it }, todayMood = moods.lastOrNull { it.date == state.today }, onSaveMood = main::saveMood, foods = foods)
                    Tab.TRAIN -> {
                        val timetable by main.timetable.collectAsStateWithLifecycle()
                        TrainScreen(state, padding, onStart = main::startWorkout, onLog = main::logPastWorkout,
                            onDelete = { main.deleteWorkout(it) }, timetable = timetable, onOpenTimetable = { detail = Detail.TIMETABLE },
                            onOpenLibrary = { detail = Detail.LIBRARY }, onStartRoutine = { routineId = it.id })
                    }
                    Tab.COACH -> CoachScreen(state, cloudState, padding, onSignIn = { showSignIn = true },
                        onLoad = cloud::loadCoach, onSend = cloud::send, onClear = { cloud.clearChat() },
                        onGeneratePlan = cloud::generatePlan, onStart = start)
                    Tab.SQUAD -> SquadScreen(cloudState, padding, onSignIn = { showSignIn = true },
                        onLoad = cloud::loadChallenges,
                        onCreate = { n, m, d, done -> cloud.createChallenge(n, m, d, done) },
                        onJoin = { code, done -> cloud.joinChallenge(code, done) },
                        onLeave = { cloud.leaveChallenge(it) },
                        onKudos = { id, to, e, remove -> cloud.toggleKudos(id, to, e, remove) },
                        onLoadCampus = { cloud.loadCampus(it); cloud.loadOfficial() }, inviteLink = cloud::inviteLink, onClearInvite = cloud::clearInvite)
                    Tab.YOU -> YouScreen(state, cloudState, padding, onUpdateProfile = main::updateProfile,
                        onManageHealth = ::connectHealth, onSignIn = { showSignIn = true }, onSignOut = cloud::signOut,
                        onDeleteAccount = { cloud.deleteAccount() }, onServerUrl = cloud::setServerUrl,
                        onPrivacy = { startActivity(Intent(this@MainActivity, PrivacyPolicyActivity::class.java)) },
                        onOpenDevices = { detail = Detail.DEVICES }, onOpenRecap = { detail = Detail.RECAP },
                        onOpenRewards = { detail = Detail.REWARDS })
                }
            }

            AnimatedVisibility(detail != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
                when (shownDetail.value) {
                    Detail.READINESS -> ReadinessScreen(state) { detail = null }
                    Detail.SLEEP -> SleepScreen(state, lastNight) { detail = null }
                    Detail.HEART -> {
                        val heartToday by main.heartToday.collectAsStateWithLifecycle()
                        val heartWeek by main.heartWeek.collectAsStateWithLifecycle()
                        HeartScreen(state, heartToday, heartWeek) { detail = null }
                    }
                    Detail.DEVICES -> {
                        val sources by main.sources.collectAsStateWithLifecycle()
                        val log by main.syncLog.collectAsStateWithLifecycle()
                        val compat by main.compatibility.collectAsStateWithLifecycle()
                        DevicesScreen(
                            health = state.health, lastWatchSync = state.lastWatchSync, lastCloudSync = main.lastCloudSync,
                            signedIn = cloudState.account != null, sources = sources, log = log, compatibility = compat,
                            refreshing = state.refreshing, onSyncNow = main::syncNow, onRunCheck = main::runCompatibilityCheck,
                            onConnect = ::connectHealth, onBack = { detail = null },
                        )
                    }
                    Detail.MOOD -> {
                        val heartToday by main.heartToday.collectAsStateWithLifecycle()
                        MoodScreen(state, moods, heartToday, hourly, onBreathe = { detail = Detail.BREATHE }) { detail = null }
                    }
                    Detail.BREATHE -> BreatheScreen { detail = Detail.MOOD }
                    Detail.RECAP -> RecapScreen(state) { detail = null }
                    Detail.REWARDS -> RewardsScreen(cloudState, onLoad = cloud::loadRewards, onRedeem = { cloud.redeem(it) }) { detail = null }
                    Detail.FOOD -> {
                        val photo by main.foodPhoto.collectAsStateWithLifecycle()
                        FoodScreen(state, foods, photo, onTakePhoto = ::launchFoodCamera, onAdd = { main.addFoods(it) },
                            onDelete = { main.deleteFood(it) }, onDismissPhoto = main::dismissFoodPhoto) { detail = null }
                    }
                    Detail.LIBRARY -> LibraryScreen(onStartRoutine = { routineId = it.id }) { detail = null }
                    Detail.TIMETABLE -> {
                        val timetable by main.timetable.collectAsStateWithLifecycle()
                        TimetableScreen(timetable, state.today, cloudState.plan, onUpdate = main::updateTimetable,
                            onImportIcs = { icsPicker.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "*/*")) },
                            onBack = { detail = null })
                    }
                    null -> Unit
                }
            }
            AnimatedVisibility(session != null && playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                session?.let { s ->
                    ActiveWorkoutScreen(s, profile.weightKg, onTogglePause = main::togglePause, onFinish = main::finishWorkout,
                        onDiscard = main::discardWorkout, onMinimize = { playerOpen = false },
                        voiceEnabled = profile.voiceCues, onToggleVoice = { main.updateProfile { it.copy(voiceCues = !it.voiceCues) } })
                }
            }
            routineId?.let { id ->
                val routine = Routines.byId(id)
                RoutinePlayer(routine, profile.voiceCues, onToggleVoice = { main.updateProfile { it.copy(voiceCues = !it.voiceCues) } },
                    onFinish = { secs -> main.saveRoutine(routine, secs); routineId = null }, onQuit = { routineId = null })
            }
            if (showSignIn) {
                SignInScreen(cloudState, profile.name, onSignIn = cloud::signIn, onRegister = cloud::register,
                    onClose = { showSignIn = false; cloud.clearAuthError() })
            }
            finished?.let { WorkoutSummaryDialog(it, main::dismissSummary) }
        }
    }
}
