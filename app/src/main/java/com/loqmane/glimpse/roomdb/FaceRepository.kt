package com.loqmane.glimpse.roomdb

import androidx.lifecycle.LiveData

class FaceRepository(private val faceDao: FaceDao) {

    val allFaces: LiveData<List<FaceEntity>> = faceDao.getAllFaces()

    suspend fun insert(face: FaceEntity) {
        faceDao.insertFace(face)
    }

    suspend fun delete(faceId: Long) {
        faceDao.deleteFaceById(faceId)
    }

    suspend fun deleteAll() {
        faceDao.deleteAllFaces()
    }
}