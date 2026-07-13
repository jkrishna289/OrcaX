@file:UseSerializers(UUIDSerializer::class)

package com.github.jkrishna289.orcax.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/**
 * Persisted MANUAL quality pick for a subject (seriesId for episodes so the
 * choice survives across a season; itemId for movies). AUTO is the default and
 * is never stored — selecting AUTO deletes the row, which also keeps the table
 * from accumulating a row per item ever played.
 *
 * `selection` holds QualitySelection.persistKey(): "ORIGINAL" or a
 * QualityRung name (e.g. "R12") — an absolute rung, so a manual pick means the
 * same bitrate every session.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = arrayOf("rowId"),
            childColumns = arrayOf("userId"),
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId", "subjectId", unique = true)],
)
@Serializable
data class QualityPreference(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val userId: Int,
    val subjectId: UUID,
    val selection: String,
)
