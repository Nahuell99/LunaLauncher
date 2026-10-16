package app.olauncher.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSubSettingsBinding
import app.olauncher.helper.dpToPx
import app.olauncher.ui.BaseFragment

/** Shell for secondary settings screens: header with close button + scrolling cards. */
abstract class SubSettingsFragment : BaseFragment() {

    private var _binding: FragmentSubSettingsBinding? = null
    protected val binding get() = _binding!!
    protected lateinit var prefs: Prefs
    protected lateinit var viewModel: MainViewModel

    abstract val titleRes: Int
    abstract fun build(builder: SettingsBuilder)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSubSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        binding.subSettingsTitle.setText(titleRes)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + 8.dpToPx(), 0, bars.bottom)
            insets
        }
        rebuild()
    }

    /** Rebuilds every card; cheap enough to call after any change that alters the layout. */
    protected fun rebuild() {
        val content = binding.subSettingsContent
        val scroll = binding.subSettingsScroll.scrollY
        content.removeAllViews()
        build(SettingsBuilder(requireContext(), content))
        binding.subSettingsScroll.post { _binding?.subSettingsScroll?.scrollTo(0, scroll) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
