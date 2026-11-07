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
)

@Entity(tableName = "menu_draft")
data class MenuDraftEntity(
    @PrimaryKey val position: Int,
    val title: String,
    val description: String,
    val adults: Int,
    val children: Int,
    val recipeId: Long?,
)

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
    entities = [RecipeEntity::class, PlannedMealEntity::class, ShoppingItemEntity::class, MenuDraftEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun mealDao(): MealDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun menuDraftDao(): MenuDraftDao
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
