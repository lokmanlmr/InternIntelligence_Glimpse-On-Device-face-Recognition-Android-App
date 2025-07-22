package com.loqmane.glimpse.roomdb

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceDao {
    @Query("SELECT * FROM faces ORDER BY timestamp DESC")
    fun getAllFaces(): LiveData<List<FaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity)

    @Query("DELETE FROM faces WHERE id = :faceId")
    suspend fun deleteFaceById(faceId: Long)

    @Query("DELETE FROM faces")
    suspend fun deleteAllFaces()
}