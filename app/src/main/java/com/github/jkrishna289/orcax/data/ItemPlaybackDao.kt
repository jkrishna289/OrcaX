package com.github.jkrishna289.orcax.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.data.model.ItemTrackModification
import com.github.jkrishna289.orcax.data.model.JellyfinUser
import java.util.UUID

@Dao
interface ItemPlaybackDao {
    suspend fun getItem(
        user: JellyfinUser,
        itemId: UUID,
    ): ItemPlayback? = getItem(user.rowId, itemId)

    @Query("SELECT * from ItemPlayback WHERE userId=:userId AND itemId=:itemId")
    suspend fun getItem(
        userId: Int,
        itemId: UUID,
    ): ItemPlayback?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveItem(item: ItemPlayback): Long

    @Delete
    suspend fun deleteItem(item: ItemPlayback)

    @Query("SELECT * from ItemPlayback WHERE userId=:userId")
    suspend fun getItems(userId: Int): List<ItemPlayback>

    @Query("SELECT * FROM ItemTrackModification WHERE userId=:userId AND itemId=:itemId")
    suspend fun getTrackModifications(
        userId: Int,
        itemId: UUID,
    ): List<ItemTrackModification>

    @Query("SELECT * FROM ItemTrackModification WHERE userId=:userId AND itemId=:itemId AND trackIndex=:trackIndex")
    suspend fun getTrackModifications(
        userId: Int,
        itemId: UUID,
        trackIndex: Int,
    ): ItemTrackModification?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveItem(item: ItemTrackModification): Long
}
