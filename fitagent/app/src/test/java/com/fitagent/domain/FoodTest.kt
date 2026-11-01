package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FoodTest {
    @Test fun builtInTableIsSane() {
        assertTrue(INDIAN_FOODS.size >= 40)
        INDIAN_FOODS.forEach { f ->
            // Calories roughly agree with macros (4/4/9 kcal per g) — catches typos in the table.
            val fromMacros = f.protein * 4 + f.carbs * 4 + f.fat * 9
            assertTrue("${f.name}: ${f.kcal} vs $fromMacros", kotlin.math.abs(fromMacros - f.kcal) <= f.kcal * 0.25 + 15)
        }
    }

    @Test fun totalsMealsAndProtein() {
        val d = LocalDate.of(2026, 11, 2)
        val entries = listOf(FoodEntry(0, d, Meal.LUNCH, "Dal", "1", 150, 9.0, 20.0, 4.0), FoodEntry(0, d, Meal.LUNCH, "Roti", "2", 200, 6.0, 36.0, 5.0))
        assertEquals(DayNutrition(350, 15.0, 56.0, 9.0), dayNutrition(entries))
        assertEquals(70, proteinGoal(58.0))
        assertEquals(Meal.LUNCH, mealFor(13))
        assertEquals(Meal.DINNER, mealFor(21))
        assertTrue(proteinHeadline(30.0, 70).startsWith("40 g protein to go"))
        listOf(proteinHeadline(0.0, 70), proteinHeadline(80.0, 70)).forEach { assertTrue(!it.contains("less", ignoreCase = true)) }
    }

    @Test fun searchRanksPrefixMatchesFirst() {
        assertEquals("Dal tadka", searchFoods("dal").first().name)
        assertTrue(searchFoods("egg").all { "egg" in it.name.lowercase() })
    }
}
