package fr.mescourses.core

import fr.mescourses.core.cart.CartPlanner
import fr.mescourses.core.catalog.keysOf
import fr.mescourses.core.ingredients.IngredientMapper
import fr.mescourses.core.menu.IngredientOverlap
import fr.mescourses.core.model.Ingredient
import fr.mescourses.core.model.MenuDish
import fr.mescourses.core.model.Portions
import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.ShoppingItem
import fr.mescourses.core.shopping.Aisle
import fr.mescourses.core.shopping.ShoppingListMerger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Branchement du mapping canonique dans les clés, la liste de courses, les rayons et la recherche drive. */
class MappingWiringTest {

    private fun useTestMapping() {
        val json = MappingWiringTest::class.java.getResourceAsStream("/mapping-test.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: fail("mapping-test.json absent")
        IngredientMapper.override(IngredientMapper.load(json))
    }

    @AfterTest fun reset() = IngredientMapper.override(null)

    @Test fun ligne_ignoree_par_le_mapping_n_entre_pas_dans_la_liste() {
        useTestMapping()
        val recipe = Recipe(title = "Soupe", ingredients = listOf(
            Ingredient("carotte", 2.0, null, "2 carottes"),
            Ingredient("g", 200.0, null, "200 g"),
            Ingredient("eau", 1.0, "l", "1 l d'eau minérale"),
        ))
        val names = ShoppingListMerger.fromRecipe(recipe, 4.0).map { it.name }
        assertEquals(listOf("carotte"), names)
    }

    @Test fun cle_regroupee_fusionne_deux_lignes() {
        useTestMapping()
        val recipe = Recipe(title = "Plat", ingredients = listOf(
            Ingredient("oignon jaune", 1.0, null, "1 oignon jaune"),
            Ingredient("oignon doux", 2.0, null, "2 oignons doux"),
        ))
        val items = ShoppingListMerger.fromRecipe(recipe, 4.0)
        assertEquals(2, items.size)
        // Les libellés restent ceux de l'utilisateur ; la fusion se fait sur la clé canonique.
        val merged = ShoppingListMerger.merge(items)
        assertEquals(1, merged.size)
        assertEquals(3.0, merged.single().quantity)
    }

    @Test fun rayon_force_par_le_mapping() {
        val avant = Aisle.of("champignon de pari")
        useTestMapping()
        assertEquals(Aisle.EPICERIE, Aisle.of("champignon de pari"))
        assertEquals(Aisle.FRUITS_LEGUMES, Aisle.of("oignon jaune"))
        IngredientMapper.override(null)
        assertEquals(avant, Aisle.of("champignon de pari"))
    }

    @Test fun recherche_drive_forcee_par_le_mapping_apres_choix_utilisateur() {
        useTestMapping()
        val item = ShoppingItem(name = "crème légère")
        val custom = mapOf(ShoppingListMerger.key(item.name) to "ma recherche")
        assertEquals("ma recherche", CartPlanner.queryFor(item, custom))
        assertEquals("crème légère 15%", CartPlanner.queryFor(item, emptyMap()))
        IngredientMapper.override(null)
        assertEquals(CartPlanner.searchQuery(item), CartPlanner.queryFor(item, emptyMap()))
    }

    @Test fun cles_du_catalogue_passent_par_le_mapping() {
        useTestMapping()
        val names = listOf("200 g", "oignon jaune", "oignon doux", "échalotes grises", "carotte")
        val raws = listOf("200 g", "1 oignon jaune", "2 oignons doux", "échalotes grises ciselées", "2 carottes")
        val keys = keysOf(names, raws)
        assertEquals(listOf("oignon", "echalote", "carotte"), keys)
        assertFalse(keys.any { it.contains("200") })
        // Liste de lignes brutes plus courte : repli sur les noms.
        assertEquals(listOf("oignon", "carotte"), keysOf(listOf("oignon jaune", "carotte"), emptyList()))
    }

    @Test fun cle_de_recouvrement_passe_par_le_mapping() {
        useTestMapping()
        assertEquals("oignon", IngredientOverlap.key("Oignons jaunes"))
        assertEquals("oignon jaune", IngredientOverlap.rawKey("oignon jaune"))
        val report = IngredientOverlap.compute(listOf(
            MenuDish("A", portions = Portions(2), mainIngredients = listOf("oignon jaune", "carotte")),
            MenuDish("B", portions = Portions(2), mainIngredients = listOf("oignon doux")),
        ))
        assertTrue(report.shared.any { it.dishes == listOf(1, 2) })
    }
}
