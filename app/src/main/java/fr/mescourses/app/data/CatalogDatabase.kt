package fr.mescourses.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import fr.mescourses.core.catalog.CatalogEntry
import fr.mescourses.core.catalog.CatalogPolicy
import fr.mescourses.core.catalog.CatalogQuery
import fr.mescourses.core.catalog.CatalogStore
import fr.mescourses.core.catalog.Course
import fr.mescourses.core.catalog.minutes
import fr.mescourses.core.menu.DishKind

/**
 * Une recette du catalogue local. Base séparée de celle de l'appli : elle peut être vidée et reconstruite
 * (fichier embarqué, mise à jour) sans toucher au livre de recettes ni aux menus.
 */
@Entity(
    tableName = "catalog_recipe",
    indices = [Index(value = ["url"], unique = true), Index("siteId"), Index(value = ["course", "kind"])],
)
data class CatalogRecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val siteId: String,
    val title: String,
    val imageUrl: String?,
    /** Lignes d'ingrédients, une par ligne. */
    val ingredients: String,
    val servings: Int,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val totalMinutes: Int?,
    /** Durée totale connue, pour le filtre de temps. */
    val minutes: Int?,
    val rating: Double?,
    val ratingCount: Int?,
    val category: String?,
    val lastmod: String?,
    val course: String,
    val kind: String,
    /** Mois de saison : bit n-1 pour le mois n. */
    val months: Int,
    val produce: String,
    val balanced: Boolean,
    val keys: String,
    /** Bien notée : passe devant dans les résultats. */
    val good: Boolean,
    /** Texte de recherche (titre et ingrédients, sans accents, au singulier). */
    val search: String,
) {
    fun toEntry() = CatalogEntry(
        url = url, siteId = siteId, title = title, imageUrl = imageUrl,
        ingredients = ingredients.split('\n').filter { it.isNotBlank() },
        servings = servings, prepMinutes = prepMinutes, cookMinutes = cookMinutes, totalMinutes = totalMinutes,
        rating = rating, ratingCount = ratingCount, category = category, lastmod = lastmod,
        course = runCatching { Course.valueOf(course) }.getOrDefault(Course.AUTRE),
        kind = runCatching { DishKind.valueOf(kind) }.getOrDefault(DishKind.VEGE),
        months = (1..12).filter { months and (1 shl (it - 1)) != 0 },
        produce = produce.split(',').filter { it.isNotBlank() },
        balanced = balanced,
        keys = keys.split(',').filter { it.isNotBlank() },
    )

    companion object {
        fun of(e: CatalogEntry) = CatalogRecipeEntity(
            url = e.url, siteId = e.siteId, title = e.title, imageUrl = e.imageUrl,
            ingredients = e.ingredients.joinToString("\n"),
            servings = e.servings, prepMinutes = e.prepMinutes, cookMinutes = e.cookMinutes, totalMinutes = e.totalMinutes,
            minutes = e.minutes, rating = e.rating, ratingCount = e.ratingCount, category = e.category, lastmod = e.lastmod,
            course = e.course.name, kind = e.kind.name,
            months = e.months.fold(0) { mask, m -> mask or (1 shl (m - 1)) },
            produce = e.produce.joinToString(","),
            balanced = e.balanced,
            keys = e.keys.joinToString(","),
            good = CatalogPolicy.wellRated(e),
            search = e.searchText,
        )
    }
}

/** Index plein texte du champ de recherche, tenu à jour par Room. */
@Fts4(contentEntity = CatalogRecipeEntity::class)
@Entity(tableName = "catalog_fts")
data class CatalogFtsEntity(val search: String)

/** Page lue qui n'a pas donné de recette : on ne la relit pas à chaque mise à jour. */
@Entity(tableName = "catalog_empty")
data class CatalogEmptyEntity(@PrimaryKey val url: String, val siteId: String, val lastmod: String?)

@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(@PrimaryKey val key: String, val value: String)

data class SiteCount(val siteId: String, val n: Int)
data class UrlLastmod(val url: String, val lastmod: String?)

@Dao
interface CatalogDao {
    @Query("SELECT COUNT(*) FROM catalog_recipe")
    suspend fun count(): Int

    @Query("SELECT siteId, COUNT(*) AS n FROM catalog_recipe GROUP BY siteId")
    suspend fun countBySite(): List<SiteCount>

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<CatalogRecipeEntity>

    @Query("SELECT * FROM catalog_recipe WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): CatalogRecipeEntity?

    @Query("DELETE FROM catalog_recipe WHERE url IN (:urls)")
    suspend fun delete(urls: List<String>)

    @Insert
    suspend fun insert(items: List<CatalogRecipeEntity>)

    /** Une fiche déjà connue (même adresse) est remplacée ; l'index de recherche suit. */
    @Transaction
    suspend fun replace(items: List<CatalogRecipeEntity>) {
        items.chunked(400).forEach { part ->
            delete(part.map { it.url })
            insert(part)
        }
    }

    @Query("SELECT url, lastmod FROM catalog_recipe WHERE siteId = :siteId")
    suspend fun recipePages(siteId: String): List<UrlLastmod>

    @Query("SELECT url, lastmod FROM catalog_empty WHERE siteId = :siteId")
    suspend fun emptyPages(siteId: String): List<UrlLastmod>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markEmpty(page: CatalogEmptyEntity)

    @Query("SELECT value FROM catalog_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: CatalogMetaEntity)
}

@Database(
    entities = [CatalogRecipeEntity::class, CatalogFtsEntity::class, CatalogEmptyEntity::class, CatalogMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun dao(): CatalogDao
}

class RoomCatalogStore(private val dao: CatalogDao) : CatalogStore {
    override suspend fun count() = dao.count()
    override suspend fun countBySite() = dao.countBySite().associate { it.siteId to it.n }
    override suspend fun byUrl(url: String) = dao.byUrl(url)?.toEntry()
    override suspend fun upsert(entries: List<CatalogEntry>) = dao.replace(entries.map(CatalogRecipeEntity::of))
    override suspend fun known(siteId: String): Map<String, String?> =
        (dao.emptyPages(siteId) + dao.recipePages(siteId)).associate { it.url to it.lastmod }
    override suspend fun markEmpty(siteId: String, url: String, lastmod: String?) = dao.markEmpty(CatalogEmptyEntity(url, siteId, lastmod))
    override suspend fun meta(key: String) = dao.meta(key)
    override suspend fun setMeta(key: String, value: String) = dao.setMeta(CatalogMetaEntity(key, value))

    override suspend fun search(query: CatalogQuery): List<CatalogEntry> {
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        val sql = StringBuilder("SELECT r.* FROM catalog_recipe r")
        // Chaque mot doit commencer un mot du texte de recherche ; seuls lettres et chiffres atteignent l'index.
        val words = query.words.map { w -> w.filter { it in 'a'..'z' || it in '0'..'9' } }.filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            sql.append(" JOIN catalog_fts ON catalog_fts.docid = r.id")
            where += "catalog_fts MATCH ?"
            args += words.joinToString(" ") { "$it*" }
        }
        if (query.courses.isNotEmpty()) {
            where += "r.course IN (${query.courses.joinToString(",") { "?" }})"
            args += query.courses.map { it.name }
        }
        query.kind?.let { where += "r.kind = ?"; args += it.name }
        query.month?.let { where += "(r.months & ?) != 0"; args += 1 shl (it - 1) }
        query.maxMinutes?.let { where += "(r.minutes IS NULL OR r.minutes <= ?)"; args += it }
        if (query.balanced) where += "r.balanced = 1"
        if (query.sites.isNotEmpty()) {
            where += "r.siteId IN (${query.sites.joinToString(",") { "?" }})"
            args += query.sites
        }
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where.joinToString(" AND "))
        sql.append(" ORDER BY r.good DESC, ")
        val seed = query.seed
        if (seed == null) sql.append("r.ratingCount DESC") else {
            // Mélange stable pour une semaine donnée : le menu varie d'une semaine à l'autre.
            sql.append("((r.id * ?) % 1009)")
            args += (seed % 997) + 1
        }
        sql.append(" LIMIT ?")
        args += query.limit
        return dao.search(SimpleSQLiteQuery(sql.toString(), args.toTypedArray())).map { it.toEntry() }
    }
}
