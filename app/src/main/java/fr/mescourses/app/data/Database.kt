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
    val favorite: Boolean,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "planned_meals")
data class PlannedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val slot: String,
    val recipeId: Long,
    val recipeTitle: String,
    val servings: Int,
    val addedToList: Boolean,
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
    @Query("SELECT * FROM recipes ORDER BY favorite DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY favorite DESC, title COLLATE NOCASE")
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
    @Query("SELECT * FROM planned_meals WHERE date >= :from ORDER BY date, slot")
    fun observeFrom(from: String): Flow<List<PlannedMealEntity>>

    @Query("SELECT * FROM planned_meals WHERE date BETWEEN :from AND :to ORDER BY date, slot")
    suspend fun between(from: String, to: String): List<PlannedMealEntity>

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

@Database(
    entities = [RecipeEntity::class, PlannedMealEntity::class, ShoppingItemEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun mealDao(): MealDao
    abstract fun shoppingDao(): ShoppingDao
}
