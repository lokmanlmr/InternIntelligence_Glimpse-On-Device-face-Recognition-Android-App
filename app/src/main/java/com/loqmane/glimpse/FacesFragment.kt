package com.loqmane.glimpse

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.Navigation
import com.loqmane.glimpse.databinding.FragmentFacesBinding
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.loqmane.glimpse.roomdb.FaceViewModel
import com.loqmane.glimpse.roomdb.FaceEntity
import java.io.File

class FacesFragment : Fragment() {
    private var _binding: FragmentFacesBinding? = null
    private val binding get() = _binding!!

    private lateinit var faceViewModel: FaceViewModel
    private lateinit var faceAdapter: FaceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFacesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup RecyclerView and Adapter with delete callback
        faceAdapter = FaceAdapter { face ->
            deleteFaceAndImage(face)
        }
        binding.facesRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = faceAdapter
        }

        // Setup ViewModel and observe faces
        faceViewModel = ViewModelProvider(this)[FaceViewModel::class.java]
        faceViewModel.allFaces.observe(viewLifecycleOwner) { faces ->
            faceAdapter.submitList(faces)
        }

        binding.btnCaptureFace.setOnClickListener {
            val faceName = binding.editTextFaceName.text.toString().trim()
            if (faceName.isNotEmpty()) {
                val action = FacesFragmentDirections.actionFacesFragmentToAddNewFaceFragment(faceName)
                Navigation.findNavController(it).navigate(action)
            } else {
                binding.editTextFaceName.error = "Please enter a name"
            }
        }
    }

    private fun deleteFaceAndImage(face: FaceEntity) {
        // Delete image file from storage if it exists and is a file path
        try {
            if (face.imagePath.startsWith("content://")) {
                // Delete from MediaStore
                val uri = android.net.Uri.parse(face.imagePath)
                requireContext().contentResolver.delete(uri, null, null)
            } else {
                val file = File(face.imagePath)
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            // Ignore errors
        }
        // Delete from Room database
        faceViewModel.delete(face.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
