package com.example.microsleeps_detector

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.microsleeps_detector.databinding.DashboardBinding

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class Dashboard : Fragment() {

    private var _binding: DashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = DashboardBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonFirst.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_camera)
        }

        binding.buttonStream.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_stream)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}