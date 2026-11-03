package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test fun libraryIsComplete() {
        assertTrue(ExerciseLibrary.all.size >= 40)
        assertEquals(ExerciseLibrary.all.size, ExerciseLibrary.all.map { it.id }.toSet().size)
        ExerciseLibrary.all.forEach { e ->
            assertTrue("${e.id}: 3 cues", e.cues.size >= 3)
            assertTrue("${e.id}: mistakes", e.mistakes.isNotEmpty())
            assertTrue("${e.id}: easier version", e.easier.isNotBlank())
            assertTrue("${e.id}: keyframes", e.keyframes.isNotEmpty())
            e.keyframes.forEach { p -> assertTrue("${e.id}: figure on screen", p.hipX in 0.2f..0.8f && p.hipY in 0.3f..0.9f) }
        }
    }

    @Test fun routinesReferenceRealExercisesAndQuietFlagIsHonest() {
        assertEquals(12, Routines.all.size)
        Routines.all.forEach { r ->
            r.steps.forEach { ExerciseLibrary.byId(it.exerciseId) }
            assertTrue("${r.id}: 3–30 min", r.minutes in 3..30)
        }
        assertTrue(Routines.byId("quiet_full_body").quiet)
        assertTrue(!Routines.byId("hiit_20").quiet)
    }

    @Test fun progressWalksThroughStepsAndRests() {
        val r = Routine("t", "t", "", Level.BEGINNER, WorkoutType.STRENGTH, 10, listOf(RoutineStep("squat", 30), RoutineStep("plank", 20)))
        assertEquals(60, r.totalSeconds)
        assertEquals(RoutineProgress(0, false, 30, 60), routineProgressAt(r, 0))
        assertEquals(RoutineProgress(1, true, 5, 25), routineProgressAt(r, 35))
        assertEquals(RoutineProgress(1, false, 20, 20), routineProgressAt(r, 40))
        assertNull(routineProgressAt(r, 60).index)
    }
}
