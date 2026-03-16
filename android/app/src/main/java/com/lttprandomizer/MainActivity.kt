package com.lttprandomizer

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lttprandomizer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.Coil
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.serialization.encodeToString

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var romUri: Uri? = null
    private var outputUri: Uri? = null
    private var lastSeedPermalink: String? = null
    private var spritePath: String = ""
    private var spritePreviewUrl: String = ""
    private var spriteNameText: TextView? = null
    private var spritePreviewImage: ImageView? = null
    private val lttprSubfolder = "lttpr"

    // All presets = built-ins + user presets (rebuilt on load)
    private val allPresets = mutableListOf<RandomizerPreset>()
    private var cachedPresetJsons = listOf<String>()
    private val settingRows = mutableListOf<SettingRowModel>()
    private val customizationRows = mutableListOf<SettingRowModel>()

    // Guards against feedback loops when applying settings programmatically
    private var suppressPresetApply = false

    // Panel collapse state (collapsed by default)
    private var settingsExpanded = false
    private var customizationExpanded = false

    // Seed input state (for racing — fetch an existing seed by hash)
    private var seedHash: String = ""
    private var fetchedSeed: AlttprApiClient.SeedResult? = null

    // MSU Music Pack state
    private val msuTracks = mutableListOf<MsuTrackSlot>()
    private var filteredMsuTracks = mutableListOf<MsuTrackSlot>()
    private var msuAdapter: MsuTrackAdapter? = null
    private var msuExpanded = false
    private var includeMsu = false
    private var msuPackName = ""
    private var currentPlaylistName = "(unsaved)"
    private val musicLibrary = MsuMusicLibrary()
    private val audioPlayer = MsuPcmAudioPlayer()

    // ── File pickers ─────────────────────────────────────────────────────────

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            romUri = uri
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            binding.romPathText.text = uri.lastPathSegment ?: uri.toString()
            updateGenerateButton()
            PresetManager.savePaths(this, romUri?.toString(), outputUri?.toString())
        }
    }

    private val pickOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            outputUri = uri
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            binding.outputPathText.text = uri.lastPathSegment ?: uri.toString()
            updateGenerateButton()
            PresetManager.savePaths(this, romUri?.toString(), outputUri?.toString())
        }
    }

    private val pickSprite = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            when {
                data.getBooleanExtra(SpriteBrowserActivity.EXTRA_IS_DEFAULT, false) -> {
                    spritePath = ""
                    spritePreviewUrl = ""
                }
                else -> {
                    spritePath = data.getStringExtra(SpriteBrowserActivity.EXTRA_SPRITE_PATH) ?: ""
                    spritePreviewUrl = data.getStringExtra(SpriteBrowserActivity.EXTRA_SPRITE_PREVIEW) ?: ""
                }
            }
            updateSpriteRow()
        }
    }

    // MSU file pickers
    private val importPackLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val (playlist, error) = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { MsuPackImporter.import(this@MainActivity, it) }
                    ?: Pair(null, "Cannot open file.")
            }
            if (error != null) { showStatus(error, isError = true); return@launch }
            applyPlaylistToTracks(playlist!!)
            showStatus("Imported pack: ${playlist.name} (${playlist.tracks.size} tracks)")
        }
    }

    private val importOstZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { MsuOriginalSoundtrack.importFromZip(this@MainActivity, it, msuTracks) }
                    ?: "Cannot open file."
            }
            if (result != null)
                showStatus(result, isError = result.contains("failed", ignoreCase = true))
            else
                showStatus("Original soundtrack imported successfully.")
            refreshMsuUi()
        }
    }

    // Library folder picker (copies PCMs to app-private storage)
    private val pickLibraryFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                // Copy PCM files from SAF tree to app-private library dir
                val libDir = java.io.File(filesDir, "msu_library/pcm")
                libDir.mkdirs()
                val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, uri)
                var copied = 0
                docTree?.listFiles()?.filter { it.name?.endsWith(".pcm", ignoreCase = true) == true }?.forEach { doc ->
                    val destFile = java.io.File(libDir, doc.name ?: return@forEach)
                    if (!destFile.exists()) {
                        contentResolver.openInputStream(doc.uri)?.use { input ->
                            destFile.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    copied++
                }
                musicLibrary.setFolder(libDir.absolutePath)
                copied
            }
            binding.msuLibraryPath.text = "$count PCM files"
            showStatus("Library set: $count files")
            saveMsuSettings()
        }
    }

    private val savePlaylistLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        val playlist = MsuPlaylist(
            name = msuPackName.ifEmpty { "my-playlist" },
            tracks = msuTracks.filter { it.hasFile }.associate { it.slotNumber.toString() to it.pcmPath!! }
        )
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { MsuPlaylistManager.save(it, playlist) }
                    ?: "Cannot open file for writing."
            }
            if (error != null) showStatus(error, isError = true)
            else {
                currentPlaylistName = playlist.name
                binding.msuPlaylistName.text = currentPlaylistName
                showStatus("Playlist saved: ${playlist.name} (${playlist.tracks.size} tracks)")
            }
        }
    }

    private val loadPlaylistLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val (playlist, error) = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { MsuPlaylistManager.load(it) }
                    ?: Pair(null, "Cannot open file.")
            }
            if (error != null) { showStatus(error, isError = true); return@launch }
            applyPlaylistToTracks(playlist!!)
            currentPlaylistName = playlist.name
            binding.msuPlaylistName.text = currentPlaylistName
            showStatus("Playlist loaded: ${playlist.name} (${playlist.tracks.size} tracks)")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(AlttprApiClient.http)
                .build()
        )

        buildSettingRows()
        buildCustomizationRows()
        initMsuTracks()
        loadPresets()
        restoreLastSettings()
        restoreCustomization()
        restoreMsuSettings()
        restorePaths()
        setupUi()
        tryMatchPreset()

        if (PresetManager.lastLoadHadError) {
            Toast.makeText(this, "Some saved settings were corrupted and reset to defaults.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        PresetManager.saveLastSettings(this, currentSettings())
        PresetManager.saveCustomization(this, currentCustomization())
        saveMsuSettings()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun buildSettingRows() {
        settingRows.clear()
        settingRows += listOf(
            SettingRowModel("glitches",           "Glitches",                 SettingsOptions.glitches),
            SettingRowModel("item_placement",     "Item Placement",           SettingsOptions.itemPlacement),
            SettingRowModel("dungeon_items",      "Dungeon Items",            SettingsOptions.dungeonItems),
            SettingRowModel("accessibility",      "Accessibility",            SettingsOptions.accessibility),
            SettingRowModel("goal",               "Goal",                     SettingsOptions.goal),
            SettingRowModel("tower_open",         "Tower Open (crystals)",    SettingsOptions.crystalCount),
            SettingRowModel("ganon_open",         "Ganon Open (crystals)",    SettingsOptions.crystalCount),
            SettingRowModel("world_state",        "World State",              SettingsOptions.worldState),
            SettingRowModel("entrance_shuffle",   "Entrance Shuffle",         SettingsOptions.entranceShuffle),
            SettingRowModel("boss_shuffle",       "Boss Shuffle",             SettingsOptions.bossShuffle),
            SettingRowModel("enemy_shuffle",      "Enemy Shuffle",            SettingsOptions.enemyShuffle),
            SettingRowModel("enemy_damage",       "Enemy Damage",             SettingsOptions.enemyDamage),
            SettingRowModel("enemy_health",       "Enemy Health",             SettingsOptions.enemyHealth),
            SettingRowModel("pot_shuffle",        "Pot Shuffle",              SettingsOptions.potShuffle),
            SettingRowModel("hints",              "Hints",                    SettingsOptions.hints),
            SettingRowModel("weapons",            "Weapons",                  SettingsOptions.weapons),
            SettingRowModel("item_pool",          "Item Pool",                SettingsOptions.itemPool),
            SettingRowModel("item_functionality", "Item Functionality",       SettingsOptions.itemFunctionality),
            SettingRowModel("spoilers",           "Spoiler Log",              SettingsOptions.spoilers),
            SettingRowModel("pegasus_boots",      "Pegasus Boots Start",      SettingsOptions.pegasusBoots),
        )
    }

    private fun buildCustomizationRows() {
        customizationRows.clear()
        customizationRows += listOf(
            SettingRowModel("heart_beep_speed", "Heart Beep",  CustomizationOptions.heartBeepSpeed),
            SettingRowModel("heart_color",      "Heart Color", CustomizationOptions.heartColor),
            SettingRowModel("menu_speed",       "Menu Speed",  CustomizationOptions.menuSpeed),
            SettingRowModel("quick_swap",       "Quick Swap",  CustomizationOptions.quickSwap),
        )
    }

    private fun inflateRows(container: android.widget.LinearLayout, rows: List<SettingRowModel>, onChanged: (() -> Unit)? = null) {
        rows.forEach { row ->
            val rowView = layoutInflater.inflate(R.layout.row_setting, container, false)
            rowView.findViewById<TextView>(R.id.settingLabel).text = row.label
            val spinner = rowView.findViewById<Spinner>(R.id.settingSpinner)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, row.options)
            spinner.setSelection(row.selectedIndex, false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    row.selectedIndex = pos
                    onChanged?.invoke()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            row.spinnerRef = spinner
            container.addView(rowView)
        }
    }

    private fun setupUi() {
        // ROM / output pickers
        binding.browseRomBtn.setOnClickListener { pickRom.launch(arrayOf("*/*")) }
        binding.browseOutputBtn.setOnClickListener { pickOutput.launch(null) }

        // Settings rows — inflate with saved indices, then attach listeners
        suppressPresetApply = true
        inflateRows(binding.settingsContainer, settingRows) { tryMatchPreset() }
        suppressPresetApply = false

        // Customization rows — inflate with saved indices, then attach listeners
        inflateRows(binding.customizationContainer, customizationRows)

        // Sprite row — inflated into the always-visible sprite section
        val spriteRow = layoutInflater.inflate(R.layout.row_sprite, binding.spriteContainer, false)
        spriteNameText = spriteRow.findViewById(R.id.spriteNameText)
        spritePreviewImage = spriteRow.findViewById(R.id.spritePreviewImage)
        spriteRow.findViewById<Button>(R.id.browseSpriteBtn).setOnClickListener {
            pickSprite.launch(Intent(this, SpriteBrowserActivity::class.java))
        }
        spriteRow.findViewById<Button>(R.id.clearSpriteBtn).setOnClickListener {
            spritePath = ""
            spritePreviewUrl = ""
            updateSpriteRow()
        }
        binding.spriteContainer.addView(spriteRow)
        updateSpriteRow()

        // Customization toggle
        binding.customizationToggle.setOnClickListener {
            customizationExpanded = !customizationExpanded
            binding.customizationContainer.visibility = if (customizationExpanded) View.VISIBLE else View.GONE
            binding.customizationToggle.text = getString(
                if (customizationExpanded) R.string.customization_toggle_expanded else R.string.customization_toggle_collapsed
            )
        }

        // Settings toggle
        binding.settingsToggle.setOnClickListener {
            settingsExpanded = !settingsExpanded
            binding.settingsContainer.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
            binding.settingsToggle.text = getString(
                if (settingsExpanded) R.string.settings_toggle_expanded else R.string.settings_toggle_collapsed
            )
        }

        // Preset spinner
        refreshPresetSpinner()
        binding.presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressPresetApply) return
                val preset = allPresets[pos]
                val isCustom = (preset === CUSTOM_SENTINEL)
                val isBuiltIn = pos < BuiltInPresets.all.size

                if (isCustom) {
                    binding.presetNameEdit.setText("")
                    binding.presetNameEdit.visibility = View.VISIBLE
                    binding.savePresetBtn.visibility = View.VISIBLE
                    binding.savePresetBtn.isEnabled = false
                    binding.deletePresetBtn.visibility = View.GONE
                } else if (isBuiltIn) {
                    applyPreset(preset)
                    binding.presetNameEdit.visibility = View.GONE
                    binding.savePresetBtn.visibility = View.GONE
                    binding.deletePresetBtn.visibility = View.GONE
                } else {
                    applyPreset(preset)
                    binding.presetNameEdit.visibility = View.GONE
                    binding.savePresetBtn.visibility = View.GONE
                    binding.deletePresetBtn.visibility = View.VISIBLE
                    binding.deletePresetBtn.isEnabled = true
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.presetNameEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.savePresetBtn.isEnabled = s?.toString()?.trim()?.isNotEmpty() == true
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.savePresetBtn.setOnClickListener {
            val name = binding.presetNameEdit.text.toString().trim()
            val err = PresetManager.savePreset(this, name, currentSettings())
            if (err != null) showStatus(err, isError = true)
            else {
                loadPresets()
                val idx = allPresets.indexOfFirst { it.name == name }
                if (idx >= 0) {
                    suppressPresetApply = true
                    binding.presetSpinner.setSelection(idx)
                    suppressPresetApply = false
                    binding.presetNameEdit.visibility = View.GONE
                    binding.savePresetBtn.visibility = View.GONE
                    binding.deletePresetBtn.visibility = View.VISIBLE
                    binding.deletePresetBtn.isEnabled = true
                }
                showStatus("Preset \"$name\" saved.", isError = false)
            }
        }

        binding.deletePresetBtn.setOnClickListener {
            val pos = binding.presetSpinner.selectedItemPosition
            if (pos < 0 || pos >= allPresets.size) return@setOnClickListener
            val name = allPresets[pos].name
            AlertDialog.Builder(this)
                .setMessage("Delete preset \"$name\"?")
                .setPositiveButton("Delete") { _, _ ->
                    val err = PresetManager.deletePreset(this, name)
                    if (err != null) showStatus(err, isError = true)
                    else {
                        loadPresets()
                        tryMatchPreset()
                        showStatus("Deleted \"$name\".", isError = false)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // MSU Music Pack
        setupMsu()

        // Seed input
        binding.loadSeedBtn.setOnClickListener { loadSeed() }
        binding.clearSeedBtn.setOnClickListener { clearSeed() }
        binding.copySeedBtn.setOnClickListener { copySeedToClipboard() }

        // Generate
        binding.generateBtn.setOnClickListener { generate() }
        binding.seedLinkText.setOnClickListener {
            lastSeedPermalink?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        // Overlay buttons
        binding.overlayDismissBtn.setOnClickListener { hideOverlay() }
        binding.overlayCopyBtn.setOnClickListener { copySeedToClipboard() }
        binding.overlayLinkText.setOnClickListener {
            lastSeedPermalink?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        updateGenerateButton()
    }

    // ── Preset helpers ────────────────────────────────────────────────────────

    private fun loadPresets() {
        allPresets.clear()
        allPresets.addAll(BuiltInPresets.all)
        allPresets.addAll(PresetManager.loadUserPresets(this))
        allPresets.add(CUSTOM_SENTINEL)
        cachedPresetJsons = allPresets.dropLast(1).map { AlttprApiClient.json.encodeToString(it.settings) }
        suppressPresetApply = true
        refreshPresetSpinner()
        suppressPresetApply = false
    }

    private fun refreshPresetSpinner() {
        binding.presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            allPresets.map { it.name }
        )
    }

    private fun restoreLastSettings() {
        suppressPresetApply = true
        applySettings(PresetManager.loadLastSettings(this))
        suppressPresetApply = false
    }

    private fun applyPreset(preset: RandomizerPreset) {
        suppressPresetApply = true
        applySettings(preset.settings)
        suppressPresetApply = false
    }

    private fun applySettings(s: RandomizerSettings) {
        setRowIn(settingRows, "glitches",           s.glitches)
        setRowIn(settingRows, "item_placement",     s.itemPlacement)
        setRowIn(settingRows, "dungeon_items",      s.dungeonItems)
        setRowIn(settingRows, "accessibility",      s.accessibility)
        setRowIn(settingRows, "goal",               s.goal)
        setRowIn(settingRows, "tower_open",         s.crystals.tower)
        setRowIn(settingRows, "ganon_open",         s.crystals.ganon)
        setRowIn(settingRows, "world_state",        s.mode)
        setRowIn(settingRows, "entrance_shuffle",   s.entrances)
        setRowIn(settingRows, "boss_shuffle",       s.enemizer.bossShuffle)
        setRowIn(settingRows, "enemy_shuffle",      s.enemizer.enemyShuffle)
        setRowIn(settingRows, "enemy_damage",       s.enemizer.enemyDamage)
        setRowIn(settingRows, "enemy_health",       s.enemizer.enemyHealth)
        setRowIn(settingRows, "pot_shuffle",        s.enemizer.potShuffle)
        setRowIn(settingRows, "hints",              s.hints)
        setRowIn(settingRows, "weapons",            s.weapons)
        setRowIn(settingRows, "item_pool",          s.item.pool)
        setRowIn(settingRows, "item_functionality", s.item.functionality)
        setRowIn(settingRows, "spoilers",           s.spoilers)
        setRowIn(settingRows, "pegasus_boots",      if (s.pseudoboots) "on" else "off")
    }

    private fun setRowIn(rows: List<SettingRowModel>, key: String, apiValue: String) {
        val row = rows.firstOrNull { it.key == key } ?: return
        val idx = row.options.indexOfFirst { it.apiValue == apiValue }
        if (idx >= 0) {
            row.selectedIndex = idx
            row.spinnerRef?.setSelection(idx, false)
        }
    }

    private fun restoreCustomization() {
        val c = PresetManager.loadCustomization(this)
        setRowIn(customizationRows, "heart_beep_speed", c.heartBeepSpeed)
        setRowIn(customizationRows, "heart_color",      c.heartColor)
        setRowIn(customizationRows, "menu_speed",       c.menuSpeed)
        setRowIn(customizationRows, "quick_swap",       c.quickSwap)
        spritePath = c.spritePath
        spritePreviewUrl = c.spritePreviewUrl
    }

    private fun restorePaths() {
        val (romStr, outputStr) = PresetManager.loadPaths(this)
        var clearedRom = false
        var clearedOutput = false
        if (romStr != null) {
            try {
                val uri = Uri.parse(romStr)
                contentResolver.openInputStream(uri)?.close()
                romUri = uri
                binding.romPathText.text = uri.lastPathSegment ?: uri.toString()
            } catch (_: Exception) { clearedRom = true }
        }
        if (outputStr != null) {
            try {
                val uri = Uri.parse(outputStr)
                val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                if (doc != null && doc.exists()) {
                    outputUri = uri
                    binding.outputPathText.text = uri.lastPathSegment ?: uri.toString()
                } else {
                    clearedOutput = true
                }
            } catch (_: Exception) { clearedOutput = true }
        }
        if (clearedRom || clearedOutput) {
            PresetManager.savePaths(this, romUri?.toString(), outputUri?.toString())
        }
        updateGenerateButton()
    }


    private fun currentCustomization(): CustomizationSettings {
        fun cv(key: String) = customizationRows.firstOrNull { it.key == key }
            ?.let { it.options[it.selectedIndex].apiValue } ?: ""
        return CustomizationSettings(
            heartBeepSpeed   = cv("heart_beep_speed"),
            heartColor       = cv("heart_color"),
            menuSpeed        = cv("menu_speed"),
            quickSwap        = cv("quick_swap"),
            spritePath       = spritePath,
            spritePreviewUrl = spritePreviewUrl,
        )
    }

    private fun tryMatchPreset() {
        if (suppressPresetApply) return
        val currentJson = AlttprApiClient.json.encodeToString(currentSettings())
        val matchIdx = cachedPresetJsons.indexOfFirst { it == currentJson }
        suppressPresetApply = true
        if (matchIdx >= 0) {
            binding.presetSpinner.setSelection(matchIdx)
            val isBuiltIn = matchIdx < BuiltInPresets.all.size
            binding.presetNameEdit.visibility = View.GONE
            binding.savePresetBtn.visibility = View.GONE
            binding.deletePresetBtn.visibility = if (isBuiltIn) View.GONE else View.VISIBLE
            binding.deletePresetBtn.isEnabled = !isBuiltIn
        } else {
            binding.presetSpinner.setSelection(allPresets.size - 1)
            binding.presetNameEdit.setText("")
            binding.presetNameEdit.visibility = View.VISIBLE
            binding.savePresetBtn.visibility = View.VISIBLE
            binding.savePresetBtn.isEnabled = false
            binding.deletePresetBtn.visibility = View.GONE
        }
        suppressPresetApply = false
    }

    private fun currentSettings(): RandomizerSettings {
        fun v(key: String) = settingRows.firstOrNull { it.key == key }
            ?.let { it.options[it.selectedIndex].apiValue } ?: ""
        return RandomizerSettings(
            glitches      = v("glitches"),
            itemPlacement = v("item_placement"),
            dungeonItems  = v("dungeon_items"),
            accessibility = v("accessibility"),
            goal          = v("goal"),
            crystals      = CrystalsSettings(tower = v("tower_open"), ganon = v("ganon_open")),
            mode          = v("world_state"),
            entrances     = v("entrance_shuffle"),
            hints         = v("hints"),
            weapons       = v("weapons"),
            item          = ItemSettings(pool = v("item_pool"), functionality = v("item_functionality")),
            spoilers      = v("spoilers"),
            pseudoboots   = v("pegasus_boots") == "on",
            enemizer      = EnemizerSettings(
                bossShuffle  = v("boss_shuffle"),
                enemyShuffle = v("enemy_shuffle"),
                enemyDamage  = v("enemy_damage"),
                enemyHealth  = v("enemy_health"),
                potShuffle   = v("pot_shuffle"),
            ),
        )
    }

    // ── Seed input ──────────────────────────────────────────────────────────────

    private fun loadSeed() {
        val input = binding.seedInputEdit.text.toString()
        val hash = AlttprApiClient.parseSeedHash(input)
        if (hash == null) {
            showStatus("Invalid seed. Enter a hash (e.g. AbC12xY) or full URL (e.g. https://alttpr.com/h/AbC12xY).", isError = true)
            return
        }

        setGenerating(true)
        lifecycleScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    AlttprApiClient.fetchSeed(hash) { msg -> runOnUiThread { showStatus(msg) } }
                }

                fetchedSeed = fetched.seed
                seedHash = hash

                if (fetched.settings != null) {
                    suppressPresetApply = true
                    applySettings(fetched.settings)
                    suppressPresetApply = false
                }
                setSeedMode(locked = true)

                lastSeedPermalink = fetched.seed.permalink
                binding.seedLinkText.text = fetched.seed.permalink
                binding.seedLinkRow.visibility = View.VISIBLE
                showStatus("Seed loaded: $hash")
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                if (msg.contains("not found", ignoreCase = true)) {
                    showStatus("Seed not found. Check the hash and try again.", isError = true)
                } else if (msg.contains("timed out", ignoreCase = true)) {
                    showStatus("Request timed out. Check your internet connection and try again.", isError = true)
                } else {
                    showStatus("Error loading seed: $msg", isError = true)
                }
            } finally {
                setGenerating(false)
            }
        }
    }

    private fun clearSeed() {
        fetchedSeed = null
        seedHash = ""
        binding.seedInputEdit.setText("")
        lastSeedPermalink = null
        binding.seedLinkRow.visibility = View.GONE
        setSeedMode(locked = false)
        showStatus("Seed cleared. You can now generate with custom settings.")
    }

    private fun copySeedToClipboard() {
        val link = lastSeedPermalink ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("seed", link))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }

    private fun setSeedMode(locked: Boolean) {
        for (row in settingRows) {
            row.spinnerRef?.isEnabled = !locked
            row.spinnerRef?.alpha = if (locked) 0.4f else 1.0f
        }
        binding.presetSpinner.isEnabled = !locked
        binding.presetNameEdit.isEnabled = !locked
        binding.savePresetBtn.isEnabled = !locked
        binding.clearSeedBtn.visibility = if (locked) View.VISIBLE else View.GONE
        binding.loadSeedBtn.isEnabled = !locked
        binding.seedInputEdit.isEnabled = !locked
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private fun generate() {
        val rom = romUri ?: return
        val output = outputUri ?: return
        val customization = currentCustomization()

        PresetManager.saveCustomization(this, customization)
        setGenerating(true)
        showOverlay()
        lastSeedPermalink = null
        binding.seedLinkRow.visibility = View.GONE

        lifecycleScope.launch {
            try {
                updateOverlayStatus("Validating ROM…")
                val (romErr, romBytes) = withContext(Dispatchers.IO) {
                    RomValidator.validate(this@MainActivity, rom)
                }
                if (romErr != null) {
                    hideOverlay()
                    showStatus(romErr, isError = true)
                    return@launch
                }

                val seed = if (fetchedSeed != null) {
                    if (fetchedSeed!!.bpsBytes.isEmpty() || fetchedSeed!!.dictPatches.isEmpty()) {
                        hideOverlay()
                        showStatus("Seed data appears invalid or incomplete. Try reloading the seed.", isError = true)
                        return@launch
                    }
                    updateOverlayStatus("Using seed: ${fetchedSeed!!.hash}")
                    fetchedSeed!!
                } else {
                    val settings = currentSettings()
                    PresetManager.saveLastSettings(this@MainActivity, settings)
                    updateOverlayStatus("Generating seed…")
                    withContext(Dispatchers.IO) {
                        AlttprApiClient.generate(settings) { msg ->
                            runOnUiThread { updateOverlayStatus(msg) }
                        }
                    }
                }

                updateOverlayStatus("Applying patches…")
                val patchedRom = withContext(Dispatchers.IO) {
                    BpsPatcher.apply(romBytes, seed.bpsBytes, seed.dictPatches, seed.sizeMb)
                }

                updateOverlayStatus("Applying cosmetics…")
                withContext(Dispatchers.IO) { CosmeticPatcher.apply(patchedRom, customization) }

                if (customization.spritePath.isNotEmpty()) {
                    updateOverlayStatus("Applying sprite…")
                    val spriteErr = withContext(Dispatchers.IO) {
                        SpriteManager.resolveAndApply(this@MainActivity, customization.spritePath, patchedRom)
                    }
                    if (spriteErr != null) {
                        hideOverlay()
                        showStatus(spriteErr, isError = true)
                        return@launch
                    }
                }

                updateOverlayStatus("Writing output ROM…")
                val writeResult = withContext(Dispatchers.IO) { writeOutput(output, seed.hash, seed.permalink, patchedRom) }

                // MSU music pack
                if (includeMsu && msuTracks.any { it.hasFile }) {
                    updateOverlayStatus("Writing MSU music pack…")
                    val activeTracks = msuTracks.filter { it.hasFile }
                        .associate { it.slotNumber.toString() to it.pcmPath!! }
                    val msuErr = withContext(Dispatchers.IO) {
                        MsuPackApplier.apply(this@MainActivity, writeResult.dir, writeResult.fileName, activeTracks)
                    }
                    if (msuErr != null) {
                        hideOverlay()
                        showStatus("MSU error: $msuErr", isError = true)
                        return@launch
                    }
                }

                lastSeedPermalink = seed.permalink
                binding.seedLinkText.text = seed.permalink
                binding.seedLinkRow.visibility = View.VISIBLE
                showStatus("Done! Seed: ${seed.hash}")

                showOverlayComplete(seed.hash, seed.permalink)
            } catch (e: Exception) {
                hideOverlay()
                showStatus("Error: ${e.message}", isError = true)
            } finally {
                setGenerating(false)
            }
        }
    }

    data class WriteResult(val dir: androidx.documentfile.provider.DocumentFile, val fileName: String)

    private fun writeOutput(treeUri: Uri, hash: String, permalink: String, rom: ByteArray): WriteResult {
        val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
            ?: throw IllegalStateException("Cannot access output folder. Please re-select it.")

        val lttprDir = docTree.findFile(lttprSubfolder)
            ?: docTree.createDirectory(lttprSubfolder)
            ?: throw IllegalStateException("Cannot create $lttprSubfolder subfolder in output folder.")
        val romFileName = "lttp_rand_$hash.sfc"
        val file = lttprDir.createFile("application/octet-stream", romFileName)
            ?: throw IllegalStateException("Cannot create output file in lttpr folder.")
        val stream = contentResolver.openOutputStream(file.uri)
            ?: throw IllegalStateException("Cannot open output file for writing.")
        stream.use { it.write(rom) }
        return WriteResult(lttprDir, romFileName)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateGenerateButton() {
        binding.generateBtn.isEnabled = romUri != null && outputUri != null
    }

    private fun setGenerating(generating: Boolean) {
        binding.progressBar.visibility = if (generating) View.VISIBLE else View.GONE
        binding.generateBtn.isEnabled = !generating && romUri != null && outputUri != null
    }

    private fun updateSpriteRow() {
        spriteNameText?.text = when (spritePath) {
            "" -> getString(R.string.default_sprite_name)
            SpriteManager.RANDOM_ALL_SENTINEL -> getString(R.string.random_all_btn)
            SpriteManager.RANDOM_FAVORITES_SENTINEL -> getString(R.string.random_fav_btn)
            else -> java.io.File(spritePath).nameWithoutExtension
        }
        val img = spritePreviewImage ?: return
        if (spritePreviewUrl.isNotEmpty()
            && spritePath != SpriteManager.RANDOM_ALL_SENTINEL
            && spritePath != SpriteManager.RANDOM_FAVORITES_SENTINEL
            && spritePath.isNotEmpty()) {
            img.visibility = View.VISIBLE
            val request = ImageRequest.Builder(this)
                .data(spritePreviewUrl)
                .target(img)
                .build()
            Coil.imageLoader(this).enqueue(request)
        } else {
            img.visibility = View.GONE
            img.setImageDrawable(null)
        }
    }

    // ── MSU Music Pack helpers ─────────────────────────────────────────────

    private fun initMsuTracks() {
        msuTracks.clear()
        msuTracks.addAll(MsuTrackCatalog.load(this))
        MsuOriginalSoundtrack.loadCachedOriginals(this, msuTracks)
    }

    private fun setupMsu() {
        filteredMsuTracks.addAll(msuTracks)
        msuAdapter = MsuTrackAdapter(filteredMsuTracks, object : MsuTrackListener {
            override fun onPlayClick(track: MsuTrackSlot, position: Int) {
                togglePlayback(track, isOriginal = false, position)
            }
            override fun onPlayOriginalClick(track: MsuTrackSlot, position: Int) {
                togglePlayback(track, isOriginal = true, position)
            }
        })
        binding.msuTrackList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.msuTrackList.adapter = msuAdapter

        binding.msuToggle.setOnClickListener {
            msuExpanded = !msuExpanded
            binding.msuContainer.visibility = if (msuExpanded) View.VISIBLE else View.GONE
            binding.msuToggle.text = getString(
                if (msuExpanded) R.string.msu_toggle_expanded else R.string.msu_toggle_collapsed
            )
        }

        binding.importLttpackBtn.setOnClickListener {
            importPackLauncher.launch(arrayOf("application/octet-stream", "application/zip"))
        }

        binding.importOstZipBtn.setOnClickListener {
            importOstZipLauncher.launch("*/*")
        }

        binding.includeMsuSwitch.setOnCheckedChangeListener { _, isChecked ->
            includeMsu = isChecked
        }

        binding.exportLttpackBtn.setOnClickListener { exportMsuPack() }
        binding.clearOstBtn.setOnClickListener {
            audioPlayer.stop()
            for (t in msuTracks) { t.isPlayingOriginal = false }
            MsuOriginalSoundtrack.clearCache(this, msuTracks)
            msuAdapter?.notifyDataSetChanged()
            showStatus("Original soundtrack cleared.")
        }
        binding.clearMsuBtn.setOnClickListener { clearMsuPack() }

        // Library folder
        binding.setLibraryFolderBtn.setOnClickListener {
            pickLibraryFolderLauncher.launch(null)
        }

        // Playlist save/load
        binding.savePlaylistBtn.setOnClickListener {
            val name = msuPackName.ifEmpty { "my-playlist" }
            savePlaylistLauncher.launch("$name.json")
        }
        binding.loadPlaylistBtn.setOnClickListener {
            loadPlaylistLauncher.launch(arrayOf("application/json", "application/octet-stream"))
        }

        // Track search
        binding.msuTrackSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filterMsuTracks(query)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun filterMsuTracks(query: String) {
        filteredMsuTracks.clear()
        if (query.isEmpty()) {
            filteredMsuTracks.addAll(msuTracks)
        } else {
            filteredMsuTracks.addAll(msuTracks.filter { it.name.contains(query, ignoreCase = true) })
        }
        msuAdapter?.notifyDataSetChanged()
    }

    private fun togglePlayback(track: MsuTrackSlot, isOriginal: Boolean, position: Int) {
        // If this track is already playing, just stop it
        val wasPlaying = if (isOriginal) track.isPlayingOriginal else track.isPlaying

        // Stop all playback
        for (t in msuTracks) { t.isPlaying = false; t.isPlayingOriginal = false }
        audioPlayer.stop()
        msuAdapter?.notifyDataSetChanged()

        if (wasPlaying) return // toggle off — done

        val path = if (isOriginal) track.originalPcmPath else track.pcmPath
        if (path == null) return

        if (isOriginal) track.isPlayingOriginal = true else track.isPlaying = true
        msuAdapter?.notifyItemChanged(position)

        audioPlayer.onPlaybackStopped = {
            track.isPlaying = false
            track.isPlayingOriginal = false
            msuAdapter?.notifyItemChanged(position)
        }

        val err = audioPlayer.play(path, lifecycleScope)
        if (err != null) {
            track.isPlaying = false
            track.isPlayingOriginal = false
            msuAdapter?.notifyItemChanged(position)
            showStatus(err, isError = true)
        }
    }

    private fun applyPlaylistToTracks(playlist: MsuPlaylist) {
        for (track in msuTracks) { track.pcmPath = null }
        for ((slotKey, pcmPath) in playlist.tracks) {
            val slot = slotKey.toIntOrNull() ?: continue
            val track = msuTracks.find { it.slotNumber == slot } ?: continue
            if (java.io.File(pcmPath).exists()) track.pcmPath = pcmPath
        }
        msuPackName = playlist.name
        includeMsu = msuTracks.any { it.hasFile }
        binding.includeMsuSwitch.isChecked = includeMsu
        refreshMsuUi()
    }

    private val exportPackLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@registerForActivityResult
        val playlist = MsuPlaylist(
            name = msuPackName.ifEmpty { "my-msu-pack" },
            tracks = msuTracks.filter { it.hasFile }
                .associate { it.slotNumber.toString() to it.pcmPath!! }
        )
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { MsuPackImporter.export(it, playlist) }
                    ?: "Cannot open file for writing."
            }
            if (error != null) showStatus(error, isError = true)
            else showStatus("Pack exported successfully.")
        }
    }

    private fun exportMsuPack() {
        val name = msuPackName.ifEmpty { "my-msu-pack" }
        exportPackLauncher.launch("$name.lttppack")
    }

    private fun clearMsuPack() {
        audioPlayer.stop()
        for (track in msuTracks) {
            track.pcmPath = null
            track.isPlaying = false
        }
        msuPackName = ""
        includeMsu = false
        binding.includeMsuSwitch.isChecked = false
        refreshMsuUi()
        showStatus("Music pack cleared.")
    }

    private fun restoreMsuSettings() {
        val settings = PresetManager.loadMsuSettings(this)
        if (settings.libraryFolder.isNotEmpty()) {
            musicLibrary.setFolder(settings.libraryFolder)
            binding.msuLibraryPath.text = "${musicLibrary.entries.size} PCM files"
        }
        if (settings.lastPlaylistName.isNotEmpty()) {
            currentPlaylistName = settings.lastPlaylistName
            binding.msuPlaylistName.text = currentPlaylistName
        }
        if (settings.tracks.isNotEmpty()) {
            val playlist = MsuPlaylist(name = settings.packName, tracks = settings.tracks)
            applyPlaylistToTracks(playlist)
            includeMsu = settings.includeMsu
            binding.includeMsuSwitch.isChecked = includeMsu
        }
    }

    private fun saveMsuSettings() {
        val settings = MsuSettings(
            includeMsu = includeMsu,
            packName = msuPackName,
            libraryFolder = musicLibrary.libraryFolder ?: "",
            lastPlaylistName = currentPlaylistName,
            tracks = msuTracks.filter { it.hasFile }
                .associate { it.slotNumber.toString() to it.pcmPath!! }
        )
        PresetManager.saveMsuSettings(this, settings)
    }

    private fun refreshMsuUi() {
        filterMsuTracks(binding.msuTrackSearch.text?.toString()?.trim() ?: "")
        val hasTracks = msuTracks.any { it.hasFile }
        val count = msuTracks.count { it.hasFile }
        if (hasTracks) {
            binding.msuPackSummary.text = "$msuPackName — $count of ${msuTracks.size} tracks assigned"
            binding.msuPackSummary.visibility = View.VISIBLE
            // Auto-check when tracks are first assigned
            if (!includeMsu) {
                includeMsu = true
                binding.includeMsuSwitch.isChecked = true
            }
        } else {
            binding.msuPackSummary.visibility = View.GONE
        }
    }

    private fun showStatus(message: String, isError: Boolean = false) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            if (isError) 0xFFFF6B6B.toInt() else 0xFFB0B0CC.toInt()
        )
    }

    // ── Overlay helpers ──────────────────────────────────────────────────────

    private fun showOverlay() {
        binding.overlaySpinner.visibility = View.VISIBLE
        binding.overlayCheckmark.visibility = View.GONE
        binding.overlayStatus.text = ""
        binding.overlaySeedHash.visibility = View.GONE
        binding.overlayLinkRow.visibility = View.GONE
        binding.overlayDismissBtn.visibility = View.GONE
        binding.generatingOverlay.visibility = View.VISIBLE
    }

    private fun updateOverlayStatus(message: String) {
        binding.overlayStatus.text = message
    }

    private fun showOverlayComplete(hash: String, permalink: String) {
        binding.overlaySpinner.visibility = View.GONE
        binding.overlayCheckmark.visibility = View.VISIBLE
        binding.overlayStatus.text = "ROM Generated!"
        binding.overlayStatus.setTextColor(0xFF4CAF50.toInt())
        binding.overlaySeedHash.text = "Seed: $hash"
        binding.overlaySeedHash.visibility = View.VISIBLE
        binding.overlayLinkText.text = permalink
        binding.overlayLinkRow.visibility = View.VISIBLE
        binding.overlayDismissBtn.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        binding.generatingOverlay.visibility = View.GONE
        binding.overlayStatus.setTextColor(0xFFE0E0F0.toInt())
    }
}

// ── SettingRowModel ───────────────────────────────────────────────────────────

data class SettingRowModel(
    val key: String,
    val label: String,
    val options: List<DropdownOption>,
    var selectedIndex: Int = 0,
    var spinnerRef: Spinner? = null,
)
