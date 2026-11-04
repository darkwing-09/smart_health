package com.fitagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fitagent.domain.Goals
import com.fitagent.domain.Profile
import com.fitagent.ui.CloudState
import com.fitagent.ui.components.ActivityRings
import com.fitagent.ui.components.Ring
import com.fitagent.ui.theme.Fit

@Composable
fun SignInScreen(
    cloud: CloudState,
    defaultName: String,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var register by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val valid = emailOk && password.length >= 8 && (!register || name.isNotBlank())

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            Text(if (register) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text("Unlock your AI coach, weekly plans and challenges with friends.",
                style = MaterialTheme.typography.bodyMedium, color = Fit.colors.muted)
            Spacer(Modifier.height(24.dp))
            if (register) {
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                isError = email.isNotBlank() && !emailOk, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                password, { if (it.length <= 128) password = it }, label = { Text("Password") }, singleLine = true,
                supportingText = { if (register) Text("At least 8 characters") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (showPassword) "Hide password" else "Show password")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            cloud.authError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (register) onRegister(email, password, name) else onSignIn(email, password) },
                enabled = valid && !cloud.authBusy, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (cloud.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (register) "Create account" else "Sign in")
            }
            TextButton(onClick = { register = !register }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (register) "Already have an account? Sign in" else "New here? Create an account")
            }
            Spacer(Modifier.height(12.dp))
            Text("Server: ${cloud.serverUrl} (change under You → Cloud account)",
                style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
        }
    }
}

private enum class Level(val label: String, val goals: Goals) {
    STARTER("Just starting", Goals(6_000, 250, 20, 3)),
    ACTIVE("Fairly active", Goals(8_000, 400, 30, 4)),
    ATHLETE("Very active", Goals(11_000, 600, 45, 5)),
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(initial: Profile, onConnectHealth: () -> Unit, onFinish: (Profile) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var age by rememberSaveable { mutableStateOf(initial.ageYears.toString()) }
    var height by rememberSaveable { mutableStateOf(initial.heightCm.toString()) }
    var weight by rememberSaveable { mutableStateOf(initial.weightKg.toInt().toString()) }
    var level by rememberSaveable { mutableStateOf(Level.ACTIVE) }
    var gender by rememberSaveable { mutableStateOf(initial.gender) }
    var city by rememberSaveable { mutableStateOf(initial.city) }
    var whatsapp by rememberSaveable { mutableStateOf(initial.whatsappNumber.ifBlank { "+91 " }) }
    var reports by rememberSaveable { mutableStateOf(initial.whatsappReports || initial.whatsappNumber.isBlank()) }
    val normalized = com.fitagent.domain.normalizeWhatsapp(if (whatsapp.trim() == "+91") "" else whatsapp)
    val canContinue = when (step) {
        1 -> name.isNotBlank() && (age.toIntOrNull() ?: 0) in 13..99
        2 -> normalized != null && (!reports || normalized.isNotEmpty())
        else -> true
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i ->
                    Surface(Modifier.weight(1f).height(4.dp), shape = MaterialTheme.shapes.extraSmall,
                        color = if (i <= step) MaterialTheme.colorScheme.primary else Fit.colors.track) {}
                }
            }
            Spacer(Modifier.height(32.dp))
            when (step) {
                0 -> Welcome()
                1 -> {
                    Text("About you", style = MaterialTheme.typography.headlineLarge)
                    Text("Used for calorie estimates and to personalise your coach.", color = Fit.colors.muted)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(name, { if (it.length <= 40) name = it }, label = { Text("Name *") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(city, { if (it.length <= 60) city = it }, label = { Text("City / campus location") },
                        placeholder = { Text("e.g. Pune") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Gender", style = MaterialTheme.typography.labelLarge, color = Fit.colors.muted)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("female" to "Female", "male" to "Male", "other" to "Other", "prefer_not" to "Prefer not to say")
                            .forEach { (k, l) -> FilterChip(gender == k, { gender = k }, label = { Text(l) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("Age *", age, { age = it }, Modifier.weight(1f))
                        NumberField("Height cm", height, { height = it }, Modifier.weight(1f))
                        NumberField("Weight kg", weight, { weight = it }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("How active are you now?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Level.entries.forEach { l ->
                        FilterChip(level == l, { level = l }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            label = { Text("${l.label} · ${l.goals.dailySteps / 1000}k steps · ${l.goals.weeklyWorkouts} workouts/week") })
                    }
                }
                2 -> {
                    Text("Daily report", style = MaterialTheme.typography.headlineLarge)
                    Text("Get a one-page PDF of your day — readiness, steps, sleep, heart rate and workouts — on WhatsApp " +
                        "every evening.", color = Fit.colors.muted)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(whatsapp, { if (it.length <= 20) whatsapp = it }, label = { Text("WhatsApp number") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                        isError = normalized == null,
                        supportingText = { Text(if (normalized == null) "Include the country code, e.g. +91 98765 43210" else "With country code") })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        androidx.compose.material3.Checkbox(reports, { reports = it })
                        Column {
                            Text("Send me the daily PDF report on WhatsApp", style = MaterialTheme.typography.bodyMedium)
                            Text("Health data goes to this number only. Turn it off any time in You → Daily report.",
                                style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                        }
                    }
                    if (reports && normalized == "") {
                        Text("Add your number, or untick to skip.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    Text("Connect your data", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("FitAgent reads steps, workouts, sleep and heart rate through Android Health Connect — from your " +
                        "phone or any watch (Noise, boAt, Samsung, Fitbit, Wear OS). Data stays on your phone unless you " +
                        "create an account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onConnectHealth, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Connect Health Connect") }
                    Spacer(Modifier.height(8.dp))
                    Text("You can also skip this and log workouts manually.", style = MaterialTheme.typography.bodySmall,
                        color = Fit.colors.muted)
                }
            }
            Spacer(Modifier.height(32.dp))
            Row {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(enabled = canContinue, onClick = {
                    if (step < 3) step++ else onFinish(
                        initial.copy(
                            name = name.trim(),
                            ageYears = age.toIntOrNull()?.coerceIn(13, 99) ?: initial.ageYears,
                            heightCm = height.toIntOrNull()?.coerceIn(120, 230) ?: initial.heightCm,
                            weightKg = weight.toDoubleOrNull()?.coerceIn(30.0, 250.0) ?: initial.weightKg,
                            goals = level.goals,
                            city = city.trim(),
                            gender = gender,
                            whatsappNumber = normalized.orEmpty(),
                            whatsappReports = reports && !normalized.isNullOrEmpty(),
                        )
                    )
                }, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
                    Text(when (step) { 0 -> "Get started"; 1, 2 -> "Continue"; else -> "Let's go" })
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    val fc = Fit.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        ActivityRings(listOf(Ring(0.8f, fc.steps), Ring(0.6f, fc.move), Ring(0.9f, fc.workout)), Modifier.size(150.dp))
        Spacer(Modifier.height(24.dp))
        Text("FitAgent", style = MaterialTheme.typography.displayMedium)
        Text("Your AI fitness coach", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(28.dp))
    Feature(Icons.Rounded.Speed, "Know when to push", "A daily readiness score from your sleep, heart rate and training load.")
    Feature(Icons.Rounded.AutoAwesome, "A coach that knows you", "Ask anything. Get a safe 7-day plan built around your schedule.")
    Feature(Icons.Rounded.LocalFireDepartment, "Build the habit", "Rings, streaks with rest-day freezes, and achievements.")
    Feature(Icons.Rounded.Groups, "Move together", "Step challenges with friends, your hostel or your class.")
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Fit.colors.muted)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(3)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}
