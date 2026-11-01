package app.mawja.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.mawja.android.data.local.entity.RatingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RatingDao {
    @Query("SELECT * FROM ratings ORDER BY stars DESC, updatedAt DESC")
    fun observeAll(): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings ORDER BY stars DESC, updatedAt DESC")
    suspend fun getAll(): List<RatingEntity>

    @Query("SELECT * FROM ratings WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<RatingEntity?>

    @Query("SELECT * FROM ratings WHERE id = :id LIMIT 1")
    suspend fun get(id: String): RatingEntity?

    @Upsert
    suspend fun upsert(rating: RatingEntity)

    @Upsert
    suspend fun upsertAll(ratings: List<RatingEntity>)

    @Query("DELETE FROM ratings WHERE id = :id")
    suspend fun deleteById(id: String)
}
