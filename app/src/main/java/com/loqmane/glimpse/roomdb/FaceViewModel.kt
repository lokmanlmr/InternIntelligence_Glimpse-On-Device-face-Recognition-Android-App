package com.loqmane.glimpse.roomdb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FaceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: FaceRepository
    val allFaces: LiveData<List<FaceEntity>>

    init {
        val faceDao = FaceDatabase.getDatabase(application).faceDao()
        repository = FaceRepository(faceDao)
        allFaces = repository.allFaces
    }

    fun insert(face: FaceEntity) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(face)
    }

    fun delete(faceId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(faceId)
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll()
    }
}