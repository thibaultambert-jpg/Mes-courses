package fr.mescourses.core.menu

import fr.mescourses.core.model.Recipe
import fr.mescourses.core.model.RecipeStatus
import fr.mescourses.core.shopping.ShoppingListMerger

/** Rapproche un titre proposé par le LLM des recettes du livre (même plat, écrit différemment). */
class CookbookIndex(recipes: List<Recipe>) {
    private val entries = recipes.map { ShoppingListMerger.key(it.title) to it }

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        // « Gratin dauphinois » ≈ « Gratin dauphinois maison » ; on évite les clés trop courtes.
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 8 && long.split(' ').containsAll(short.split(' '))
    }

    fun find(title: String): Recipe? {
        val key = ShoppingListMerger.key(title)
        return entries.firstOrNull { same(it.first, key) }?.second
    }

    /** Recette du livre importée depuis cette adresse. */
    fun byUrl(url: String): Recipe? {
        val key = fr.mescourses.core.recipes.RecipeSites.normalize(url)
        return entries.firstOrNull { it.second.sourceUrl?.let(fr.mescourses.core.recipes.RecipeSites::normalize) == key }?.second
    }

    fun isBanned(title: String): Boolean = find(title)?.status == RecipeStatus.BANNI

    fun titles(status: RecipeStatus): List<String> = entries.map { it.second }.filter { it.status == status }.map { it.title }
}
