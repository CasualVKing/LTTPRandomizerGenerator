using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using LTTPRandomizerGenerator.Models;
using LTTPRandomizerGenerator.Services;
using Microsoft.Win32;

namespace LTTPRandomizerGenerator
{
    public partial class MainWindow : Window, INotifyPropertyChanged
    {
        public MainWindow()
        {
            InitializeComponent();
            DataContext = this;

            LoadPresets();
            RestoreLastSettings();
            BuildCustomizationRows();
            RestoreCustomization();
            InitMsuTracks();
            RestoreMsuSettings();
            _initialized = true;
            TryMatchPreset();

            if (PresetManager.LastLoadHadError)
                ShowStatus("Some saved settings were corrupted and reset to defaults.", isError: true);
        }

        // ── Preset matching state ─────────────────────────────────────────────

        private bool _suppressPresetApply = false;
        private bool _initialized = false;
        private List<string> _cachedPresetJsons = new();

        // ── Observable properties ─────────────────────────────────────────────

        private string _romPath = string.Empty;
        public string RomPath
        {
            get => _romPath;
            set { _romPath = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanGenerate)); PresetManager.SavePaths(_romPath, _outputFolder); }
        }

        private string _outputFolder = string.Empty;
        public string OutputFolder
        {
            get => _outputFolder;
            set { _outputFolder = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanGenerate)); PresetManager.SavePaths(_romPath, _outputFolder); }
        }

        private bool _isGenerating;
        public bool IsGenerating
        {
            get => _isGenerating;
            set { _isGenerating = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanGenerate)); }
        }

        private string _statusMessage = string.Empty;
        public string StatusMessage
        {
            get => _statusMessage;
            set { _statusMessage = value; OnPropertyChanged(); }
        }

        private Brush _statusColor = Brushes.Gray;
        public Brush StatusColor
        {
            get => _statusColor;
            set { _statusColor = value; OnPropertyChanged(); }
        }

        private string _seedPermalink = string.Empty;
        public string SeedPermalink
        {
            get => _seedPermalink;
            set { _seedPermalink = value; OnPropertyChanged(); OnPropertyChanged(nameof(HasSeedLink)); }
        }

        public bool HasSeedLink => !string.IsNullOrEmpty(SeedPermalink);

        private string _newPresetName = string.Empty;
        public string NewPresetName
        {
            get => _newPresetName;
            set { _newPresetName = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanSavePreset)); }
        }

        public bool CanGenerate =>
            !IsGenerating &&
            !string.IsNullOrWhiteSpace(RomPath) &&
            !string.IsNullOrWhiteSpace(OutputFolder);

        // ── Seed input state ────────────────────────────────────────────────

        private string _seedInput = string.Empty;
        public string SeedInput
        {
            get => _seedInput;
            set { _seedInput = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanLoadSeed)); }
        }

        private string _seedHash = string.Empty;
        public string SeedHash
        {
            get => _seedHash;
            set
            {
                _seedHash = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(HasSeedHash));
                OnPropertyChanged(nameof(IsSettingsEditable));
                OnPropertyChanged(nameof(CanLoadSeed));
            }
        }

        public bool HasSeedHash => !string.IsNullOrWhiteSpace(_seedHash);
        public bool IsSettingsEditable => !HasSeedHash;
        public bool CanLoadSeed => !string.IsNullOrWhiteSpace(SeedInput) && !IsGenerating && !HasSeedHash;

        private FetchedSeed? _fetchedSeed;

        private bool _isSettingsExpanded = false;
        public bool IsSettingsExpanded
        {
            get => _isSettingsExpanded;
            set { _isSettingsExpanded = value; OnPropertyChanged(); OnPropertyChanged(nameof(SettingsToggleLabel)); }
        }

        public string SettingsToggleLabel => IsSettingsExpanded ? "▲ RANDOMIZER SETTINGS" : "▶ RANDOMIZER SETTINGS";

        // ── Preset state ──────────────────────────────────────────────────────

        public ObservableCollection<RandomizerPreset> AllPresets { get; } = new();

        private RandomizerPreset? _selectedPreset;
        public RandomizerPreset? SelectedPreset
        {
            get => _selectedPreset;
            set
            {
                _selectedPreset = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(IsCustomSelected));
                OnPropertyChanged(nameof(IsUserPresetSelected));
                OnPropertyChanged(nameof(CanSavePreset));
                OnPropertyChanged(nameof(CanDeletePreset));
            }
        }

        public bool IsCustomSelected => SelectedPreset?.IsCustomSentinel == true;
        public bool IsUserPresetSelected => SelectedPreset is { IsBuiltIn: false, IsCustomSentinel: false };
        public bool CanSavePreset => IsCustomSelected && !string.IsNullOrWhiteSpace(NewPresetName);
        public bool CanDeletePreset => SelectedPreset is { IsBuiltIn: false, IsCustomSentinel: false };

        // ── Customization rows ────────────────────────────────────────────────

        public ObservableCollection<SettingRow> CustomizationRows { get; } = new();

        private bool _isCustomizationExpanded = false;
        public bool IsCustomizationExpanded
        {
            get => _isCustomizationExpanded;
            set { _isCustomizationExpanded = value; OnPropertyChanged(); OnPropertyChanged(nameof(CustomizationToggleLabel)); }
        }

        public string CustomizationToggleLabel => IsCustomizationExpanded ? "▲ CUSTOMIZATION" : "▶ CUSTOMIZATION";

        // ── MSU Music Pack ───────────────────────────────────────────────────

        private readonly MsuAudioPlayer _audioPlayer = new();
        public ObservableCollection<MsuTrackSlot> MsuTracks { get; } = new();

        private bool _isMsuExpanded = false;
        public bool IsMsuExpanded
        {
            get => _isMsuExpanded;
            set { _isMsuExpanded = value; OnPropertyChanged(); OnPropertyChanged(nameof(MsuToggleLabel)); }
        }

        public string MsuToggleLabel => IsMsuExpanded ? "▲ MUSIC PACK" : "▶ MUSIC PACK";

        private bool _includeMsuPack;
        public bool IncludeMsuPack
        {
            get => _includeMsuPack;
            set { _includeMsuPack = value; OnPropertyChanged(); }
        }

        private string _msuPackName = string.Empty;
        public string MsuPackName
        {
            get => _msuPackName;
            set { _msuPackName = value; OnPropertyChanged(); OnPropertyChanged(nameof(MsuPackSummary)); }
        }

        public bool HasMsuPack => MsuTracks.Any(t => t.HasFile);
        public string MsuPackSummary => HasMsuPack
            ? $"{MsuPackName} — {MsuTracks.Count(t => t.HasFile)} of {MsuTracks.Count} tracks assigned"
            : string.Empty;

        private void RefreshMsuState()
        {
            OnPropertyChanged(nameof(HasMsuPack));
            OnPropertyChanged(nameof(MsuPackSummary));
            // Auto-check when tracks are first assigned
            if (HasMsuPack && !IncludeMsuPack)
                IncludeMsuPack = true;
            if (_initialized) SaveMsuSettings();
        }

        private readonly MsuMusicLibrary _musicLibrary = new();

        public string LibraryFolderDisplay => string.IsNullOrEmpty(_musicLibrary.LibraryFolder)
            ? "Not set" : $"{_musicLibrary.LibraryFolder} ({_musicLibrary.Entries.Count} files)";

        private string _currentPlaylistName = "(unsaved)";
        public string CurrentPlaylistName
        {
            get => _currentPlaylistName;
            set { _currentPlaylistName = value; OnPropertyChanged(); }
        }

        private string _trackSearchText = string.Empty;
        private MsuTrackSlot? _libraryPickTarget;

        // ── Sprite selection ──────────────────────────────────────────────────

        private string _spritePath = string.Empty;
        public string SpritePath
        {
            get => _spritePath;
            set
            {
                _spritePath = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(SpriteDisplayName));
                OnPropertyChanged(nameof(IsRandomSprite));
                OnPropertyChanged(nameof(RandomGlyph));
                CustomizationManager.Save(CurrentCustomization());
            }
        }

        public string SpriteDisplayName => _spritePath switch
        {
            SpriteBrowserWindow.RandomAllSentinel       => "Random (any sprite)",
            SpriteBrowserWindow.RandomFavoritesSentinel => "Random (from favorites)",
            "" or null                                  => "Default (Link)",
            _                                           => Path.GetFileNameWithoutExtension(_spritePath)
        };

        public bool IsRandomSprite =>
            _spritePath == SpriteBrowserWindow.RandomAllSentinel ||
            _spritePath == SpriteBrowserWindow.RandomFavoritesSentinel;

        public string RandomGlyph =>
            _spritePath == SpriteBrowserWindow.RandomFavoritesSentinel ? "?★" : "?";

        private string _spritePreviewUrl = string.Empty;
        public string SpritePreviewUrl
        {
            get => _spritePreviewUrl;
            set { _spritePreviewUrl = value; OnPropertyChanged(); OnPropertyChanged(nameof(EffectiveSpritePreviewUrl)); }
        }

        public string EffectiveSpritePreviewUrl =>
            !string.IsNullOrEmpty(_spritePreviewUrl)
                ? _spritePreviewUrl
                : SpriteBrowserWindow.DefaultLinkPreviewFallbackUrl;

        // ── Settings rows (drives the XAML ItemsControl) ─────────────────────

        public ObservableCollection<SettingRow> SettingRows { get; } = new();

        private RandomizerSettings CurrentSettings()
        {
            var s = new RandomizerSettings();
            foreach (var row in SettingRows)
            {
                if (row.SelectedOption is null) continue;
                string v = row.SelectedOption.ApiValue;
                switch (row.FieldKey)
                {
                    case "glitches":             s.Glitches              = v; break;
                    case "item_placement":       s.ItemPlacement         = v; break;
                    case "dungeon_items":        s.DungeonItems          = v; break;
                    case "accessibility":        s.Accessibility         = v; break;
                    case "goal":                 s.Goal                  = v; break;
                    case "tower_open":           s.Crystals.Tower        = v; break;
                    case "ganon_open":           s.Crystals.Ganon        = v; break;
                    case "world_state":          s.Mode                  = v; break;
                    case "entrance_shuffle":     s.Entrances             = v; break;
                    case "boss_shuffle":         s.Enemizer.BossShuffle  = v; break;
                    case "enemy_shuffle":        s.Enemizer.EnemyShuffle = v; break;
                    case "enemy_damage":         s.Enemizer.EnemyDamage  = v; break;
                    case "enemy_health":         s.Enemizer.EnemyHealth  = v; break;
                    case "pot_shuffle":          s.Enemizer.PotShuffle   = v; break;
                    case "hints":                s.Hints                 = v; break;
                    case "weapons":              s.Weapons               = v; break;
                    case "item_pool":            s.Item.Pool             = v; break;
                    case "item_functionality":   s.Item.Functionality    = v; break;
                    case "spoilers":             s.Spoilers              = v; break;
                    case "pegasus_boots":        s.Pseudoboots           = v == "on"; break;
                }
            }
            return s;
        }

        private void ApplySettingsToRows(RandomizerSettings s)
        {
            SetRow("glitches",           s.Glitches);
            SetRow("item_placement",     s.ItemPlacement);
            SetRow("dungeon_items",      s.DungeonItems);
            SetRow("accessibility",      s.Accessibility);
            SetRow("goal",               s.Goal);
            SetRow("tower_open",         s.Crystals.Tower);
            SetRow("ganon_open",         s.Crystals.Ganon);
            SetRow("world_state",        s.Mode);
            SetRow("entrance_shuffle",   s.Entrances);
            SetRow("boss_shuffle",       s.Enemizer.BossShuffle);
            SetRow("enemy_shuffle",      s.Enemizer.EnemyShuffle);
            SetRow("enemy_damage",       s.Enemizer.EnemyDamage);
            SetRow("enemy_health",       s.Enemizer.EnemyHealth);
            SetRow("pot_shuffle",        s.Enemizer.PotShuffle);
            SetRow("hints",              s.Hints);
            SetRow("weapons",            s.Weapons);
            SetRow("item_pool",          s.Item.Pool);
            SetRow("item_functionality", s.Item.Functionality);
            SetRow("spoilers",           s.Spoilers);
            SetRow("pegasus_boots",      s.Pseudoboots ? "on" : "off");
        }

        private void SetRow(string key, string apiValue)
        {
            var row = SettingRows.FirstOrDefault(r => r.FieldKey == key);
            if (row is null) return;
            row.SelectedOption = row.Options.FirstOrDefault(o => o.ApiValue == apiValue)
                               ?? row.Options.FirstOrDefault();
        }

        private void BuildSettingRows()
        {
            foreach (var row in SettingRows) row.PropertyChanged -= OnSettingRowChanged;
            SettingRows.Clear();
            SettingRows.Add(new("glitches",           "Glitches",                 SettingsOptions.Glitches));
            SettingRows.Add(new("item_placement",     "Item Placement",           SettingsOptions.ItemPlacement));
            SettingRows.Add(new("dungeon_items",      "Dungeon Items",            SettingsOptions.DungeonItems));
            SettingRows.Add(new("accessibility",      "Accessibility",            SettingsOptions.Accessibility));
            SettingRows.Add(new("goal",               "Goal",                     SettingsOptions.Goal));
            SettingRows.Add(new("tower_open",         "Tower Open (crystals)",    SettingsOptions.CrystalCount));
            SettingRows.Add(new("ganon_open",         "Ganon Open (crystals)",    SettingsOptions.CrystalCount));
            SettingRows.Add(new("world_state",        "World State",              SettingsOptions.WorldState));
            SettingRows.Add(new("entrance_shuffle",   "Entrance Shuffle",         SettingsOptions.EntranceShuffle));
            SettingRows.Add(new("boss_shuffle",       "Boss Shuffle",             SettingsOptions.BossShuffle));
            SettingRows.Add(new("enemy_shuffle",      "Enemy Shuffle",            SettingsOptions.EnemyShuffle));
            SettingRows.Add(new("enemy_damage",       "Enemy Damage",             SettingsOptions.EnemyDamage));
            SettingRows.Add(new("enemy_health",       "Enemy Health",             SettingsOptions.EnemyHealth));
            SettingRows.Add(new("pot_shuffle",        "Pot Shuffle",              SettingsOptions.PotShuffle));
            SettingRows.Add(new("hints",              "Hints",                    SettingsOptions.Hints));
            SettingRows.Add(new("weapons",            "Weapons",                  SettingsOptions.Weapons));
            SettingRows.Add(new("item_pool",          "Item Pool",                SettingsOptions.ItemPool));
            SettingRows.Add(new("item_functionality", "Item Functionality",       SettingsOptions.ItemFunctionality));
            SettingRows.Add(new("spoilers",           "Spoiler Log",              SettingsOptions.Spoilers));
            SettingRows.Add(new("pegasus_boots",      "Pegasus Boots Start",      SettingsOptions.PegasusBoots));

            foreach (var row in SettingRows)
                row.PropertyChanged += OnSettingRowChanged;
        }

        private void OnSettingRowChanged(object? sender, PropertyChangedEventArgs e)
            => TryMatchPreset();

        private CustomizationSettings CurrentCustomization()
        {
            var c = new CustomizationSettings { SpritePath = _spritePath, SpritePreviewUrl = _spritePreviewUrl };
            foreach (var row in CustomizationRows)
            {
                if (row.SelectedOption is null) continue;
                string v = row.SelectedOption.ApiValue;
                switch (row.FieldKey)
                {
                    case "heart_beep":   c.HeartBeepSpeed = v; break;
                    case "heart_color":  c.HeartColor     = v; break;
                    case "menu_speed":   c.MenuSpeed      = v; break;
                    case "quick_swap":   c.QuickSwap      = v; break;
                }
            }
            return c;
        }

        private void ApplyCustomizationToRows(CustomizationSettings c)
        {
            SetCustomizationRow("heart_beep",  c.HeartBeepSpeed);
            SetCustomizationRow("heart_color", c.HeartColor);
            SetCustomizationRow("menu_speed",  c.MenuSpeed);
            SetCustomizationRow("quick_swap",  c.QuickSwap);
        }

        private void SetCustomizationRow(string key, string value)
        {
            var row = CustomizationRows.FirstOrDefault(r => r.FieldKey == key);
            if (row is null) return;
            row.SelectedOption = row.Options.FirstOrDefault(o => o.ApiValue == value)
                               ?? row.Options.FirstOrDefault();
        }

        private void BuildCustomizationRows()
        {
            foreach (var row in CustomizationRows) row.PropertyChanged -= OnCustomizationRowChanged;
            CustomizationRows.Clear();
            CustomizationRows.Add(new("heart_beep",  "Heart Beep Speed", CustomizationOptions.HeartBeepSpeed));
            CustomizationRows.Add(new("heart_color", "Heart Color",       CustomizationOptions.HeartColor));
            CustomizationRows.Add(new("menu_speed",  "Menu Speed",        CustomizationOptions.MenuSpeed));
            CustomizationRows.Add(new("quick_swap",  "Quick Swap",        CustomizationOptions.QuickSwap));

            foreach (var row in CustomizationRows)
                row.PropertyChanged += OnCustomizationRowChanged;
        }

        private void OnCustomizationRowChanged(object? sender, PropertyChangedEventArgs e)
            => CustomizationManager.Save(CurrentCustomization());

        private void TryMatchPreset()
        {
            if (!_initialized || _suppressPresetApply) return;
            string currentJson = System.Text.Json.JsonSerializer.Serialize(CurrentSettings());
            int matchIdx = _cachedPresetJsons.IndexOf(currentJson);
            _suppressPresetApply = true;
            SelectedPreset = matchIdx >= 0 ? AllPresets[matchIdx] : RandomizerPreset.CustomSentinel;
            _suppressPresetApply = false;
        }

        // ── Initialization ────────────────────────────────────────────────────

        private void LoadPresets()
        {
            AllPresets.Clear();
            foreach (var p in BuiltInPresets.All)
                AllPresets.Add(p);
            foreach (var p in PresetManager.LoadUserPresets())
                AllPresets.Add(p);
            AllPresets.Add(RandomizerPreset.CustomSentinel);

            _cachedPresetJsons = AllPresets
                .Where(p => !p.IsCustomSentinel)
                .Select(p => System.Text.Json.JsonSerializer.Serialize(p.Settings))
                .ToList();

            BuildSettingRows();
        }

        private void RestoreLastSettings()
        {
            var last = PresetManager.LoadLastSettings();
            ApplySettingsToRows(last);

            var (romPath, outputFolder) = PresetManager.LoadPaths();
            if (!string.IsNullOrEmpty(romPath))      _romPath      = romPath;
            if (!string.IsNullOrEmpty(outputFolder)) _outputFolder = outputFolder;
            OnPropertyChanged(nameof(RomPath));
            OnPropertyChanged(nameof(OutputFolder));
            OnPropertyChanged(nameof(CanGenerate));
        }

        private void RestoreCustomization()
        {
            var c = CustomizationManager.Load();
            ApplyCustomizationToRows(c);
            _spritePath = c.SpritePath ?? string.Empty;
            _spritePreviewUrl = c.SpritePreviewUrl ?? string.Empty;
            OnPropertyChanged(nameof(SpritePath));
            OnPropertyChanged(nameof(SpriteDisplayName));
            OnPropertyChanged(nameof(SpritePreviewUrl));
            OnPropertyChanged(nameof(EffectiveSpritePreviewUrl));
        }

        // ── Event handlers ────────────────────────────────────────────────────

        private void BrowseRom_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog
            {
                Title = "Select ALttP Base ROM",
                Filter = "SNES ROM files (*.sfc;*.smc;*.rom)|*.sfc;*.smc;*.rom|All files (*.*)|*.*",
            };
            if (dlg.ShowDialog() == true) RomPath = dlg.FileName;
        }

        private void BrowseOutput_Click(object sender, RoutedEventArgs e)
        {
            // FolderBrowserDialog not available in WPF by default; use OpenFileDialog trick
            var dlg = new OpenFileDialog
            {
                Title = "Select Output Folder (pick any file in it, or type a path)",
                ValidateNames = false,
                CheckFileExists = false,
                FileName = "Select Folder",
            };
            if (dlg.ShowDialog() == true)
                OutputFolder = Path.GetDirectoryName(dlg.FileName) ?? string.Empty;
        }

        private void PresetCombo_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
        {
            if (_suppressPresetApply || SelectedPreset is null) return;

            if (SelectedPreset.IsCustomSentinel)
            {
                NewPresetName = string.Empty;
                return;
            }

            _suppressPresetApply = true;
            ApplySettingsToRows(SelectedPreset.Settings);
            _suppressPresetApply = false;
            NewPresetName = SelectedPreset.IsBuiltIn ? string.Empty : SelectedPreset.Name;
        }

        private void ToggleCustomization_Click(object sender, MouseButtonEventArgs e)
            => IsCustomizationExpanded = !IsCustomizationExpanded;

        private void BrowseSprite_Click(object sender, RoutedEventArgs e)
        {
            var window = new SpriteBrowserWindow { Owner = this };
            if (window.ShowDialog() != true) return;
            if (window.SelectedIsDefault)
            {
                SpritePath = string.Empty;
                SpritePreviewUrl = string.Empty;
            }
            else if (window.SelectedSpritePath == SpriteBrowserWindow.RandomAllSentinel ||
                     window.SelectedSpritePath == SpriteBrowserWindow.RandomFavoritesSentinel)
            {
                SpritePath = window.SelectedSpritePath!;
                SpritePreviewUrl = string.Empty; // no preview — it's a surprise
            }
            else
            {
                SpritePath = window.SelectedSpritePath ?? string.Empty;
                SpritePreviewUrl = window.SelectedSpritePreviewUrl ?? string.Empty;
            }
            CustomizationManager.Save(CurrentCustomization());
        }

        private void ClearSprite_Click(object sender, RoutedEventArgs e)
        {
            SpritePath = string.Empty;
            SpritePreviewUrl = string.Empty;
        }

        private void ToggleSettings_Click(object sender, MouseButtonEventArgs e)
            => IsSettingsExpanded = !IsSettingsExpanded;

        private void SavePreset_Click(object sender, RoutedEventArgs e)
        {
            string name = NewPresetName.Trim();
            string? err = PresetManager.SavePreset(name, CurrentSettings());
            if (err is not null) { ShowStatus(err, isError: true); return; }

            // Refresh list
            LoadPresets();
            _suppressPresetApply = true;
            SelectedPreset = AllPresets.FirstOrDefault(p => p.Name == name);
            _suppressPresetApply = false;
            ShowStatus($"Preset \"{name}\" saved.", isError: false);
        }

        private void DeletePreset_Click(object sender, RoutedEventArgs e)
        {
            if (SelectedPreset is null) return;
            string name = SelectedPreset.Name;
            if (MessageBox.Show($"Delete preset \"{name}\"?", "Confirm",
                    MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;

            string? err = PresetManager.DeletePreset(name);
            if (err is not null) { ShowStatus(err, isError: true); return; }
            LoadPresets();
            ShowStatus($"Preset \"{name}\" deleted.", isError: false);
        }

        // ── Seed input handlers ──────────────────────────────────────────────

        private async void LoadSeed_Click(object sender, RoutedEventArgs e)
        {
            string? hash = AlttprApiClient.ParseSeedHash(SeedInput);
            if (hash is null)
            {
                ShowStatus("Invalid seed hash. Enter a hash (e.g. ABC123) or URL (e.g. https://alttpr.com/h/ABC123).", isError: true);
                return;
            }

            IsGenerating = true;
            _cts = new CancellationTokenSource();
            try
            {
                var progress = new Progress<string>(msg => ShowStatus(msg, isError: false));
                var fetched = await AlttprApiClient.FetchSeedAsync(hash, progress, _cts.Token);
                if (fetched is null) { ShowStatus("Failed to load seed.", isError: true); return; }

                _fetchedSeed = fetched;
                SeedHash = hash;

                // Apply and lock settings
                if (fetched.Settings is not null)
                {
                    _suppressPresetApply = true;
                    ApplySettingsToRows(fetched.Settings);
                    _suppressPresetApply = false;
                }
                foreach (var row in SettingRows)
                    row.IsEnabled = false;

                SeedPermalink = fetched.Seed.Permalink;
                ShowStatus($"Seed loaded: {hash}", isError: false);
            }
            catch (InvalidOperationException ex) when (ex.Message.Contains("not found", StringComparison.OrdinalIgnoreCase))
            {
                ShowStatus("Seed not found. Check the hash and try again.", isError: true);
            }
            catch (InvalidOperationException ex) when (ex.Message.Contains("timed out", StringComparison.OrdinalIgnoreCase))
            {
                ShowStatus("Request timed out. Check your internet connection and try again.", isError: true);
            }
            catch (Exception ex)
            {
                ShowStatus($"Error loading seed: {ex.Message}", isError: true);
            }
            finally
            {
                IsGenerating = false;
                _cts?.Dispose();
                _cts = null;
            }
        }

        private void ClearSeed_Click(object sender, RoutedEventArgs e)
        {
            _fetchedSeed = null;
            SeedHash = string.Empty;
            SeedInput = string.Empty;
            SeedPermalink = string.Empty;

            foreach (var row in SettingRows)
                row.IsEnabled = true;

            ShowStatus("Seed cleared. You can now generate with custom settings.", isError: false);
        }

        private CancellationTokenSource? _cts;

        private async void Generate_Click(object sender, RoutedEventArgs e)
        {
            IsGenerating = true;
            SeedPermalink = string.Empty;
            _cts = new CancellationTokenSource();

            try
            {
                string? romErr = RomValidator.Validate(RomPath, out byte[] romBytes);
                if (romErr is not null) { ShowStatus(romErr, isError: true); return; }

                SeedResult? seed;
                if (_fetchedSeed is not null)
                {
                    seed = _fetchedSeed.Seed;
                    if (seed.BpsPatchBytes.Length == 0 || seed.DictionaryPatches is null || seed.DictionaryPatches.Count == 0)
                    {
                        ShowStatus("Seed data appears invalid or incomplete. Try reloading the seed.", isError: true);
                        return;
                    }
                    ShowStatus($"Using seed: {seed.Hash}", isError: false);
                }
                else
                {
                    var settings = CurrentSettings();
                    PresetManager.SaveLastSettings(settings);

                    string boots = settings.Pseudoboots ? "Boots" : "No Boots";
                    ShowStatus($"Sending: {settings.Mode} | {settings.Goal} | {boots}", isError: false);

                    var progress = new Progress<string>(msg => ShowStatus(msg, isError: false));
                    seed = await AlttprApiClient.GenerateAsync(settings, progress, _cts.Token);
                    if (seed is null) { ShowStatus("Generation failed: no response from API.", isError: true); return; }
                }

                ShowStatus("Applying patches...", isError: false);
                var customization = CurrentCustomization();
                byte[] output = await Task.Run(() =>
                {
                    byte[] rom = BpsPatcher.Apply(romBytes, seed.BpsPatchBytes, seed.DictionaryPatches, seed.RomSizeMb);
                    return CosmeticPatcher.Apply(rom, customization);
                }, _cts.Token);

                string lttprFolder = Path.Combine(OutputFolder, "lttpr");
                Directory.CreateDirectory(lttprFolder);
                string outFile = Path.Combine(lttprFolder, $"lttp_rand_{seed.Hash}.sfc");
                await File.WriteAllBytesAsync(outFile, output, _cts.Token);

                if (!string.IsNullOrEmpty(SpritePath))
                {
                    ShowStatus("Applying sprite...", isError: false);
                    string? spriteErr;
                    if (IsRandomSprite)
                    {
                        bool favsOnly = SpritePath == SpriteBrowserWindow.RandomFavoritesSentinel;
                        spriteErr = await PickRandomSpriteAsync(favsOnly, outFile, _cts.Token);
                    }
                    else
                    {
                        spriteErr = await Task.Run(() => SpriteApplier.Apply(SpritePath, outFile), _cts.Token);
                    }
                    if (spriteErr is not null) { ShowStatus($"Sprite error: {spriteErr}", isError: true); return; }
                }

                // MSU music pack
                if (IncludeMsuPack && HasMsuPack)
                {
                    ShowStatus("Writing MSU music pack...", isError: false);
                    var activeTracks = MsuTracks
                        .Where(t => t.PcmPath != null)
                        .ToDictionary(t => t.SlotNumber.ToString(), t => t.PcmPath!);
                    var msuErr = await Task.Run(() => MsuPackApplier.Apply(outFile, activeTracks), _cts.Token);
                    if (msuErr is not null) { ShowStatus($"MSU error: {msuErr}", isError: true); return; }
                }

                SeedPermalink = seed.Permalink;
                var msuNote = (IncludeMsuPack && HasMsuPack) ? " + MSU" : "";
                ShowStatus($"Done! Seed: {seed.Hash}  —  saved to {Path.GetFileName(outFile)}{msuNote}", isError: false);
            }
            catch (OperationCanceledException)
            {
                ShowStatus("Cancelled.", isError: false);
            }
            catch (Exception ex)
            {
                ShowStatus($"Error: {ex.Message}", isError: true);
            }
            finally
            {
                IsGenerating = false;
                _cts?.Dispose();
                _cts = null;
            }
        }

        // ── Random sprite resolution ──────────────────────────────────────────

        private static readonly System.Net.Http.HttpClient _randomHttp =
            new() { Timeout = TimeSpan.FromSeconds(30) };

        private static readonly System.Text.Json.JsonSerializerOptions _jsonOpts =
            new() { PropertyNameCaseInsensitive = true };

        /// <summary>
        /// Picks a random sprite from the cached list (or fetches from network),
        /// downloads it to the sprite cache if needed, and applies it to <paramref name="romPath"/>.
        /// Returns null on success, or an error string.
        /// </summary>
        private async Task<string?> PickRandomSpriteAsync(bool favoritesOnly, string romPath, CancellationToken ct)
        {
            try
            {
                List<Models.SpriteEntry>? sprites;
                if (File.Exists(SpriteBrowserWindow.SpritesListCachePath))
                {
                    var json = await File.ReadAllTextAsync(SpriteBrowserWindow.SpritesListCachePath, ct);
                    sprites = System.Text.Json.JsonSerializer.Deserialize<List<Models.SpriteEntry>>(json, _jsonOpts);
                }
                else
                {
                    var json = await _randomHttp.GetStringAsync("https://alttpr.com/sprites", ct);
                    sprites = System.Text.Json.JsonSerializer.Deserialize<List<Models.SpriteEntry>>(json, _jsonOpts);
                }

                if (sprites is null || sprites.Count == 0)
                    return "No sprites available for random selection.";

                List<Models.SpriteEntry> pool = sprites;
                if (favoritesOnly)
                {
                    var favs = Services.FavoritesManager.Load();
                    pool = sprites.Where(s => favs.Contains(s.Name)).ToList();
                    if (pool.Count == 0)
                        return "No favorites found. Add favorites in the sprite browser first.";
                }

                var picked = pool[Random.Shared.Next(pool.Count)];

                var cacheDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "LTTPRandomizerGenerator", "SpriteCache");
                Directory.CreateDirectory(cacheDir);

                var safeName = string.Concat(picked.Name.Split(Path.GetInvalidFileNameChars()));
                var localPath = Path.Combine(cacheDir, safeName + ".zspr");

                if (!File.Exists(localPath))
                {
                    ShowStatus($"Downloading random sprite…", isError: false);
                    var data = await _randomHttp.GetByteArrayAsync(picked.File, ct);
                    await File.WriteAllBytesAsync(localPath, data, ct);
                }

                return await Task.Run(() => Services.SpriteApplier.Apply(localPath, romPath), ct);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex)
            {
                return $"Random sprite failed: {ex.Message}";
            }
        }

        private void SeedLink_Click(object sender, MouseButtonEventArgs e)
        {
            if (!string.IsNullOrEmpty(SeedPermalink))
                Process.Start(new ProcessStartInfo(SeedPermalink) { UseShellExecute = true });
        }

        private void CopySeed_Click(object sender, RoutedEventArgs e)
        {
            if (!string.IsNullOrEmpty(SeedPermalink))
            {
                Clipboard.SetText(SeedPermalink);
                ShowStatus("Permalink copied to clipboard.", isError: false);
            }
        }

        // ── MSU Music Pack handlers ──────────────────────────────────────────

        private void InitMsuTracks()
        {
            foreach (var slot in MsuTrackCatalog.Load())
                MsuTracks.Add(slot);
            MsuOriginalSoundtrack.LoadCachedOriginals(MsuTracks.ToList());
        }

        private void RestoreMsuSettings()
        {
            var settings = MsuSettingsManager.Load();
            if (!string.IsNullOrEmpty(settings.LibraryFolder))
            {
                _musicLibrary.SetFolder(settings.LibraryFolder);
                OnPropertyChanged(nameof(LibraryFolderDisplay));
            }
            if (!string.IsNullOrEmpty(settings.LastPlaylistPath))
                CurrentPlaylistName = Path.GetFileNameWithoutExtension(settings.LastPlaylistPath);
            if (settings.Tracks.Count > 0)
            {
                var playlist = new MsuPlaylist { Name = settings.PackName, Tracks = settings.Tracks };
                ApplyPlaylistToTracks(playlist);
                IncludeMsuPack = settings.IncludeMsu;
            }
        }

        private void SaveMsuSettings()
        {
            var settings = new MsuSettings
            {
                IncludeMsu = IncludeMsuPack,
                PackName = MsuPackName,
                LibraryFolder = _musicLibrary.LibraryFolder ?? string.Empty,
                LastPlaylistPath = string.Empty,
                Tracks = MsuTracks.Where(t => t.HasFile)
                    .ToDictionary(t => t.SlotNumber.ToString(), t => t.PcmPath!)
            };
            MsuSettingsManager.Save(settings);
        }

        private void ToggleMsu_Click(object sender, MouseButtonEventArgs e)
            => IsMsuExpanded = !IsMsuExpanded;

        private void ImportLttppack_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog
            {
                Filter = "Music Pack (*.lttppack)|*.lttppack|ZIP files (*.zip)|*.zip",
                Title = "Import MSU Music Pack"
            };
            if (dlg.ShowDialog() != true) return;

            var (playlist, error) = MsuPackImporter.Import(dlg.FileName);
            if (error is not null) { ShowStatus(error, isError: true); return; }

            ApplyPlaylistToTracks(playlist!);
            ShowStatus($"Imported pack: {playlist!.Name} ({playlist.Tracks.Count} tracks)", isError: false);
        }

        private async void ImportOstFolder_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new System.Windows.Forms.FolderBrowserDialog
            {
                Description = "Select folder containing original soundtrack audio files"
            };
            if (dlg.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;

            MsuProgressText.Visibility = Visibility.Visible;
            var progress = new Progress<(int current, int total, string trackName)>(p =>
            {
                MsuProgressText.Text = $"Converting {p.current}/{p.total}: {p.trackName}...";
            });

            var result = await MsuOriginalSoundtrack.ImportFromFolderAsync(dlg.SelectedPath, MsuTracks.ToList(), progress);
            MsuProgressText.Visibility = Visibility.Collapsed;

            if (result is not null)
                ShowStatus(result, isError: result.Contains("failed", StringComparison.OrdinalIgnoreCase));
            else
                ShowStatus("Original soundtrack imported successfully.", isError: false);
            RefreshMsuState();
        }

        private async void ImportOstZip_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog
            {
                Filter = "ZIP files (*.zip)|*.zip",
                Title = "Import Original Soundtrack ZIP"
            };
            if (dlg.ShowDialog() != true) return;

            MsuProgressText.Visibility = Visibility.Visible;
            var progress = new Progress<(int current, int total, string trackName)>(p =>
            {
                MsuProgressText.Text = $"Converting {p.current}/{p.total}: {p.trackName}...";
            });

            var result = await MsuOriginalSoundtrack.ImportFromZipAsync(dlg.FileName, MsuTracks.ToList(), progress);
            MsuProgressText.Visibility = Visibility.Collapsed;

            if (result is not null)
                ShowStatus(result, isError: result.Contains("failed", StringComparison.OrdinalIgnoreCase));
            else
                ShowStatus("Original soundtrack imported successfully.", isError: false);
            RefreshMsuState();
        }

        private void ExportPack_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new SaveFileDialog
            {
                Filter = "Music Pack (*.lttppack)|*.lttppack",
                FileName = string.IsNullOrEmpty(MsuPackName) ? "my-msu-pack" : MsuPackName
            };
            if (dlg.ShowDialog() != true) return;

            var playlist = new MsuPlaylist
            {
                Name = Path.GetFileNameWithoutExtension(dlg.FileName),
                Tracks = MsuTracks.Where(t => t.HasFile)
                    .ToDictionary(t => t.SlotNumber.ToString(), t => t.PcmPath!)
            };

            var (result, error) = MsuPackImporter.Export(dlg.FileName, playlist);
            if (error is not null)
                ShowStatus(error, isError: true);
            else
                ShowStatus($"Exported {result!.TracksWritten} tracks to {Path.GetFileName(dlg.FileName)}", isError: false);
        }

        private void ClearPack_Click(object sender, RoutedEventArgs e)
        {
            _audioPlayer.Stop();
            foreach (var track in MsuTracks)
            {
                track.PcmPath = null;
                track.IsPlaying = false;
            }
            MsuPackName = string.Empty;
            IncludeMsuPack = false;
            RefreshMsuState();
            ShowStatus("Music pack cleared.", isError: false);
        }

        private void PlayTrack_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuTrackSlot track) return;
            TogglePlayback(track, isOriginal: false);
        }

        private void PlayOriginal_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuTrackSlot track) return;
            TogglePlayback(track, isOriginal: true);
        }

        private void TogglePlayback(MsuTrackSlot track, bool isOriginal)
        {
            // If this track is already playing, just stop it
            bool wasPlaying = isOriginal ? track.IsPlayingOriginal : track.IsPlaying;

            // Stop all playback
            foreach (var t in MsuTracks) { t.IsPlaying = false; t.IsPlayingOriginal = false; }
            _audioPlayer.Stop();

            if (wasPlaying) return; // toggle off — done

            string? path = isOriginal ? track.OriginalPcmPath : track.PcmPath;
            if (path is null) return;

            if (isOriginal)
                track.IsPlayingOriginal = true;
            else
                track.IsPlaying = true;

            _audioPlayer.PlaybackStopped += (_, _) => Dispatcher.Invoke(() =>
            {
                track.IsPlaying = false;
                track.IsPlayingOriginal = false;
            });

            var err = _audioPlayer.Play(path);
            if (err is not null)
            {
                track.IsPlaying = false;
                track.IsPlayingOriginal = false;
                ShowStatus(err, isError: true);
            }
        }

        private void AssignTrack_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuTrackSlot track) return;

            var dlg = new OpenFileDialog
            {
                Filter = "MSU-1 PCM (*.pcm)|*.pcm",
                Title = $"Assign PCM for Slot {track.SlotDisplay} — {track.Name}"
            };
            if (dlg.ShowDialog() != true) return;

            var validationErr = MsuPcmValidator.Validate(dlg.FileName);
            if (validationErr is not null)
            {
                track.ValidationError = validationErr;
                ShowStatus($"Invalid PCM: {validationErr}", isError: true);
                return;
            }

            track.PcmPath = dlg.FileName;
            track.ValidationError = null;
            RefreshMsuState();
        }

        private void ClearTrack_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuTrackSlot track) return;

            if (track.IsPlaying) _audioPlayer.Stop();
            track.PcmPath = null;
            track.ValidationError = null;
            track.IsPlaying = false;
            RefreshMsuState();
        }

        private void ApplyPlaylistToTracks(MsuPlaylist playlist)
        {
            // Clear existing assignments
            foreach (var track in MsuTracks) { track.PcmPath = null; track.ValidationError = null; }

            foreach (var (slotKey, pcmPath) in playlist.Tracks)
            {
                if (!int.TryParse(slotKey, out int slot)) continue;
                var track = MsuTracks.FirstOrDefault(t => t.SlotNumber == slot);
                if (track is null) continue;

                if (File.Exists(pcmPath))
                {
                    track.PcmPath = pcmPath;
                    var err = MsuPcmValidator.Validate(pcmPath);
                    track.ValidationError = err;
                }
            }

            MsuPackName = playlist.Name;
            IncludeMsuPack = MsuTracks.Any(t => t.HasFile);
            RefreshMsuState();
        }

        // ── Library / Playlist handlers ────────────────────────────────────────

        private void SetLibraryFolder_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new System.Windows.Forms.FolderBrowserDialog
            {
                Description = "Select your MSU-1 music library folder",
                SelectedPath = _musicLibrary.LibraryFolder ?? ""
            };
            if (dlg.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;

            _musicLibrary.SetFolder(dlg.SelectedPath);

            // Auto-create subfolders
            Directory.CreateDirectory(Path.Combine(dlg.SelectedPath, "_cache"));
            Directory.CreateDirectory(Path.Combine(dlg.SelectedPath, "Playlists"));

            OnPropertyChanged(nameof(LibraryFolderDisplay));
            SaveMsuSettings();
            ShowStatus($"Library set: {_musicLibrary.Entries.Count} files found.", isError: false);
        }

        private void SavePlaylist_Click(object sender, RoutedEventArgs e)
        {
            string initialDir = _musicLibrary.LibraryFolder is not null
                ? Path.Combine(_musicLibrary.LibraryFolder, "Playlists")
                : Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            Directory.CreateDirectory(initialDir);

            var dlg = new SaveFileDialog
            {
                Filter = "Playlist (*.json)|*.json",
                InitialDirectory = initialDir,
                FileName = string.IsNullOrEmpty(MsuPackName) ? "my-playlist" : MsuPackName
            };
            if (dlg.ShowDialog() != true) return;

            var playlist = new MsuPlaylist
            {
                Name = Path.GetFileNameWithoutExtension(dlg.FileName),
                Tracks = MsuTracks.Where(t => t.HasFile)
                    .ToDictionary(t => t.SlotNumber.ToString(), t => t.PcmPath!)
            };

            var err = MsuPlaylistManager.Save(dlg.FileName, playlist);
            if (err is not null) { ShowStatus(err, isError: true); return; }

            MsuPackName = playlist.Name;
            CurrentPlaylistName = playlist.Name;
            ShowStatus($"Playlist saved: {playlist.Name} ({playlist.Tracks.Count} tracks)", isError: false);
        }

        private void LoadPlaylist_Click(object sender, RoutedEventArgs e)
        {
            string initialDir = _musicLibrary.LibraryFolder is not null
                ? Path.Combine(_musicLibrary.LibraryFolder, "Playlists")
                : Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);

            var dlg = new OpenFileDialog
            {
                Filter = "Playlist (*.json)|*.json",
                InitialDirectory = initialDir
            };
            if (dlg.ShowDialog() != true) return;

            var (playlist, err) = MsuPlaylistManager.Load(dlg.FileName);
            if (err is not null) { ShowStatus(err, isError: true); return; }

            ApplyPlaylistToTracks(playlist!);
            CurrentPlaylistName = playlist!.Name;
            ShowStatus($"Playlist loaded: {playlist.Name} ({playlist.Tracks.Count} tracks)", isError: false);
        }

        private void TrackSearch_TextChanged(object sender, TextChangedEventArgs e)
        {
            _trackSearchText = TrackSearchBox.Text.Trim();
            // Filter the ItemsControl by toggling Visibility on each track
            foreach (var track in MsuTracks)
            {
                // Use a simple approach: set a filter flag, but since ItemsControl doesn't support
                // ICollectionView easily, we iterate containers
            }
            // Workaround: use CollectionViewSource filtering
            var view = System.Windows.Data.CollectionViewSource.GetDefaultView(MsuTracks);
            if (string.IsNullOrEmpty(_trackSearchText))
                view.Filter = null;
            else
                view.Filter = obj => obj is MsuTrackSlot t &&
                    t.Name.Contains(_trackSearchText, StringComparison.OrdinalIgnoreCase);
        }

        private void AssignTrack_FromLibrary(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuTrackSlot track) return;
            _libraryPickTarget = track;

            // Populate library list
            PopulateLibraryPopup("");

            LibraryPopupTitle.Text = $"Assign to Slot {track.SlotDisplay} — {track.Name}";
            LibrarySearchBox.Text = "";
            LibraryPopup.PlacementTarget = btn;
            LibraryPopup.IsOpen = true;
        }

        private void PopulateLibraryPopup(string filter)
        {
            LibraryList.Children.Clear();

            var entries = _musicLibrary.Entries;
            var filtered = string.IsNullOrEmpty(filter)
                ? entries
                : entries.Where(e => e.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)).ToList();

            LibraryCountText.Text = $"{filtered.Count}/{entries.Count}";

            foreach (var entry in filtered)
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 1, 0, 1) };

                // Play button
                var playBtn = new System.Windows.Controls.Button
                {
                    Content = "▶", FontSize = 10, MinWidth = 22, Padding = new Thickness(3, 1, 3, 1),
                    Style = (Style)FindResource("DarkButton"),
                    IsEnabled = entry.IsPlayable,
                    Tag = entry
                };
                playBtn.Click += LibraryItemPlay_Click;
                row.Children.Add(playBtn);

                // Name (clickable to assign)
                var nameBtn = new System.Windows.Controls.Button
                {
                    Content = entry.Name, FontSize = 11, Padding = new Thickness(6, 2, 6, 2),
                    Style = (Style)FindResource("DarkButton"),
                    Foreground = new SolidColorBrush(Color.FromRgb(0xE0, 0xE0, 0xF0)),
                    Tag = entry
                };
                nameBtn.Click += LibraryItemAssign_Click;
                row.Children.Add(nameBtn);

                // Format tag
                if (!entry.IsPcm)
                {
                    var fmt = new TextBlock
                    {
                        Text = entry.FormatTag, FontSize = 9, Margin = new Thickness(4, 0, 0, 0),
                        Foreground = new SolidColorBrush(Color.FromRgb(0x88, 0x88, 0x99)),
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    row.Children.Add(fmt);
                }

                // Cached indicator
                if (entry.IsCached)
                {
                    var cached = new TextBlock
                    {
                        Text = "✓", FontSize = 10, Margin = new Thickness(4, 0, 0, 0),
                        Foreground = new SolidColorBrush(Color.FromRgb(0x4C, 0xAF, 0x50)),
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    row.Children.Add(cached);
                }

                LibraryList.Children.Add(row);
            }
        }

        private void LibrarySearch_TextChanged(object sender, TextChangedEventArgs e)
        {
            PopulateLibraryPopup(LibrarySearchBox.Text.Trim());
        }

        private async void LibraryItemAssign_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuLibraryEntry entry) return;
            if (_libraryPickTarget is null) return;

            LibraryPopup.IsOpen = false;

            string assignPath = entry.AssignablePath;

            // If needs conversion, convert first
            if (entry.NeedsConversion)
            {
                ShowStatus($"Converting {entry.Name}...", isError: false);
                string cachePath = _musicLibrary.GetCacheTargetPath(entry.SourcePath);
                Directory.CreateDirectory(Path.GetDirectoryName(cachePath)!);
                var convErr = await MsuPcmConverter.ConvertAsync(entry.SourcePath, cachePath);
                if (convErr is not null) { ShowStatus($"Conversion failed: {convErr}", isError: true); return; }
                _musicLibrary.Refresh();
                OnPropertyChanged(nameof(LibraryFolderDisplay));
                assignPath = cachePath;
            }

            var validErr = MsuPcmValidator.Validate(assignPath);
            if (validErr is not null)
            {
                _libraryPickTarget.ValidationError = validErr;
                ShowStatus($"Invalid PCM: {validErr}", isError: true);
                return;
            }

            _libraryPickTarget.PcmPath = assignPath;
            _libraryPickTarget.ValidationError = null;
            RefreshMsuState();
            ShowStatus($"Assigned {entry.Name} to slot {_libraryPickTarget.SlotDisplay}", isError: false);
        }

        private void LibraryItemPlay_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not System.Windows.Controls.Button btn || btn.Tag is not MsuLibraryEntry entry) return;

            if (_audioPlayer.IsPlaying) { _audioPlayer.Stop(); return; }

            string path = entry.AssignablePath;
            if (!entry.IsPlayable) return;

            _audioPlayer.Play(path);
        }

        private void LibraryBrowseFile_Click(object sender, RoutedEventArgs e)
        {
            LibraryPopup.IsOpen = false;
            if (_libraryPickTarget is null) return;

            var dlg = new OpenFileDialog
            {
                Filter = "MSU-1 PCM (*.pcm)|*.pcm|All Audio|*.pcm;*.mp3;*.wav;*.wma;*.aac;*.m4a;*.aiff",
                Title = $"Assign file for Slot {_libraryPickTarget.SlotDisplay} — {_libraryPickTarget.Name}"
            };
            if (dlg.ShowDialog() != true) return;

            var validationErr = MsuPcmValidator.Validate(dlg.FileName);
            if (validationErr is not null)
            {
                _libraryPickTarget.ValidationError = validationErr;
                ShowStatus($"Invalid PCM: {validationErr}", isError: true);
                return;
            }

            _libraryPickTarget.PcmPath = dlg.FileName;
            _libraryPickTarget.ValidationError = null;
            RefreshMsuState();
        }

        private void ShowStatus(string message, bool isError)
        {
            StatusMessage = message;
            StatusColor = isError
                ? new SolidColorBrush(Color.FromRgb(0xFF, 0x6B, 0x6B))
                : new SolidColorBrush(Color.FromRgb(0xB0, 0xB0, 0xCC));
        }

        // ── INotifyPropertyChanged ────────────────────────────────────────────

        public event PropertyChangedEventHandler? PropertyChanged;
        private void OnPropertyChanged([CallerMemberName] string? name = null)
            => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }

    // ── SettingRow helper (one row in the settings grid) ─────────────────────

    public class SettingRow : INotifyPropertyChanged
    {
        public string FieldKey { get; }
        public string Label { get; }
        public DropdownOption[] Options { get; }

        private DropdownOption? _selectedOption;
        public DropdownOption? SelectedOption
        {
            get => _selectedOption;
            set { _selectedOption = value; OnPropertyChanged(); }
        }

        private bool _isEnabled = true;
        public bool IsEnabled
        {
            get => _isEnabled;
            set { _isEnabled = value; OnPropertyChanged(); }
        }

        public SettingRow(string fieldKey, string label, DropdownOption[] options)
        {
            FieldKey = fieldKey;
            Label = label;
            Options = options;
            _selectedOption = options.FirstOrDefault();
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void OnPropertyChanged([CallerMemberName] string? name = null)
            => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
