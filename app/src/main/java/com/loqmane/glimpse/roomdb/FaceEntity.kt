package com.loqmane.glimpse.roomdb

import android.graphics.RectF
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson

@Entity(tableName = "faces")
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val imagePath: String, // Path to the image where the face was detected
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val features: ByteArray // Store face features or image bytes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceEntity

        if (id != other.id) return false
        if (boundingBoxLeft != other.boundingBoxLeft) return false
        if (boundingBoxTop != other.boundingBoxTop) return false
        if (boundingBoxRight != other.boundingBoxRight) return false
        if (boundingBoxBottom != other.boundingBoxBottom) return false
        if (timestamp != other.timestamp) return false
        if (name != other.name) return false
        if (imagePath != other.imagePath) return false
        if (!features.contentEquals(other.features)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + boundingBoxLeft.hashCode()
        result = 31 * result + boundingBoxTop.hashCode()
        result = 31 * result + boundingBoxRight.hashCode()
        result = 31 * result + boundingBoxBottom.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + imagePath.hashCode()
        result = 31 * result + features.contentHashCode()
        return result
    }
}

class Converters {
    @TypeConverter
    fun fromRectF(rect: RectF?): String? {
        return Gson().toJson(rect)
    }

    @TypeConverter
    fun toRectF(json: String?): RectF? {
        val type = object : TypeToken<RectF>() {}.type
        return Gson().fromJson(json, type)
    }

    // Example for a List of PointF if you store landmarks
    /*
    @TypeConverter
    fun fromPointFList(list: List<PointF>?): String? {
        return Gson().toJson(list)
    }

    @TypeConverter
    fun toPointFList(json: String?): List<PointF>? {
        val type = object : TypeToken<List<PointF>>() {}.type
        return Gson().fromJson(json, type)
    }
    */
}