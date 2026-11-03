package com.fitagent.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/** The clock screens read "now" from. Screenshot tests provide a fixed clock so snapshots never depend on the date. */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
