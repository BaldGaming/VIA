package com.example.via

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

class FragmentDev : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_dev, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // We find the toolbar
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.admin_toolbar)

        /**
         * Close logic
         */
        toolbar.setNavigationOnClickListener {
            // Hides the fragment container
            requireActivity().findViewById<View>(R.id.fragment_dev).visibility = View.GONE
        }
    }
}