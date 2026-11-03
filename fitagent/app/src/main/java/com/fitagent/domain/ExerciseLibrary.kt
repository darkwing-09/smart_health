package com.fitagent.domain

/*
 * Dorm workout library (S3): no equipment, small floor space, and a "quiet" flag for hostel rooms (no jumping).
 * Demos are drawn by ui/components/PoseFigure.kt from the keyframes below — original, offline, no asset licences.
 *
 * Pose angles are screen degrees: 0 = pointing right (the way the figure faces), 90 = straight down,
 * −90 = straight up, 180 = pointing left. Torso is the hip → shoulder direction; limbs are absolute directions
 * of the upper and lower segment. "Near" limbs are drawn brighter than "far" ones.
 */

data class Pose(
    val hipX: Float, val hipY: Float, val torso: Float, val head: Float = torso,
    val nearThigh: Float, val nearShin: Float, val farThigh: Float, val farShin: Float,
    val nearArm: Float, val nearForearm: Float, val farArm: Float, val farForearm: Float,
)

object Poses {
    // Segment lengths (× figure height): torso 0.27, thigh/shin 0.20, upper arm 0.15, forearm 0.14; floor at y = 0.92.
    // Floor-contact poses below are solved from those lengths so hands, knees and feet land on the floor line.
    val STAND = Pose(0.5f, 0.5f, -90f, -90f, 92f, 90f, 88f, 90f, 95f, 95f, 85f, 85f)
    val ARMS_UP = STAND.copy(nearArm = -95f, nearForearm = -95f, farArm = -85f, farForearm = -85f)
    val SQUAT = Pose(0.42f, 0.69f, -55f, -60f, 8f, 100f, 12f, 102f, 0f, 0f, -5f, -5f)
    val HALF_SQUAT = Pose(0.45f, 0.6f, -70f, -75f, 40f, 105f, 44f, 105f, 20f, 20f, 15f, 15f)
    /** Straight body at 25.6° so both hands (under shoulders) and toes touch the floor. */
    val PLANK_HIGH = Pose(0.55f, 0.747f, -25.6f, -25.6f, 154.4f, 154.4f, 155f, 155f, 90f, 90f, 88f, 88f)
    val PUSH_DOWN = Pose(0.55f, 0.85f, -10f, -10f, 170f, 170f, 170.5f, 170.5f, 165f, 34f, 166f, 34f)
    val PLANK_FOREARM = Pose(0.55f, 0.83f, -12.9f, -12.9f, 167.1f, 167.1f, 167.6f, 167.6f, 90f, 0f, 90f, 2f)
    val KNEE_PLANK = Pose(0.55f, 0.797f, -38f, -38f, 142f, 185f, 142.5f, 185f, 90f, 90f, 88f, 88f)
    val KNEE_PUSH_DOWN = Pose(0.55f, 0.868f, -15f, -15f, 165f, 185f, 165.5f, 185f, 165f, 36f, 166f, 36f)
    val CLIMB_NEAR = PLANK_HIGH.copy(nearThigh = 50f, nearShin = 175f)
    val CLIMB_FAR = PLANK_HIGH.copy(farThigh = 50f, farShin = 175f)
    /** Deep reverse lunge: front thigh level, back knee just above the floor. */
    val LUNGE = Pose(0.5f, 0.7f, -90f, -90f, 0f, 92f, 100f, 175f, 95f, 95f, 85f, 85f)
    val HIGH_KNEE_NEAR = STAND.copy(nearThigh = -5f, nearShin = 85f, nearArm = 120f, nearForearm = 40f, farArm = 60f, farForearm = 140f)
    val HIGH_KNEE_FAR = STAND.copy(farThigh = -5f, farShin = 85f, nearArm = 60f, nearForearm = 140f, farArm = 120f, farForearm = 40f)
    /** On the back, head to the left, knees bent with feet flat. */
    val BRIDGE_DOWN = Pose(0.5f, 0.87f, 180f, 180f, -45f, 75f, -43f, 77f, 8f, 0f, 10f, 0f)
    val BRIDGE_UP = Pose(0.52f, 0.74f, 146.3f, 170f, 0f, 65f, 2f, 67f, 20f, 5f, 22f, 5f)
    val LYING = BRIDGE_DOWN.copy(nearArm = 20f, nearForearm = -20f, farArm = 22f, farForearm = -18f)
    val CRUNCH = LYING.copy(torso = 200f, head = 215f)
    /** Face down, head to the right. */
    val PRONE = Pose(0.45f, 0.89f, 0f, 0f, 180f, 180f, 181f, 181f, -5f, -5f, -3f, -3f)
    val SUPERMAN = Pose(0.45f, 0.89f, -12f, -15f, 190f, 192f, 191f, 193f, -15f, -15f, -12f, -12f)
    val CHILD = Pose(0.4f, 0.8f, 15f, 35f, 30f, 180f, 32f, 182f, 8f, 0f, 10f, 0f)
    val COBRA = Pose(0.45f, 0.89f, -30f, -45f, 180f, 180f, 181f, 181f, 110f, 10f, 112f, 12f)
    val DOWN_DOG = Pose(0.5f, 0.52f, 50f, 60f, 100f, 100f, 102f, 102f, 45f, 45f, 47f, 47f)
    val WALL_SIT = Pose(0.4f, 0.72f, -90f, -90f, 0f, 90f, 2f, 90f, 40f, -10f, 42f, -8f)
    val CALF_UP = STAND.copy(hipY = 0.47f)
    val SIDE_REACH = STAND.copy(torso = -110f, head = -115f, nearArm = -110f, nearForearm = -120f)
    val TOE_TOUCH = Pose(0.5f, 0.5f, 70f, 85f, 95f, 90f, 93f, 90f, 140f, 140f, 138f, 138f)
    val STEP_UP = Pose(0.5f, 0.48f, -88f, -88f, 20f, 100f, 95f, 92f, 95f, 95f, 85f, 85f)
    val JUMP = ARMS_UP.copy(hipY = 0.4f, nearThigh = 100f, nearShin = 105f, farThigh = 95f, farShin = 100f)
    /** Hands on a chair seat behind (≈ y 0.70), feet on the floor. */
    val DIP_UP = Pose(0.45f, 0.72f, -85f, -85f, 0f, 90f, 2f, 92f, 120f, 120f, 122f, 122f)
    val DIP_DOWN = Pose(0.45f, 0.8f, -80f, -80f, -20f, 95f, -18f, 97f, 165f, 60f, 166f, 62f)
    /** Hands and knees: hands under shoulders and knees under hips both on the floor. */
    val QUADRUPED = Pose(0.45f, 0.72f, -19.5f, -19.5f, 90f, 180f, 92f, 182f, 90f, 90f, 92f, 92f)
    val DONKEY_KICK = QUADRUPED.copy(nearThigh = 195f, nearShin = -80f)
    val BIRD_DOG = QUADRUPED.copy(nearArm = -10f, nearForearm = -10f, farThigh = 185f, farShin = 185f)
    val DEAD_BUG = Pose(0.5f, 0.87f, 180f, 180f, -90f, 0f, -88f, 2f, -90f, -90f, -88f, -88f)
    val DEAD_BUG_REACH = DEAD_BUG.copy(nearThigh = -15f, nearShin = -10f, farArm = 190f, farForearm = 190f)
    val FLUTTER_A = Pose(0.5f, 0.87f, 180f, 180f, -25f, -25f, -8f, -8f, 8f, 0f, 10f, 0f)
    val FLUTTER_B = FLUTTER_A.copy(nearThigh = -8f, nearShin = -8f, farThigh = -25f, farShin = -25f)
    val KNEES_IN = Pose(0.5f, 0.87f, 180f, 180f, -115f, -20f, -113f, -18f, 8f, 0f, 10f, 0f)
    val KNEES_OUT = KNEES_IN.copy(nearThigh = -60f, nearShin = 30f, farThigh = -58f, farShin = 32f)
    val SUMO_SQUAT = SQUAT.copy(torso = -78f, head = -80f, nearArm = 60f, nearForearm = -30f, farArm = 62f, farForearm = -28f)
    val PIKE_DOWN = DOWN_DOG.copy(torso = 62f, head = 75f, nearArm = 120f, nearForearm = 40f, farArm = 122f, farForearm = 42f)
    val LOW_LUNGE = LUNGE.copy(torso = -95f, head = -95f, nearArm = -100f, nearForearm = -100f, farArm = -95f, farForearm = -95f)
    val ARMS_FORWARD = STAND.copy(nearArm = 0f, nearForearm = 0f, farArm = 2f, farForearm = 2f)
    val ARMS_HIGH = STAND.copy(nearArm = -45f, nearForearm = -45f, farArm = -43f, farForearm = -43f)
}

enum class BodyArea(val label: String) { FULL("Full body"), LEGS("Legs"), CORE("Core"), UPPER("Upper body"), MOBILITY("Mobility"), CARDIO("Cardio") }
enum class Level(val label: String) { BEGINNER("Beginner"), INTERMEDIATE("Intermediate"), ADVANCED("Advanced") }

data class Exercise(
    val id: String,
    val name: String,
    val area: BodyArea,
    val level: Level,
    /** No jumping or stomping — safe for a hostel room at night. */
    val quiet: Boolean,
    val cues: List<String>,
    val mistakes: List<String>,
    val easier: String,
    val keyframes: List<Pose>,
    /** One full movement cycle, in milliseconds. */
    val cycleMs: Int = 2000,
)

object ExerciseLibrary {
    private fun ex(id: String, name: String, area: BodyArea, level: Level, quiet: Boolean, cues: List<String>, mistakes: List<String>,
                   easier: String, frames: List<Pose>, cycleMs: Int = 2000) =
        Exercise(id, name, area, level, quiet, cues, mistakes, easier, frames, cycleMs)

    val all: List<Exercise> = listOf(
        ex("squat", "Bodyweight squat", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Feet shoulder-width, toes slightly out", "Sit back and down, chest proud", "Drive through your heels to stand"),
            listOf("Knees caving in", "Heels lifting"), "Squat to a chair or your bed edge", listOf(Poses.STAND, Poses.SQUAT)),
        ex("pushup", "Push-up", BodyArea.UPPER, Level.INTERMEDIATE, true,
            listOf("Hands under shoulders", "Body in one straight line", "Lower until elbows reach 90°"),
            listOf("Hips sagging", "Elbows flaring wide"), "Hands on the bed or a desk (incline push-up)", listOf(Poses.PLANK_HIGH, Poses.PUSH_DOWN)),
        ex("plank", "Forearm plank", BodyArea.CORE, Level.BEGINNER, true,
            listOf("Elbows under shoulders", "Squeeze glutes and brace your stomach", "Breathe steadily"),
            listOf("Hips too high or sagging", "Holding your breath"), "Knees down plank", listOf(Poses.PLANK_FOREARM, Poses.PLANK_FOREARM.copy(hipY = 0.825f)), 3000),
        ex("lunge", "Reverse lunge", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Step back, lower the back knee", "Front knee over the ankle", "Push through the front heel to return"),
            listOf("Front knee past toes", "Leaning forward"), "Hold the wall for balance", listOf(Poses.STAND, Poses.LUNGE)),
        ex("glute_bridge", "Glute bridge", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Feet flat, knees bent", "Squeeze glutes to lift hips", "Pause at the top"),
            listOf("Arching the lower back", "Pushing through toes"), "Smaller range, hold 2 s at the top", listOf(Poses.BRIDGE_DOWN, Poses.BRIDGE_UP)),
        ex("mountain_climber", "Mountain climbers", BodyArea.CARDIO, Level.INTERMEDIATE, true,
            listOf("Start in a high plank", "Drive knees toward your chest", "Keep hips level"),
            listOf("Bouncing hips up", "Hands drifting forward"), "Slow, step-in climbers", listOf(Poses.CLIMB_NEAR, Poses.PLANK_HIGH, Poses.CLIMB_FAR, Poses.PLANK_HIGH), 1200),
        ex("high_knees", "High knees (march)", BodyArea.CARDIO, Level.BEGINNER, true,
            listOf("Knees to hip height", "Pump your arms", "Land softly on the balls of your feet"),
            listOf("Leaning back", "Stomping"), "Marching pace", listOf(Poses.HIGH_KNEE_NEAR, Poses.STAND, Poses.HIGH_KNEE_FAR, Poses.STAND), 1000),
        ex("jumping_jacks", "Star jumps", BodyArea.CARDIO, Level.BEGINNER, false,
            listOf("Jump and raise arms overhead", "Land softly with bent knees", "Keep a steady rhythm"),
            listOf("Landing stiff-legged"), "Step-out jacks (quiet)", listOf(Poses.STAND, Poses.JUMP), 900),
        ex("burpee", "Burpee", BodyArea.FULL, Level.ADVANCED, false,
            listOf("Squat, hands down", "Jump or step back to plank", "Return and jump up"),
            listOf("Sagging hips in the plank", "Landing heavily"), "No-jump burpee: step back and stand up", listOf(Poses.STAND, Poses.SQUAT, Poses.PLANK_HIGH, Poses.SQUAT, Poses.JUMP), 3000),
        ex("superman", "Superman", BodyArea.CORE, Level.BEGINNER, true,
            listOf("Lie face down", "Lift arms, chest and legs a little", "Look at the floor, neck long"),
            listOf("Cranking the neck up"), "Lift only arms, then only legs", listOf(Poses.PRONE, Poses.SUPERMAN), 2500),
        ex("crunch", "Crunch", BodyArea.CORE, Level.BEGINNER, true,
            listOf("Knees bent, feet flat", "Curl shoulders off the floor", "Exhale as you lift"),
            listOf("Pulling on your neck"), "Smaller range of motion", listOf(Poses.LYING, Poses.CRUNCH)),
        ex("wall_sit", "Wall sit", BodyArea.LEGS, Level.INTERMEDIATE, true,
            listOf("Back flat on the wall", "Thighs parallel to the floor", "Knees over ankles"),
            listOf("Knees past toes"), "Sit higher on the wall", listOf(Poses.WALL_SIT, Poses.WALL_SIT.copy(hipY = 0.715f)), 3000),
        ex("calf_raise", "Calf raises", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Rise onto the balls of your feet", "Pause at the top", "Lower slowly"),
            listOf("Bouncing"), "Hold a wall or desk", listOf(Poses.STAND, Poses.CALF_UP), 1600),
        ex("tricep_dip", "Chair dips", BodyArea.UPPER, Level.INTERMEDIATE, true,
            listOf("Hands on a sturdy chair or bed edge", "Lower until elbows reach 90°", "Press back up"),
            listOf("Shrugging shoulders", "Using a rolling chair"), "Bend knees more", listOf(Poses.DIP_UP, Poses.DIP_DOWN)),
        ex("step_up", "Step-ups", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Use a low, stable step or the bottom stair", "Drive through the top heel", "Step down with control"),
            listOf("Pushing off the bottom foot"), "Lower step", listOf(Poses.STAND, Poses.STEP_UP)),
        ex("squat_jump", "Squat jumps", BodyArea.LEGS, Level.ADVANCED, false,
            listOf("Squat down", "Explode up", "Land softly back into the squat"),
            listOf("Landing on straight legs"), "Squat + calf raise (quiet)", listOf(Poses.SQUAT, Poses.JUMP), 1400),
        ex("plank_shoulder_tap", "Plank shoulder taps", BodyArea.CORE, Level.INTERMEDIATE, true,
            listOf("High plank, feet wide", "Tap the opposite shoulder", "Keep hips still"),
            listOf("Rocking hips"), "From the knees", listOf(Poses.PLANK_HIGH, Poses.PLANK_HIGH.copy(nearArm = -30f, nearForearm = 200f)), 1400),
        ex("inchworm", "Inchworm", BodyArea.FULL, Level.INTERMEDIATE, true,
            listOf("Fold forward, hands to floor", "Walk hands out to plank", "Walk back and stand"),
            listOf("Rushing the walk-out"), "Bend knees as you fold", listOf(Poses.STAND, Poses.TOE_TOUCH, Poses.PLANK_HIGH, Poses.TOE_TOUCH), 4000),
        ex("down_dog", "Downward dog", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Hands and feet on the floor, hips high", "Press the floor away", "Heels toward the floor"),
            listOf("Rounding the back"), "Bend your knees", listOf(Poses.DOWN_DOG, Poses.DOWN_DOG.copy(hipY = 0.5f)), 4000),
        ex("child_pose", "Child's pose", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Knees wide, sit back on heels", "Arms long, forehead down", "Breathe into your back"),
            listOf("Forcing the stretch"), "Pillow under the hips", listOf(Poses.CHILD, Poses.CHILD.copy(torso = 12f)), 4000),
        ex("cobra", "Cobra", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Lie face down, hands by chest", "Gently lift the chest", "Shoulders away from ears"),
            listOf("Locking the elbows"), "Stay on forearms (sphinx)", listOf(Poses.PRONE, Poses.COBRA), 3500),
        ex("side_bend", "Standing side stretch", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Reach one arm overhead", "Lean gently to the side", "Switch sides"),
            listOf("Twisting forward"), "Hand on hip", listOf(Poses.STAND, Poses.SIDE_REACH), 3500),
        ex("toe_touch", "Forward fold", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Hinge at the hips", "Let the head hang", "Soft knees"),
            listOf("Bouncing"), "Hands on thighs or shins", listOf(Poses.STAND, Poses.TOE_TOUCH), 4000),
        ex("shadow_box", "Shadow boxing", BodyArea.CARDIO, Level.BEGINNER, true,
            listOf("Soft knees, guard up", "Alternate straight punches", "Exhale on each punch"),
            listOf("Locking elbows"), "Slower pace", listOf(
                Poses.HALF_SQUAT.copy(hipY = 0.55f, torso = -80f, nearArm = 0f, nearForearm = 0f, farArm = 60f, farForearm = -60f),
                Poses.HALF_SQUAT.copy(hipY = 0.55f, torso = -80f, nearArm = 60f, nearForearm = -60f, farArm = 0f, farForearm = 0f)), 800),
        ex("good_morning", "Good mornings", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Hands behind your head", "Hinge forward with a flat back", "Squeeze glutes to stand"),
            listOf("Rounding the spine"), "Smaller hinge", listOf(Poses.STAND.copy(nearArm = -60f, nearForearm = 160f), Poses.TOE_TOUCH.copy(torso = 0f, head = 10f, nearArm = -20f, nearForearm = 200f)), 2500),
        ex("bicycle", "Bicycle crunch", BodyArea.CORE, Level.INTERMEDIATE, true,
            listOf("Lift shoulders, hands by ears", "Elbow toward opposite knee", "Slow and controlled"),
            listOf("Pulling the neck"), "Feet on the floor, alternate knee lifts", listOf(Poses.CRUNCH.copy(nearThigh = -70f, nearShin = 20f, farThigh = 0f, farShin = 0f),
                Poses.CRUNCH.copy(nearThigh = 0f, nearShin = 0f, farThigh = -70f, farShin = 20f)), 1600),
        ex("pushup_knee", "Knee push-up", BodyArea.UPPER, Level.BEGINNER, true,
            listOf("Knees down, body straight from knees to head", "Lower chest toward the floor", "Press up"),
            listOf("Hips bending"), "Incline against a wall", listOf(Poses.KNEE_PLANK, Poses.KNEE_PUSH_DOWN)),
        ex("lateral_lunge", "Side lunge", BodyArea.LEGS, Level.INTERMEDIATE, true,
            listOf("Wide stance", "Sit into one hip, other leg straight", "Push back to centre"),
            listOf("Knee caving in"), "Shorter step", listOf(Poses.STAND, Poses.HALF_SQUAT)),
        ex("skater", "Skaters (step)", BodyArea.CARDIO, Level.INTERMEDIATE, true,
            listOf("Step wide side to side", "Swing arms across", "Stay low"),
            listOf("Standing tall between reps"), "Smaller steps", listOf(Poses.HALF_SQUAT, Poses.STAND, Poses.HALF_SQUAT.copy(nearArm = -20f, nearForearm = -20f), Poses.STAND), 1400),
        ex("donkey_kick", "Donkey kicks", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Hands and knees, back flat", "Kick one heel up toward the ceiling", "Squeeze the glute at the top"),
            listOf("Arching the lower back"), "Smaller kick, slower", listOf(Poses.QUADRUPED, Poses.DONKEY_KICK), 1800),
        ex("bird_dog", "Bird dog", BodyArea.CORE, Level.BEGINNER, true,
            listOf("Hands and knees", "Reach one arm forward and the opposite leg back", "Hold 2 s, keep hips level"),
            listOf("Rotating the hips"), "Move only the leg", listOf(Poses.QUADRUPED, Poses.BIRD_DOG), 3000),
        ex("dead_bug", "Dead bug", BodyArea.CORE, Level.BEGINNER, true,
            listOf("On your back, arms up, knees over hips", "Lower opposite arm and leg slowly", "Lower back stays on the floor"),
            listOf("Back lifting off the floor"), "Move only the legs", listOf(Poses.DEAD_BUG, Poses.DEAD_BUG_REACH), 3000),
        ex("flutter_kicks", "Flutter kicks", BodyArea.CORE, Level.INTERMEDIATE, true,
            listOf("On your back, legs straight and low", "Small fast up-and-down kicks", "Press the lower back down"),
            listOf("Arching the back"), "Legs higher, hands under hips", listOf(Poses.FLUTTER_A, Poses.FLUTTER_B), 700),
        ex("reverse_crunch", "Reverse crunch", BodyArea.CORE, Level.BEGINNER, true,
            listOf("On your back, knees bent", "Pull knees toward your chest, hips lift a little", "Lower slowly"),
            listOf("Swinging the legs"), "Smaller range", listOf(Poses.KNEES_OUT, Poses.KNEES_IN)),
        ex("sumo_squat", "Sumo squat", BodyArea.LEGS, Level.BEGINNER, true,
            listOf("Wide stance, toes turned out", "Sit straight down, chest tall", "Knees track over toes"),
            listOf("Knees caving in"), "Shallower squat", listOf(Poses.STAND, Poses.SUMO_SQUAT)),
        ex("pike_pushup", "Pike push-up", BodyArea.UPPER, Level.ADVANCED, true,
            listOf("Hips high in an inverted V", "Bend elbows to bring the head toward the floor", "Press back up"),
            listOf("Elbows flaring"), "Hands on the bed edge", listOf(Poses.DOWN_DOG, Poses.PIKE_DOWN)),
        ex("hip_flexor_stretch", "Low lunge stretch", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Back knee down, front foot forward", "Reach arms up, tuck the pelvis", "Feel the stretch at the front of the back hip"),
            listOf("Arching the lower back"), "Hands on the front knee", listOf(Poses.LUNGE, Poses.LOW_LUNGE), 4000),
        ex("arm_circles", "Arm circles", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("Arms out at shoulder height", "Small circles, growing bigger", "Switch direction halfway"),
            listOf("Shrugging the shoulders"), "Smaller circles", listOf(Poses.ARMS_FORWARD, Poses.ARMS_HIGH, Poses.ARMS_UP, Poses.ARMS_HIGH), 1600),
        ex("jump_lunge", "Jump lunges", BodyArea.LEGS, Level.ADVANCED, false,
            listOf("Start in a lunge", "Jump and switch legs in the air", "Land softly back in a lunge"),
            listOf("Front knee collapsing inward"), "Alternating reverse lunges (quiet)", listOf(Poses.LUNGE, Poses.JUMP), 1400),
        ex("breathing", "Box breathing", BodyArea.MOBILITY, Level.BEGINNER, true,
            listOf("In for 4", "Hold for 4", "Out for 4, hold for 4"),
            listOf("Shallow chest breathing"), "Shorter counts (3-3-3-3)", listOf(Poses.STAND, Poses.STAND.copy(nearArm = 80f, nearForearm = 80f)), 8000),
    )

    fun byId(id: String): Exercise = all.first { it.id == id }
}

/** One step in a routine. */
data class RoutineStep(val exerciseId: String, val seconds: Int)

data class Routine(
    val id: String,
    val name: String,
    val description: String,
    val level: Level,
    val type: WorkoutType,
    val restSeconds: Int,
    val steps: List<RoutineStep>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds } + restSeconds * (steps.size - 1).coerceAtLeast(0)
    val minutes: Int get() = (totalSeconds + 59) / 60
    val quiet: Boolean get() = steps.all { ExerciseLibrary.byId(it.exerciseId).quiet }
}

object Routines {
    private fun r(id: String, name: String, desc: String, level: Level, type: WorkoutType, rest: Int, vararg steps: Pair<String, Int>) =
        Routine(id, name, desc, level, type, rest, steps.map { RoutineStep(it.first, it.second) })

    val all: List<Routine> = listOf(
        r("wake_up", "5-min wake-up", "Gentle mobility to start the day", Level.BEGINNER, WorkoutType.YOGA, 5,
            "side_bend" to 40, "toe_touch" to 40, "cobra" to 40, "child_pose" to 40, "down_dog" to 40, "breathing" to 40),
        r("study_break", "Study-break stretch", "3 minutes, no sweat — undo the desk slump", Level.BEGINNER, WorkoutType.YOGA, 5,
            "arm_circles" to 30, "side_bend" to 30, "hip_flexor_stretch" to 30, "good_morning" to 30, "toe_touch" to 30, "breathing" to 40),
        r("quiet_full_body", "Quiet full body", "No jumping — fine for a hostel room at night", Level.BEGINNER, WorkoutType.STRENGTH, 15,
            "squat" to 40, "pushup_knee" to 40, "glute_bridge" to 40, "lunge" to 40, "plank" to 30, "superman" to 30, "calf_raise" to 40),
        r("core_10", "10-min core", "Stable middle for sport and posture", Level.INTERMEDIATE, WorkoutType.STRENGTH, 15,
            "plank" to 40, "dead_bug" to 40, "superman" to 40, "bicycle" to 40, "plank_shoulder_tap" to 40, "reverse_crunch" to 40,
            "bird_dog" to 40, "flutter_kicks" to 30, "child_pose" to 40),
        r("legs_15", "Leg day, no gym", "Strong legs with just the floor and a wall", Level.INTERMEDIATE, WorkoutType.STRENGTH, 20,
            "squat" to 45, "lunge" to 45, "wall_sit" to 40, "glute_bridge" to 45, "lateral_lunge" to 45, "sumo_squat" to 45,
            "donkey_kick" to 45, "step_up" to 45, "calf_raise" to 45, "wall_sit" to 40),
        r("upper_12", "Upper body", "Push, dip and hold", Level.INTERMEDIATE, WorkoutType.STRENGTH, 20,
            "arm_circles" to 30, "pushup" to 40, "tricep_dip" to 40, "pike_pushup" to 30, "plank_shoulder_tap" to 40, "pushup_knee" to 40,
            "superman" to 40, "tricep_dip" to 40, "plank" to 40),
        r("cardio_quiet", "Quiet cardio", "Heart rate up without the stomping", Level.BEGINNER, WorkoutType.HIIT, 15,
            "high_knees" to 40, "shadow_box" to 40, "skater" to 40, "mountain_climber" to 30, "high_knees" to 40, "shadow_box" to 40),
        r("hiit_20", "20-min HIIT", "Hard intervals for a push day", Level.ADVANCED, WorkoutType.HIIT, 15,
            "jumping_jacks" to 40, "squat_jump" to 40, "burpee" to 40, "mountain_climber" to 40, "high_knees" to 40,
            "pushup" to 40, "skater" to 40, "burpee" to 40, "plank" to 40, "squat_jump" to 40, "jumping_jacks" to 40),
        r("beginner_7", "First workout", "7 easy moves to learn the basics", Level.BEGINNER, WorkoutType.STRENGTH, 20,
            "squat" to 30, "pushup_knee" to 30, "glute_bridge" to 30, "high_knees" to 30, "plank" to 20, "lunge" to 30, "child_pose" to 30),
        r("exam_reset", "Exam-day reset", "Calm, easy movement before a big day", Level.BEGINNER, WorkoutType.YOGA, 5,
            "breathing" to 60, "side_bend" to 40, "cobra" to 40, "child_pose" to 40, "down_dog" to 40, "breathing" to 60),
        r("bedtime", "Wind-down stretch", "Slow stretches before sleep", Level.BEGINNER, WorkoutType.YOGA, 5,
            "child_pose" to 60, "cobra" to 45, "toe_touch" to 45, "side_bend" to 45, "breathing" to 90),
        r("full_body_25", "25-min full body", "Strength + cardio for a strong day", Level.ADVANCED, WorkoutType.STRENGTH, 20,
            "inchworm" to 45, "squat" to 45, "pushup" to 45, "lunge" to 45, "mountain_climber" to 40, "glute_bridge" to 45,
            "tricep_dip" to 45, "skater" to 40, "plank_shoulder_tap" to 40, "wall_sit" to 45, "burpee" to 40, "superman" to 40,
            "bicycle" to 40, "child_pose" to 45),
    )

    fun byId(id: String): Routine = all.first { it.id == id }
}

/** Where a guided routine is after [elapsedSec]: current step index (or null when done), whether resting, seconds left. */
data class RoutineProgress(val index: Int?, val resting: Boolean, val secondsLeft: Int, val totalLeft: Int)

fun routineProgressAt(r: Routine, elapsedSec: Long): RoutineProgress {
    var t = elapsedSec
    r.steps.forEachIndexed { i, s ->
        if (t < s.seconds) return RoutineProgress(i, false, (s.seconds - t).toInt(), (r.totalSeconds - elapsedSec).toInt())
        t -= s.seconds
        if (i < r.steps.lastIndex) {
            if (t < r.restSeconds) return RoutineProgress(i + 1, true, (r.restSeconds - t).toInt(), (r.totalSeconds - elapsedSec).toInt())
            t -= r.restSeconds
        }
    }
    return RoutineProgress(null, false, 0, 0)
}
