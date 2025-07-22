package com.loqmane.glimpse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.loqmane.glimpse.roomdb.FaceEntity

class FaceAdapter(
    private val onDelete: (FaceEntity) -> Unit
) : ListAdapter<FaceEntity, FaceAdapter.FaceViewHolder>(FaceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.face_item, parent, false)
        return FaceViewHolder(view, onDelete)
    }

    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FaceViewHolder(
        itemView: View,
        private val onDelete: (FaceEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val faceImg: ShapeableImageView = itemView.findViewById(R.id.face_img)
        private val faceName: TextView = itemView.findViewById(R.id.face_name)
        private val deleteBtn: ImageButton = itemView.findViewById(R.id.btn_delete_face)

        fun bind(face: FaceEntity) {
            faceName.text = face.name
            Glide.with(itemView.context)
                .load(face.imagePath)
                .placeholder(R.drawable.person_img)
                .error(R.drawable.person_img)
                .centerCrop()
                .into(faceImg)

            deleteBtn.setOnClickListener {
                onDelete(face)
            }
        }
    }

    class FaceDiffCallback : DiffUtil.ItemCallback<FaceEntity>() {
        override fun areItemsTheSame(oldItem: FaceEntity, newItem: FaceEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FaceEntity, newItem: FaceEntity): Boolean =
            oldItem == newItem
    }
}
