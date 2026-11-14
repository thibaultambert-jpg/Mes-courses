package fr.mescourses.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val servings: Int,
    /** List<Ingredient> en JSON */
    val ingredientsJson: String,
    /** List<String> en JSON */
    val stepsJson: String,
    val sourceUrl: String?,
    val imageUrl: String?,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val tagsJson: String,
    /** HISTORIQUE, FAVORI ou BANNI */
    val status: String,
    val timesCooked: Int,
    val lastCookedAt: String?,
    val description: String?,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "planned_meals")
data class PlannedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val recipeId: Long,
    val recipeTitle: String,
    val adults: Int,
    val children: Int,
    val cooked: Boolean,
    /** Nom de [fr.mescourses.core.catalog.Course] (ENTREE, DESSERT) ; null = plat principal. */
    val course: String? = null,
)

@Entity(tableName = "menu_draft")
data class MenuDraftEntity(
    @PrimaryKey val position: Int,
    val title: String,
    val description: String,
    val adults: Int,
    val children: Int,
    val recipeId: Long?,
    /** List<String> en JSON */
    @androidx.room.ColumnInfo(defaultValue = "[]") val mainIngredients: String,
    /** Adresse de la recette quand le plat vient d'un site. */
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val totalMinutes: Int? = null,
    /** Recette inventée par le chef à la demande. */
    @androidx.room.ColumnInfo(defaultValue = "0") val invented: Boolean = false,
    /** Nom de [fr.mescourses.core.catalog.Course] (ENTREE, DESSERT) ; null = plat principal. */
    val course: String? = null,
)

/** Plats des menus validés : jamais effacés avec le menu de la semaine, ils servent à varier les suivants. */
@Entity(tableName = "menu_history")
data class MenuHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val title: String,
    val sourceUrl: String?,
    val kind: String,
    /** List<String> en JSON */
    val mainIngredients: String,
)

@Dao
interface MenuHistoryDao {
    @Query("SELECT * FROM menu_history ORDER BY date DESC, id DESC")
    suspend fun all(): List<MenuHistoryEntity>

    @Insert
    suspend fun insertAll(items: List<MenuHistoryEntity>)

    /** L'historique ne sert que sur quelques semaines : on ne garde pas tout indéfiniment. */
    @Query("DELETE FROM menu_history WHERE date < :before")
    suspend fun deleteBefore(before: String)
}

@Entity(tableName = "shopping_items")
data class ShoppingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val source: String,
    val origin: String?,
    val checked: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY lastCookedAt DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY lastCookedAt DESC, title COLLATE NOCASE")
    suspend fun all(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: Long): Flow<RecipeEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: RecipeEntity): Long

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MealDao {
    @Query("SELECT * FROM planned_meals ORDER BY cooked, id")
    fun observeAll(): Flow<List<PlannedMealEntity>>

    @Query("SELECT * FROM planned_meals ORDER BY cooked, id")
    suspend fun all(): List<PlannedMealEntity>

    @Query("DELETE FROM planned_meals")
    suspend fun clear()

    @Insert
    suspend fun insert(meal: PlannedMealEntity): Long

    @Update
    suspend fun update(meal: PlannedMealEntity)

    @Query("DELETE FROM planned_meals WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_items ORDER BY checked, createdAt")
    fun observeAll(): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items ORDER BY checked, createdAt")
    suspend fun all(): List<ShoppingItemEntity>

    @Insert
    suspend fun insertAll(items: List<ShoppingItemEntity>)

    @Update
    suspend fun update(item: ShoppingItemEntity)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shopping_items WHERE checked = 1")
    suspend fun clearChecked()

    /** Coche ou décoche plusieurs articles en une seule requête. */
    @Query("UPDATE shopping_items SET checked = :checked WHERE id IN (:ids)")
    suspend fun setChecked(ids: List<Long>, checked: Boolean)
}

/** Mémoire des choix : produit Carrefour retenu pour un ingrédient (clé normalisée). */
@Entity(tableName = "ingredient_product")
data class IngredientProductEntity(
    @PrimaryKey val ingredientKey: String,
    val ean: String,
    val name: String,
    val brand: String?,
    val price: Double?,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface IngredientProductDao {
    @Query("SELECT * FROM ingredient_product WHERE ingredientKey = :key")
    suspend fun get(key: String): IngredientProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IngredientProductEntity)
}

@Dao
interface MenuDraftDao {
    @Query("SELECT * FROM menu_draft ORDER BY position")
    fun observe(): Flow<List<MenuDraftEntity>>

    @Query("SELECT * FROM menu_draft ORDER BY position")
    suspend fun all(): List<MenuDraftEntity>

    @Query("DELETE FROM menu_draft")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<MenuDraftEntity>)

    @androidx.room.Transaction
    suspend fun replace(items: List<MenuDraftEntity>) {
        clear()
        insertAll(items)
    }
}

@Database(
    entities = [
        RecipeEntity::class, PlannedMealEntity::class, ShoppingItemEntity::class, MenuDraftEntity::class,
        IngredientProductEntity::class, MenuHistoryEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun mealDao(): MealDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun menuDraftDao(): MenuDraftDao
    abstract fun ingredientProductDao(): IngredientProductDao
    abstract fun menuHistoryDao(): MenuHistoryDao
}

/**
 * v1 → v2 : statuts du livre de recettes (Historique / Favoris / Ne plus proposer),
 * menu de la semaine sans dates avec adultes/enfants, brouillon de menu.
 */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE recipes_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, servings INTEGER NOT NULL,
                ingredientsJson TEXT NOT NULL, stepsJson TEXT NOT NULL, sourceUrl TEXT, imageUrl TEXT,
                prepMinutes INTEGER, cookMinutes INTEGER, tagsJson TEXT NOT NULL, status TEXT NOT NULL,
                timesCooked INTEGER NOT NULL, lastCookedAt TEXT, description TEXT, notes TEXT, createdAt INTEGER NOT NULL)""",
        )
        db.execSQL(
            """INSERT INTO recipes_new (id, title, servings, ingredientsJson, stepsJson, sourceUrl, imageUrl, prepMinutes,
                cookMinutes, tagsJson, status, timesCooked, lastCookedAt, description, notes, createdAt)
               SELECT id, title, servings, ingredientsJson, stepsJson, sourceUrl, imageUrl, prepMinutes, cookMinutes, tagsJson,
                CASE WHEN favorite = 1 THEN 'FAVORI' ELSE 'HISTORIQUE' END, 0, NULL, NULL, notes, createdAt FROM recipes""",
        )
        db.execSQL("DROP TABLE recipes")
        db.execSQL("ALTER TABLE recipes_new RENAME TO recipes")

        db.execSQL("DROP TABLE planned_meals")
        db.execSQL(
            """CREATE TABLE planned_meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, recipeId INTEGER NOT NULL,
                recipeTitle TEXT NOT NULL, adults INTEGER NOT NULL, children INTEGER NOT NULL, cooked INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE menu_draft (
                position INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL,
                adults INTEGER NOT NULL, children INTEGER NOT NULL, recipeId INTEGER)""",
        )
    }
}

/** v2 → v3 : ingrédients principaux des plats du brouillon (mutualisation). */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN mainIngredients TEXT NOT NULL DEFAULT '[]'")
    }
}

/** v3 → v4 : mémoire des produits choisis par ingrédient (panier Carrefour). */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ingredient_product (
                ingredientKey TEXT PRIMARY KEY NOT NULL, ean TEXT NOT NULL, name TEXT NOT NULL,
                brand TEXT, price REAL, updatedAt INTEGER NOT NULL)""",
        )
    }
}

/** v4 → v5 : plats du brouillon venus des sites de recettes, et historique durable des menus. */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN sourceUrl TEXT")
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN imageUrl TEXT")
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN totalMinutes INTEGER")
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN invented INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS menu_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, title TEXT NOT NULL,
                sourceUrl TEXT, kind TEXT NOT NULL, mainIngredients TEXT NOT NULL)""",
        )
    }
}

/** v5 → v6 : entrée / plat / dessert gardés dans le brouillon et dans le menu de la semaine. */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE menu_draft ADD COLUMN course TEXT")
        db.execSQL("ALTER TABLE planned_meals ADD COLUMN course TEXT")
    }
}
