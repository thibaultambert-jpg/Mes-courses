package fr.mescourses.core

import fr.mescourses.core.catalog.Course
import fr.mescourses.core.menu.MenuWishes
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuCoursesTest {
    @Test fun `entrées et desserts restent dans des bornes raisonnables`() {
        val w = MenuWishes(meals = 4, portions = Portions(2), starters = 40, desserts = -3).normalized()
        assertEquals(21, w.starters)
        assertEquals(0, w.desserts)
        assertEquals(4, w.meals)
    }

    @Test fun `un plat sans type est un plat principal`() {
        assertTrue(MenuDish("Gratin", portions = Portions(2)).isMain)
        assertFalse(MenuDish("Tarte", portions = Portions(2), course = Course.DESSERT).isMain)
    }

    @Test fun `le menu se range par partie du repas`() {
        val dishes = listOf(
            MenuDish("Tarte", portions = Portions(2), course = Course.DESSERT),
            MenuDish("Gratin", portions = Portions(2)),
            MenuDish("Velouté", portions = Portions(2), course = Course.ENTREE),
            MenuDish("Curry", portions = Portions(2)),
        )
        assertEquals(listOf("Velouté", "Gratin", "Curry", "Tarte"), dishes.sortedBy { it.courseRank }.map { it.title })
    }
}
