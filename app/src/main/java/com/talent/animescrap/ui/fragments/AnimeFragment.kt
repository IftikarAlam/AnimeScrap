package com.talent.animescrap.ui.fragments

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.PreferenceManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.talent.animescrap.R
import com.talent.animescrap.databinding.FragmentAnimeBinding
import com.talent.animescrap.model.AnimeDetails
import com.talent.animescrap.model.AnimePlayingDetails
import com.talent.animescrap.model.AnimeStreamLink
import com.talent.animescrap.ui.activities.PlayerActivity
import com.talent.animescrap.ui.viewmodels.AnimeDetailsViewModel
import com.talent.animescrap.ui.viewmodels.AnimeStreamViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class AnimeFragment : Fragment() {

    private val animeStreamViewModel: AnimeStreamViewModel by viewModels()
    private var _binding: FragmentAnimeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var animeMainLink: String? = "null"
    private var animeName: String? = null
    private lateinit var animeDetails: AnimeDetails
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var lastWatchedPrefString: String
    private var isExternalPlayerEnabled = false
    private val args: AnimeFragmentArgs by navArgs()
    private val animeDetailsViewModel: AnimeDetailsViewModel by viewModels()
    private lateinit var selectedSource: String
    private lateinit var settingsPreferenceManager: SharedPreferences
    private lateinit var bottomSheet: BottomSheetDialog
    private lateinit var epList: List<String>
    private lateinit var epType : String
    private lateinit var epIndex : String

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeBinding.inflate(inflater, container, false)

        settingsPreferenceManager = PreferenceManager.getDefaultSharedPreferences(requireContext())
        selectedSource = settingsPreferenceManager.getString("source", "yugen")!!
        isExternalPlayerEnabled = settingsPreferenceManager.getBoolean("external_player", false)
        if (isExternalPlayerEnabled) {
            animeStreamViewModel.animeStreamLink.observe(viewLifecycleOwner) {
                binding.progressbarInPage.visibility = View.GONE
                binding.pageLayout.visibility = View.VISIBLE
                if (it.link.isNotBlank()) animeName?.let { name -> startExternalPlayer(it, name) }
                else Toast.makeText(requireContext(), "No Streaming Url Found", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        binding.animeDetailsTxt.movementMethod = ScrollingMovementMethod()

        animeMainLink = args.animeLink

        if (animeMainLink == "null") {
            findNavController().popBackStack()
            Toast.makeText(activity, "Some Unexpected error occurred", Toast.LENGTH_SHORT).show()
        }

        sharedPreferences =
            requireActivity().getSharedPreferences(
                "LastWatchedPref",
                AppCompatActivity.MODE_PRIVATE
            )
        lastWatchedPrefString =
            sharedPreferences.getString(animeMainLink, "Not Started Yet").toString()

        // Check Favorite
        animeMainLink?.let { animeDetailsViewModel.checkFavorite(it, selectedSource) }
        animeDetailsViewModel.isAnimeFav.observe(viewLifecycleOwner) { isFav ->
            if (isFav) {
                inFav()
                binding.favCard.setOnClickListener {
                    animeDetailsViewModel.removeFav(animeMainLink!!, selectedSource)
                }
            } else {
                notInFav()
                binding.favCard.setOnClickListener {
                    animeDetailsViewModel.addToFav(
                        animeMainLink!!,
                        animeDetails.animeName,
                        animeDetails.animeCover,
                        selectedSource
                    )
                }
            }
        }


        binding.pageLayout.visibility = View.GONE
        binding.progressbarInPage.visibility = View.VISIBLE

        animeDetailsViewModel.animeDetails.observe(viewLifecycleOwner) {
            binding.progressbarInPage.visibility = View.GONE
            if (it != null) {
                animeDetails = it
                binding.animeNameTxt.text = animeDetails.animeName
                binding.animeDetailsTxt.text = animeDetails.animeDesc

                binding.lastWatchedTxt.text =
                    if (lastWatchedPrefString == "Not Started Yet") lastWatchedPrefString
                    else "Last Watched : $lastWatchedPrefString/${animeDetails.animeEpisodes.size}"

                // load background image.
                binding.backgroundImage.load(animeDetails.animeCover) {
                    error(R.drawable.ic_broken_image)
                }
                // load cover image.
                binding.coverAnime.load(animeDetails.animeCover) {
                    error(R.drawable.ic_broken_image)
                }
                binding.errorCard?.visibility = View.GONE
                binding.pageLayout.visibility = View.VISIBLE


                animeName = animeDetails.animeName
                setupEpisodes(animeDetails.animeEpisodes)
            } else {
                binding.errorCard?.visibility = View.VISIBLE
            }
        }

        println(animeMainLink)
        animeMainLink?.let { animeDetailsViewModel.getAnimeDetails(it) }


        return binding.root
    }

    private fun setupEpisodes(animeEpisodesMap: Map<String, Map<String, String>>) {

        // Setup Episode Bottom Sheet Dialog
        setupEpListBottomSheet(animeEpisodesMap)


        binding.epCard.setOnClickListener { bottomSheet.show() }
        binding.playCard.setOnClickListener {

            // Navigate to Internal Player
            if (!isExternalPlayerEnabled) {

                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(
                        "animePlayingDetails", AnimePlayingDetails(
                            animeName = animeName!!,
                            animeUrl = animeMainLink!!,
                            animeEpisodeIndex = epIndex,
                            animeEpisodeMap = animeEpisodesMap[epType] as HashMap<String, String>,
                            animeTotalEpisode = epList.size.toString()
                        )
                    )
                })

            } else {
                binding.progressbarInPage.visibility = View.VISIBLE
                binding.pageLayout.visibility = View.GONE
                animeStreamViewModel.setAnimeLink(
                    animeMainLink!!,
                    animeEpisodesMap[epType]!![epIndex]!!
                )
            }

        }


    }

    private fun startExternalPlayer(
        animeStreamLink: AnimeStreamLink,
        animeName: String,
    ) {
        val title = "$animeName Episode ${binding.epTextView.text}"

        val customIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(animeStreamLink.link), "video/*")
            if (!animeStreamLink.extraHeaders.isNullOrEmpty())
                putExtra("headers", animeStreamLink.extraHeaders.toString())
            if (animeStreamLink.subsLink.isNotBlank())
                putExtra("subs", Uri.parse(animeStreamLink.subsLink))
            putExtra("title", title)
        }
        val isMX =
            settingsPreferenceManager.getBoolean("mx_player", false)

        if (isMX) {
            startMX(customIntent)
        } else {
            startActivity(Intent.createChooser(customIntent, "Play using"))
        }


    }

    private fun startMX(
        customIntent: Intent
    ) {
        try {
            customIntent.apply {
                setPackage("com.mxtech.videoplayer.pro")
                startActivity(this)
            }
        } catch (e: ActivityNotFoundException) {
            Log.i(
                R.string.app_name.toString(),
                "MX Player pro isn't installed, falling back to MX player Ads"
            )
            try {
                Intent(Intent.ACTION_VIEW).apply {
                    customIntent.apply {
                        setPackage("com.mxtech.videoplayer.ad")
                        startActivity(this)
                    }
                }
            } catch (e: ActivityNotFoundException) {
                Log.i(
                    R.string.app_name.toString(),
                    "No version of MX Player is installed, falling back to other external player"
                )
                startActivity(Intent.createChooser(customIntent, "Play using"))
            }
        }
    }


    private fun inFav() {
        println("In Fav")
        binding.buttonFavorite.setImageResource(R.drawable.ic_heart_minus)
    }

    private fun notInFav() {
        println("Not in Fav")
        binding.buttonFavorite.setImageResource(R.drawable.ic_heart_plus)

    }

    override fun onResume() {
        super.onResume()
        if (::animeDetails.isInitialized) {
            sharedPreferences.getString(animeMainLink, "Not Started Yet").apply {
                binding.lastWatchedTxt.text =
                    if (this == "Not Started Yet") this else "Last Watched : $this/${animeDetails.animeEpisodes.size}"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupEpListBottomSheet(animeEpisodesMap: Map<String, Map<String, String>>) {

        bottomSheet = BottomSheetDialog(requireContext())
        bottomSheet.setContentView(R.layout.episode_bottom_sheet_layout)
        bottomSheet.behavior.peekHeight = 1000
        bottomSheet.behavior.isDraggable = false

        val list = bottomSheet.findViewById<ListView>(R.id.listView)
        val editText = bottomSheet.findViewById<EditText>(R.id.text_input_edit_text)
        val spinner = bottomSheet.findViewById<Spinner>(R.id.sub_dub_spinner)
        val ascDscImageBtn = bottomSheet.findViewById<ImageView>(R.id.asc_dsc_image_button)
        val upIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_arrow_upward_24)
        val downIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_arrow_downward_24)

        // Episodes aye
        epType = animeEpisodesMap.keys.first()
        epList = animeEpisodesMap[epType]!!.keys.toList()
        epIndex = epList.first()
        binding.epTextView.text = "$epIndex - $epType"

        val adapterForEpList = ArrayAdapter(
            requireContext(), R.layout.support_simple_spinner_dropdown_item,
            epList
        ).apply {
            list?.adapter = this
        }

        ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            animeEpisodesMap.keys.toList()
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinner?.adapter = this
        }

        // Search
        editText?.addTextChangedListener {
            val searchedText = it.toString()
            adapterForEpList.filter.filter(searchedText)
        }

        // Toggle Asc/Desc
        var isDown = true
        ascDscImageBtn?.setOnClickListener {
            epList = epList.reversed()
            adapterForEpList.notifyDataSetChanged()
            ascDscImageBtn.apply {
                if (isDown) this.setImageDrawable(downIcon)
                else this.setImageDrawable(upIcon)
                isDown = !isDown
            }
        }

        //spinner type
        spinner?.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                println(p2)
                epType = animeEpisodesMap.keys.toList()[p2]
                epList = animeEpisodesMap[epType]!!.keys.toList()
                adapterForEpList.notifyDataSetChanged()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }

        }

        val pos = adapterForEpList.getPosition(binding.epTextView.text.toString())
        list?.setSelection(pos)
        list?.setOnItemClickListener { _, view, _, _ ->
            val episodeString = (view as TextView).text.toString()
            epIndex = episodeString
            binding.epTextView.text = "$epIndex - $epType"
            bottomSheet.dismiss()
        }
    }

}