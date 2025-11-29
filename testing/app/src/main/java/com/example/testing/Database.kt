package com.example.testing

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// 1. Entity (Replaces the data class in SpeechRecognizerUtil)
@Entity(tableName = "transcriptions")
data class Transcription(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val text: String,
    val riskScore: Int? = null,
    val advice: String? = null,
    val isAdviceLoading: Boolean = false,
    val timestamp: Long = System.currentTimeMillis() // NEW: Timestamp
)

// 2. DAO
@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<Transcription>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcription: Transcription)

    @Query("UPDATE transcriptions SET text = :text WHERE id = :id")
    suspend fun updateText(id: String, text: String)

    @Query("UPDATE transcriptions SET riskScore = :score, advice = :advice WHERE id = :id")
    suspend fun updateRisk(id: String, score: Int, advice: String?)

    @Query("UPDATE transcriptions SET advice = :advice, isAdviceLoading = 0 WHERE id = :id")
    suspend fun updateAdvice(id: String, advice: String)

    @Query("UPDATE transcriptions SET isAdviceLoading = :isLoading WHERE id = :id")
    suspend fun updateLoading(id: String, isLoading: Boolean)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transcriptions")
    suspend fun deleteAll()
}

// 3. Database
@Database(entities = [Transcription::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "speech_app_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}