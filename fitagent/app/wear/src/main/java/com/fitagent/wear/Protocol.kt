package com.fitagent.wear

/*
 * Watch ⇄ phone Data Layer protocol. Mirrored in the phone app (com.fitagent.wearsync.WearProtocol) — keep in sync.
 *
 * Watch → phone: one DataItem per finished workout at /fitagent/workout/<uid>. DataItems are durable: Play services
 * delivers them whenever the phone is reachable, even days later. The phone saves the workout (idempotent by uid)
 * and then deletes the item — the deletion is the acknowledgement.
 *
 * Phone → watch: /fitagent/summary holds the latest readiness + today's progress for the app, tile and complication.
 */
object Protocol {
    const val WORKOUT_PREFIX = "/fitagent/workout/"
    const val SUMMARY_PATH = "/fitagent/summary"
    const val VERSION = 1

    const val K_VERSION = "v"
    const val K_UID = "uid"
    const val K_TYPE = "type"
    const val K_START = "start_ms"
    const val K_END = "end_ms"
    const val K_ACTIVE = "active_s"
    const val K_KCAL = "kcal"
    const val K_AVG_HR = "avg_hr"
    const val K_MAX_HR = "max_hr"
    const val K_ZONES = "zone_s"
    const val K_DISTANCE = "distance_m"

    const val K_SCORE = "score"
    const val K_BAND = "band"
    const val K_STEPS = "steps"
    const val K_STEP_GOAL = "step_goal"
    const val K_STREAK = "streak"
    const val K_UPDATED = "updated_ms"
}
