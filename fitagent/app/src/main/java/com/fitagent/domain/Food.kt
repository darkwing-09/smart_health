package com.fitagent.domain

import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * Food log (S4). Entries stay on the phone. Built-in values are typical Indian hostel/mess portions
 * (approximate, from common nutrition tables) so logging works offline and without an AI key.
 */

enum class Meal(val label: String) { BREAKFAST("Breakfast"), LUNCH("Lunch"), SNACKS("Snacks"), DINNER("Dinner") }

data class FoodEntry(
    val id: Long = 0,
    val date: LocalDate,
    val meal: Meal,
    val name: String,
    val portion: String,
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fromPhoto: Boolean = false,
)

/** One built-in food with values for one typical portion. */
data class FoodRef(val name: String, val portion: String, val kcal: Int, val protein: Double, val carbs: Double, val fat: Double)

val INDIAN_FOODS: List<FoodRef> = listOf(
    FoodRef("Chapati / roti", "1 piece", 100, 3.0, 18.0, 2.5),
    FoodRef("Plain rice", "1 cup cooked", 200, 4.0, 44.0, 0.5),
    FoodRef("Jeera rice", "1 cup", 240, 4.5, 44.0, 5.0),
    FoodRef("Dal tadka", "1 katori", 150, 9.0, 20.0, 4.0),
    FoodRef("Sambar", "1 katori", 130, 6.0, 18.0, 4.0),
    FoodRef("Rajma", "1 katori", 220, 10.0, 30.0, 6.0),
    FoodRef("Chole", "1 katori", 230, 10.0, 30.0, 8.0),
    FoodRef("Paneer curry", "1 katori", 300, 14.0, 10.0, 22.0),
    FoodRef("Mixed veg sabzi", "1 katori", 120, 3.0, 14.0, 6.0),
    FoodRef("Aloo sabzi", "1 katori", 180, 3.0, 26.0, 7.0),
    FoodRef("Egg curry", "2 eggs", 280, 13.0, 8.0, 21.0),
    FoodRef("Boiled egg", "1 egg", 78, 6.3, 0.6, 5.3),
    FoodRef("Omelette", "2 eggs", 190, 13.0, 2.0, 14.0),
    FoodRef("Chicken curry", "1 katori", 300, 25.0, 8.0, 18.0),
    FoodRef("Fish curry", "1 katori", 240, 22.0, 6.0, 14.0),
    FoodRef("Curd / dahi", "1 katori", 100, 6.0, 8.0, 5.0),
    FoodRef("Raita", "1 katori", 90, 4.0, 8.0, 4.0),
    FoodRef("Idli", "2 pieces", 80, 3.0, 16.0, 0.4),
    FoodRef("Plain dosa", "1 dosa", 170, 4.0, 28.0, 5.0),
    FoodRef("Masala dosa", "1 dosa", 300, 6.0, 42.0, 12.0),
    FoodRef("Poha", "1 plate", 250, 5.0, 45.0, 6.0),
    FoodRef("Upma", "1 plate", 250, 6.0, 38.0, 8.0),
    FoodRef("Aloo paratha", "1 piece", 280, 6.0, 38.0, 12.0),
    FoodRef("Puri", "2 pieces", 200, 3.0, 22.0, 11.0),
    FoodRef("Khichdi", "1 bowl", 250, 9.0, 40.0, 6.0),
    FoodRef("Veg biryani", "1 plate", 400, 9.0, 60.0, 13.0),
    FoodRef("Chicken biryani", "1 plate", 500, 25.0, 60.0, 17.0),
    FoodRef("Maggi noodles", "1 pack", 350, 8.0, 48.0, 14.0),
    FoodRef("Samosa", "1 piece", 260, 4.0, 28.0, 15.0),
    FoodRef("Vada pav", "1 piece", 290, 6.0, 40.0, 12.0),
    FoodRef("Bread omelette", "1 serving", 300, 15.0, 28.0, 14.0),
    FoodRef("Peanut butter toast", "2 slices", 330, 12.0, 32.0, 17.0),
    FoodRef("Banana", "1 medium", 105, 1.3, 27.0, 0.4),
    FoodRef("Apple", "1 medium", 95, 0.5, 25.0, 0.3),
    FoodRef("Sprouts chaat", "1 bowl", 150, 10.0, 24.0, 1.5),
    FoodRef("Roasted chana", "1 handful (30 g)", 110, 6.0, 18.0, 2.0),
    FoodRef("Milk", "1 glass (250 ml)", 150, 8.0, 12.0, 8.0),
    FoodRef("Chai with sugar", "1 cup", 90, 2.5, 12.0, 3.0),
    FoodRef("Coffee with milk", "1 cup", 80, 3.0, 9.0, 3.5),
    FoodRef("Lassi (sweet)", "1 glass", 220, 7.0, 34.0, 6.0),
    FoodRef("Soya chunks curry", "1 katori", 180, 18.0, 12.0, 6.0),
    FoodRef("Whey protein shake", "1 scoop in water", 120, 24.0, 3.0, 1.5),
)

data class DayNutrition(val kcal: Int, val protein: Double, val carbs: Double, val fat: Double)

fun dayNutrition(entries: List<FoodEntry>): DayNutrition = DayNutrition(
    kcal = entries.sumOf { it.kcal },
    protein = entries.sumOf { it.proteinG },
    carbs = entries.sumOf { it.carbsG },
    fat = entries.sumOf { it.fatG },
)

/** Protein target for active students: 1.2 g per kg (within the 1.0–1.6 g/kg range for regular training). */
fun proteinGoal(weightKg: Double): Int = (weightKg * 1.2).roundToInt()

/** Meal for the time of day, so a photo taken at 13:10 defaults to lunch. */
fun mealFor(hour: Int): Meal = when (hour) {
    in 4..10 -> Meal.BREAKFAST
    in 11..15 -> Meal.LUNCH
    in 16..18 -> Meal.SNACKS
    else -> Meal.DINNER
}

/** Encouraging, non-judgemental line about the day's protein (never about eating less). */
fun proteinHeadline(protein: Double, goal: Int): String = when {
    protein <= 0 -> "Log a meal to see your protein for the day."
    protein >= goal -> "Protein goal reached — great for recovery."
    else -> "${(goal - protein).roundToInt()} g protein to go — dal, eggs, curd, paneer or chana are easy wins."
}

fun searchFoods(query: String): List<FoodRef> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return INDIAN_FOODS
    return INDIAN_FOODS.filter { q in it.name.lowercase() }.sortedBy { !it.name.lowercase().startsWith(q) }
}
