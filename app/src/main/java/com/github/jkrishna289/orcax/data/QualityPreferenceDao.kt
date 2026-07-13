package com.github.jkrishna289.orcax.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.jkrishna289.orcax.data.model.QualityPreference
import java.util.UUID

@Dao
interface QualityPreferenceDao {
    @Query("SELECT * FROM QualityPreference WHERE userId=:userId AND subjectId=:subjectId")
    suspend fun get(
        userId: Int,
        subjectId: UUID,
    ): QualityPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(preference: QualityPreference): Long

    @Query("DELETE FROM QualityPreference WHERE userId=:userId AND subjectId=:subjectId")
    suspend fun delete(
        userId: Int,
        subjectId: UUID,
    )
}
