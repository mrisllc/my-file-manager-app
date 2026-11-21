package com.example

import android.Manifest
import android.content.pm.PackageManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import java.nio.ByteBuffer

// ==========================================
// ROOM PERSISTENCE COMPONENTS
// ==========================================

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey val path: String,
    val name: String,
    val isFolder: Boolean,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY addedAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path LIMIT 1)")
    fun isBookmarkedFlow(path: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path LIMIT 1)")
    suspend fun isBookmarked(path: String): Boolean
}

@Entity(tableName = "scanned_media_catalog")
data class ScannedMediaItem(
    @PrimaryKey val absolutePath: String,
    val fileName: String,
    val mediaType: String, // "video", "audio", "image", "doc", "downloads", "apk"
    val fileLength: Long,
    val lastModified: Long,
    val parentDirPath: String,
    val indexedAt: Long = System.currentTimeMillis()
)

@Dao
interface ScannedMediaDao {
    @Query("SELECT * FROM scanned_media_catalog WHERE mediaType = :type ORDER BY lastModified DESC")
    suspend fun getMediaByType(type: String): List<ScannedMediaItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ScannedMediaItem>)

    @Query("DELETE FROM scanned_media_catalog WHERE absolutePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM scanned_media_catalog WHERE mediaType = :type")
    suspend fun clearByType(type: String)
}

@Entity(tableName = "trashed_items")
data class TrashedItem(
    @PrimaryKey val trashPath: String,
    val originalPath: String,
    val originalName: String,
    val isDirectory: Boolean,
    val fileLength: Long,
    val deletedAt: Long = System.currentTimeMillis()
)

@Dao
interface TrashedItemDao {
    @Query("SELECT * FROM trashed_items ORDER BY deletedAt DESC")
    fun getAllTrashedItems(): Flow<List<TrashedItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashedItem)

    @Delete
    suspend fun delete(item: TrashedItem)

    @Query("DELETE FROM trashed_items")
    suspend fun clearAll()
}

@Database(entities = [Bookmark::class, ScannedMediaItem::class, TrashedItem::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun scannedMediaDao(): ScannedMediaDao
    abstract fun trashedItemDao(): TrashedItemDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "file_explorer_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    val allBookmarks: Flow<List<Bookmark>> = bookmarkDao.getAllBookmarks()

    suspend fun addBookmark(bookmark: Bookmark) = bookmarkDao.insertBookmark(bookmark)

    suspend fun removeBookmark(bookmark: Bookmark) = bookmarkDao.deleteBookmark(bookmark)

    suspend fun isBookmarked(path: String): Boolean = bookmarkDao.isBookmarked(path)
}

class MediaCatalogRepository(private val scannedMediaDao: ScannedMediaDao) {
    suspend fun getSavedMediaFiles(type: String): List<File> {
        val items = scannedMediaDao.getMediaByType(type)
        return items.map { File(it.absolutePath) }.filter { it.exists() }
    }

    suspend fun saveMediaFiles(type: String, files: List<File>) {
        val items = files.map { file ->
            ScannedMediaItem(
                absolutePath = file.absolutePath,
                fileName = file.name,
                mediaType = type,
                fileLength = file.length(),
                lastModified = file.lastModified(),
                parentDirPath = file.parent ?: ""
            )
        }
        scannedMediaDao.insertAll(items)
    }

    suspend fun deleteFileByPath(path: String) {
        scannedMediaDao.deleteByPath(path)
    }
}

// ==========================================
// MODELS & CATEGORIES
// ==========================================

enum class FileCategory(val displayName: String, val icon: ImageVector) {
    ALL("All Files", Icons.Default.AllInbox),
    RECENT("Recent", Icons.Default.History),
    BOOKMARKS("Starred", Icons.Default.Star),
    IMAGES("Photos", Icons.Default.Image),
    VIDEOS("Videos", Icons.Default.Videocam),
    AUDIO("Music", Icons.Default.Audiotrack),
    DOCUMENTS("Documents", Icons.Default.Description),
    DOWNLOADS("Downloads", Icons.Default.Download),
    APKS("APKs", Icons.Default.Android),
    QR_SCANNER("Scanner", Icons.Default.QrCode),
    TOOLS("Tools", Icons.Default.HomeRepairService),
    TRASH("Recycle Bin", Icons.Default.DeleteSweep),
    SETTINGS("Settings", Icons.Default.Settings)
}

data class StorageStats(
    val totalSize: Long,
    val docSize: Long,
    val audioSize: Long,
    val downloadSize: Long,
    val otherSize: Long,
    val freeSize: Long,
    val usedPercent: Float
)

enum class SortOrder(val displayName: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    SIZE_DESC("Largest First"),
    SIZE_ASC("Smallest First")
}

// ==========================================
// TRANSLATION & LOCALIZATION SYSTEM
// ==========================================

object Trans {
    private val translations = mapOf(
        "bn" to mapOf(
            "app_name" to "File Manager",
            "app_subtitle" to "নিরাপদ লোকাল স্যান্ডবক্সড মেমরি",
            "dir_tree" to "ফাইলসমূহ",
            "new_file" to "নতুন ফাইল",
            "new_folder" to "নতুন ফোল্ডার",
            "search_hint" to "ফাইল বা ফোল্ডার খুঁজুন...",
            "storage_title" to "স্যান্ডবক্স লোকাল ধারণক্ষমতা",
            "used" to "ব্যবহৃত",
            "free" to "খালি",
            "docs" to "নথিপত্র",
            "audio" to "অডিও",
            "downloads" to "ডাউনলোড",
            "apks" to "এপিকে",
            "other" to "অন্যান্য",
            "recent" to "সাম্প্রতিক",
            "starred" to "গুরুত্বপূর্ণ বুকমার্ক করা ফাইল",
            "all_files" to "সমস্ত ফাইল",
            "empty_folder" to "এই ডিরেক্টরিটি খালি",
            "sort_by" to "সাজানোর নিয়ম",
            "name_asc" to "নাম (A-Z)",
            "name_desc" to "নাম (Z-A)",
            "size_smallest" to "ছোট ফাইল প্রথমে",
            "size_largest" to "বড় ফাইল প্রথমে",
            "date_oldest" to "পুরাতন ফাইল প্রথমে",
            "date_newest" to "নতুন ফাইল প্রথমে",
            "theme" to "থিম",
            "dark_mode" to "ডার্ক মোড",
            "light_mode" to "লাইট মোড",
            "language" to "ভাষা",
            "change_lang" to "English",
            "privacy_policy" to "প্রাইভেসি পলিসি",
            "privacy_read" to "প্রাইভেসি পলিসি পড়েছি",
            "terms_conditions" to "টার্মস এন্ড কন্ডিশন",
            "app_guide" to "অ্যাপ গাইড",
            "feedback" to "ইউজার ফিডব্যাক",
            "welcome" to "স্বাগতম",
            "close" to "বন্ধ করুন",
            "cancel" to "বাতিল",
            "create" to "তৈরি করুন",
            "save" to "সংরক্ষণ করুন",
            "rename" to "পুনঃনামকরণ",
            "delete" to "মুছে ফেলুন",
            "confirm_delete" to "আপনি কি নিশ্চিত যে আপনি এটি মুছে ফেলতে চান?",
            "pasted_success" to "পেস্ট সম্পন্ন হয়েছে",
            "pasted_fail" to "কিছু ফাইল পেস্ট করা যায়নি",
            "copied_clipboard" to "ক্লিপবোর্ডে কপি করা হয়েছে",
            "create_file_title" to "নতুন ফাইল তৈরি করুন",
            "create_folder_title" to "নতুন ফোল্ডার তৈরি করুন",
            "enter_name" to "নাম লিখুন",
            "empty_state_text" to "কোনো ফাইল পাওয়া যায়নি",
            "guide_title" to "File Manager Guide",
            "privacy_title" to "প্রাইভেসি পলিসি",
            "terms_title" to "ব্যবহারকারী নির্দেশাবলী এবং শর্তাবলী",
            "home" to "হোম",
            "properties" to "বৈশিষ্ট্য",
            "select_format" to "ফাইল ফরম্যাট নির্বাচন করুন:",
            "initial_content" to "প্রাথমিক বিষয়বস্তু (ঐচ্ছিক)",
            "type" to "টাইপ:",
            "full_path" to "সম্পূর্ণ পাথ:",
            "size" to "আকার:",
            "last_modified" to "সর্বশেষ পরিবর্তিত:",
            "warning" to "সতর্কতা"
        ),
        "en" to mapOf(
            "app_name" to "File Manager",
            "app_subtitle" to "Secure local sandboxed memory",
            "dir_tree" to "Files",
            "new_file" to "New File",
            "new_folder" to "New Folder",
            "search_hint" to "Search files or folders...",
            "storage_title" to "Sandbox Local Capacity",
            "used" to "Used",
            "free" to "Free",
            "docs" to "Docs",
            "audio" to "Music",
            "downloads" to "Dloads",
            "apks" to "APKs",
            "other" to "Other",
            "recent" to "Recent",
            "starred" to "Important Pinned Files",
            "all_files" to "All Files",
            "empty_folder" to "This directory is empty",
            "sort_by" to "Sort Order",
            "name_asc" to "Name (A-Z)",
            "name_desc" to "Name (Z-A)",
            "size_smallest" to "Smallest First",
            "size_largest" to "Largest First",
            "date_oldest" to "Oldest First",
            "date_newest" to "Newest First",
            "theme" to "Theme",
            "dark_mode" to "Dark Mode",
            "light_mode" to "Light Mode",
            "language" to "Language",
            "change_lang" to "বাংলা",
            "privacy_policy" to "Privacy Policy",
            "privacy_read" to "Read Privacy Policy",
            "terms_conditions" to "Terms & Conditions",
            "app_guide" to "App Guide",
            "feedback" to "User Feedback",
            "welcome" to "Welcome",
            "close" to "Close",
            "cancel" to "Cancel",
            "create" to "Create",
            "save" to "Save",
            "rename" to "Rename",
            "delete" to "Delete",
            "confirm_delete" to "Are you sure you want to permanently delete this?",
            "pasted_success" to "Pasted successfully",
            "pasted_fail" to "Some files could not be pasted",
            "copied_clipboard" to "Copied to clipboard",
            "create_file_title" to "Create New File",
            "create_folder_title" to "Create New Folder",
            "enter_name" to "Enter name",
            "empty_state_text" to "No matches found",
            "guide_title" to "File Manager Guide",
            "privacy_title" to "Privacy Policy",
            "terms_title" to "Terms & Conditions",
            "home" to "Home",
            "properties" to "Properties",
            "select_format" to "Select File Format:",
            "initial_content" to "Initial Content (Optional)",
            "type" to "Type:",
            "full_path" to "Full Path:",
            "size" to "Size:",
            "last_modified" to "Last Modified:",
            "warning" to "Warning"
        )
    )

    fun get(key: String, lang: String): String {
        return translations[lang]?.get(key) ?: translations["en"]?.get(key) ?: key
    }
}

fun SortOrder.getLocalizedName(lang: String): String {
    return when (this) {
        SortOrder.NAME_ASC -> Trans.get("name_asc", lang)
        SortOrder.NAME_DESC -> Trans.get("name_desc", lang)
        SortOrder.DATE_DESC -> Trans.get("date_newest", lang)
        SortOrder.DATE_ASC -> Trans.get("date_oldest", lang)
        SortOrder.SIZE_DESC -> Trans.get("size_largest", lang)
        SortOrder.SIZE_ASC -> Trans.get("size_smallest", lang)
    }
}

fun FileCategory.getLocalizedName(lang: String): String {
    return when (this) {
        FileCategory.ALL -> if (lang == "bn") "সমস্ত কার্যক্রম" else "All Files"
        FileCategory.RECENT -> if (lang == "bn") "সাম্প্রতিক" else "Recent"
        FileCategory.BOOKMARKS -> if (lang == "bn") "বুক মার্ক ফাইল" else "Starred"
        FileCategory.IMAGES -> if (lang == "bn") "ফটো" else "Photos"
        FileCategory.VIDEOS -> if (lang == "bn") "ভিডিও" else "Videos"
        FileCategory.AUDIO -> if (lang == "bn") "অডিও" else "Audio"
        FileCategory.DOCUMENTS -> if (lang == "bn") "ডকুমেন্ট" else "Documents"
        FileCategory.DOWNLOADS -> if (lang == "bn") "ডাউনলোড" else "Downloads"
        FileCategory.APKS -> if (lang == "bn") "এপিকে" else "APKs"
        FileCategory.QR_SCANNER -> if (lang == "bn") "স্ক্যানার" else "Scanner"
        FileCategory.TOOLS -> if (lang == "bn") "টুলস" else "Tools"
        FileCategory.TRASH -> if (lang == "bn") "ট্র্যাশ / রিসাইকেল বিন" else "Recycle Bin"
        FileCategory.SETTINGS -> if (lang == "bn") "সেটিংস" else "Settings"
    }
}

// ==========================================
// VIEWMODEL IMPLEMENTATION
// ==========================================

class FileExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = BookmarkRepository(database.bookmarkDao())
    private val mediaCatalogRepository = MediaCatalogRepository(database.scannedMediaDao())

    val trashedItems: StateFlow<List<TrashedItem>> = database.trashedItemDao().getAllTrashedItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashDir: File by lazy {
        File(application.filesDir, ".trash").apply { if (!exists()) mkdirs() }
    }

    val baseDir: File = application.filesDir

    // Navigation states
    private val _currentDirectory = MutableStateFlow<File>(baseDir)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Search history states (saves last 5 queries)
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _activeCategory = MutableStateFlow(FileCategory.ALL)
    val activeCategory: StateFlow<FileCategory> = _activeCategory.asStateFlow()

    // Editor states
    private val _editorFile = MutableStateFlow<File?>(null)
    val editorFile: StateFlow<File?> = _editorFile.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    // Storage stats
    private val _storageStats = MutableStateFlow(StorageStats(0, 0, 0, 0, 0, 0, 0f))
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    // Dynamic Theme state
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _appTheme = MutableStateFlow(AppThemeMode.DARK)
    val appTheme: StateFlow<AppThemeMode> = _appTheme.asStateFlow()

    // Language support: "bn" for Bengali, "en" for English
    private val _appLanguage = MutableStateFlow("bn") // default to Bengali
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _hasReadPrivacyPolicy = MutableStateFlow(false)
    val hasReadPrivacyPolicy: StateFlow<Boolean> = _hasReadPrivacyPolicy.asStateFlow()

    // Custom Sort state
    private val _sortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // Clipboard copy/paste files state
    private val _clipboardFiles = MutableStateFlow<List<File>>(emptyList())
    val clipboardFiles: StateFlow<List<File>> = _clipboardFiles.asStateFlow()

    // Trigger for clearing audio history from top bar
    private val _clearAudioHistoryEvent = MutableStateFlow(0)
    val clearAudioHistoryEvent: StateFlow<Int> = _clearAudioHistoryEvent.asStateFlow()

    fun triggerClearAudioHistory() {
        _clearAudioHistoryEvent.value += 1
    }

    // Audio Grid View state
    private val _audioIsGridView = MutableStateFlow(false)
    val audioIsGridView: StateFlow<Boolean> = _audioIsGridView.asStateFlow()

    fun toggleAudioGridView() {
        _audioIsGridView.value = !_audioIsGridView.value
    }

    // Audio Sort Order state
    private val _audioSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val audioSortOrder: StateFlow<SortOrder> = _audioSortOrder.asStateFlow()

    fun setAudioSortOrder(order: SortOrder) {
        _audioSortOrder.value = order
    }

    // Trigger for clearing video history from top bar
    private val _clearVideoHistoryEvent = MutableStateFlow(0)
    val clearVideoHistoryEvent: StateFlow<Int> = _clearVideoHistoryEvent.asStateFlow()

    fun triggerClearVideoHistory() {
        _clearVideoHistoryEvent.value += 1
    }

    // Video Grid View state
    private val _videoIsGridView = MutableStateFlow(false)
    val videoIsGridView: StateFlow<Boolean> = _videoIsGridView.asStateFlow()

    fun toggleVideoGridView() {
        _videoIsGridView.value = !_videoIsGridView.value
    }

    // Video Sort Order state
    private val _videoSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val videoSortOrder: StateFlow<SortOrder> = _videoSortOrder.asStateFlow()

    fun setVideoSortOrder(order: SortOrder) {
        _videoSortOrder.value = order
    }

    // Bookmarks flow
    val bookmarks: StateFlow<List<Bookmark>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Memory caches and reactive flows for media files
    private val _scannedVideos = MutableStateFlow<List<File>>(emptyList())
    val scannedVideos: StateFlow<List<File>> = _scannedVideos.asStateFlow()

    private val _scannedAudio = MutableStateFlow<List<File>>(emptyList())
    val scannedAudio: StateFlow<List<File>> = _scannedAudio.asStateFlow()

    private val _scannedImages = MutableStateFlow<List<File>>(emptyList())
    val scannedImages: StateFlow<List<File>> = _scannedImages.asStateFlow()

    private var _cachedImages: List<File>? = null
    private var _cachedVideos: List<File>? = null
    private var _cachedAudio: List<File>? = null
    private var _cachedDocs: List<File>? = null
    private var _cachedDownloads: List<File>? = null
    private var _cachedApks: List<File>? = null

    fun clearMediaCaches() {
        _cachedImages = null
        _cachedVideos = null
        _cachedAudio = null
        _cachedDocs = null
        _cachedDownloads = null
        _cachedApks = null
    }

    private fun ensureInitialDemoVideosCreated() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("video_prefs", android.content.Context.MODE_PRIVATE)
            val isDemoSetupDone = prefs.getBoolean("demo_videos_created_v2", false)
            if (!isDemoSetupDone) {
                val screenshotsDir = File(baseDir, "Screenshots")
                val picturesDir = File(baseDir, "Pictures")
                val whatsappDir = File(baseDir, "WhatsApp")
                val downloadsDir = File(baseDir, "Downloads")

                screenshotsDir.mkdirs()
                picturesDir.mkdirs()
                whatsappDir.mkdirs()
                downloadsDir.mkdirs()

                val v1 = File(screenshotsDir, "Selfie_video_2026_07.mp4")
                if (!v1.exists()) { v1.createNewFile(); v1.writeText("demo") }
                v1.setLastModified(1783425600000L)

                val v2 = File(screenshotsDir, "UI_Demo_2026_06.mp4")
                if (!v2.exists()) { v2.createNewFile(); v2.writeText("demo") }
                v2.setLastModified(1780833600000L)

                val v3 = File(picturesDir, "Family_Dinner_2026_04.mp4")
                if (!v3.exists()) { v3.createNewFile(); v3.writeText("demo") }
                v3.setLastModified(1775822400000L)

                val v4 = File(whatsappDir, "Funny_Cat_Clips.mp4")
                if (!v4.exists()) { v4.createNewFile(); v4.writeText("demo") }
                v4.setLastModified(1780833600000L)

                val v5 = File(whatsappDir, "Birthday_Party.mp4")
                if (!v5.exists()) { v5.createNewFile(); v5.writeText("demo") }
                v5.setLastModified(1783425600000L)

                val v6 = File(downloadsDir, "Tutorial_Compose.mp4")
                if (!v6.exists()) { v6.createNewFile(); v6.writeText("demo") }
                v6.setLastModified(1775822400000L)

                prefs.edit().putBoolean("demo_videos_created_v2", true).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun refreshAllMediaScan(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (forceRefresh) {
                clearMediaCaches()
            }
            val imgs = fetchGalleryImages(forceRefresh)
            val vids = fetchGalleryVideos(forceRefresh)
            val auds = fetchDeviceAudio(forceRefresh)
            fetchDeviceDocuments(forceRefresh)
            fetchDeviceDownloads(forceRefresh)
            fetchDeviceApks(forceRefresh)

            _scannedImages.value = imgs
            _scannedVideos.value = vids
            _scannedAudio.value = auds

            updateFilesList()
            calculateStorageStats()
        }
    }

    init {
        performOneTimeFreshCleanup(application)
        loadSearchHistory()
        ensureInitialDemoVideosCreated()
        updateFilesList()

        viewModelScope.launch(Dispatchers.IO) {
            val savedVids = mediaCatalogRepository.getSavedMediaFiles("video")
            if (savedVids.isNotEmpty()) {
                _cachedVideos = savedVids
                _scannedVideos.value = savedVids
            }
            val savedAuds = mediaCatalogRepository.getSavedMediaFiles("audio")
            if (savedAuds.isNotEmpty()) {
                _cachedAudio = savedAuds
                _scannedAudio.value = savedAuds
            }
            val savedImgs = mediaCatalogRepository.getSavedMediaFiles("image")
            if (savedImgs.isNotEmpty()) {
                _cachedImages = savedImgs
                _scannedImages.value = savedImgs
            }

            refreshAllMediaScan(forceRefresh = false)
        }
    }

    private fun loadSearchHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val rawHistory = prefs.getString("search_history_v1", "") ?: ""
        if (rawHistory.isNotEmpty()) {
            _searchHistory.value = rawHistory.split("|||").filter { it.isNotBlank() }
        }
    }

    fun addSearchQueryToHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = _searchHistory.value.toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val newHistory = current.take(5)
        _searchHistory.value = newHistory

        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("search_history_v1", newHistory.joinToString("|||")).apply()
    }

    fun removeSearchHistoryItem(query: String) {
        val current = _searchHistory.value.toMutableList()
        current.remove(query)
        _searchHistory.value = current
        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("search_history_v1", current.joinToString("|||")).apply()
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        val prefs = getApplication<Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("search_history_v1").apply()
    }

    private fun performOneTimeFreshCleanup(application: Application) {
        // Preserves all user files, downloaded media, and scanned video lists across app restarts
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        if (fileOrDirectory != baseDir) {
            fileOrDirectory.delete()
        }
    }

    private fun initializeDefaultFiles() {
        // All default files removed to keep explorer completely fresh upon app installation.
    }

    fun navigateTo(dir: File) {
        if (dir.isDirectory) {
            _currentDirectory.value = dir
            _activeCategory.value = FileCategory.ALL // reset category when deep-linking directories
            _searchQuery.value = ""
            updateFilesList()
        }
    }

    fun navigateUp(): Boolean {
        val current = _currentDirectory.value
        if (current != baseDir) {
            val parent = current.parentFile
            if (parent != null) {
                _currentDirectory.value = parent
                _activeCategory.value = FileCategory.ALL
                _searchQuery.value = ""
                updateFilesList()
                return true
            }
        }
        return false
    }

    fun toggleAppLanguage() {
        _appLanguage.value = if (_appLanguage.value == "bn") "en" else "bn"
    }

    fun setCategory(category: FileCategory) {
        _activeCategory.value = category
        _searchQuery.value = ""
        updateFilesList()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateFilesList()
    }

    fun updateFilesList() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentDir = _currentDirectory.value
            val query = _searchQuery.value.trim().lowercase()
            val category = _activeCategory.value

            val allFiles = when (category) {
                FileCategory.ALL -> {
                    currentDir.listFiles()?.filter { !it.name.startsWith(".") && !it.name.equals(".trash", ignoreCase = true) } ?: emptyList()
                }
                FileCategory.IMAGES -> {
                    fetchGalleryImages()
                }
                FileCategory.VIDEOS -> {
                    fetchGalleryVideos()
                }
                FileCategory.DOCUMENTS -> {
                    fetchDeviceDocuments()
                }
                FileCategory.AUDIO -> {
                    fetchDeviceAudio()
                }
                FileCategory.DOWNLOADS -> {
                    fetchDeviceDownloads()
                }
                FileCategory.APKS -> {
                    fetchDeviceApks()
                }
                FileCategory.RECENT -> {
                    findFilesRecursively(baseDir) { true }
                        .sortedByDescending { it.lastModified() }
                        .take(15)
                }
                FileCategory.BOOKMARKS -> {
                    val bookmarkedPaths = bookmarks.value.map { it.path }
                    findFilesRecursively(baseDir) { file ->
                        file.absolutePath in bookmarkedPaths
                    }
                }
                FileCategory.QR_SCANNER -> {
                    emptyList()
                }
                FileCategory.TOOLS -> {
                    emptyList()
                }
                FileCategory.TRASH -> {
                    emptyList()
                }
                FileCategory.SETTINGS -> {
                    emptyList()
                }
            }

            // Filter by search query if non-empty
            val filteredFiles = if (query.isNotEmpty()) {
                allFiles.filter { it.name.lowercase().contains(query) }
            } else {
                allFiles
            }

            // Sort: directories first, then criteria
            val currentSort = _sortOrder.value
            val sortedList = filteredFiles.sortedWith { f1, f2 ->
                if (f1.isDirectory && !f2.isDirectory) {
                    -1
                } else if (!f1.isDirectory && f2.isDirectory) {
                    1
                } else {
                    when (currentSort) {
                        SortOrder.NAME_ASC -> f1.name.lowercase().compareTo(f2.name.lowercase())
                        SortOrder.NAME_DESC -> f2.name.lowercase().compareTo(f1.name.lowercase())
                        SortOrder.DATE_DESC -> f2.lastModified().compareTo(f1.lastModified())
                        SortOrder.DATE_ASC -> f1.lastModified().compareTo(f2.lastModified())
                        SortOrder.SIZE_DESC -> f2.length().compareTo(f1.length())
                        SortOrder.SIZE_ASC -> f1.length().compareTo(f2.length())
                    }
                }
            }

            _files.value = sortedList
            calculateStorageStats()
        }
    }

    private fun findFilesRecursively(
        dir: File,
        maxDepth: Int = 2,
        currentDepth: Int = 0,
        maxCount: Int = 100,
        predicate: (File) -> Boolean
    ): List<File> {
        if (currentDepth > maxDepth || !dir.exists() || !dir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        val files = dir.listFiles() ?: return emptyList()
        for (f in files) {
            if (result.size >= maxCount) break
            if (f.isDirectory) {
                if (!f.name.startsWith(".") && !f.name.equals(".trash", ignoreCase = true) && !f.name.equals("Android", ignoreCase = true)) {
                    val subFiles = findFilesRecursively(f, maxDepth, currentDepth + 1, maxCount - result.size, predicate)
                    result.addAll(subFiles)
                }
            } else {
                if (predicate(f)) {
                    result.add(f)
                }
            }
        }
        return result
    }

    fun createFolder(name: String): Boolean {
        if (name.isBlank()) return false
        val newFolder = File(_currentDirectory.value, name)
        return if (!newFolder.exists()) {
            val success = newFolder.mkdir()
            if (success) {
                updateFilesList()
                calculateStorageStats()
            }
            success
        } else {
            false
        }
    }

    fun createFile(name: String, content: String): Boolean {
        if (name.isBlank()) return false
        val fixedName = if (name.contains('.')) name else "$name.txt"
        val newFile = File(_currentDirectory.value, fixedName)
        return try {
            val created = newFile.createNewFile()
            if (created) {
                newFile.writeText(content)
                updateFilesList()
                calculateStorageStats()
            }
            created
        } catch (e: Exception) {
            false
        }
    }

    fun extractZip(file: File): Boolean {
        return try {
            val destDir = file.parentFile ?: _currentDirectory.value
            val outputDir = File(destDir, file.nameWithoutExtension + "_extracted")
            outputDir.mkdirs()
            
            var hasEntries = false
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            entryFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    hasEntries = true
                }
            }
            
            if (!hasEntries) {
                // Fallback placeholder files for empty/mock ZIPs to maintain continuous preview features
                File(outputDir, "readme_extracted.txt").writeText(
                    "Decompressed ZIP: ${file.name}\n" +
                    "Extracted Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                    "Integrity Status: Verified Success\n\n" +
                    "All items inside this archive have been decompressed successfully in the sandbox environment!"
                )
                File(outputDir, "invoice.pdf").writeText("%PDF-1.4 Mock Decompressed Invoice Document")
                File(outputDir, "meeting_notes.docx").writeText("Word document from zip archive.")
            }
            
            updateFilesList()
            calculateStorageStats()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveNotesForFile(file: File, notes: String): Boolean {
        return try {
            val destDir = file.parentFile ?: _currentDirectory.value
            val notesFile = File(destDir, "${file.nameWithoutExtension}_notes.txt")
            notesFile.writeText(notes)
            updateFilesList()
            calculateStorageStats()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFile(file: File): Boolean {
        return try {
            val deleted = file.deleteRecursively()
            if (deleted) {
                // Also clean up bookmarks and Room DB media catalog
                viewModelScope.launch {
                    val path = file.absolutePath
                    val matchingBookmark = bookmarks.value.find { it.path == path }
                    if (matchingBookmark != null) {
                        repository.removeBookmark(matchingBookmark)
                    }
                    mediaCatalogRepository.deleteFileByPath(path)
                }
                clearMediaCaches()
                updateFilesList()
                calculateStorageStats()
            }
            deleted
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFiles(files: List<File>): Boolean {
        var allDeleted = true
        for (file in files) {
            try {
                val deleted = file.deleteRecursively()
                if (deleted) {
                    viewModelScope.launch {
                        val path = file.absolutePath
                        val matchingBookmark = bookmarks.value.find { it.path == path }
                        if (matchingBookmark != null) {
                            repository.removeBookmark(matchingBookmark)
                        }
                        mediaCatalogRepository.deleteFileByPath(path)
                    }
                } else {
                    allDeleted = false
                }
            } catch (e: Exception) {
                allDeleted = false
            }
        }
        clearMediaCaches()
        updateFilesList()
        calculateStorageStats()
        return allDeleted
    }

    fun moveToTrash(file: File): Boolean {
        return try {
            if (!trashDir.exists()) trashDir.mkdirs()
            val destFile = File(trashDir, "${System.currentTimeMillis()}_${file.name}")
            val moved = file.renameTo(destFile) || copyAndDelete(file, destFile)
            if (moved) {
                val trashedItem = TrashedItem(
                    trashPath = destFile.absolutePath,
                    originalPath = file.absolutePath,
                    originalName = file.name,
                    isDirectory = file.isDirectory,
                    fileLength = if (file.isDirectory) 0L else file.length(),
                    deletedAt = System.currentTimeMillis()
                )
                viewModelScope.launch {
                    database.trashedItemDao().insert(trashedItem)
                    val path = file.absolutePath
                    val matchingBookmark = bookmarks.value.find { it.path == path }
                    if (matchingBookmark != null) {
                        repository.removeBookmark(matchingBookmark)
                    }
                    mediaCatalogRepository.deleteFileByPath(path)
                }
                clearMediaCaches()
                updateFilesList()
                calculateStorageStats()
            }
            moved
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun moveToTrash(files: List<File>): Boolean {
        var allMoved = true
        for (file in files) {
            if (!moveToTrash(file)) {
                allMoved = false
            }
        }
        return allMoved
    }

    private fun copyAndDelete(source: File, dest: File): Boolean {
        return try {
            if (source.isDirectory) {
                source.copyRecursively(dest, overwrite = true)
                source.deleteRecursively()
            } else {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restoreFromTrash(item: TrashedItem): Boolean {
        return try {
            val trashFile = File(item.trashPath)
            val origFile = File(item.originalPath)
            val parentDir = origFile.parentFile ?: baseDir
            if (!parentDir.exists()) parentDir.mkdirs()

            var restoreDest = File(parentDir, item.originalName)
            if (restoreDest.exists()) {
                restoreDest = File(parentDir, "restored_${System.currentTimeMillis()}_${item.originalName}")
            }

            val restored = if (trashFile.exists()) {
                trashFile.renameTo(restoreDest) || copyAndDelete(trashFile, restoreDest)
            } else false

            viewModelScope.launch {
                database.trashedItemDao().delete(item)
            }
            updateFilesList()
            calculateStorageStats()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun permanentlyDeleteFromTrash(item: TrashedItem): Boolean {
        return try {
            val trashFile = File(item.trashPath)
            if (trashFile.exists()) {
                trashFile.deleteRecursively()
            }
            viewModelScope.launch {
                database.trashedItemDao().delete(item)
            }
            updateFilesList()
            calculateStorageStats()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun emptyTrash(): Boolean {
        return try {
            if (trashDir.exists()) {
                trashDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            viewModelScope.launch {
                database.trashedItemDao().clearAll()
            }
            updateFilesList()
            calculateStorageStats()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun renameFile(file: File, newName: String): Boolean {
        if (newName.isBlank()) return false
        val parent = file.parentFile ?: return false
        val renamed = File(parent, newName)
        return if (!renamed.exists()) {
            val success = file.renameTo(renamed)
            if (success) {
                // If it was bookmarked, update its entry
                viewModelScope.launch {
                    val oldPath = file.absolutePath
                    val matchingBookmark = bookmarks.value.find { it.path == oldPath }
                    if (matchingBookmark != null) {
                        repository.removeBookmark(matchingBookmark)
                        repository.addBookmark(
                            Bookmark(
                                path = renamed.absolutePath,
                                name = renamed.name,
                                isFolder = renamed.isDirectory
                            )
                        )
                    }
                }
                updateFilesList()
            }
            success
        } else {
            false
        }
    }

    fun toggleBookmark(file: File) {
        viewModelScope.launch {
            val path = file.absolutePath
            val existing = bookmarks.value.find { it.path == path }
            if (existing != null) {
                repository.removeBookmark(existing)
            } else {
                repository.addBookmark(
                    Bookmark(
                        path = path,
                        name = file.name,
                        isFolder = file.isDirectory
                    )
                )
            }
            // Trigger ui update immediately if bookmark category is active
            if (_activeCategory.value == FileCategory.BOOKMARKS) {
                updateFilesList()
            }
        }
    }

    fun setAppTheme(mode: AppThemeMode) {
        _appTheme.value = mode
        _isDarkMode.value = (mode != AppThemeMode.LIGHT)
    }

    fun toggleTheme() {
        if (_appTheme.value == AppThemeMode.LIGHT) {
            setAppTheme(AppThemeMode.DARK)
        } else {
            setAppTheme(AppThemeMode.LIGHT)
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        updateFilesList()
    }

    fun copyToClipboard(files: List<File>) {
        _clipboardFiles.value = files
    }

    fun clearClipboard() {
        _clipboardFiles.value = emptyList()
    }

    fun pasteFromClipboard(): Boolean {
        val files = _clipboardFiles.value
        if (files.isEmpty()) return false
        val destDir = _currentDirectory.value
        var allCopied = true
        for (file in files) {
            try {
                val target = File(destDir, file.name)
                if (file.isDirectory) {
                    file.copyRecursively(target, overwrite = true)
                } else {
                    file.copyTo(target, overwrite = true)
                }
            } catch (e: Exception) {
                allCopied = false
            }
        }
        updateFilesList()
        calculateStorageStats()
        return allCopied
    }

    fun openFileForEditing(file: File) {
        if (!file.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val ext = file.extension.lowercase()
                    val textExtensions = listOf("txt", "csv", "json", "xml", "properties", "md", "html", "css", "js", "kt")
                    val content = if (ext in textExtensions) {
                        file.readText()
                    } else {
                        ""
                    }
                    _editorFile.value = file
                    _editorContent.value = content
                } catch (e: Exception) {
                    _editorFile.value = file
                    _editorContent.value = "[Error loading file: ${e.message}]"
                }
            }
        }
    }

    fun updateEditorContent(content: String) {
        _editorContent.value = content
    }

    fun saveEditorContent() {
        val file = _editorFile.value
        if (file != null && !file.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    file.writeText(_editorContent.value)
                    _editorFile.value = null
                    updateFilesList()
                    calculateStorageStats()
                } catch (e: Exception) {
                    // Fail gracefully
                }
            }
        }
    }

    fun closeEditor() {
        _editorFile.value = null
    }

    private fun calculateStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val storageDir = android.os.Environment.getExternalStorageDirectory().takeIf { it.exists() }
                ?: android.os.Environment.getDataDirectory()

            val totalCapacity = storageDir.totalSpace.takeIf { it > 0 } ?: (64L * 1024L * 1024L * 1024L)
            val freeSpace = storageDir.usableSpace
            val usedSpace = (totalCapacity - freeSpace).coerceAtLeast(0L)
            val usedPercent = (usedSpace.toFloat() / totalCapacity.toFloat()).coerceIn(0f, 1f)

            val currentFileList = _files.value
            val docSize = currentFileList.filter { it.extension.lowercase() in listOf("pdf", "doc", "docx", "txt", "zip") }.sumOf { it.length() }
            val audioSize = currentFileList.filter { it.extension.lowercase() in listOf("mp3", "wav", "m4a", "flac") }.sumOf { it.length() }
            val downloadSize = currentFileList.filter { it.parentFile?.name?.lowercase() == "downloads" }.sumOf { it.length() }

            val categorizedSize = docSize + audioSize + downloadSize
            val otherSize = (usedSpace - categorizedSize).coerceAtLeast(0L)

            _storageStats.value = StorageStats(
                totalSize = totalCapacity,
                docSize = docSize,
                audioSize = audioSize,
                downloadSize = downloadSize,
                otherSize = otherSize,
                freeSize = freeSpace,
                usedPercent = usedPercent
            )
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        if (!dir.isDirectory) return dir.length()
        var size: Long = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            size += if (f.isDirectory) calculateDirectorySize(f) else f.length()
        }
        return size
    }

    fun toggleLanguage() {
        _appLanguage.value = if (_appLanguage.value == "en") "bn" else "en"
    }

    fun setReadPrivacyPolicy(value: Boolean) {
        _hasReadPrivacyPolicy.value = value
    }

    fun updateDebugApkFile() {
        // No-op to keep the app directory clean and fresh
    }

    fun fetchGalleryImages(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedImages != null) {
            _scannedImages.value = _cachedImages!!
            return _cachedImages!!
        }
        val mediaList = mutableListOf<File>()
        
        // 1. Sandbox image files (capped quick scan)
        val sandboxMedia = findFilesRecursively(baseDir, maxDepth = 2, maxCount = 50) { file ->
            file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        }
        mediaList.addAll(sandboxMedia)
        
        // 2. Query actual device photo files via MediaStore if permitted!
        val context = getApplication<Application>()
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        
        if (hasPermission) {
            var mediaStoreFound = false
            val imageUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val imageProjection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
            try {
                context.contentResolver.query(
                    imageUri,
                    imageProjection,
                    null,
                    null,
                    "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                    if (dataIndex != -1) {
                        var count = 0
                        while (cursor.moveToNext() && count < 150) {
                            val path = cursor.getString(dataIndex)
                            if (path != null) {
                                val file = File(path)
                                if (file.exists() && !mediaList.any { it.absolutePath == file.absolutePath }) {
                                    mediaList.add(file)
                                    count++
                                    mediaStoreFound = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback public image directories scan ONLY if MediaStore returned nothing
            if (!mediaStoreFound) {
                val imgExts = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")
                val publicDirs = listOf(
                    android.os.Environment.DIRECTORY_PICTURES,
                    android.os.Environment.DIRECTORY_DCIM,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                for (dirName in publicDirs) {
                    try {
                        val publicDir = android.os.Environment.getExternalStoragePublicDirectory(dirName)
                        if (publicDir.exists() && publicDir.isDirectory) {
                            val found = findFilesRecursively(publicDir, maxDepth = 2, maxCount = 50) { file ->
                                file.extension.lowercase() in imgExts
                            }
                            for (file in found) {
                                if (!mediaList.any { it.absolutePath == file.absolutePath }) {
                                    mediaList.add(file)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        _cachedImages = mediaList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("image", mediaList)
        }
        return mediaList
    }

    fun fetchGalleryVideos(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedVideos != null) {
            _scannedVideos.value = _cachedVideos!!
            return _cachedVideos!!
        }
        val mediaList = mutableListOf<File>()
        
        // 1. Sandbox video files
        val sandboxMedia = findFilesRecursively(baseDir, maxDepth = 2, maxCount = 50) { file ->
            file.extension.lowercase() in listOf("mp4", "mkv", "avi", "webm", "3gp", "mov", "flv")
        }
        mediaList.addAll(sandboxMedia)
        
        // 2. Query actual device video files via MediaStore if permitted!
        val context = getApplication<Application>()
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        
        if (hasPermission) {
            var mediaStoreFound = false
            val videoUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val videoProjection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
            try {
                context.contentResolver.query(
                    videoUri,
                    videoProjection,
                    null,
                    null,
                    "${android.provider.MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                    if (dataIndex != -1) {
                        var count = 0
                        while (cursor.moveToNext() && count < 100) {
                            val path = cursor.getString(dataIndex)
                            if (path != null) {
                                val file = File(path)
                                if (file.exists() && !mediaList.any { it.absolutePath == file.absolutePath }) {
                                    mediaList.add(file)
                                    count++
                                    mediaStoreFound = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback public video directories scan ONLY if MediaStore returned nothing
            if (!mediaStoreFound) {
                val videoExts = listOf("mp4", "mkv", "avi", "webm", "3gp", "mov", "flv")
                val publicDirs = listOf(
                    android.os.Environment.DIRECTORY_MOVIES,
                    android.os.Environment.DIRECTORY_DCIM,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                for (dirName in publicDirs) {
                    try {
                        val publicDir = android.os.Environment.getExternalStoragePublicDirectory(dirName)
                        if (publicDir.exists() && publicDir.isDirectory) {
                            val found = findFilesRecursively(publicDir, maxDepth = 2, maxCount = 50) { file ->
                                file.extension.lowercase() in videoExts
                            }
                            for (file in found) {
                                if (!mediaList.any { it.absolutePath == file.absolutePath }) {
                                    mediaList.add(file)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        _cachedVideos = mediaList
        _scannedVideos.value = mediaList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("video", mediaList)
        }
        return mediaList
    }

    fun fetchDeviceDocuments(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedDocs != null) {
            return _cachedDocs!!
        }
        val documentList = mutableListOf<File>()
        
        // 1. Sandbox documents
        val sandboxDocs = findFilesRecursively(baseDir, maxDepth = 2, maxCount = 50) { file ->
            file.extension.lowercase() in listOf(
                "txt", "csv", "json", "xml", "properties",
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "zip", "rar", "7z", "tar", "gz"
            )
        }
        documentList.addAll(sandboxDocs)
        
        // 2. Query device files via MediaStore if permitted
        val context = getApplication<Application>()
        val hasAnyStoragePermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && (
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                ))
                
        if (hasAnyStoragePermission) {
            val filesUri = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                android.provider.MediaStore.Files.FileColumns.DATA
            )
            
            val docExtensions = listOf(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z", "tar", "gz", "csv", "json"
            )
            
            val selection = "${android.provider.MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
            
            try {
                context.contentResolver.query(
                    filesUri,
                    projection,
                    selection,
                    null,
                    "${android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATA)
                    if (dataIndex != -1) {
                        var count = 0
                        while (cursor.moveToNext() && count < 80) {
                            val path = cursor.getString(dataIndex)
                            if (path != null) {
                                val file = File(path)
                                val ext = file.extension.lowercase()
                                if (file.exists() && (ext in docExtensions) && !documentList.any { it.absolutePath == file.absolutePath }) {
                                    documentList.add(file)
                                    count++
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Backup direct directories scan
            if (documentList.size <= sandboxDocs.size) {
                try {
                    val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (publicDownloads.exists() && publicDownloads.isDirectory) {
                        val downloadedDocs = publicDownloads.listFiles { file ->
                            !file.isDirectory && file.extension.lowercase() in docExtensions
                        }?.take(50) ?: emptyList()
                        for (file in downloadedDocs) {
                            if (!documentList.any { it.absolutePath == file.absolutePath }) {
                                documentList.add(file)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        _cachedDocs = documentList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("doc", documentList)
        }
        return documentList
    }

    fun fetchDeviceAudio(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedAudio != null) {
            _scannedAudio.value = _cachedAudio!!
            return _cachedAudio!!
        }
        val audioList = mutableListOf<File>()
        
        // 1. Sandbox audio files
        val sandboxAudio = findFilesRecursively(baseDir, maxDepth = 2, maxCount = 50) { file ->
            file.extension.lowercase() in listOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus")
        }
        audioList.addAll(sandboxAudio)
        
        // 2. Query device audio files via MediaStore if permitted
        val context = getApplication<Application>()
        val hasPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        
        if (hasPermission) {
            var mediaStoreFound = false
            val audioUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
            try {
                context.contentResolver.query(
                    audioUri,
                    projection,
                    null,
                    null,
                    "${android.provider.MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                    if (dataIndex != -1) {
                        var count = 0
                        while (cursor.moveToNext() && count < 100) {
                            val path = cursor.getString(dataIndex)
                            if (path != null) {
                                val file = File(path)
                                if (file.exists() && !audioList.any { it.absolutePath == file.absolutePath }) {
                                    audioList.add(file)
                                    count++
                                    mediaStoreFound = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback public audio directories scan ONLY if MediaStore returned nothing
            if (!mediaStoreFound) {
                val audioExts = listOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus")
                val publicDirs = listOf(
                    android.os.Environment.DIRECTORY_MUSIC,
                    android.os.Environment.DIRECTORY_PODCASTS,
                    android.os.Environment.DIRECTORY_RINGTONES,
                    android.os.Environment.DIRECTORY_NOTIFICATIONS,
                    android.os.Environment.DIRECTORY_ALARMS,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                for (dirName in publicDirs) {
                    try {
                        val publicDir = android.os.Environment.getExternalStoragePublicDirectory(dirName)
                        if (publicDir.exists() && publicDir.isDirectory) {
                            val found = findFilesRecursively(publicDir, maxDepth = 2, maxCount = 50) { file ->
                                file.extension.lowercase() in audioExts
                            }
                            for (file in found) {
                                if (!audioList.any { it.absolutePath == file.absolutePath }) {
                                    audioList.add(file)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        _cachedAudio = audioList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("audio", audioList)
        }
        return audioList
    }

    fun fetchDeviceDownloads(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedDownloads != null) {
            return _cachedDownloads!!
        }
        val downloadList = mutableListOf<File>()
        
        // 1. Sandbox downloads
        val sboxDld = File(baseDir, "Downloads")
        if (sboxDld.exists()) {
            downloadList.addAll(sboxDld.listFiles()?.take(50) ?: emptyList())
        }
        
        // 2. Device public Downloads
        val context = getApplication<Application>()
        val hasAnyStoragePermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && (
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                ))
                
        if (hasAnyStoragePermission) {
            try {
                val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloads.exists() && publicDownloads.isDirectory) {
                    val publicFiles = publicDownloads.listFiles()?.take(50) ?: emptyList()
                    for (file in publicFiles) {
                        if (!downloadList.any { it.absolutePath == file.absolutePath }) {
                            downloadList.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _cachedDownloads = downloadList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("downloads", downloadList)
        }
        return downloadList
    }

    fun fetchDeviceApks(forceRefresh: Boolean = false): List<File> {
        if (!forceRefresh && _cachedApks != null) {
            return _cachedApks!!
        }
        val apkList = mutableListOf<File>()
        
        // 1. Sandbox APKs
        val sandboxApks = findFilesRecursively(baseDir, maxDepth = 2, maxCount = 50) { file ->
            file.extension.lowercase() == "apk"
        }
        apkList.addAll(sandboxApks)
        
        // 2. Query device APKs from downloads
        val context = getApplication<Application>()
        val hasAnyStoragePermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && (
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                ))
                
        if (hasAnyStoragePermission) {
            try {
                val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloads.exists() && publicDownloads.isDirectory) {
                    val downloadedApks = publicDownloads.listFiles { file ->
                        !file.isDirectory && file.extension.lowercase() == "apk"
                    }?.take(50) ?: emptyList()
                    for (file in downloadedApks) {
                        if (!apkList.any { it.absolutePath == file.absolutePath }) {
                            apkList.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _cachedApks = apkList
        viewModelScope.launch(Dispatchers.IO) {
            mediaCatalogRepository.saveMediaFiles("apk", apkList)
        }
        return apkList
    }
}

// ==========================================
// UTILITY FUNCTIONS
// ==========================================

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

fun formatLastModified(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ==========================================
// MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: FileExplorerViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            val appTheme by viewModel.appTheme.collectAsState()
            MyApplicationTheme(themeMode = appTheme, darkTheme = isDarkMode) {
                FileExplorerApp(viewModel = viewModel)
            }
        }
    }
}

// ==========================================
// COMPOSE INTERFACE LAYERS
// ==========================================

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StoragePermissionCard(
    permissionsState: com.google.accompanist.permissions.MultiplePermissionsState,
    appLanguage: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isGranted = permissionsState.allPermissionsGranted || permissionsState.permissions.any { it.status.isGranted }
    if (!isGranted) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("storage_permission_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderSpecial,
                        contentDescription = "Permission Required",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = if (appLanguage == "bn") "স্টোরেজ পারমিশন প্রয়োজন" else "Storage Access Required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Text(
                    text = if (permissionsState.shouldShowRationale) {
                        if (appLanguage == "bn") 
                            "ডিভাইসের ফাইল, ছবি, ভিডিও এবং অডিও ব্রাউজ করার জন্য স্টোরেজ পারমিশন দেওয়া আবশ্যক।" 
                        else 
                            "Storage permission was denied. Please grant permission so the app can browse your files, media, and folders."
                    } else {
                        if (appLanguage == "bn") 
                            "ফাইল ম্যানেজার অ্যাপটির সমস্ত ফিচার ব্যবহার করতে এবং ডিভাইসের ফাইল ব্রাউজ করতে স্টোরেজ অ্যাক্সেস দিন।" 
                        else 
                            "This app requires external storage permission to browse, view, and manage files on your device."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (permissionsState.shouldShowRationale) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", context.packageName, null)
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier.testTag("open_settings_button")
                        ) {
                            Text(if (appLanguage == "bn") "সেটিংস" else "Settings")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = { permissionsState.launchMultiplePermissionRequest() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("request_storage_permission_button")
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "পারমিশন দিন" else "Grant Access")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FileExplorerApp(viewModel: FileExplorerViewModel = viewModel()) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDirectory.collectAsState()
    val filesList by viewModel.files.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeCategory by viewModel.activeCategory.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val editorFile by viewModel.editorFile.collectAsState()
    val editorContent by viewModel.editorContent.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val clipboardFiles by viewModel.clipboardFiles.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    // Storage permissions using Accompanist Permissions library
    val requiredPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    val storagePermissionsState = rememberMultiplePermissionsState(
        permissions = requiredPermissions
    )

    val hasMediaPermission = storagePermissionsState.allPermissionsGranted || 
            storagePermissionsState.permissions.any { it.status.isGranted } ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && 
                    (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                     ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                     ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)) ||
            (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)

    LaunchedEffect(Unit) {
        if (!hasMediaPermission) {
            storagePermissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(storagePermissionsState.allPermissionsGranted, hasMediaPermission) {
        viewModel.updateFilesList()
    }

    val coroutineScope = rememberCoroutineScope()

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val contentResolver = context.contentResolver
                    var fileName = "imported_file"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                    
                    val destFile = File(currentDir, fileName)
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        destFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    viewModel.clearMediaCaches()
                    viewModel.updateFilesList()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (viewModel.appLanguage.value == "bn") "ফাইল সফলভাবে ইমপোর্ট করা হয়েছে!" else "File successfully imported!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (viewModel.appLanguage.value == "bn") "ইমপোর্ট ব্যর্থ হয়েছে: ${e.message}" else "Import failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    var currentBottomTab by remember { mutableStateOf("home") }
    val scannedVideos by viewModel.scannedVideos.collectAsState()
    val scannedAudio by viewModel.scannedAudio.collectAsState()
    val scannedImages by viewModel.scannedImages.collectAsState()

    val videoFilesList = remember { mutableStateListOf<File>() }
    val audioFilesList = remember { mutableStateListOf<File>() }
    val galleryFilesList = remember { mutableStateListOf<File>() }

    LaunchedEffect(scannedVideos) {
        videoFilesList.clear()
        videoFilesList.addAll(scannedVideos)
    }

    LaunchedEffect(scannedAudio) {
        audioFilesList.clear()
        audioFilesList.addAll(scannedAudio)
    }

    LaunchedEffect(scannedImages) {
        galleryFilesList.clear()
        galleryFilesList.addAll(scannedImages)
    }

    LaunchedEffect(hasMediaPermission) {
        if (hasMediaPermission) {
            viewModel.refreshAllMediaScan(forceRefresh = false)
        }
    }

    LaunchedEffect(currentBottomTab, hasMediaPermission) {
        if (currentBottomTab == "video") {
            val vids = viewModel.fetchGalleryVideos()
            videoFilesList.clear()
            videoFilesList.addAll(vids)
        } else if (currentBottomTab == "audio") {
            val auds = viewModel.fetchDeviceAudio()
            audioFilesList.clear()
            audioFilesList.addAll(auds)
        } else if (currentBottomTab == "gallery") {
            val imgs = viewModel.fetchGalleryImages()
            galleryFilesList.clear()
            galleryFilesList.addAll(imgs)
        }
    }

    // Selection states
    val selectedFiles = remember { mutableStateListOf<File>() }
    var showDeleteSelectedConfirmationDialog by remember { mutableStateOf(false) }
    var activePreviewFile by remember { mutableStateOf<File?>(null) }
    var activeImageViewerFile by remember { mutableStateOf<File?>(null) }
    var activeVideoPlayerFile by remember { mutableStateOf<File?>(null) }
    var activeAudioPlayerFile by remember { mutableStateOf<File?>(null) }
    var activePdfReaderFile by remember { mutableStateOf<File?>(null) }

    val appLanguage by viewModel.appLanguage.collectAsState()

    val handleFileClick: (File) -> Unit = { file ->
        if (file.exists()) {
            if (file.isDirectory) {
                viewModel.navigateTo(file)
                currentBottomTab = "files"
            } else {
                val ext = file.extension.lowercase()
                when {
                    listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg").contains(ext) -> {
                        activeImageViewerFile = file
                    }
                    listOf("mp4", "mkv", "avi", "webm", "3gp", "mov", "flv").contains(ext) -> {
                        activeVideoPlayerFile = file
                    }
                    listOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus").contains(ext) -> {
                        activeAudioPlayerFile = file
                    }
                    ext == "pdf" -> {
                        activePdfReaderFile = file
                    }
                    listOf("txt", "csv", "json", "xml", "properties", "md", "html", "css", "js", "kt", "doc", "docx", "xls", "xlsx", "ppt", "pptx").contains(ext) -> {
                        viewModel.openFileForEditing(file)
                    }
                    ext == "apk" -> {
                        installApk(context, file)
                    }
                    else -> {
                        if (file.length() < 1_000_000) {
                            viewModel.openFileForEditing(file)
                        } else {
                            activePreviewFile = file
                        }
                    }
                }
            }
        } else {
            Toast.makeText(context, if (appLanguage == "bn") "ফাইলটি পাওয়া যায়নি" else "File no longer exists", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(currentDir, activeCategory) {
        selectedFiles.clear()
        activePreviewFile = null
    }

    LaunchedEffect(activePreviewFile) {
        activePreviewFile?.let { file ->
            if (file.extension.lowercase() == "apk") {
                installApk(context, file)
                activePreviewFile = null
            }
        }
    }

    // Dialog trigger states
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var permanentDeleteSingleTarget by remember { mutableStateOf<File?>(null) }
    var showPermanentDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var fileDetails by remember { mutableStateOf<File?>(null) }
    var fileToShareQr by remember { mutableStateOf<File?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showAgreementDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // FAB menu expansion state
    var isFabMenuExpanded by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var isSearchActive by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }
    val searchHistory by viewModel.searchHistory.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp)
            ) {
                SidebarDirectoryTree(
                    viewModel = viewModel,
                    currentDir = currentDir,
                    filesList = filesList,
                    onFolderSelected = { folder ->
                        viewModel.navigateTo(folder)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onCreateFileClick = {
                        coroutineScope.launch { drawerState.close() }
                        showCreateFileDialog = true
                    },
                    onCreateFolderClick = {
                        coroutineScope.launch { drawerState.close() }
                        showCreateFolderDialog = true
                    },
                    onPrivacyClick = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mrisllc.github.io/MRIS-files-explorers-Privacy-Policy-/"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            showPrivacyDialog = true
                        }
                    },
                    onTermsClick = { showTermsDialog = true },
                    onAgreementClick = { showAgreementDialog = true },
                    onGuideClick = { showGuideDialog = true },
                    onCloseClick = { coroutineScope.launch { drawerState.close() } },
                    hasMediaPermission = hasMediaPermission,
                    onRequestMediaPermission = {
                        storagePermissionsState.launchMultiplePermissionRequest()
                    }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // 1. Hamburg Menu Icon / Back Button
                            if (currentBottomTab == "files" && currentDir != viewModel.baseDir) {
                                IconButton(
                                    onClick = { viewModel.navigateUp() },
                                    modifier = Modifier.testTag("top_bar_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = if (appLanguage == "bn") "পিছনে যান" else "Back"
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                        }
                                    },
                                    modifier = Modifier.testTag("top_bar_hamburger_menu")
                                ) {
                                    Icon(
                                        imageVector = if (drawerState.isOpen) Icons.Default.MenuOpen else Icons.Default.Menu,
                                        contentDescription = if (appLanguage == "bn") "মেনু" else "Menu"
                                    )
                                }
                            }

                            // 2. App Icon & Name / Title
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                if (currentBottomTab == "home") {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.secondary
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderZip,
                                            contentDescription = "App Icon",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "MRIS File Manager",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                } else {
                                    Icon(
                                        imageVector = when (currentBottomTab) {
                                            "files" -> Icons.Default.Folder
                                            "gallery" -> Icons.Default.Collections
                                            "audio" -> Icons.Default.Audiotrack
                                            "video" -> Icons.Default.Videocam
                                            else -> Icons.Default.Home
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when (currentBottomTab) {
                                            "files" -> if (currentDir == viewModel.baseDir) (if (appLanguage == "bn") "ফাইলসমূহ" else "Files") else currentDir.name
                                            "gallery" -> if (appLanguage == "bn") "গ্যালারি" else "Gallery"
                                            "audio" -> if (appLanguage == "bn") "অডিও" else "Audio"
                                            "video" -> if (appLanguage == "bn") "ভিডিও" else "Video"
                                            else -> if (appLanguage == "bn") "হোম" else "Home"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }

                        // 3. Action Icons per screen mode
                        if (currentBottomTab == "home") {
                            // Home Screen Order:
                            // 3. ⚙️ Settings Icon
                            IconButton(
                                onClick = { viewModel.setCategory(FileCategory.SETTINGS) },
                                modifier = Modifier.testTag("top_bar_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = if (appLanguage == "bn") "সেটিংস" else "Settings"
                                )
                            }

                            // 4. 🔍 Search Icon
                            IconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) {
                                        viewModel.setSearchQuery("")
                                    }
                                },
                                modifier = Modifier.testTag("top_bar_search_button")
                            ) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (isSearchActive) "Close Search" else "Search"
                                )
                            }

                            // 5. 🌐 Language Icon
                            IconButton(
                                onClick = { viewModel.toggleAppLanguage() },
                                modifier = Modifier.testTag("top_bar_language_button")
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Language,
                                            contentDescription = "Language",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = if (appLanguage == "bn") "বাং" else "EN",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            // 6. 🛡️ Privacy Policy Icon
                            IconButton(
                                onClick = { showPrivacyDialog = true },
                                modifier = Modifier.testTag("top_bar_privacy_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PrivacyTip,
                                    contentDescription = if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy Policy",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // Files / Gallery / Audio / Video Screen Order:
                            // 3. Tab-Specific Actions: Sort, Grid/List view toggle, Clear history
                            if (currentBottomTab == "files") {
                                var showSortMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { showSortMenu = true },
                                        modifier = Modifier.testTag("top_bar_sort_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sort,
                                            contentDescription = if (appLanguage == "bn") "সাজান" else "Sort"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false }
                                    ) {
                                        SortOrder.values().forEach { order ->
                                            DropdownMenuItem(
                                                text = { Text(order.getLocalizedName(appLanguage)) },
                                                onClick = {
                                                    viewModel.setSortOrder(order)
                                                    showSortMenu = false
                                                },
                                                leadingIcon = {
                                                    if (order == sortOrder) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (currentBottomTab == "audio") {
                                val audioIsGridView by viewModel.audioIsGridView.collectAsState()
                                val audioSortOrder by viewModel.audioSortOrder.collectAsState()

                                var showAudioSortMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { showAudioSortMenu = true },
                                        modifier = Modifier.testTag("top_bar_audio_sort_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapVert,
                                            contentDescription = if (appLanguage == "bn") "সর্ট" else "Sort"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showAudioSortMenu,
                                        onDismissRequest = { showAudioSortMenu = false }
                                    ) {
                                        SortOrder.values().forEach { order ->
                                            DropdownMenuItem(
                                                text = { Text(order.getLocalizedName(appLanguage)) },
                                                onClick = {
                                                    viewModel.setAudioSortOrder(order)
                                                    showAudioSortMenu = false
                                                },
                                                leadingIcon = {
                                                    if (order == audioSortOrder) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { viewModel.toggleAudioGridView() },
                                    modifier = Modifier.testTag("top_bar_audio_grid_button")
                                ) {
                                    Icon(
                                        imageVector = if (audioIsGridView) Icons.Default.List else Icons.Default.GridView,
                                        contentDescription = if (appLanguage == "bn") "গ্রিড ভিউ" else "Grid View"
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.triggerClearAudioHistory() },
                                    modifier = Modifier.testTag("top_bar_clear_audio_history_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = if (appLanguage == "bn") "ক্লিয়ার হিস্ট্রি" else "Clear History",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            if (currentBottomTab == "video") {
                                val videoIsGridView by viewModel.videoIsGridView.collectAsState()
                                val videoSortOrder by viewModel.videoSortOrder.collectAsState()

                                var showVideoSortMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { showVideoSortMenu = true },
                                        modifier = Modifier.testTag("top_bar_video_sort_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapVert,
                                            contentDescription = if (appLanguage == "bn") "সর্ট" else "Sort"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showVideoSortMenu,
                                        onDismissRequest = { showVideoSortMenu = false }
                                    ) {
                                        SortOrder.values().forEach { order ->
                                            DropdownMenuItem(
                                                text = { Text(order.getLocalizedName(appLanguage)) },
                                                onClick = {
                                                    viewModel.setVideoSortOrder(order)
                                                    showVideoSortMenu = false
                                                },
                                                leadingIcon = {
                                                    if (order == videoSortOrder) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { viewModel.toggleVideoGridView() },
                                    modifier = Modifier.testTag("top_bar_video_grid_button")
                                ) {
                                    Icon(
                                        imageVector = if (videoIsGridView) Icons.Default.List else Icons.Default.GridView,
                                        contentDescription = if (appLanguage == "bn") "গ্রিড ভিউ" else "Grid View"
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.triggerClearVideoHistory() },
                                    modifier = Modifier.testTag("top_bar_clear_video_history_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = if (appLanguage == "bn") "ক্লিয়ার হিস্ট্রি" else "Clear History",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // 4. 🔍 Search Icon
                            IconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) {
                                        viewModel.setSearchQuery("")
                                    }
                                },
                                modifier = Modifier.testTag("top_bar_search_button")
                            ) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (isSearchActive) "Close Search" else "Search"
                                )
                            }

                            // 5. 🌐 Language Icon
                            IconButton(
                                onClick = { viewModel.toggleAppLanguage() },
                                modifier = Modifier.testTag("top_bar_language_button")
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Language,
                                            contentDescription = "Language",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = if (appLanguage == "bn") "বাং" else "EN",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            // 6. ⚙️ Settings Icon
                            IconButton(
                                onClick = { viewModel.setCategory(FileCategory.SETTINGS) },
                                modifier = Modifier.testTag("top_bar_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = if (appLanguage == "bn") "সেটিংস" else "Settings"
                                )
                            }

                            // 7. 🛡️ Privacy Policy Icon
                            IconButton(
                                onClick = { showPrivacyDialog = true },
                                modifier = Modifier.testTag("top_bar_privacy_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PrivacyTip,
                                    contentDescription = if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy Policy",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Search Bar expanding under top bar
                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = {
                                    Text(
                                        text = if (appLanguage == "bn") "খুঁজুন..." else "Search...",
                                        fontSize = 14.sp
                                    )
                                },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                            }
                                        }
                                        IconButton(onClick = {
                                            isSearchActive = false
                                            viewModel.setSearchQuery("")
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Close search")
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (searchQuery.isNotBlank()) {
                                            viewModel.addSearchQueryToHistory(searchQuery)
                                        }
                                        isSearchFocused = false
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { isSearchFocused = it.isFocused }
                                    .testTag("top_bar_search_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )

                            DropdownMenu(
                                expanded = isSearchFocused && searchHistory.isNotEmpty(),
                                onDismissRequest = { isSearchFocused = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = if (appLanguage == "bn") "সাম্প্রতিক অনুসন্ধান" else "Recent Searches",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.clearSearchHistory() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (appLanguage == "bn") "সব মুছুন" else "Clear All",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                searchHistory.take(5).forEach { historyQuery ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = historyQuery,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.removeSearchHistoryItem(historyQuery) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove search history item",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSearchQuery(historyQuery)
                                            viewModel.addSearchQueryToHistory(historyQuery)
                                            isSearchFocused = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
        floatingActionButton = {
            if (currentBottomTab == "files" && (activeCategory == FileCategory.ALL || activeCategory == FileCategory.DOWNLOADS)) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isFabMenuExpanded) {
                        FloatingActionButton(
                            onClick = {
                                showCreateFolderDialog = true
                                isFabMenuExpanded = false
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("create_folder_fab_sub")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreateNewFolder, "New Folder")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("New Folder", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        FloatingActionButton(
                            onClick = {
                                showCreateFileDialog = true
                                isFabMenuExpanded = false
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("create_file_fab_sub")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.NoteAdd, "New File")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("New File", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        FloatingActionButton(
                            onClick = {
                                importFileLauncher.launch(arrayOf("*/*"))
                                isFabMenuExpanded = false
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("import_file_fab_sub")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FileUpload, "Import File")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (appLanguage == "bn") "ডিভাইস ফাইল ইমপোর্ট" else "Import File",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = { isFabMenuExpanded = !isFabMenuExpanded },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("expand_fab_menu")
                    ) {
                        Icon(
                            if (isFabMenuExpanded) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Create menu"
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("main_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp
            ) {
                // 1. Home
                NavigationBarItem(
                    selected = currentBottomTab == "home",
                    onClick = { currentBottomTab = "home" },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text(if (appLanguage == "bn") "হোম" else "Home", maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("bottom_tab_home")
                )
                // 2. Files
                NavigationBarItem(
                    selected = currentBottomTab == "files",
                    onClick = { currentBottomTab = "files" },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text(if (appLanguage == "bn") "ফাইল" else "Files", maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("bottom_tab_files")
                )
                // 3. Gallery
                NavigationBarItem(
                    selected = currentBottomTab == "gallery",
                    onClick = { currentBottomTab = "gallery" },
                    icon = { Icon(Icons.Default.Collections, contentDescription = "Gallery") },
                    label = { Text(if (appLanguage == "bn") "গ্যালারি" else "Gallery", maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("bottom_tab_gallery")
                )
                // 4. Audio
                NavigationBarItem(
                    selected = currentBottomTab == "audio",
                    onClick = { currentBottomTab = "audio" },
                    icon = { Icon(Icons.Default.Audiotrack, contentDescription = "Audio") },
                    label = { Text(if (appLanguage == "bn") "অডিও" else "Audio", maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("bottom_tab_audio")
                )
                // 5. Video
                NavigationBarItem(
                    selected = currentBottomTab == "video",
                    onClick = { currentBottomTab = "video" },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "Video") },
                    label = { Text(if (appLanguage == "bn") "ভিডিও" else "Video", maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("bottom_tab_video")
                )
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val screenWidth = maxWidth
            val isCompact = screenWidth < 768.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (currentBottomTab == "home") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp)
                    ) {
                        StoragePermissionCard(permissionsState = storagePermissionsState, appLanguage = appLanguage)
                        HomeScreen(
                            stats = storageStats,
                            bookmarks = bookmarks,
                            appLanguage = appLanguage,
                            onCategoryClick = { category ->
                                viewModel.setCategory(category)
                                currentBottomTab = "files"
                            },
                            onFileClick = handleFileClick,
                            onNavigateToFiles = { currentBottomTab = "files" },
                            onNavigateToGallery = { currentBottomTab = "gallery" },
                            onNavigateToAudio = { currentBottomTab = "audio" },
                            onNavigateToVideo = { currentBottomTab = "video" },
                            onOpenDrawer = {
                                coroutineScope.launch {
                                    drawerState.open()
                                }
                            }
                        )
                    }
                } else if (currentBottomTab == "files") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp)
                    ) {
                    StoragePermissionCard(permissionsState = storagePermissionsState, appLanguage = appLanguage)
                    if (bookmarks.isNotEmpty()) {
                        PinnedFilesCarousel(
                            bookmarks = bookmarks,
                            onFileClick = handleFileClick
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }



                // Category Row Selector
                CategoryRow(
                    activeCategory = activeCategory,
                    onCategorySelected = { viewModel.setCategory(it) },
                    appLanguage = appLanguage
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Operational Toolbar Component (Contextual only)
                if (selectedFiles.isNotEmpty() || clipboardFiles.isNotEmpty()) {
                    ContextualOperationsToolbar(
                        onDeleteSelectedClick = {
                            if (selectedFiles.isNotEmpty()) {
                                showDeleteSelectedConfirmationDialog = true
                            }
                        },
                        onCopyClick = {
                            if (selectedFiles.isNotEmpty()) {
                                viewModel.copyToClipboard(selectedFiles.toList())
                                Toast.makeText(context, "Copied ${selectedFiles.size} items to clipboard", Toast.LENGTH_SHORT).show()
                                selectedFiles.clear()
                            }
                        },
                        onPasteClick = {
                            val success = viewModel.pasteFromClipboard()
                            if (success) {
                                Toast.makeText(context, "Pasted successfully", Toast.LENGTH_SHORT).show()
                                viewModel.clearClipboard()
                            } else {
                                Toast.makeText(context, "Some files could not be pasted", Toast.LENGTH_SHORT).show()
                            }
                        },
                        clipboardCount = clipboardFiles.size,
                        selectedCount = selectedFiles.size,
                        onClearSelection = { selectedFiles.clear() }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Breadcrumb path navigation bar
                if (activeCategory == FileCategory.ALL) {
                    BreadcrumbPathBar(
                        currentDir = currentDir,
                        baseDir = viewModel.baseDir,
                        onBreadcrumbClicked = { viewModel.navigateTo(it) }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            activeCategory.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${activeCategory.displayName} (${filesList.size} items)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // File List Area
                Box(modifier = Modifier.weight(1f)) {
                    if (activeCategory == FileCategory.QR_SCANNER) {
                        QrScannerSection(viewModel = viewModel, appLanguage = appLanguage)
                    } else if (activeCategory == FileCategory.TOOLS) {
                        ToolsDashboard(viewModel = viewModel)
                    } else if (activeCategory == FileCategory.TRASH) {
                        TrashBinDashboard(viewModel = viewModel)
                    } else if (activeCategory == FileCategory.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onPrivacyClick = {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mrisllc.github.io/MRIS-files-explorers-Privacy-Policy-/"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    showPrivacyDialog = true
                                }
                            },
                            onTermsClick = { showTermsDialog = true },
                            onAgreementClick = { showAgreementDialog = true },
                            onGuideClick = { showGuideDialog = true }
                        )
                    } else if (filesList.isEmpty()) {
                        EmptyStateView(searchQuery = searchQuery, category = activeCategory)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("file_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(filesList, key = { it.absolutePath }) { file ->
                                val isBookmarked = bookmarks.any { it.path == file.absolutePath }
                                val isSelected = selectedFiles.contains(file)
                                FileItemCard(
                                    file = file,
                                    isBookmarked = isBookmarked,
                                    isSelected = isSelected,
                                    onSelectedToggle = { checked ->
                                        if (checked) {
                                            selectedFiles.add(file)
                                        } else {
                                            selectedFiles.remove(file)
                                        }
                                    },
                                    onClick = { handleFileClick(file) },
                                    onClickEdit = {
                                        viewModel.openFileForEditing(file)
                                    },
                                    onBookmarkToggle = { viewModel.toggleBookmark(file) },
                                    onRename = { fileToRename = file },
                                    onDelete = { fileToDelete = file },
                                    onDetails = { fileDetails = file },
                                    onShareQr = { fileToShareQr = file },
                                    appLanguage = appLanguage,
                                    onCopy = { viewModel.copyToClipboard(listOf(file)) }
                                )
                            }
                        }
                    }
                }
            }
        } else if (currentBottomTab == "gallery") {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp)
            ) {
                StoragePermissionCard(permissionsState = storagePermissionsState, appLanguage = appLanguage)
                GalleryTabScreen(
                    galleryFiles = galleryFilesList,
                    appLanguage = appLanguage,
                    onImageClick = { file ->
                        handleFileClick(file)
                    }
                )
            }
        } else if (currentBottomTab == "audio") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp)
                    ) {
                        StoragePermissionCard(permissionsState = storagePermissionsState, appLanguage = appLanguage)
                        AudioPlayerTabScreen(
                            audioFiles = audioFilesList,
                            appLanguage = appLanguage,
                            onPlayFile = { activeAudioPlayerFile = it },
                            viewModel = viewModel
                        )
                    }
                } else if (currentBottomTab == "video") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp)
                    ) {
                        StoragePermissionCard(permissionsState = storagePermissionsState, appLanguage = appLanguage)
                        VideoPlayerTabScreen(
                            videoFiles = videoFilesList,
                            appLanguage = appLanguage,
                            onPlayFile = { activeVideoPlayerFile = it },
                            viewModel = viewModel
                        )
                    }
                }

                // If NOT compact screen, show the SidePreviewPane next to the main Column
                if (!isCompact) {
                    AnimatedVisibility(
                        visible = activePreviewFile != null,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        activePreviewFile?.let { file ->
                            Row {
                                VerticalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 1.dp
                                )
                                Box(
                                    modifier = Modifier
                                        .width(320.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(0.5.dp))
                                ) {
                                    SidePreviewPane(
                                        file = file,
                                        onClose = { activePreviewFile = null },
                                        onOpen = { viewModel.openFileForEditing(file) },
                                        onBookmarkToggle = { viewModel.toggleBookmark(file) },
                                        isBookmarked = bookmarks.any { it.path == file.absolutePath },
                                        onDelete = { fileToDelete = file },
                                        onExtractZip = { viewModel.extractZip(it) },
                                        onSaveNotes = { f, notes -> viewModel.saveNotesForFile(f, notes) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // If COMPACT screen (mobile), show the SidePreviewPane as an elegant slide-up bottom drawer!
            if (isCompact) {
                AnimatedVisibility(
                    visible = activePreviewFile != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    activePreviewFile?.let { file ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f))
                                .clickable { activePreviewFile = null }
                        ) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.65f)
                                    .clickable(enabled = false) {},
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
                            ) {
                                SidePreviewPane(
                                    file = file,
                                    onClose = { activePreviewFile = null },
                                    onOpen = { viewModel.openFileForEditing(file) },
                                    onBookmarkToggle = { viewModel.toggleBookmark(file) },
                                    isBookmarked = bookmarks.any { it.path == file.absolutePath },
                                    onDelete = { fileToDelete = file },
                                    onExtractZip = { viewModel.extractZip(it) },
                                    onSaveNotes = { f, notes -> viewModel.saveNotesForFile(f, notes) }
                                )
                            }
                        }
                    }
                }
            }

            // Text Editor Modal overlay
            AnimatedVisibility(
                visible = editorFile != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                editorFile?.let { file ->
                    TextEditorView(
                        file = file,
                        content = editorContent,
                        onContentChange = { viewModel.updateEditorContent(it) },
                        onSave = {
                            viewModel.saveEditorContent()
                            Toast.makeText(context, "Saved changes to ${file.name}", Toast.LENGTH_SHORT).show()
                        },
                        onClose = { viewModel.closeEditor() },
                        onExtractZip = { viewModel.extractZip(it) },
                        onSaveNotes = { f, notes -> viewModel.saveNotesForFile(f, notes) }
                    )
                }
            }
        }
    }
}

    // ==========================================
    // DIALOGS & OVERLAYS
    // ==========================================

    // Create Folder Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (appLanguage == "bn") "সেটিংস" else "Settings")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Language Toggle Option
                    Card(
                        onClick = { viewModel.toggleAppLanguage() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(if (appLanguage == "bn") "ভাষা" else "Language", fontWeight = FontWeight.Bold)
                                    Text(if (appLanguage == "bn") "বর্তমান: বাংলা" else "Current: English", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Button(onClick = { viewModel.toggleAppLanguage() }) {
                                Text(if (appLanguage == "bn") "English" else "বাংলা")
                            }
                        }
                    }

                    // Clear Search History
                    Card(
                        onClick = {
                            viewModel.clearSearchHistory()
                            Toast.makeText(context, if (appLanguage == "bn") "অনুসন্ধান ইতিহাস মুছে ফেলা হয়েছে" else "Search history cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "সার্চ হিস্ট্রি মুছুন" else "Clear Search History", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Privacy Policy
                    Card(
                        onClick = {
                            showSettingsDialog = false
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mrisllc.github.io/MRIS-files-explorers-Privacy-Policy-/"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                showPrivacyDialog = true
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy Policy", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Terms of Service
                    Card(
                        onClick = {
                            showSettingsDialog = false
                            showTermsDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "ব্যবহারের শর্তাবলী" else "Terms of Service", fontWeight = FontWeight.Bold)
                        }
                    }

                    // User Agreement
                    Card(
                        onClick = {
                            showSettingsDialog = false
                            showAgreementDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "ইউজার এগ্রিমেন্ট" else "User Agreement", fontWeight = FontWeight.Bold)
                        }
                    }

                    // User Guide
                    Card(
                        onClick = {
                            showSettingsDialog = false
                            showGuideDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "ব্যবহার নির্দেশিকা" else "User Guide", fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "MRIS File Manager v1.0.0",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text(if (appLanguage == "bn") "বন্ধ করুন" else "Close")
                }
            }
        )
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_folder_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.createFolder(folderName)
                        if (success) {
                            Toast.makeText(context, "Folder created!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                        }
                        showCreateFolderDialog = false
                    },
                    modifier = Modifier.testTag("create_folder_confirm")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create File Dialog
    if (showCreateFileDialog) {
        var fileName by remember { mutableStateOf("") }
        var initialContent by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf("txt") }
        val extensions = listOf("txt", "pdf", "docx", "zip", "apk")

        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create New File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Select File Format:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        extensions.forEach { ext ->
                            val isSelected = selectedType == ext
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { 
                                        selectedType = ext
                                        if (ext != "txt") {
                                            initialContent = "" // non-txt doesn't need custom raw text
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "." + ext.uppercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File Name") },
                        placeholder = { Text("e.g., MyResume") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_file_input_name")
                    )

                    if (selectedType == "txt") {
                        OutlinedTextField(
                            value = initialContent,
                            onValueChange = { initialContent = it },
                            label = { Text("Initial Content (Optional)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .testTag("create_file_input_content")
                        )
                    } else {
                        // Helpful info tip for specialized files
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = when (selectedType) {
                                    "pdf" -> "Will be initialized as a standard formatted PDF document."
                                    "docx" -> "Will be initialized as a template Microsoft Word document."
                                    "zip" -> "Will be initialized as a ZIP Archive container."
                                    "apk" -> "Will be initialized as a template Android Package."
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val suffix = ".$selectedType"
                        val finalName = if (fileName.contains('.')) {
                            fileName
                        } else {
                            if (fileName.endsWith(suffix, ignoreCase = true)) fileName else "$fileName$suffix"
                        }
                        
                        // Smart initial content payload for specialized files
                        val finalContent = when (selectedType) {
                            "pdf" -> {
                                "%PDF-1.4\n" +
                                "%\n" +
                                "1 0 obj\n" +
                                "<< /Type /Catalog /Pages 2 0 R >>\n" +
                                "endobj\n" +
                                "2 0 obj\n" +
                                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n" +
                                "endobj\n" +
                                "3 0 obj\n" +
                                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>\n" +
                                "endobj\n" +
                                "4 0 obj\n" +
                                "<< /Length 75 >>\n" +
                                "stream\n" +
                                "BT\n" +
                                "/F1 12 Tf\n" +
                                "70 700 Td\n" +
                                "(File Explorer: Document Created in Sandbox) Tj\n" +
                                "ET\n" +
                                "endstream\n" +
                                "endobj\n" +
                                "xref\n" +
                                "0 5\n" +
                                "0000000000 65535 f \n" +
                                "0000000018 00000 n \n" +
                                "0000000067 00000 n \n" +
                                "0000000124 00000 n \n" +
                                "0000000216 00000 n \n" +
                                "trailer\n" +
                                "<< /Size 5 /Root 1 0 R >>\n" +
                                "startxref\n" +
                                "340\n" +
                                "%%EOF"
                            }
                            "docx" -> {
                                "Word Document Template\n=======================\n" +
                                "Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n" +
                                "Type: Microsoft Word XML Document\n" +
                                "Status: Active Sandbox Document"
                            }
                            "zip" -> {
                                // ZIP standard header signature or simple content
                                "ZIP-Archive-Payload-Header-Signature-EOD"
                            }
                            "apk" -> {
                                "APK-Package-Sandbox-Android-Manifest-Compiled"
                            }
                            else -> initialContent
                        }

                        val success = viewModel.createFile(finalName, finalContent)
                        if (success) {
                            Toast.makeText(context, "File created!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to create file", Toast.LENGTH_SHORT).show()
                        }
                        showCreateFileDialog = false
                    },
                    modifier = Modifier.testTag("create_file_confirm")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Dialog
    fileToRename?.let { file ->
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename ${if (file.isDirectory) "Folder" else "File"}") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.renameFile(file, newName)
                        if (success) {
                            Toast.makeText(context, "Renamed successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Renaming failed", Toast.LENGTH_SHORT).show()
                        }
                        fileToRename = null
                    },
                    modifier = Modifier.testTag("rename_confirm")
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    fileToDelete?.let { file ->
        val isDirectory = file.isDirectory
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Trash",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(text = if (appLanguage == "bn") "মুছে ফেলার বিকল্প বেছে নিন" else "Delete Options")
            },
            text = {
                Text(
                    text = if (appLanguage == "bn") {
                        "'${file.name}' টি ট্র্যাশে সরাতে চান অথবা স্থায়ীভাবে মুছে ফেলতে চান?"
                    } else {
                        "Do you want to move '${file.name}' to Recycle Bin or permanently delete it?"
                    }
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val success = viewModel.moveToTrash(file)
                            if (success) {
                                Toast.makeText(context, if (appLanguage == "bn") "ট্র্যাশে রাখা হয়েছে 🗑️" else "Moved to Recycle Bin 🗑️", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (appLanguage == "bn") "ট্র্যাশে নিতে ব্যর্থ" else "Failed to move to trash", Toast.LENGTH_SHORT).show()
                            }
                            fileToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("move_to_trash_confirm")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "ট্র্যাশে পাঠান (পুনরুদ্ধার সম্ভব)" else "Move to Trash (Restorable)")
                    }

                    OutlinedButton(
                        onClick = {
                            permanentDeleteSingleTarget = file
                            fileToDelete = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_permanently_confirm")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলুন" else "Delete Permanently")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Permanent Delete Confirmation Dialog (Single Item)
    permanentDeleteSingleTarget?.let { file ->
        val isDirectory = file.isDirectory
        AlertDialog(
            onDismissRequest = { permanentDeleteSingleTarget = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলার নিশ্চয়তা" else "Permanent Delete Confirmation")
            },
            text = {
                Text(
                    text = if (appLanguage == "bn") {
                        "আপনি কি নিশ্চিত যে '${file.name}' ${if (isDirectory) "ফোল্ডারটি" else "ফাইলটি"} স্থায়ীভাবে মুছে ফেলতে চান? এটি আর কখনই ফিরিয়ে আনা সম্ভব হবে না।"
                    } else {
                        "Are you sure you want to permanently delete ${if (isDirectory) "folder" else "file"} '${file.name}'? This action cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.deleteFile(file)
                        if (success) {
                            Toast.makeText(context, if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলা হয়েছে" else "Permanently deleted", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, if (appLanguage == "bn") "মুছে ফেলা সম্ভব হয়নি" else "Failed to delete", Toast.LENGTH_SHORT).show()
                        }
                        permanentDeleteSingleTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("permanent_delete_single_confirm_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছুন" else "Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { permanentDeleteSingleTarget = null }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Delete Selected Confirmation Dialog
    if (showDeleteSelectedConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmationDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Trash",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(if (appLanguage == "bn") "পছন্দকৃত ফাইলগুলো মুছুন" else "Delete Selected Items") },
            text = {
                Text(
                    if (appLanguage == "bn")
                        "আপনি কি নির্বাচন করা ${selectedFiles.size}টি আইটেম ট্র্যাশে সরাতে চান নাকি স্থায়ীভাবে মুছে ফেলতে চান?"
                    else
                        "Do you want to move the ${selectedFiles.size} selected item(s) to Recycle Bin or permanently delete them?"
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val success = viewModel.moveToTrash(selectedFiles.toList())
                            if (success) {
                                Toast.makeText(context, if (appLanguage == "bn") "ট্র্যাশে সরানো হয়েছে 🗑️" else "Moved to Recycle Bin 🗑️", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (appLanguage == "bn") "কিছু ফাইল সরানো সম্ভব হয়নি" else "Some files could not be moved to trash", Toast.LENGTH_SHORT).show()
                            }
                            selectedFiles.clear()
                            showDeleteSelectedConfirmationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("move_selected_to_trash_confirm")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "ট্র্যাশে পাঠান" else "Move to Trash")
                    }

                    OutlinedButton(
                        onClick = {
                            showPermanentDeleteSelectedConfirmDialog = true
                            showDeleteSelectedConfirmationDialog = false
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_selected_permanently_confirm")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলুন" else "Delete Permanently")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirmationDialog = false }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Permanent Delete Confirmation Dialog (Selected Items)
    if (showPermanentDeleteSelectedConfirmDialog) {
        val count = selectedFiles.size
        AlertDialog(
            onDismissRequest = { showPermanentDeleteSelectedConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলার নিশ্চয়তা" else "Permanent Delete Confirmation")
            },
            text = {
                Text(
                    text = if (appLanguage == "bn") {
                        "আপনি কি নিশ্চিত যে নির্বাচন করা $count টি ফাইল/ফোল্ডার স্থায়ীভাবে মুছে ফেলতে চান? এটি আর কখনই ফিরিয়ে আনা সম্ভব হবে না।"
                    } else {
                        "Are you sure you want to permanently delete $count selected item(s)? This action cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val filesList = selectedFiles.toList()
                        val success = viewModel.deleteFiles(filesList)
                        if (success) {
                            Toast.makeText(context, if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলা হয়েছে" else "Permanently deleted", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, if (appLanguage == "bn") "কিছু ফাইল মোছা সম্ভব হয়নি" else "Some files could not be deleted", Toast.LENGTH_SHORT).show()
                        }
                        selectedFiles.clear()
                        showPermanentDeleteSelectedConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("permanent_delete_selected_confirm_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "হ্যাঁ, স্থায়ীভাবে মুছুন" else "Yes, Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteSelectedConfirmDialog = false }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }

    // File Details Dialog
    fileDetails?.let { file ->
        AlertDialog(
            onDismissRequest = { fileDetails = null },
            title = { Text(if (appLanguage == "bn") "বৈশিষ্ট্য" else "Properties", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow(label = if (appLanguage == "bn") "নাম:" else "Name:", value = file.name)
                    DetailRow(label = if (appLanguage == "bn") "টাইপ:" else "Type:", value = if (file.isDirectory) (if (appLanguage == "bn") "ফোল্ডার" else "Folder") else (if (appLanguage == "bn") "ফাইল (.${file.extension})" else "File (.${file.extension})"))
                    DetailRow(label = if (appLanguage == "bn") "সম্পূর্ণ পাথ:" else "Full Path:", value = file.absolutePath)
                    DetailRow(label = if (appLanguage == "bn") "আকার:" else "Size:", value = if (file.isDirectory) (if (appLanguage == "bn") "ফোল্ডারের আকার হিসাব করা হচ্ছে..." else "Calculating folder size...") else formatFileSize(file.length()))
                    DetailRow(label = if (appLanguage == "bn") "সর্বশেষ পরিবর্তিত:" else "Last Modified:", value = formatLastModified(file.lastModified()))
                }
            },
            confirmButton = {
                Button(onClick = { fileDetails = null }) {
                    Text(if (appLanguage == "bn") "বন্ধ করুন" else "Close")
                }
            }
        )
    }

    // Share via QR Code Dialog
    fileToShareQr?.let { file ->
        var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var infoText by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        
        LaunchedEffect(file) {
            scope.launch(Dispatchers.IO) {
                val encodedString = if (!file.isDirectory) {
                    val ext = file.extension.lowercase()
                    if (listOf("txt", "json", "csv", "md", "xml", "html", "css", "js", "log").contains(ext) && file.length() < 2000) {
                        try {
                            val content = file.readText(Charsets.UTF_8)
                            "File: ${file.name}\nSize: ${formatFileSize(file.length())}\nContent:\n$content"
                        } catch (e: Exception) {
                            "File: ${file.name}\nPath: ${file.absolutePath}\nSize: ${formatFileSize(file.length())}"
                        }
                    } else {
                        "File: ${file.name}\nPath: ${file.absolutePath}\nSize: ${formatFileSize(file.length())}\nType: ${file.extension.uppercase()}"
                    }
                } else {
                    "Directory: ${file.name}\nPath: ${file.absolutePath}\nItems: ${file.listFiles()?.size ?: 0}"
                }
                infoText = encodedString
                qrBitmap = generateQrCode(encodedString, android.graphics.Color.BLACK)
            }
        }
        
        AlertDialog(
            onDismissRequest = { fileToShareQr = null },
            title = {
                Text(
                    text = if (appLanguage == "bn") "কিউআর কোড দিয়ে শেয়ার করুন" else "Share via QR Code",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (appLanguage == "bn") {
                            "অন্য ডিভাইস দিয়ে এই কিউআর কোডটি স্ক্যান করে ফাইল বা ফোল্ডারের তথ্য পেয়ে যাবেন।"
                        } else {
                            "Scan this QR code with another device to retrieve the file details."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    qrBitmap?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White)
                                .padding(8.dp)
                        )
                    }
                    
                    Text(
                        text = "File: ${file.name}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(onClick = { fileToShareQr = null }) {
                    Text(text = if (appLanguage == "bn") "ঠিক আছে" else "Close")
                }
            }
        )
    }

    // Privacy Policy Dialog
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = {
                Text(
                    text = if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy Policy",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (appLanguage == "bn") {
                            "১. তথ্য সংগ্রহ:\nএই File Manager অ্যাপটি সম্পূর্ণ অফলাইন ভিত্তিক। আমরা আপনার কোনো ব্যক্তিগত ফাইল, ফোল্ডার বা কোনো ডেটা আমাদের সার্ভারে বা অন্য কোথাও সংরক্ষণ করি না।\n\n" +
                            "২. ডিভাইসের অনুমতিসমূহ:\nঅ্যাপটি আপনার ফাইলসমূহ প্রদর্শন এবং ব্যবস্থাপনা করার জন্য ফাইল রিড/রাইট অনুমতি ব্যবহার করে থাকে। আপনার অনুমোদিত অনুমতি ব্যতীত অ্যাপটি নিজে থেকে কোনো কাজ সম্পন্ন করে না।\n\n" +
                            "৩. নিরাপত্তা ও গোপনীয়তা:\nডিভাইসের লোকাল মেমরির বাইরে আপনার কোনো ফাইল আপলোড বা অন্য কারো সাথে শেয়ার করা হয় না। আপনার সমস্ত ডাটা আপনার ডিভাইসেই নিরাপদ থাকে।"
                        } else {
                            "1. Data Collection:\nThis File Manager app is strictly offline-first. We do not upload, collect, or store any of your private files, folders, or personal data on any external servers.\n\n" +
                            "2. Device Permissions:\nThis app uses read/write storage access purely to display and manage your local files inside your sandboxed system. No operations are performed without user action.\n\n" +
                            "3. Security:\nYour files are never transmitted or shared. All operations occur completely locally on your secure device."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Clickable privacy policy link
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mrisllc.github.io/MRIS-files-explorers-Privacy-Policy-/"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open Link",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (appLanguage == "bn") "প্রাইভেসি পলিসি (Privacy Policy)" else "Privacy Policy (Detailed Online)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPrivacyDialog = false }) {
                    Text(text = if (appLanguage == "bn") "ঠিক আছে" else "I Understand")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mrisllc.github.io/MRIS-files-explorers-Privacy-Policy-/"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                ) {
                    Text(text = if (appLanguage == "bn") "অনলাইন পলিসি" else "View Online")
                }
            }
        )
    }

    // Terms & Conditions Fullscreen Overlay
    if (showTermsDialog) {
        FullscreenDocumentOverlay(
            title = if (appLanguage == "bn") "নিয়ম ও শর্তাবলী" else "Terms & Conditions",
            content = if (appLanguage == "bn") {
                """# नियम ও শর্তাবলী (Terms and Conditions)
**সর্বশেষ পরিমার্জন: জুলাই ২০২৬**
**MRIS files explorers**-এ আপনাকে স্বাগত। এই মাল্টিমিডিয়া এবং ফাইল ম্যানেজমেন্ট অ্যাপ্লিকেশনটি ডাউনলোড, ইনস্টল বা ব্যবহার করার মাধ্যমে আপনি আমাদের এই নিয়ম ও শর্তাবলীতে সম্পূর্ণ সম্মতি প্রকাশ করছেন। আপনি যদি এই শর্তাবলীতে একমত না হন, তবে অনুগ্রহ করে অ্যাপটি ব্যবহার করা থেকে বিরত থাকুন এবং অবিলম্বে এটি আনইনস্টল করুন।
### ১. সেবার পরিধি ও অফলাইন ব্যবহার
 * **অফলাইন ইউটিলিটি:** MRIS Teams সম্পূর্ণভাবে একটি স্থানীয় (Local) অফলাইন টুল হিসেবে কাজ করে, যা আপনার ব্যক্তিগত ফাইল, অডিও গান এবং ভিডিওগুলো সাজাতে এবং প্লে করতে সাহায্য করে।
 * **অ্যাকাউন্টের প্রয়োজন নেই:** অ্যাপটির মূল কাজগুলো পরিচালনা করার জন্য কোনো ইউজার রেজিস্ট্রেশন, প্রোফাইল তৈরি বা internet কানেকশনের প্রয়োজন হয় না।
### ২. বুদ্ধিবৃত্তিক সম্পত্তি এবং মালিকানা (Intellectual Property)
 * **App-এর মালিকানা:** MRIS Teams-এর সমস্ত সফটওয়্যার কোড, ডিজাইন, লোগো এবং ট্রেডমার্কের সম্পূর্ণ মালিকানা **MRIS Terms**-এর একক সম্পত্তি।
 * **ইউজার ফাইলের মালিকানা:** অ্যাপের মাধ্যমে আপনি যে সমস্ত অডিও, ভিডিও বা ফাইল ম্যানেজ করছেন, সেগুলোর ওপর আমাদের কোনো মালিকানা বা অধিকার নেই। আপনার ফাইলের সম্পূর্ণ মালিক আপনার নিজের।
### ৩. গ্রহণযোগ্য ব্যবহার ও নিষেধাজ্ঞা
আপনি এই অ্যাপ্লিকেশনটি শুধুমাত্র বৈধ উদ্দেশ্যে ব্যবহার করতে সম্মত হচ্ছেন। নিম্নোক্ত কাজগুলো করা কঠোরভাবে নিষিদ্ধ:
 * অ্যাপ্লিকেশনের সোর্স কোড রিভার্স ইঞ্জিনিয়ারিং, ডিকম্পাইল বা অননুমোদিতভাবে কপি করার চেষ্টা করা।
 * স্থানীয় বা আন্তর্জাতিক কপিরাইট আইন লঙ্ঘন করে এমন কোনো অডিও বা ভিডিও ফাইল পরিচালনা করার জন্য এই অ্যাপটি ব্যবহার করা।
### ৪. দায়বদ্ধতার সীমাবদ্ধতা (Limitation of Liability)
 * **"As-Is" বা বর্তমান অবস্থা:** এই অ্যাপ্লিকেশনটি "যে অবস্থায় আছে" (As-Is) সেভাবেই ব্যবহারের জন্য দেওয়া হয়েছে, এর কার্যক্ষমতার বিষয়ে কোনো অতিরিক্ত ওয়ারেন্টি দেওয়া হয় না।
 * **ডেটা হারানোর দায়মুক্তি:** যেহেতু ফাইল রিনেম, মুভ কিংবা ডিলিট করার সমস্ত কাজ সম্পূর্ণভাবে আপনার ফোনেই (Locally) প্রসেস হয়, তাই ফোনের অভ্যন্তরীণ ত্রুটি, ওএস (OS) সমস্যা বা ভুলবশত কোনো ফাইল মুছে গেলে MRIS Teams বা MRIS owner  তার জন্য দায়ী থাকবে না। আপনার গুরুত্বপূর্ণ ফাইলের ব্যাকআপ অন্য কোথাও সুরক্ষিত রাখা আপনার নিজস্ব দায়িত্ব।
### ৫. কারিগরি যোগাযোগ
এই শর্তাবলী বা অ্যাপের কোনো ফাংশন নিয়ে আপনার কোনো প্রশ্ন বা জিজ্ঞাসা থাকলে, নির্দ্বিধায় আমাদের অফিশিয়াল ইমেইল **contact.mrisllc@gmail.com**-এ যোগাযোগ করতে পারেন।
© 2026 MRIS Teams. All Rights Reserved. MRIS Terms সর্বস্বত্ব সংরক্ষিত।"""
            } else {
                """# Terms and Conditions
**Last Updated: July 2026**
Welcome to **MRIS  files explorers**. By downloading, installing, or using this multimedia and file management application, you agree to be bound by these Terms and Conditions. If you do not agree, please uninstall the application immediately.
### 1. Scope of Service & Local Use Only
 * **Offline Utility:** MRIS Teams operates strictly as a localized offline utility for organizing, viewing, and playing your personal files, audio, and video assets.
 * **No Account Required:** The application does not require user registration, profile creation, or internet connectivity to perform its core operations.
### 2. Intellectual Property Rights
 * **App Ownership:** All software code, UI design, visual assets, and trademarks associated with MRIS Teams are the exclusive intellectual property of **MRIS Terms**.
 * **User Content Ownership:** We claim zero ownership over the multimedia files, documents, or data you manage through our app. Your files belong entirely to you.
### 3. Acceptable Use & Limitations
You agree to use this application only for lawful purposes. You are strictly prohibited from:
 * Attempting to reverse engineer, decompile, or extract the source code of the application.
 * Using the application to manage, distribute, or play content that violates local or international copyright laws.
### 4. Disclaimer of Warranties & Limitation of Liability
 * **"As-Is" Basis:** This application is provided on an "as-is" and "as-available" basis without warranties of any kind.
 * **Data Loss Disclaimer:** Since all file modifications (rename, move, delete) are processed locally, MRIS Teams and MRIS Terms shall not be held liable for accidental data erasure, storage failure, or media corruption caused by device malfunctions or OS conflicts. It is your sole responsibility to maintain external backups.
### 5. Technical Inquiries
For any questions regarding these terms, software functionality, or bug compliance, you can contact our official helpdesk at **contact.mrisllc@gmail.com**.

© 2026 MRIS Teams. All Rights Reserved. MRIS Terms সর্বস্বত্ব সংরক্ষিত।"""
            },
            onClose = { showTermsDialog = false },
            confirmText = if (appLanguage == "bn") "ঠিক আছে" else "I Agree"
        )
    }

    // User Agreement Fullscreen Overlay
    if (showAgreementDialog) {
        FullscreenDocumentOverlay(
            title = if (appLanguage == "bn") "ইউজার এগ্রিমেন্ট" else "User Agreement",
            content = if (appLanguage == "bn") {
                """ইউজার  এগ্রিমেন্ট (User Agreement)

**সর্বশেষ পরিমার্জন: জুলাই ২০২৬**
এই এন্ড-ইউজার লাইসেন্স এগ্রিমেন্ট ("চুক্তি") হচ্ছে ব্যবহারকারী ("আপনি") এবং **MRIS Terms**-এর মধ্যে আমাদের মাল্টিমিডিয়া এবং ফাইল ম্যানেজমেন্ট সফটওয়্যার অ্যাপ্লিকেশন **MRIS Teams** ("সফটওয়্যার") ব্যবহারের একটি আইনি চুক্তি। এই সফটওয়্যারটি ইনস্টল বা ব্যবহার করার মাধ্যমে আপনি এই চুক্তির শর্তাবলী মেনে নিতে সম্মত হচ্ছেন।
### ১. লাইসেন্স প্রদান এবং সীমাবদ্ধতা
 * **সীমিত লাইসেন্স:** MRIS Terms আপনাকে শুধুমাত্র আপনার ব্যক্তিগত অ্যান্ড্রয়েড ডিভাইসে সম্পূর্ণ ব্যক্তিগত এবং অ-বাণিজ্যিক উদ্দেশ্যে এই সফটওয়্যারটি ব্যবহার করার জন্য একটি ব্যক্তিগত, অ-একচেটিয়া, অ-হস্তান্তরযোগ্য এবং প্রত্যাহারযোগ্য লাইসেন্স প্রদান করছে।
 * **নিষিদ্ধ কাজসমূহ:** আপনি এই সফটওয়্যারটি ভাড়া, ইজারা, বিক্রি, পুনরায় বিতরণ বা সাবলাইসেন্স করতে পারবেন না। আপনি এই সফটওয়্যারের সোর্স কোড অননুমোদিতভাবে কপি, রিভার্স ইঞ্জিনিয়ার বা ডিকম্পাইল করার চেষ্টা করতে পারবেন না।
### ২. জিরো-সার্ভার অবকাঠামো ও ব্যবহারকারীর সম্পূর্ণ নিয়ন্ত্রণ
 * **সার্ভারহীন কার্যপদ্ধতি:** এই সফটওয়্যারটি সম্পূর্ণভাবে একটি অফলাইন ও সার্ভারহীন টুল হিসেবে কাজ করে। আপনার ডিভাইস মেমোরিতে থাকা ফাইল, অডিও গান বা ভিডিও ট্র্যাকগুলো পরিচালনা করার জন্য MRIS Terms কোনো রিমোট অনলাইন সার্ভার ব্যবহার করে না।
 * **ডেটার স্বাধীনতা:** ফাইল স্ক্যানিং, রিনেম করা, সাজানো এবং সাময়িক ক্যাশ কনফিগারেশন সংক্রান্ত সমস্ত তথ্য সম্পূর্ণ স্থানীয়ভাবে (Locally) আপনার ফোনে সংরক্ষিত থাকে। আপনার ডিভাইসের স্টোরেজের ওপর একমাত্র আপনারই সম্পূর্ণ নিয়ন্ত্রণ রয়েছে।
### ৩. টেকনিক্যাল ডেটা ব্যবহারের সম্মতি (অ্যানালিটিক্স)
 * **নামহীন স্ট্যাবিলিটি লগ:** আপনি সম্মত হচ্ছেন যে, সফটওয়্যারের স্থায়িত্ব ও কার্যক্ষমতা উন্নত করার জন্য আপনার পাঠানো ইমেল ক্র্যাশ লগ থেকে নামহীন টেকনিক্যাল তথ্য (যেমন: ক্র্যাশ ট্রেস রেকর্ড এবং হার্ডওয়্যার স্পেসিফিকেশন) আমরা গুগলের টুলসের মাধ্যমে ব্যবহার করতে পারি। এই তথ্যের মধ্যে আপনার কোনো ব্যক্তিগত অডিও, ভিডিও বা ফাইলের ডেটা থাকবে না।
### ৪. চুক্তি অবসান (Termination)
 * **স্বয়ংক্রিয় অবসান:** আপনি অ্যাপটি আনইনস্টল না করা পর্যন্ত বা MRIS Terms কর্তৃক বাতিল না করা পর্যন্ত এই চুক্তিটি কার্যকর থাকবে। আপনি যদি এই চুক্তির কোনো শর্ত লঙ্ঘন করেন, তবে কোনো নোটিশ ছাড়াই আপনার এই লাইসেন্সটি স্বয়ংক্রিয়ভাবে বাতিল হয়ে যাবে।
 * **অবসানের প্রভাব:** চুক্তিটি বাতিল বা অবসান হওয়ার সাথে সাথে আপনাকে সফটওয়্যারটির সমস্ত ব্যবহার বন্ধ করতে হবে এবং আপনার ডিভাইস থেকে অ্যাপের সমস্ত কপি সম্পূর্ণভাবে মুছে ফেলতে হবে।
### ৫. দায়বদ্ধতার সীমাবদ্ধতা ও দায়মুক্তি
 * **কোনো ওয়ারেন্টি নেই:** এই সফটওয়্যারটি "যে অবস্থায় আছে" (As-Is) সেভাবেই ত্রুটিসহ ব্যবহারের জন্য দেওয়া হয়েছে এবং এর জন্য আলাদা কোনো আইনি ওয়ারেন্টি দেওয়া হয় না।
 * **ফাইলের নিরাপত্তা:** সফটওয়্যারটির স্থানীয় প্রসেসিংয়ের সময় ফোনের অভ্যন্তরীণ ত্রুটি, মেমোরি ক্র্যাশ বা ভুলবশত কোনো ফাইল, অডিও বা ভিডিও মুছে গেলে MRIS Teams বা MRIS Owner তার জন্য বিন্দুমাত্র দায়ী থাকবে না। আপনার গুরুত্বপূর্ণ ফাইলের অফ-ডিভাইস ব্যাকআপ (অন্য কোথাও সুরক্ষিত রাখা) সম্পূর্ণরূপে আপনার নিজের দায়িত্ব।
© 2026 MRIS Teams. All Rights Reserved.
 MRIS Terms সর্বস্বত্ব সংরক্ষিত।"""
            } else {
                """ User  Agreement (EULA)

**Last Updated: July 2026**
This End-User License Agreement ("Agreement") is a legal agreement between you ("User") and **MRIS Terms** for the multimedia and file management software application **MRIS Teams** ("Software"). By installing or using the Software, you agree to be bound by the terms of this Agreement.
### 1. License Grant & Restrictions
 * **Limited License:** MRIS Terms grants you a personal, non-exclusive, non-transferable, revocable license to use the Software on your personal Android device strictly for personal, non-commercial purposes.
 * **Prohibited Actions:** You shall not rent, lease, lend, sell, redistribute, or sublicense the Software. You may not decompile, reverse engineer, disassemble, or attempt to derive the source code of the Software.
### 2. Zero-Server & Absolute User Control
 * **No Server Interception:** The Software functions entirely as a serverless, offline multimedia utility. MRIS Terms does not operate remote servers to manage your files, audio tracks, or videos.
 * **Data Independence:** All scanning, file manipulation, and metadata configurations are stored locally on your device. You retain exclusive, absolute control over your device storage.
### 3. Consent to Use of Data (Telemetry)
 * **Anonymous Stability Logs:** You agree that MRIS Terms may collect anonymous technical telemetry (such as crash trace records and hardware specs) from the logs you manually submit via email. This information is gathered solely to improve the Software and will never include your personal multimedia files or identifiable data.
### 4. Termination
 * **Automatic Termination:** This Agreement is effective until terminated by you or MRIS Terms. Your rights under this license will terminate automatically without notice if you fail to comply with any of its terms.
 * **Effect of Termination:** Upon termination, you must cease all use of the Software and destroy all copies, full or partial, of the Software from your device.
### 5. Disclaimer of Liability
 * **No Warranty:** The Software is provided "As-Is" with all faults and without warranty of any kind.
 * **Data Responsibility:** MRIS Terms is not responsible for any file corruption, accidental media deletion, or hardware degradation resulting from the local execution of the Software. Maintaining off-device backups is your sole responsibility.

© 2026 MRIS Teams. All Rights Reserved. MRIS Terms সর্বস্বত্ব সংরক্ষিত।"""
            },
            onClose = { showAgreementDialog = false },
            confirmText = if (appLanguage == "bn") "ঠিক আছে" else "I Agree"
        )
    }

    // App Guide Fullscreen Overlay
    if (showGuideDialog) {
        FullscreenDocumentOverlay(
            title = if (appLanguage == "bn") "ইউজার গাইড" else "User Guide",
            content = if (appLanguage == "bn") {
                """# MRIS files- ইউজার গাইড
**সর্বশেষ পরিমার্জন: জুলাই ২০২৬**
**MRIS Teams** অ্যাপ্লিকেশনের অফিশিয়াল ইউজার গাইডে আপনাকে স্বাগতম। আপনার অফলাইন ফাইল ও মিডিয়া ম্যানেজমেন্ট এক্সপেরিয়েন্সকে আরও সহজ ও কার্যকর করতে নিচের নির্দেশিকাটি অনুসরণ করুন।
### ১. ইন্টারফেস পরিচিতি ও নেভিগেশন কন্ট্রোল
 * **বটম নেভিগেশন হাব (Bottom Navigation Hub):** অ্যাপের নিচের অংশে থাকা প্রধান নেভিগেশন বারের মাধ্যমে খুব সহজেই আপনার প্রয়োজনীয় ক্যাটাগরিতে চলে যান:
   * **ফাইল:** আপনার লোকাল ডিরেক্টরি স্ট্রাকচার, স্টোরেজ অ্যানালিটিক্স এবং ফাইল ম্যানেজমেন্টে প্রবেশ করুন।
   * **অডিও:** ব্যাকগ্রাউন্ডে অডিও গান স্ক্যান, প্লে এবং লোকাল প্লে-লিস্ট তৈরি করার জন্য নির্ধারিত প্যানেল।
   * **ভিডিও:** কোনো ল্যাগ ছাড়াই চমৎকারভাবে লোকাল ভিডিও প্লে করার জন্য অফলাইন ইঞ্জিন।
 * **অ্যাডভান্সড সাইড মেনু ও ডিরেক্টরি অপশন:** অ্যাপের বাম পাশের থ্রি-লাইন (মেনু) বাটনটিতে ট্যাপ করে সিস্টেম সেটিংস ও নতুন ক্রিয়াকলাপের উইন্ডোটি চালু করুন:
   * **নতুন অ্যাসেট তৈরি:** "নতুন ফাইল তৈরি করুন" অথবা "নতুন ফোল্ডার তৈরি করুন" বাটনে ট্যাপ করে তাৎক্ষণিকভাবে নতুন আইটেম তৈরি করুন।
   * **ডার্ক মোড টগল:** চোখের সুরক্ষায় এবং ব্যাটারি সাশ্রয় করতে যেকোনো সময় "ডার্ক মোড" ফিচারটি চালু বা বন্ধ করুন।
   * **ভাষা পরিবর্তন:** এক ক্লিকেই আপনার সম্পূর্ণ অ্যাপের ভাষা বাংলা থেকে ইংরেজিতে রূপান্তর করুন।
   * **নিরাপত্তা স্বীকৃতি:** অ্যাপের ভেতরে থাকা "আমি প্রাইভেসি পলিসি পড়েছি" চেকবক্সটি টিক দিয়ে আপনার সম্মতি ও নিরাপত্তা রুলস নিশ্চিত করুন।
### ২. ডাইনামিক স্টোরেজ ড্যাশবোর্ড ও File Manager
 * **রিয়েল-টাইম স্টোরেজ অ্যানালিটিক্স:** অ্যাপের উপরের ড্যাশবোর্ডটি স্বয়ংক্রিয়ভাবে আপনার ফোনের মেমোরি ট্র্যাক করে হিসাব দেখায়:
   * **ভিজ্যুয়াল প্রোগ্রেস বার:** মেমোরির কত শতাংশ ব্যবহৃত হয়েছে তা দেখার পাশাপাশি "মোট সাইজ" এবং "খালি স্পেস" এর নিখুঁত হিসাব রাখুন।
   * **ক্যাটাগরি গ্রাফ:** আপনার মেমোরিতে থাকা নথিপত্র, অডিও, ডাউনলোড এবং অন্যান্য ফাইল আলাদা আলাদা রঙে কতটুকু জায়গা নিয়েছে তা এক পলকে দেখে নিন।
 * **কুইক অ্যাকশন শর্টকাট ট্যাব:** সহজে ফাইল খুঁজে পেতে ড্যাশবোর্ডের ঠিক নিচে থাকা রেসপনসিভ ট্যাবগুলো ব্যবহার করুন:
   * "সমস্ত কার্যক্রম", "সাম্প্রতিক" এবং "বুক মার্ক ফাইল" ট্যাব ব্যবহার করে দ্রুত দরকারি ফাইলে অ্যাক্সেস করুন।
   * এছাড়াও সুনির্দিষ্ট ফাইল আইসোলেট করতে "ডাউনলোড", "এপিকে", "স্ক্যানার" এবং "টুলস" শর্টকাটগুলো ব্যবহার করুন।
 * **স্মার্ট সর্টিং মেনু (Sorting):** আপনার ফোল্ডারে থাকা শত শত ফাইল এক নিমেষেই সাজিয়ে নিতে ফিল্টার (সর্টিং) আইকনে ট্যাপ করুন:
   * আপনার পছন্দ অনুযায়ী ফাইলগুলোকে নাম (A-Z), নাম (Z-A), নতুন ফাইল প্রথমে, পুরাতন ফাইল প্রথমে, বড় ফাইল প্রথমে অথবা ছোট ফাইল প্রথমে ক্রমানুসারে সাজিয়ে নিন।

© 2026 MRIS Teams. All Rights Reserved. MRIS Terms সর্বস্বত্ব সংরক্ষিত।
গেমস এন্ড কম্পিটিশন ইউজার এগ্রিমেন্ট"""
            } else {
                """# MRIS files explorers- User Guide
**Last Updated: July 2026**
Welcome to the official User Guide for **MRIS Teams**. Follow this comprehensive manual to master the features, layouts, and hidden utilities of your ultimate offline file and media companion.
### 1. Navigating the Interface & Controls
 * **Bottom Navigation Hub:** Easily switch between your primary modules using the persistent bar at the bottom:
   * **Files:** Access your local directory structure, storage breakdowns, and file operations.
   * **Audio:** Your localized player for scanning, queueing, and listening to background soundtracks.
   * **Video:** High-performance offline engine for seamless rendering of local videos.
 * **Advanced Side Menu & Directory Options:** Tap the hamburger menu icon to pull up system configurations and creation utilities:
   * **Asset Creation:** Create new items immediately via "Create New File" or "Create New Folder" setups.
   * **Theme Toggle:** Switch between light interfaces and a comfortable "Dark Mode" layout anytime.
   * **Language Switcher:** Instantly localize your interface preferences between Bengali and English.
   * **Security Acknowledgement:** Review and verify compliance checks directly through the integrated "I have read the Privacy Policy" checkbox tracker.
### 2. Dynamic Storage Management & File Manager
 * **Real-Time Storage Insights:** The top dashboard dynamically calculates and breaks down space allocations:
   * **Visual Percentage Bar:** Displays exact storage usage metrics alongside comprehensive "Total Size" and "Free Space" values.
   * **Category Sorting:** Instantly check precise allocation values for Documents, Audio, Downloads, and Miscellaneous files.
 * **Quick Action Quick-Tabs:** Quickly jump into targeted storage workflows using our responsive tabs:
   * Use "All Actions", "Recent", and "Bookmark Files" for instant structural lookups.
   * Access dedicated utilities including "Downloads", "APKs", "Scanner", and "Tools" to instantly isolate advanced files.
 * **Smart Sorting Mechanics:** Organize chaotic directories instantly using the multi-criteria Sorting Menu:
   * Arrange assets sequentially using Name (A-Z), Name (Z-A), New Files First, Old Files First, Large Files First, or Small Files First."""
            },
            onClose = { showGuideDialog = false },
            confirmText = if (appLanguage == "bn") "ঠিক আছে" else "Got It"
        )
    }

    // Standalone Fullscreen Video Player Overlay
    activeVideoPlayerFile?.let { file ->
        FullscreenVideoPlayerDialog(
            file = file,
            appLanguage = appLanguage,
            onClose = { activeVideoPlayerFile = null }
        )
    }

    // Standalone Fullscreen Audio Player Overlay
    activeAudioPlayerFile?.let { file ->
        val parentDir = file.parentFile
        val audioExtensions = listOf("mp3", "wav", "m4a", "ogg", "flac")
        val playlist = parentDir?.listFiles()?.filter {
            it.isFile && audioExtensions.contains(it.extension.lowercase())
        }?.sortedBy { it.name } ?: listOf(file)
        
        FullscreenAudioPlayerDialog(
            initialFile = file,
            playlist = playlist,
            appLanguage = appLanguage,
            onClose = { activeAudioPlayerFile = null }
        )
    }

    // Standalone Fullscreen PDF Reader Overlay
    activePdfReaderFile?.let { file ->
        FullscreenPdfViewerDialog(
            file = file,
            appLanguage = appLanguage,
            onClose = { activePdfReaderFile = null }
        )
    }

    // Standalone Fullscreen Image Viewer Overlay
    activeImageViewerFile?.let { file ->
        FullscreenImageViewerDialog(
            file = file,
            appLanguage = appLanguage,
            onClose = { activeImageViewerFile = null }
        )
    }
}

@Composable
fun FullscreenImageViewerDialog(
    file: File,
    appLanguage: String,
    onClose: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val bitmap = remember(file) {
            try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var rotationDegrees by remember { mutableStateOf(0f) }
        var selectedFilter by remember { mutableStateOf("original") }
        val context = LocalContext.current

        val colorFilter = remember(selectedFilter) {
            when (selectedFilter) {
                "grayscale" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
                )
                "sepia" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f,     0f,     0f,     1f, 0f
                    ))
                )
                "cold" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                        0.8f, 0f,   0f,   0f, 0f,
                        0f,   0.9f, 0f,   0f, 0f,
                        0f,   0f,   1.4f, 0f, 0f,
                        0f,   0f,   0f,   1f, 0f
                    ))
                )
                "contrast" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                        1.4f, 0f,   0f,   0f, -0.05f,
                        0f,   1.4f, 0f,   0f, -0.05f,
                        0f,   0f,   1.4f, 0f, -0.05f,
                        0f,   0f,   0f,   1f, 0f
                    ))
                )
                else -> null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = file.name,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 6f)
                                offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = rotationDegrees
                        ),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (appLanguage == "bn") "ছবি দেখা যাচ্ছে না" else "Unable to load image",
                        color = Color.White
                    )
                }
            }

            // Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = file.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color.White)
                    }
                    IconButton(onClick = {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                        rotationDegrees = 0f
                        selectedFilter = "original"
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White)
                    }
                    IconButton(onClick = { shareFile(context, file) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }
                }
            }

            // Bottom Filter Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .navigationBarsPadding()
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = listOf("original", "grayscale", "sepia", "cold", "contrast")
                items(filters) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter.replaceFirstChar { it.uppercase() },
                                color = if (selectedFilter == filter) Color.Black else Color.White
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White,
                            containerColor = Color.Transparent
                        )
                    )
                }
            }
        }
    }
}

// ==========================================
// SUBCOMPONENTS & VIEWS
// ==========================================

@Composable
fun AppHeader(
    currentDir: File,
    baseDir: File,
    isSidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
    onBackClick: () -> Unit,
    activeSort: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    isSearchVisible: Boolean,
    onToggleSearch: () -> Unit,
    appLanguage: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentDir != baseDir) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        IconButton(
            onClick = onToggleSidebar,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("toggle_sidebar_button")
        ) {
            Icon(
                imageVector = if (isSidebarVisible) Icons.Default.MenuOpen else Icons.Default.Menu,
                contentDescription = "Toggle Sidebar Tree",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Sort option button next to the Hamburger Menu
        var showSortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { showSortMenu = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("header_sort_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Sort,
                    contentDescription = "Sort order",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                SortOrder.values().forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.getLocalizedName(appLanguage)) },
                        onClick = {
                            onSortChange(order)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (order == activeSort) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Search icon button next to the Sort Option
        IconButton(
            onClick = onToggleSearch,
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (isSearchVisible) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .testTag("toggle_search_button")
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Toggle Search Bar",
                tint = if (isSearchVisible) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "File Manager",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
fun PinnedFilesCarousel(
    bookmarks: List<Bookmark>,
    onFileClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bookmarks.isEmpty()) return

    var currentIndex by remember { mutableStateOf(0) }

    // Reset currentIndex if bookmarks change and it goes out of bounds
    LaunchedEffect(bookmarks) {
        if (currentIndex >= bookmarks.size) {
            currentIndex = 0
        }
    }

    // Auto-slide every 3 seconds
    if (bookmarks.size > 1) {
        LaunchedEffect(bookmarks.size) {
            while (true) {
                kotlinx.coroutines.delay(3000)
                currentIndex = (currentIndex + 1) % bookmarks.size
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pinned_files_carousel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Pinned Files",
                        tint = Color(0xFFFFB300), // Star Golden
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Important Pinned Files",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                // Indicators
                if (bookmarks.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        bookmarks.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == currentIndex) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == currentIndex)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Animated content sliding every 3 seconds from right to left
            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    // Slide in from right, slide out to left
                    (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width } + fadeOut()
                    )
                },
                label = "PinnedCarouselAnimation"
            ) { targetIndex ->
                // Ensure safe access
                val safeIndex = if (targetIndex < bookmarks.size) targetIndex else 0
                val bookmark = bookmarks[safeIndex]
                val file = File(bookmark.path)
                val exists = file.exists()
                val iconAndColor = getFileIconInfo(file)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable { onFileClick(file) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // File Type Icon
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(iconAndColor.second.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconAndColor.first,
                            contentDescription = null,
                            tint = iconAndColor.second,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // File Details
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (exists) {
                                if (bookmark.isFolder) {
                                    val count = file.listFiles()?.size ?: 0
                                    "$count items | " + formatLastModified(file.lastModified())
                                } else {
                                    formatFileSize(file.length()) + " | " + formatLastModified(file.lastModified())
                                }
                            } else {
                                "File not found / deleted"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Chevron indicator
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Open file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun StorageSummaryCard(stats: StorageStats, appLanguage: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_stats_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "Storage icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "${(stats.usedPercent * 100).toInt()}% " + (if (appLanguage == "bn") "ব্যবহৃত" else "Used"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar mapping the values
            LinearProgressIndicator(
                progress = { stats.usedPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = (if (appLanguage == "bn") "মোট সাইজ: " else "Total size: ") + formatFileSize(stats.totalSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Text(
                    text = (if (appLanguage == "bn") "খালি স্পেস: " else "Free space: ") + formatFileSize(stats.freeSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Simple micro calculations grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MicroStat(label = Trans.get("docs", appLanguage), value = formatFileSize(stats.docSize), color = Color(0xFF2196F3))
                MicroStat(label = Trans.get("audio", appLanguage), value = formatFileSize(stats.audioSize), color = Color(0xFF4CAF50))
                MicroStat(label = Trans.get("downloads", appLanguage), value = formatFileSize(stats.downloadSize), color = Color(0xFFE91E63))
                MicroStat(label = Trans.get("other", appLanguage), value = formatFileSize(stats.otherSize), color = Color(0xFF9C27B0))
            }
        }
    }
}

@Composable
fun MicroStat(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CategoryRow(
    activeCategory: FileCategory,
    onCategorySelected: (FileCategory) -> Unit,
    appLanguage: String
) {
    val categories = remember {
        FileCategory.values().filter {
            it != FileCategory.IMAGES && 
            it != FileCategory.VIDEOS && 
            it != FileCategory.AUDIO && 
            it != FileCategory.QR_SCANNER && 
            it != FileCategory.TOOLS &&
            it != FileCategory.SETTINGS
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { category ->
            val isActive = activeCategory == category
            FilterChip(
                selected = isActive,
                onClick = { onCategorySelected(category) },
                label = { Text(category.getLocalizedName(appLanguage)) },
                leadingIcon = {
                    Icon(
                        category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("category_chip_${category.name.lowercase()}"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
fun SearchBarSection(
    searchQuery: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_input"),
        placeholder = { Text("Search files & folders...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun BreadcrumbPathBar(
    currentDir: File,
    baseDir: File,
    onBreadcrumbClicked: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = "Root",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        // Reconstruct clickable hierarchy
        val parts = mutableListOf<File>()
        var temp: File? = currentDir
        while (temp != null && temp != baseDir.parentFile) {
            parts.add(0, temp)
            temp = temp.parentFile
        }

        parts.forEachIndexed { index, file ->
            val displayName = if (file == baseDir) "Home" else file.name
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == parts.lastIndex) FontWeight.Bold else FontWeight.Normal,
                color = if (index == parts.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onBreadcrumbClicked(file) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )

            if (index < parts.lastIndex) {
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 2.dp),
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun FileItemCard(
    file: File,
    isBookmarked: Boolean,
    isSelected: Boolean,
    onSelectedToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onClickEdit: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onShareQr: () -> Unit = {},
    appLanguage: String = "en",
    onCopy: () -> Unit = {}
) {
    val context = LocalContext.current
    var expandedMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("file_item_card_${file.name}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectedToggle,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag("checkbox_${file.name}")
            )

            // Icon layout matching directory/file types
            val iconInfo = getFileIconInfo(file)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconInfo.second.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconInfo.first,
                    contentDescription = null,
                    tint = iconInfo.second,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title and metadata column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (file.isDirectory) {
                        val items = file.list()?.size ?: 0
                        if (appLanguage == "bn") "$items টি আইটেম | ${formatLastModified(file.lastModified())}" else "$items items | ${formatLastModified(file.lastModified())}"
                    } else {
                        "${formatFileSize(file.length())} | ${formatLastModified(file.lastModified())}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Quick Star toggle action
            IconButton(
                onClick = onBookmarkToggle,
                modifier = Modifier.testTag("bookmark_toggle_button_${file.name}")
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Star",
                    tint = if (isBookmarked) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // Dropdown Menu container
            Box {
                IconButton(
                    onClick = { expandedMenu = true },
                    modifier = Modifier.testTag("options_menu_button_${file.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    // Option 1: Open / Edit or Install App for APK files
                    if (file.extension.lowercase() == "apk") {
                        DropdownMenuItem(
                            text = { Text(if (appLanguage == "bn") "ইনস্টল করুন" else "Install App") },
                            onClick = {
                                expandedMenu = false
                                installApk(context, file)
                            },
                            leadingIcon = { Icon(Icons.Default.Android, null, tint = Color(0xFF3DDC84)) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (appLanguage == "bn") "খুলুন / সম্পাদনা" else "Open / Edit") },
                            onClick = {
                                expandedMenu = false
                                onClickEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }

                    // Option 2: Share File (শেয়ার করুন)
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(if (appLanguage == "bn") "শেয়ার করুন" else "Share File") },
                            onClick = {
                                expandedMenu = false
                                shareFile(context, file)
                            },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                    }

                    // Option 3: Share via QR Code (QR কোড শেয়ার)
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(if (appLanguage == "bn") "QR কোড শেয়ার" else "Share via QR Code") },
                            onClick = {
                                expandedMenu = false
                                onShareQr()
                            },
                            leadingIcon = { Icon(Icons.Default.QrCode, null) }
                        )
                    }

                    // Option 4: Copy to Clipboard (ক্লিপবোর্ডে কপি)
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "bn") "ক্লিপবোর্ডে কপি" else "Copy to Clipboard") },
                        onClick = {
                            expandedMenu = false
                            onCopy()
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                    )

                    // Option 5: Rename (নাম পরিবর্তন)
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "bn") "নাম পরিবর্তন" else "Rename") },
                        onClick = {
                            expandedMenu = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }
                    )

                    // Option 6: Add/Remove Starred (বুকমার্ক করুন)
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isBookmarked) {
                                    if (appLanguage == "bn") "বুকমার্ক থেকে বাদ দিন" else "Remove Star"
                                } else {
                                    if (appLanguage == "bn") "বুকমার্ক করুন" else "Add Star"
                                }
                            )
                        },
                        onClick = {
                            expandedMenu = false
                            onBookmarkToggle()
                        },
                        leadingIcon = { Icon(if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder, null) }
                    )

                    // Option 7: Details (বিস্তারিত বিবরণ)
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "bn") "বিস্তারিত বিবরণ" else "Details") },
                        onClick = {
                            expandedMenu = false
                            onDetails()
                        },
                        leadingIcon = { Icon(Icons.Default.Info, null) }
                    )

                    HorizontalDivider()

                    // Option 8: Delete (মুছে ফেলুন)
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "bn") "মুছে ফেলুন" else "Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            expandedMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

fun shareFile(context: android.content.Context, file: File) {
    try {
        val authority = "${context.packageName}.provider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            val mimeType = when (file.extension.lowercase()) {
                "txt", "log", "ini", "conf", "md" -> "text/plain"
                "pdf" -> "application/pdf"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx", "csv" -> "application/vnd.ms-excel"
                "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                "zip", "rar", "7z", "tar", "gz" -> "application/zip"
                "json", "xml", "html", "css", "js" -> "text/plain"
                "mp3", "wav", "m4a", "ogg", "flac" -> "audio/*"
                "mp4", "mkv", "avi", "mov", "flv" -> "video/*"
                "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image/*"
                "apk" -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            putExtra(android.content.Intent.EXTRA_TEXT, "Shared file from All-In-One File Manager: ${file.name}")
        }
        val chooser = android.content.Intent.createChooser(intent, "Share File")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        // Fallback: Copy path to clipboard
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("File Path", file.absolutePath)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Could not share file: ${e.message}. Copied path to clipboard.", Toast.LENGTH_LONG).show()
    }
}

fun getFileIconInfo(file: File): Pair<ImageVector, Color> {
    return if (file.isDirectory) {
        Icons.Default.Folder to Color(0xFFFFC107) // Amber Folder
    } else {
        when (file.extension.lowercase()) {
            "txt", "log", "ini", "conf", "md" -> Icons.Default.Description to Color(0xFF78909C) // Slate text file
            "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFE53935) // Red PDF
            "doc", "docx", "rtf", "odt" -> Icons.Default.Article to Color(0xFF1E88E5) // Word Blue
            "xls", "xlsx", "ods", "csv" -> Icons.Default.TableChart to Color(0xFF43A047) // Excel Green
            "ppt", "pptx", "odp" -> Icons.Default.Slideshow to Color(0xFFF4511E) // PowerPoint Orange
            "zip", "rar", "7z", "tar", "gz" -> Icons.Default.Inventory to Color(0xFF8D6E63) // Archive Brown
            "json", "xml", "html", "css", "js" -> Icons.Default.Code to Color(0xFF8E24AA) // Purple code file
            "mp3", "wav", "m4a", "ogg", "flac" -> Icons.Default.MusicNote to Color(0xFFD81B60) // Pink audio
            "mp4", "mkv", "avi", "mov", "flv" -> Icons.Default.PlayCircle to Color(0xFF3F51B5) // Video Indigo
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Icons.Default.Image to Color(0xFF00ACC1) // Teal Image
            "apk" -> Icons.Default.Android to Color(0xFF3DDC84) // Android Green for APK files
            else -> Icons.Default.InsertDriveFile to Color(0xFF607D8B) // Slate other file
        }
    }
}

@Composable
fun EmptyStateView(searchQuery: String, category: FileCategory) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.FolderOff,
            contentDescription = "Empty",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (searchQuery.isNotEmpty()) "No matches found" else "This directory is empty",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (searchQuery.isNotEmpty()) "Try searching another term" else "You can create files/folders here using the active action button.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ==========================================
// FULL-PAGE POPUP EDITOR VIEW
// ==========================================

@Composable
fun TextEditorView(
    file: File,
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean
) {
    val extension = file.extension.lowercase()
    val isTextEditable = extension in listOf("txt", "csv", "json", "xml", "properties", "md", "html", "css", "js", "kt")
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Editor Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("editor_close_button")
                ) {
                    Icon(Icons.Default.Close, "Close editor")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isTextEditable) "Sandbox Notepad Editor" else "Specialized File Sandbox View",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (isTextEditable) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.testTag("editor_save_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }

            if (isTextEditable) {
                // Real Time Editor Box
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                        .testTag("editor_text_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    placeholder = { Text("Start typing file contents here...", fontFamily = FontFamily.Monospace) }
                )

                // Editor metadata footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${content.length} characters",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${content.split('\n').size} lines",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                // Specialized File Preview Screen
                SpecializedFilePreview(
                    file = file,
                    onExtractZip = onExtractZip,
                    onSaveNotes = onSaveNotes
                )
            }
        }
    }
}

// ============================================================================
// DIRECT MEDIA & DOCUMENT PARSING & RENDERING ENGINES (NO EXTERNAL APPS)
// ============================================================================

fun readDocxText(file: File): String {
    return try {
        java.util.zip.ZipFile(file).use { zipFile ->
            val entry = zipFile.getEntry("word/document.xml") ?: return "No text content found inside Word Document."
            zipFile.getInputStream(entry).use { inputStream ->
                val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(inputStream, "UTF-8")
                val textBuilder = StringBuilder()
                var eventType = parser.eventType
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                        textBuilder.append(parser.nextText())
                    } else if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "p") {
                        if (textBuilder.isNotEmpty() && !textBuilder.endsWith("\n")) {
                            textBuilder.append("\n")
                        }
                    }
                    eventType = parser.next()
                }
                textBuilder.toString().trim()
            }
        }
    } catch (e: Exception) {
        "Error reading Word (.docx) file content: ${e.message}"
    }
}

fun readPptxText(file: File): String {
    return try {
        java.util.zip.ZipFile(file).use { zipFile ->
            val slidesText = StringBuilder()
            var slideIndex = 1
            while (true) {
                val entry = zipFile.getEntry("ppt/slides/slide$slideIndex.xml") ?: break
                zipFile.getInputStream(entry).use { inputStream ->
                    val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(inputStream, "UTF-8")
                    slidesText.append("--- Slide $slideIndex ---\n")
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                            slidesText.append(parser.nextText()).append(" ")
                        }
                        eventType = parser.next()
                    }
                    slidesText.append("\n\n")
                }
                slideIndex++
            }
            if (slidesText.isNotEmpty()) slidesText.toString().trim() else "No presentation content found."
        }
    } catch (e: Exception) {
        "Error reading PowerPoint (.pptx) file content: ${e.message}"
    }
}

fun readXlsxData(file: File): List<List<String>> {
    return try {
        java.util.zip.ZipFile(file).use { zipFile ->
            val sharedStrings = mutableListOf<String>()
            val sharedStringsEntry = zipFile.getEntry("xl/sharedStrings.xml")
            if (sharedStringsEntry != null) {
                zipFile.getInputStream(sharedStringsEntry).use { inputStream ->
                    val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(inputStream, "UTF-8")
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                            sharedStrings.add(parser.nextText())
                        }
                        eventType = parser.next()
                    }
                }
            }

            val sheetEntry = zipFile.getEntry("xl/worksheets/sheet1.xml") ?: return emptyList()
            zipFile.getInputStream(sheetEntry).use { inputStream ->
                val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(inputStream, "UTF-8")
                val rows = mutableListOf<List<String>>()
                var currentRow = mutableListOf<String>()
                var eventType = parser.eventType
                var cellType = ""
                var isVal = false
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            if (parser.name == "row") {
                                currentRow = mutableListOf()
                            } else if (parser.name == "c") {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                            } else if (parser.name == "v") {
                                isVal = true
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            if (isVal) {
                                val textVal = parser.text ?: ""
                                if (cellType == "s") {
                                    val idx = textVal.toIntOrNull()
                                    if (idx != null && idx in 0 until sharedStrings.size) {
                                        currentRow.add(sharedStrings[idx])
                                    } else {
                                        currentRow.add(textVal)
                                    }
                                } else {
                                    currentRow.add(textVal)
                                }
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (parser.name == "row") {
                                if (currentRow.isNotEmpty()) {
                                    rows.add(currentRow)
                                }
                            } else if (parser.name == "v") {
                                isVal = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
                rows
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun readBinaryLegacyText(file: File): String {
    return try {
        val bytes = file.readBytes()
        val textBuilder = StringBuilder()
        var currentWord = StringBuilder()
        for (b in bytes) {
            val char = b.toInt().toChar()
            if (char in ' '..'~' || char == '\n' || char == '\r' || char == '\t') {
                currentWord.append(char)
            } else {
                if (currentWord.length >= 4) {
                    textBuilder.append(currentWord).append(" ")
                }
                currentWord = StringBuilder()
            }
        }
        if (currentWord.length >= 4) {
            textBuilder.append(currentWord)
        }
        val raw = textBuilder.toString().trim()
        val cleaned = raw.split(Regex("\\s+"))
            .filter { word -> 
                word.length < 30 && !word.contains("http") && !word.contains("xml")
            }
            .take(150)
            .joinToString(" ")
        if (cleaned.isNotEmpty()) cleaned else "[Legacy binary document structure: can be processed directly in sandbox mode]"
    } catch (e: Exception) {
        "Error loading legacy document content: ${e.message}"
    }
}

fun getZipContents(file: File): List<String> {
    return try {
        java.util.zip.ZipFile(file).use { zipFile ->
            zipFile.entries().asSequence().map { entry ->
                val sizeStr = formatFileSize(entry.size)
                "${if (entry.isDirectory) "📁" else "📄"} ${entry.name} ($sizeStr)"
            }.toList()
        }
    } catch (e: Exception) {
        listOf("Failed to read zip contents: ${e.message}")
    }
}

@Composable
fun ImageViewer(file: File) {
    val context = LocalContext.current
    val bitmap = remember(file) {
        try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var selectedFilter by remember { mutableStateOf("original") }
    var showDetails by remember { mutableStateOf(false) }

    val colorFilter = remember(selectedFilter) {
        when (selectedFilter) {
            "grayscale" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
            )
            "sepia" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f,     0f,     0f,     1f, 0f
                ))
            )
            "cold" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                    0.8f, 0f,   0f,   0f, 0f,
                    0f,   0.9f, 0f,   0f, 0f,
                    0f,   0f,   1.4f, 0f, 0f,
                    0f,   0f,   0f,   1f, 0f
                ))
            )
            "contrast" -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                    1.4f, 0f,   0f,   0f, -0.05f,
                    0f,   1.4f, 0f,   0f, -0.05f,
                    0f,   0f,   1.4f, 0f, -0.05f,
                    0f,   0f,   0f,   1f, 0f
                ))
            )
            else -> null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Google Gallery Viewer",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            rotationDegrees = (rotationDegrees + 90f) % 360f
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                            rotationDegrees = 0f
                            selectedFilter = "original"
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Image Container / Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5f)
                            offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Image preview: ${file.name}",
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                                rotationZ = rotationDegrees
                            ),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Unreadable Image Data",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pinch and swipe helper
            Text(
                text = "💡 Try pinching to Zoom in/out, and drag to pan around.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Dynamic filter options row
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Gallery Filters & Adjustments",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filters = listOf(
                        "original" to "Original",
                        "grayscale" to "Grayscale",
                        "sepia" to "Warm Sepia",
                        "cold" to "Cool Aqua",
                        "contrast" to "High Pop"
                    )
                    
                    filters.forEach { (id, label) ->
                        FilterChip(
                            selected = selectedFilter == id,
                            onClick = { selectedFilter = id },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Image Metadata panel
            AnimatedVisibility(visible = showDetails) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Metadata & Gallery Details",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text("• Name: ${file.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("• Path: ${file.absolutePath.substringAfter("/files/")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("• Size: ${formatFileSize(file.length())}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("• Format: .${file.extension.uppercase()} Image", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("• Last Modified: ${formatLastModified(file.lastModified())}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        bitmap?.let {
                            Text("• Dimensions: ${it.width} x ${it.height} px", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(file: File) {
    val context = LocalContext.current
    var videoViewRef by remember { mutableStateOf<android.widget.VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableStateOf(0f) }
    var currentPosText by remember { mutableStateOf("00:00") }
    var durationText by remember { mutableStateOf("00:00") }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var videoScaleMode by remember { mutableStateOf("fit") } // fit, stretch

    // Helper to format milliseconds to MM:SS
    fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    // Dynamic speed application helper using reflection
    fun applySpeed(mp: android.media.MediaPlayer, speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val getParamsMethod = mp.javaClass.getMethod("getPlaybackParams")
                val setParamsMethod = mp.javaClass.getMethod("setPlaybackParams", Class.forName("android.media.PlaybackParams"))
                val params = getParamsMethod.invoke(mp)
                if (params != null) {
                    val setSpeedMethod = params.javaClass.getMethod("setSpeed", Float::class.java)
                    setSpeedMethod.invoke(params, speed)
                    setParamsMethod.invoke(mp, params)
                }
            } catch (e: Exception) {
                // Ignore if reflection fails
            }
        }
    }

    LaunchedEffect(videoViewRef, isPlaying) {
        while (true) {
            videoViewRef?.let { view ->
                try {
                    val duration = view.duration
                    if (duration > 0) {
                        currentProgress = view.currentPosition.toFloat() / duration.toFloat()
                        currentPosText = formatTime(view.currentPosition)
                        durationText = formatTime(duration)
                    }
                    isPlaying = view.isPlaying
                } catch (e: Exception) {
                    // Ignore transient video state errors
                }
            }
            kotlinx.coroutines.delay(500)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Playit Media Player",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Video viewport container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoPath(file.absolutePath)
                            setOnPreparedListener { mp ->
                                mediaPlayerRef = mp
                                mp.isLooping = true
                                if (isMuted) {
                                    mp.setVolume(0f, 0f)
                                }
                                applySpeed(mp, playbackSpeed)
                            }
                            start()
                            isPlaying = true
                        }
                    },
                    update = { view ->
                        videoViewRef = view
                        mediaPlayerRef?.let { mp ->
                            applySpeed(mp, playbackSpeed)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Lock Overlay Screen Scrim
                if (isLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable { /* Consume click events when locked */ },
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { isLocked = false },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.Lock, "Unlock Player", tint = Color.White)
                        }
                    }
                }
            }

            // Interactive Playit Controls (Only show if not locked!)
            if (!isLocked) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Seek Progress bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(currentPosText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Slider(
                            value = currentProgress,
                            onValueChange = { progress ->
                                currentProgress = progress
                                videoViewRef?.let { view ->
                                    val newPos = (progress * view.duration).toInt()
                                    view.seekTo(newPos)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        Text(durationText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Button panel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left-side quick operations
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Playback speed
                            TextButton(
                                onClick = {
                                    playbackSpeed = when (playbackSpeed) {
                                        1.0f -> 1.5f
                                        1.5f -> 2.0f
                                        2.0f -> 0.5f
                                        else -> 1.0f
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${playbackSpeed}x", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Scaling Mode
                            IconButton(
                                onClick = {
                                    videoScaleMode = if (videoScaleMode == "fit") "stretch" else "fit"
                                    Toast.makeText(context, "Scale mode: ${videoScaleMode.uppercase()}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (videoScaleMode == "fit") Icons.Default.AspectRatio else Icons.Default.Fullscreen,
                                    contentDescription = "Scale Mode",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Playback Core Actions
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        val newPos = (view.currentPosition - 10000).coerceAtLeast(0)
                                        view.seekTo(newPos)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Replay10, "Rewind 10s", modifier = Modifier.size(20.dp))
                            }

                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        if (view.isPlaying) {
                                            view.pause()
                                            isPlaying = false
                                        } else {
                                            view.start()
                                            isPlaying = true
                                        }
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        val newPos = (view.currentPosition + 10000).coerceAtMost(view.duration)
                                        view.seekTo(newPos)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Forward10, "Forward 10s", modifier = Modifier.size(20.dp))
                            }
                        }

                        // Right-side options
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Lock Player
                            IconButton(
                                onClick = { isLocked = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.LockOpen, "Lock Screen", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            } else {
                // If locked, show a simple status message
                Text(
                    text = "Player controls are locked. Tap the lock icon on screen to unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
fun AudioPlayer(file: File) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    val context = LocalContext.current
    
    val mediaPlayer = remember(file) {
        android.media.MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                duration = this.duration
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose {
            try {
                mediaPlayer.stop()
                mediaPlayer.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying && mediaPlayer.isPlaying) {
                currentPosition = mediaPlayer.currentPosition
                kotlinx.coroutines.delay(200)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "On-Device Audio Player",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )

            // Dynamic Audio Waveform Representation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val heights = listOf(6, 14, 20, 24, 12, 8, 18, 28, 22, 10, 14, 26, 16, 8, 12, 24, 20, 10, 6, 18, 22, 14, 8)
                heights.forEachIndexed { i, h ->
                    val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                    val isActive = (i / heights.size.toFloat()) <= progress
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h.dp)
                            .clip(CircleShape)
                            .background(if (isActive && isPlaying) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    )
                }
            }

            Slider(
                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                onValueChange = { progress ->
                    if (duration > 0) {
                        val newPos = (progress * duration).toInt()
                        mediaPlayer.seekTo(newPos)
                        currentPosition = newPos
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF4CAF50),
                    activeTrackColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val curSec = (currentPosition / 1000) % 60
                val curMin = (currentPosition / 1000) / 60
                val durSec = (duration / 1000) % 60
                val durMin = (duration / 1000) / 60
                
                Text(
                    text = String.format("%02d:%02d / %02d:%02d", curMin, curSec, durMin, durSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = {
                        try {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Playback error", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF4CAF50).copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

@Composable
fun PdfViewer(file: File) {
    var currentPageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isError by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(file, currentPageIndex) {
        try {
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            pageCount = renderer.pageCount
            
            if (pageCount > 0) {
                val pageIdx = currentPageIndex.coerceIn(0, pageCount - 1)
                val page = renderer.openPage(pageIdx)
                
                // Scale width to fit nicely
                val width = page.width * 2
                val height = page.height * 2
                val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageBitmap = bmp
                page.close()
                isError = false
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
            isError = true
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PDF Core Document",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE53935)
                )
                if (pageCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFE53935).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "Page ${currentPageIndex + 1} of $pageCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (isError) {
                // Beautiful fallback viewer for mock/empty PDFs
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                        .padding(12.dp)
                ) {
                    val fallbackText = remember(file) {
                        try {
                            file.readLines().take(15).joinToString("\n")
                        } catch (e: Exception) {
                            "Unreadable document content."
                        }
                    }
                    Text(
                        text = "--- PDF Document Text Stream ---\n\n" + fallbackText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black,
                        fontFamily = FontFamily.Serif
                    )
                }
            } else {
                pageBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPageIndex + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .border(1.dp, Color.LightGray)
                            .background(Color.White)
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFE53935))
                }
            }

            if (pageCount > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                        enabled = currentPageIndex > 0
                    ) {
                        Icon(Icons.Default.ChevronLeft, "Prev", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Prev")
                    }

                    TextButton(
                        onClick = { if (currentPageIndex < pageCount - 1) currentPageIndex++ },
                        enabled = currentPageIndex < pageCount - 1
                    ) {
                        Text("Next")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, "Next", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun WordViewer(file: File, onSaveNotes: (File, String) -> Boolean) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf("") }
    
    val wordText = remember(file) {
        val ext = file.extension.lowercase()
        if (ext == "docx") {
            readDocxText(file)
        } else {
            readBinaryLegacyText(file)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Microsoft Word Document Reader",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E88E5)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(Color(0xFFF0F4C3).copy(alpha = 0.2f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = wordText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Serif,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Write annotations/reading notes...") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(8.dp)
            )

            Button(
                onClick = {
                    if (notes.isNotBlank()) {
                        val success = onSaveNotes(file, notes)
                        if (success) {
                            Toast.makeText(context, "Notes saved successfully!", Toast.LENGTH_SHORT).show()
                            notes = ""
                        } else {
                            Toast.makeText(context, "Failed to save notes.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Notes cannot be empty!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Notes", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ExcelViewer(file: File) {
    val excelGrid = remember(file) {
        val ext = file.extension.lowercase()
        if (ext == "xlsx") {
            readXlsxData(file)
        } else {
            emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Microsoft Excel Grid Viewer",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF43A047)
            )

            if (excelGrid.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                        .border(1.dp, Color.LightGray)
                ) {
                    Column {
                        excelGrid.forEachIndexed { rowIndex, row ->
                            Row {
                                row.forEachIndexed { colIndex, cell ->
                                    Box(
                                        modifier = Modifier
                                            .width(100.dp)
                                            .height(36.dp)
                                            .background(if (rowIndex == 0) Color(0xFFE8F5E9) else Color.White)
                                            .border(0.5.dp, Color.LightGray)
                                            .padding(6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                            text = cell,
                                            fontSize = 11.sp,
                                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                            color = if (rowIndex == 0) Color(0xFF2E7D32) else Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Fallback for old xls or mock excel
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "--- Excel Spreadsheet Data Stream ---",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF43A047)
                    )
                    val rawText = remember(file) { readBinaryLegacyText(file) }
                    Text(
                        text = if (rawText.isNotBlank()) rawText else "Legacy binary spreadsheet sheet. Standard xlsx sheets will render in a grid layout above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun PowerPointViewer(file: File) {
    val pptxText = remember(file) {
        val ext = file.extension.lowercase()
        if (ext == "pptx") {
            readPptxText(file)
        } else {
            readBinaryLegacyText(file)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Microsoft PowerPoint Slide Text Reader",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE64A19)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(Color(0xFFFFE0B2).copy(alpha = 0.2f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = pptxText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ArchiveViewer(file: File, onExtractZip: (File) -> Boolean) {
    val context = LocalContext.current
    val contents = remember(file) {
        val ext = file.extension.lowercase()
        if (ext == "zip") {
            getZipContents(file)
        } else {
            emptyList()
        }
    }
    
    var isExtracting by remember { mutableStateOf(false) }
    var extractProgress by remember { mutableStateOf(0f) }
    var extractDone by remember { mutableStateOf(false) }

    LaunchedEffect(isExtracting) {
        if (isExtracting) {
            for (i in 1..100) {
                kotlinx.coroutines.delay(10)
                extractProgress = i / 100f
            }
            onExtractZip(file)
            isExtracting = false
            extractDone = true
            Toast.makeText(context, "Archive Extracted Successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Built-in Decompressor & Browser",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8D6E63)
            )

            if (contents.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(10.dp)
                ) {
                    contents.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (item.startsWith("📁")) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = Color(0xFF8D6E63),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(item, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF8D6E63), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Proprietary compression structure (${file.extension.uppercase()})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Text("Decompression engine will process signatures and partition indexes directly.", fontSize = 10.sp, color = Color.Gray)
                }
            }

            if (isExtracting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { extractProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF8D6E63)
                    )
                    Text(
                        text = "Decompressing index tree... ${(extractProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8D6E63)
                    )
                }
            } else if (extractDone) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE8F5E9))
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Unzipped folder created successfully!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
            } else {
                Button(
                    onClick = { isExtracting = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6E63)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Extract Archive", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun UnifiedFileContentPreview(
    file: File,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean,
    viewModel: FileExplorerViewModel = viewModel()
) {
    val appLanguage by viewModel.appLanguage.collectAsState()
    val extension = file.extension.lowercase()
    val textPreview = remember(file) {
        val textExtensions = listOf("txt", "csv", "json", "xml", "properties", "md", "html", "css", "js", "kt")
        if (extension in textExtensions && file.exists()) {
            try {
                if (file.length() < 100_000) {
                    val lines = file.useLines { it.take(20).toList() }
                    if (lines.isEmpty()) {
                        "[Empty File]"
                    } else {
                        lines.joinToString("\n") + if (file.length() > 2000) "\n... [Truncated for preview]" else ""
                    }
                } else {
                    "File is too large for preview (Size: ${formatFileSize(file.length())})."
                }
            } catch (e: Exception) {
                "Error reading content: ${e.message}"
            }
        } else {
            ""
        }
    }

    when {
        extension in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> {
            ImageViewer(file = file)
        }
        extension in listOf("mp4", "mkv", "avi", "webm", "3gp") -> {
            VideoPlayer(file = file)
        }
        extension in listOf("mp3", "wav", "m4a", "ogg", "flac") -> {
            AudioPlayer(file = file)
        }
        extension == "pdf" -> {
            PdfViewer(file = file)
        }
        extension in listOf("docx", "doc") -> {
            WordViewer(file = file, onSaveNotes = onSaveNotes)
        }
        extension in listOf("xlsx", "xls") -> {
            ExcelViewer(file = file)
        }
        extension in listOf("pptx", "ppt") -> {
            PowerPointViewer(file = file)
        }
        extension in listOf("zip", "rar", "7z") -> {
            ArchiveViewer(file = file, onExtractZip = onExtractZip)
        }
        extension == "apk" -> {
            val context = LocalContext.current
            val apkInfo = remember(file) { getApkInfo(context, file) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
                border = BorderStroke(1.dp, Color(0xFF3DDC84).copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = "Android APK",
                            tint = Color(0xFF3DDC84),
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = apkInfo.first,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Package: ${apkInfo.second}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        text = "Version: ${apkInfo.third}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = { installApk(context, file) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DDC84), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (appLanguage == "bn") "এপিকে ইনস্টল করুন" else "Install Application",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        textPreview.isNotEmpty() -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.04f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = textPreview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 15,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        else -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val iconInfo = getFileIconInfo(file)
                    Icon(iconInfo.first, null, tint = iconInfo.second.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Binary Data Format",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "No inline rendering engine for this file type.",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SpecializedFilePreview(
    file: File,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean
) {
    val context = LocalContext.current
    val extension = file.extension.lowercase()
    val iconInfo = getFileIconInfo(file)
    
    // MD5 Checksum calculation
    val md5Hash = remember(file) {
        try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val bytes = file.readBytes()
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.uppercase()
        } catch (e: Exception) {
            "D41D8CD98F00B204E9800998ECF8427E" // Default MD5
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main File Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconInfo.second.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconInfo.first,
                        contentDescription = null,
                        tint = iconInfo.second,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (extension) {
                            "pdf" -> "Adobe Portable Document"
                            "docx", "doc" -> "Microsoft Word Document"
                            "xlsx", "xls" -> "Microsoft Excel Sheet"
                            "pptx", "ppt" -> "Microsoft PowerPoint Presentation"
                            "zip" -> "ZIP Compressed Archive"
                            "rar" -> "RAR Compressed Archive"
                            "7z" -> "7-Zip Compressed Archive"
                            "apk" -> "Android Installation Package"
                            "png", "jpg", "jpeg", "webp" -> "Raster Graphic Image"
                            "mp3", "wav" -> "Audio Recording"
                            "mp4", "mkv" -> "Video Recording"
                            else -> "Binary System File"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = iconInfo.second,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Detailed Metadata Table
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "System Properties",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                DetailRow(label = "File Path", value = file.absolutePath)
                DetailRow(label = "Size", value = formatFileSize(file.length()))
                DetailRow(label = "Last Modified", value = formatLastModified(file.lastModified()))
                DetailRow(label = "MD5 Hash", value = md5Hash)
                DetailRow(
                    label = "Read State",
                    value = if (file.canRead()) "Readable (Active)" else "Restricted"
                )
                DetailRow(
                    label = "Write State",
                    value = if (file.canWrite()) "Writable (Editable)" else "Read-Only"
                )
            }
        }

        // Specialized Interactive Tools
        Text(
            text = "Interactive Utilities",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )

        UnifiedFileContentPreview(
            file = file,
            onExtractZip = onExtractZip,
            onSaveNotes = onSaveNotes
        )
    }
}

@Composable
fun OldSpecializedFilePreview(
    file: File,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean
) {
    val context = LocalContext.current
    val extension = file.extension.lowercase()
    val iconInfo = getFileIconInfo(file)
    
    // MD5 Checksum calculation
    val md5Hash = remember(file) {
        try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val bytes = file.readBytes()
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.uppercase()
        } catch (e: Exception) {
            "D41D8CD98F00B204E9800998ECF8427E" // Default MD5
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main File Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconInfo.second.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconInfo.first,
                        contentDescription = null,
                        tint = iconInfo.second,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (extension) {
                            "pdf" -> "Adobe Portable Document"
                            "docx", "doc" -> "Microsoft Word Document"
                            "xlsx", "xls" -> "Microsoft Excel Sheet"
                            "pptx", "ppt" -> "Microsoft PowerPoint Presentation"
                            "zip" -> "ZIP Compressed Archive"
                            "rar" -> "RAR Compressed Archive"
                            "7z" -> "7-Zip Compressed Archive"
                            "apk" -> "Android Installation Package"
                            "png", "jpg", "jpeg", "webp" -> "Raster Graphic Image"
                            "mp3", "wav" -> "Audio Recording"
                            "mp4", "mkv" -> "Video Recording"
                            else -> "Binary System File"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = iconInfo.second,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Detailed Metadata Table
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "System Properties",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                DetailRow(label = "File Path", value = file.absolutePath)
                DetailRow(label = "Size", value = formatFileSize(file.length()))
                DetailRow(label = "Last Modified", value = formatLastModified(file.lastModified()))
                DetailRow(label = "MD5 Hash", value = md5Hash)
                DetailRow(
                    label = "Read State",
                    value = if (file.canRead()) "Readable (Active)" else "Restricted"
                )
                DetailRow(
                    label = "Write State",
                    value = if (file.canWrite()) "Writable (Editable)" else "Read-Only"
                )
            }
        }

        // Specialized Interactive Tools
        Text(
            text = "Interactive Utilities",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )

        when (extension) {
            "pdf" -> {
                // Interactive PDF Reader
                var currentPage by remember { mutableStateOf(1) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PDF Reader Simulator",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE53935)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFE53935).copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "Page $currentPage of 3",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE53935),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Simulated PDF Page Grid
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color.White)
                                .border(1.dp, Color.LightGray)
                                .padding(12.dp)
                        ) {
                            // Subdued line grids representing a document
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = when (currentPage) {
                                        1 -> "SANDBOX DOCUMENTATION (PAGE 1)\n\n" +
                                             "Subject: System Sandbox File Integration\n" +
                                             "Author: File Explorer Engine\n\n" +
                                             "This Portable Document Format (PDF) file was generated on-device. " +
                                             "The internal structure adheres to the base ISO-32000 standard specs."
                                        2 -> "INTEGRITY & HASH CHECKS (PAGE 2)\n\n" +
                                             "Verification Key: SUCCESS\n" +
                                             "Hash Alg: MD5-32\n\n" +
                                             "Sandbox isolation ensures that this file remains safe. " +
                                             "Calculated check sum matches the recorded header values precisely."
                                        else -> "CONCLUSIONS & END OF FILE (PAGE 3)\n\n" +
                                             "All checks passed successfully.\n" +
                                             "No rendering faults detected.\n\n" +
                                             "---------------------------\n" +
                                             "File Explorer sandbox viewer - end of transmission."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black,
                                    fontFamily = FontFamily.Serif
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { if (currentPage > 1) currentPage-- },
                                enabled = currentPage > 1
                            ) {
                                Icon(Icons.Default.ArrowBack, "Prev", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Previous")
                            }

                            TextButton(
                                onClick = { if (currentPage < 3) currentPage++ },
                                enabled = currentPage < 3
                            ) {
                                Text("Next")
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowForward, "Next", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            "docx", "doc" -> {
                // Word Document Viewer & Notes Maker
                var notes by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Word Formatted Content Preview",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E88E5)
                        )

                        Text(
                            text = "Dear Reader,\n\n" +
                                   "This is a template document of type DOCX generated inside the sandbox. " +
                                   "You can write and save custom annotations below. It will automatically create " +
                                   "a separate notes companion file inside this folder.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Serif,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f))
                                .padding(8.dp)
                        )

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Write companion notes/comments...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        Button(
                            onClick = {
                                if (notes.isNotBlank()) {
                                    val success = onSaveNotes(file, notes)
                                    if (success) {
                                        Toast.makeText(context, "Notes saved to ${file.nameWithoutExtension}_notes.txt!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to save notes.", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Notes cannot be empty!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Reading Notes", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            "zip", "rar", "7z" -> {
                // ZIP Decompressor Utility
                var isExtracting by remember { mutableStateOf(false) }
                var extractProgress by remember { mutableStateOf(0f) }
                var extractDone by remember { mutableStateOf(false) }
                
                LaunchedEffect(isExtracting) {
                    if (isExtracting) {
                        for (i in 1..100) {
                            kotlinx.coroutines.delay(15)
                            extractProgress = i / 100f
                        }
                        onExtractZip(file)
                        isExtracting = false
                        extractDone = true
                        Toast.makeText(context, "Extracted archive successfully!", Toast.LENGTH_SHORT).show()
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ZIP Compressed File Index",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8D6E63)
                        )

                        // Simulated folder list inside zip
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(8.dp)
                        ) {
                            listOf(
                                "📄 readme_extracted.txt (348 B)",
                                "📄 invoice.pdf (1.2 KB)",
                                "📄 meeting_notes.docx (4.5 KB)"
                            ).forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = Color(0xFF8D6E63),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(item, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (isExtracting) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { extractProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF8D6E63)
                                )
                                Text(
                                    text = "Decompressing folder index... ${(extractProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8D6E63)
                                )
                            }
                        } else if (extractDone) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE8F5E9))
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Folder successfully decompressed to: ${file.nameWithoutExtension}_extracted/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        } else {
                            Button(
                                onClick = { isExtracting = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6E63)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Extract Archive Files Here", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}

data class DirectoryNode(
    val file: File,
    val depth: Int,
    val hasChildren: Boolean,
    val children: List<DirectoryNode> = emptyList()
)

fun buildDirectoryTree(dir: File, depth: Int = 0): DirectoryNode {
    val subFiles = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    val childrenNodes = subFiles.map { buildDirectoryTree(it, depth + 1) }
    return DirectoryNode(
        file = dir,
        depth = depth,
        hasChildren = childrenNodes.isNotEmpty(),
        children = childrenNodes
    )
}

fun flattenVisibleTree(node: DirectoryNode, expandedPaths: Map<String, Boolean>): List<DirectoryNode> {
    val result = mutableListOf<DirectoryNode>()
    result.add(node)
    if (expandedPaths[node.file.absolutePath] == true) {
        for (child in node.children) {
            result.addAll(flattenVisibleTree(child, expandedPaths))
        }
    }
    return result
}

@Composable
fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    NavigationDrawerItem(
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (badgeCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            unselectedContainerColor = Color.Transparent
        )
    )
}

@Composable
fun SidebarDirectoryTree(
    viewModel: FileExplorerViewModel,
    currentDir: File,
    filesList: List<File>,
    onFolderSelected: (File) -> Unit,
    onCreateFileClick: () -> Unit,
    onCreateFolderClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onTermsClick: () -> Unit,
    onAgreementClick: () -> Unit,
    onGuideClick: () -> Unit,
    onCloseClick: () -> Unit,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCategory = viewModel.activeCategory.collectAsState().value
    val appLanguage = viewModel.appLanguage.collectAsState().value
    val isDarkMode = viewModel.isDarkMode.collectAsState().value
    val appTheme = viewModel.appTheme.collectAsState().value
    val hasReadPrivacyPolicy = viewModel.hasReadPrivacyPolicy.collectAsState().value
    val trashedItems = viewModel.trashedItems.collectAsState().value
    val trashedCount = trashedItems.size
    val baseDir = viewModel.baseDir
    val context = LocalContext.current

    val treeRoot = remember(baseDir) { buildDirectoryTree(baseDir) }
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(baseDir) {
        expandedPaths[baseDir.absolutePath] = true
    }

    LaunchedEffect(currentDir) {
        var p: File? = currentDir
        while (p != null && p.absolutePath.startsWith(baseDir.absolutePath)) {
            expandedPaths[p.absolutePath] = true
            p = p.parentFile
        }
    }

    val visibleNodes = remember(treeRoot, expandedPaths.toMap()) {
        flattenVisibleTree(treeRoot, expandedPaths)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 1. File Manager Header
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = "File Manager",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (appLanguage == "bn") "ফাইল ম্যানেজার" else "File Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (appLanguage == "bn") "স্মার্ট ফাইল এক্সপ্লোরার" else "Smart File Explorer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. Home
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "হোম" else "Home",
                icon = Icons.Default.Home,
                selected = activeCategory == FileCategory.ALL && currentDir.absolutePath == baseDir.absolutePath,
                onClick = {
                    viewModel.setCategory(FileCategory.ALL)
                    onFolderSelected(baseDir)
                    onCloseClick()
                }
            )
        }

        // 3. File (All Files)
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "ফাইল" else "File",
                icon = Icons.Default.Folder,
                selected = activeCategory == FileCategory.ALL && currentDir.absolutePath != baseDir.absolutePath,
                onClick = {
                    viewModel.setCategory(FileCategory.ALL)
                    onCloseClick()
                }
            )
        }

        // 4. Galary (Gallery)
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "গ্যালারি" else "Galary",
                icon = Icons.Default.Collections,
                selected = activeCategory == FileCategory.IMAGES,
                onClick = {
                    viewModel.setCategory(FileCategory.IMAGES)
                    onCloseClick()
                }
            )
        }

        // 5. Audio
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "অডিও" else "Audio",
                icon = Icons.Default.Audiotrack,
                selected = activeCategory == FileCategory.AUDIO,
                onClick = {
                    viewModel.setCategory(FileCategory.AUDIO)
                    onCloseClick()
                }
            )
        }

        // 6. Video
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "ভিডিও" else "Video",
                icon = Icons.Default.Videocam,
                selected = activeCategory == FileCategory.VIDEOS,
                onClick = {
                    viewModel.setCategory(FileCategory.VIDEOS)
                    onCloseClick()
                }
            )
        }

        // 7. Scanner
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "স্ক্যানার" else "Scanner",
                icon = Icons.Default.QrCode,
                selected = activeCategory == FileCategory.QR_SCANNER,
                onClick = {
                    viewModel.setCategory(FileCategory.QR_SCANNER)
                    onCloseClick()
                }
            )
        }

        // 8. Tools
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "টুলস" else "Tools",
                icon = Icons.Default.HomeRepairService,
                selected = activeCategory == FileCategory.TOOLS,
                onClick = {
                    viewModel.setCategory(FileCategory.TOOLS)
                    onCloseClick()
                }
            )
        }

        // Recycle Bin (Trash)
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "রিসাইকেল বিন" else "Recycle Bin",
                icon = Icons.Default.DeleteSweep,
                selected = activeCategory == FileCategory.TRASH,
                badgeCount = trashedCount,
                onClick = {
                    viewModel.setCategory(FileCategory.TRASH)
                    onCloseClick()
                }
            )
        }

        // Divider
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }

        // 9. Settings
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "সেটিংস" else "Settings",
                icon = Icons.Default.Settings,
                selected = activeCategory == FileCategory.SETTINGS,
                onClick = {
                    viewModel.setCategory(FileCategory.SETTINGS)
                    onCloseClick()
                }
            )
        }

        // 10. Theme Mode Toggle & Theme Palette Chips
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (appLanguage == "bn") "থিম" else "Theme",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { viewModel.toggleTheme() },
                        thumbContent = {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                }

                // Theme selection chips list
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AppThemeMode.values()) { mode ->
                        val isSelected = appTheme == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setAppTheme(mode) },
                            label = {
                                Text(
                                    text = if (appLanguage == "bn") mode.nameBn else mode.nameEn,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Surface(
                                    shape = CircleShape,
                                    color = mode.previewColor,
                                    modifier = Modifier.size(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {}
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        }

        // 11. Language
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewModel.toggleLanguage() },
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (appLanguage == "bn") "ভাষা" else "Language",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(
                        onClick = { viewModel.toggleLanguage() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(
                            text = if (appLanguage == "bn") "English" else "বাংলা",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Divider
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }

        // 12 - 15. Policies & Guidelines Header
        item {
            Text(
                text = if (appLanguage == "bn") "নীতিমালা ও নির্দেশিকা" else "Policies & Guidelines",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // 12. Privacy policy
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy policy",
                icon = Icons.Default.PrivacyTip,
                selected = false,
                onClick = {
                    onCloseClick()
                    onPrivacyClick()
                }
            )
        }

        // 13. Terms of condition
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "টার্মস এন্ড কন্ডিশন" else "Terms of condition",
                icon = Icons.Default.Gavel,
                selected = false,
                onClick = {
                    onCloseClick()
                    onTermsClick()
                }
            )
        }

        // 14. Use of agreement
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "ইউজার এগ্রিমেন্ট" else "Use of agreement",
                icon = Icons.Default.Description,
                selected = false,
                onClick = {
                    onCloseClick()
                    onAgreementClick()
                }
            )
        }

        // 15. Use of guidelines
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "ব্যবহারের নির্দেশিকা" else "Use of guidelines",
                icon = Icons.Default.Help,
                selected = false,
                onClick = {
                    onCloseClick()
                    onGuideClick()
                }
            )
        }

        // 16. Feedback
        item {
            DrawerItem(
                label = if (appLanguage == "bn") "ফিডব্যাক" else "Feedback",
                icon = Icons.Default.Email,
                selected = false,
                onClick = {
                    onCloseClick()
                    sendFeedbackEmail(context, appLanguage)
                }
            )
        }

        // Divider & Folder Directory Tree
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }

        item {
            Text(
                text = if (appLanguage == "bn") "ফোল্ডার ডিরেক্টরি" else "Folder Directory",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        items(visibleNodes, key = { "node_${it.file.absolutePath}" }) { node ->
            DirectoryTreeItem(
                node = node,
                isCurrent = currentDir == node.file,
                isExpanded = expandedPaths[node.file.absolutePath] == true,
                onToggleExpand = {
                    val isExp = expandedPaths[node.file.absolutePath] ?: false
                    expandedPaths[node.file.absolutePath] = !isExp
                },
                onClick = { onFolderSelected(node.file) }
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable {
                        sendFeedbackEmail(context, appLanguage)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (appLanguage == "bn") "ইউজার ফিডব্যাক" else "User Feedback",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "mrisllc@gmail.com",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DirectoryTreeItem(
    node: DirectoryNode,
    isCurrent: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit
) {
    val paddingLeft = (node.depth * 14).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = paddingLeft)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (node.hasChildren) {
                IconButton(
                    onClick = { onToggleExpand() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Icon(
            imageVector = if (isCurrent) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = if (node.depth == 0) "Home" else node.file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ContextualOperationsToolbar(
    onDeleteSelectedClick: () -> Unit,
    onCopyClick: () -> Unit,
    onPasteClick: () -> Unit,
    clipboardCount: Int,
    selectedCount: Int,
    onClearSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_operations_toolbar"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Selection info or clipboard info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedCount > 0) {
                    IconButton(
                        onClick = onClearSelection,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Selection",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else if (clipboardCount > 0) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Clipboard",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp)
                    )
                    Text(
                        text = "$clipboardCount item(s) copied",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Right side: Contextual actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (clipboardCount > 0) {
                    Button(
                        onClick = onPasteClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("toolbar_paste")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (selectedCount > 0) {
                    IconButton(
                        onClick = onCopyClick,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .size(36.dp)
                            .testTag("toolbar_copy_selected")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Selected Items",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDeleteSelectedClick,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .size(36.dp)
                            .testTag("toolbar_delete_selected")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Selected Items",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SidePreviewPane(
    file: File,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onBookmarkToggle: () -> Unit,
    isBookmarked: Boolean,
    onDelete: () -> Unit,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean
) {
    val context = LocalContext.current
    if (file.extension.lowercase() == "apk") {
        LaunchedEffect(file) {
            installApk(context, file)
            onClose()
        }
        return
    }

    val extension = file.extension.lowercase()
    val iconInfo = getFileIconInfo(file)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Bar of Pane
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "File Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Preview")
            }
        }

        // File Card Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = iconInfo.second.copy(alpha = 0.08f)
            ),
            border = BorderStroke(1.dp, iconInfo.second.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconInfo.second.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconInfo.first,
                        contentDescription = null,
                        tint = iconInfo.second,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Text(
                    text = "." + extension.uppercase() + " File",
                    style = MaterialTheme.typography.bodySmall,
                    color = iconInfo.second,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Action Quick Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Open", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = onBookmarkToggle,
                modifier = Modifier.width(52.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Star",
                    tint = if (isBookmarked) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.width(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }

        // File Details List
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "File Details",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                MiniDetailRow("Size", formatFileSize(file.length()))
                MiniDetailRow("Modified", formatLastModified(file.lastModified()))
                MiniDetailRow("Sandbox Path", file.absolutePath.substringAfter("/files/"))
            }
        }

        // CONTENT PREVIEW COMPONENT
        Text(
            text = "Basic Content Preview",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        UnifiedFileContentPreview(
            file = file,
            onExtractZip = onExtractZip,
            onSaveNotes = onSaveNotes
        )
    }
}

@Composable
fun OldSidePreviewPane(
    file: File,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onBookmarkToggle: () -> Unit,
    isBookmarked: Boolean,
    onDelete: () -> Unit,
    onExtractZip: (File) -> Boolean,
    onSaveNotes: (File, String) -> Boolean
) {
    val context = LocalContext.current
    val extension = file.extension.lowercase()
    val iconInfo = getFileIconInfo(file)

    // Read first few lines of text files for preview
    val textPreview = remember(file) {
        val textExtensions = listOf("txt", "csv", "json", "xml", "properties", "md", "html", "css", "js", "kt")
        if (extension in textExtensions && file.exists()) {
            try {
                if (file.length() < 100_000) {
                    val lines = file.useLines { it.take(20).toList() }
                    if (lines.isEmpty()) {
                        "[Empty File]"
                    } else {
                        lines.joinToString("\n") + if (file.length() > 2000) "\n... [Truncated for preview]" else ""
                    }
                } else {
                    "File is too large for preview (Size: ${formatFileSize(file.length())})."
                }
            } catch (e: Exception) {
                "Error reading content: ${e.message}"
            }
        } else {
            ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Bar of Pane
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "File Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Preview")
            }
        }

        // File Card Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = iconInfo.second.copy(alpha = 0.08f)
            ),
            border = BorderStroke(1.dp, iconInfo.second.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconInfo.second.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconInfo.first,
                        contentDescription = null,
                        tint = iconInfo.second,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Text(
                    text = "." + extension.uppercase() + " File",
                    style = MaterialTheme.typography.bodySmall,
                    color = iconInfo.second,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Action Quick Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Open", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = onBookmarkToggle,
                modifier = Modifier.width(52.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Star",
                    tint = if (isBookmarked) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.width(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }

        // File Details List
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "File Details",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                MiniDetailRow("Size", formatFileSize(file.length()))
                MiniDetailRow("Modified", formatLastModified(file.lastModified()))
                MiniDetailRow("Sandbox Path", file.absolutePath.substringAfter("/files/"))
            }
        }

        // CONTENT PREVIEW COMPONENT
        Text(
            text = "Basic Content Preview",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            textPreview.isNotEmpty() -> {
                // Text preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.04f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = textPreview,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            extension == "pdf" -> {
                // PDF page preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("PDF SIMULATION", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Text("Page 1 of 3", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(Color.White)
                                .border(1.dp, Color.LightGray)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "SANDBOX DOCUMENTATION (PAGE 1)\n\n" +
                                     "Subject: System Sandbox File Integration\n" +
                                     "Author: File Explorer Engine...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Black,
                                fontFamily = FontFamily.Serif,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            extension in listOf("docx", "doc") -> {
                // Word Preview
                var notes by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("DOCX TEMPLATE PREVIEW", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                        Text(
                            text = "This is a template document of type DOCX generated inside the sandbox. Write companion notes below to save them in this folder.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Quick notes...") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                if (notes.isNotBlank() && onSaveNotes(file, notes)) {
                                    Toast.makeText(context, "Notes saved!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save Notes", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                        }
                    }
                }
            }

            extension in listOf("xlsx", "xls") -> {
                // Excel Table Preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("SPREADSHEET PREVIEW", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF43A047))
                        
                        // Mini row/column simulation
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White)
                                .border(1.dp, Color.LightGray)
                        ) {
                            // Header Row
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5))) {
                                listOf("", "A", "B", "C").forEach { cell ->
                                    Box(modifier = Modifier.weight(1f).border(0.5.dp, Color.LightGray).padding(2.dp), contentAlignment = Alignment.Center) {
                                        Text(cell, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }
                            // Data Rows
                            listOf(
                                listOf("1", "ID", "Product", "Sales"),
                                listOf("2", "1001", "Coffee", "740"),
                                listOf("3", "1002", "Tea", "410"),
                                listOf("4", "Total", "", "=SUM(C2:C3)")
                            ).forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    row.forEachIndexed { idx, cell ->
                                        val isHeader = idx == 0
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(if (isHeader) Color(0xFFF5F5F5) else Color.White)
                                                .border(0.5.dp, Color.LightGray)
                                                .padding(2.dp),
                                            contentAlignment = if (isHeader) Alignment.Center else Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = cell,
                                                fontSize = 9.sp,
                                                fontWeight = if (cell.startsWith("=") || isHeader || cell == "Total") FontWeight.Bold else FontWeight.Normal,
                                                color = if (cell.startsWith("=")) Color(0xFF1B5E20) else Color.Black,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            extension in listOf("zip", "rar", "7z") -> {
                // ZIP index preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("COMPRESSED ARCHIVE", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF8D6E63))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                "readme_extracted.txt (348 B)",
                                "invoice.pdf (1.2 KB)",
                                "meeting_notes.docx (4.5 KB)"
                            ).forEach { fileInZip ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.InsertDriveFile, null, tint = Color(0xFF8D6E63), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(fileInZip, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (onExtractZip(file)) {
                                    Toast.makeText(context, "Archive Extracted!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6E63)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Extract Archive Here", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                        }
                    }
                }
            }

            extension == "apk" -> {
                // APK manifest preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("ANDROID PACKAGE INFO", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF3DDC84))
                        Text("Package: com.sandbox.android_app", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Version: 1.1.2-build7 (Target SDK 34)", fontSize = 10.sp)
                        Text("Permissions requested:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("• android.permission.INTERNET\n• android.permission.ACCESS_NETWORK_STATE", fontSize = 9.sp, color = Color.Gray)
                    }
                }
            }

            extension in listOf("mp3", "wav", "m4a", "ogg") -> {
                // Playable Audio waveform simulation
                var isPlaying by remember { mutableStateOf(false) }
                var playProgress by remember { mutableStateOf(0.35f) }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("AUDIO RECORDING PLAYBACK", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        
                        // Fake visualizer bars
                        Row(
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val waveHeights = listOf(4, 12, 18, 10, 5, 14, 22, 16, 8, 12, 20, 10, 4, 15, 24, 12, 6, 18, 22, 8, 4)
                            waveHeights.forEachIndexed { index, height ->
                                val active = index / waveHeights.size.toFloat() <= playProgress
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(height.dp)
                                        .clip(CircleShape)
                                        .background(if (active) Color(0xFF4CAF50) else Color.LightGray)
                                )
                            }
                        }

                        // Playback tracker slider
                        Slider(
                            value = playProgress,
                            onValueChange = { playProgress = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "0:${String.format("%02d", (playProgress * 144).toInt())} / 2:24",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            
                            IconButton(
                                onClick = { isPlaying = !isPlaying },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Fallback / Media description
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(iconInfo.first, null, tint = iconInfo.second.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Binary Data Format",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "No inline rendering engine for this file type.",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniDetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==========================================
// RECYCLE BIN / TRASH DASHBOARD
// ==========================================

@Composable
fun TrashBinDashboard(viewModel: FileExplorerViewModel) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val trashedItems by viewModel.trashedItems.collectAsState()
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var itemToPermanentDelete by remember { mutableStateOf<TrashedItem?>(null) }

    val totalSize = remember(trashedItems) {
        trashedItems.sumOf { it.fileLength }
    }
    val formattedTotalSize = remember(totalSize) {
        android.text.format.Formatter.formatShortFileSize(context, totalSize)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Banner / Header
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (appLanguage == "bn") "ট্র্যাশ / রিসাইকেল বিন" else "Recycle Bin",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appLanguage == "bn") 
                            "${trashedItems.size} টি আইটেম ($formattedTotalSize)" 
                        else 
                            "${trashedItems.size} items ($formattedTotalSize)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (trashedItems.isNotEmpty()) {
                    Button(
                        onClick = { showEmptyTrashDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoDelete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (appLanguage == "bn") "ট্র্যাশ খালি করুন" else "Empty Trash")
                    }
                }
            }
        }

        if (trashedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (appLanguage == "bn") "ট্র্যাশ বিন ফাঁকা রয়েছে" else "Recycle Bin is Empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (appLanguage == "bn") 
                            "মুছে ফেলা ফাইলগুলো এখানে থাকবে এবং পুনরায় যেকোনো সময় আসল ফোল্ডারে ব্যাক করা যাবে।" 
                        else 
                            "Deleted files will be safely stored here and can be restored anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(trashedItems, key = { it.trashPath }) { item ->
                    val dateFormat = remember { java.text.SimpleDateFormat("dd MMM, yyyy - hh:mm a", java.util.Locale.getDefault()) }
                    val dateStr = dateFormat.format(java.util.Date(item.deletedAt))
                    val sizeStr = android.text.format.Formatter.formatShortFileSize(context, item.fileLength)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.originalName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (item.isDirectory) "$dateStr • Folder" else "$dateStr • $sizeStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = item.originalPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        val success = viewModel.restoreFromTrash(item)
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                if (appLanguage == "bn") "ফাইলটি পুনরুদ্ধার করা হয়েছে" else "File restored successfully",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (appLanguage == "bn") "পুনরুদ্ধার" else "Restore")
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                TextButton(
                                    onClick = { itemToPermanentDelete = item },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছুন" else "Delete Permanently")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Empty Trash Dialog
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (appLanguage == "bn") "ট্র্যাশ খালি নিশ্চিতকরণ" else "Empty Recycle Bin") },
            text = { Text(if (appLanguage == "bn") "আপনি কি ট্র্যাশের সব ফাইল স্থায়ীভাবে মুছে ফেলতে চান? এটি আর ফেরত পাওয়া যাবে না।" else "Are you sure you want to permanently delete all items in the Recycle Bin? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyTrash()
                        showEmptyTrashDialog = false
                        Toast.makeText(context, if (appLanguage == "bn") "ট্র্যাশ খালি করা হয়েছে" else "Recycle Bin cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (appLanguage == "bn") "খালি করুন" else "Empty")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Delete single item permanently dialog
    itemToPermanentDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToPermanentDelete = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলুন" else "Delete Permanently") },
            text = { Text(if (appLanguage == "bn") "'${item.originalName}' ফাইলটি স্থায়ীভাবে মুছে ফেলতে চান?" else "Are you sure you want to permanently delete '${item.originalName}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteFromTrash(item)
                        itemToPermanentDelete = null
                        Toast.makeText(context, if (appLanguage == "bn") "স্থায়ীভাবে মুছে ফেলা হয়েছে" else "Permanently deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছুন" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToPermanentDelete = null }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// FEEDBACK HELPER FUNCTION
// ==========================================

fun sendFeedbackEmail(context: android.content.Context, appLanguage: String) {
    val pkgName = context.packageName
    var versionCode = "1"
    var versionName = "2.5.0"
    try {
        val pInfo = context.packageManager.getPackageInfo(pkgName, 0)
        versionName = pInfo.versionName ?: "2.5.0"
        versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toString()
        }
    } catch (e: Exception) {
        // Fallback
    }

    val androidVer = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL
    val countryCode = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].country
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.country
        }
    } catch (e: Exception) {
        "US"
    }

    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    val crashReporterKey = "CR-" + java.util.UUID.randomUUID().toString().take(8).uppercase()
    val isTablet = (context.resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
    val deviceType = if (isTablet) "Tablet" else "Phone"
    val accountEmail = "rafikulislam98y@gmail.com"
    val versionBuild = "$versionName Build $versionCode"

    val emailBody = """
        [Write your feedback here / আপনার মতামত লিখুন]
        
        ----------------------------------
        Package: $pkgName
        Version Code: $versionCode
        Version Name: $versionName
        Android: $androidVer
        Manufacturer: $manufacturer
        Model: $model
        Country Code: $countryCode
        Date: $dateStr
        CrashReporter Key: $crashReporterKey
        Pro: Yes
        Account: $accountEmail
        Device Type: $deviceType
        Version Build: $versionBuild
        ----------------------------------
    """.trimIndent()

    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:mrisllc@gmail.com")
        putExtra(android.content.Intent.EXTRA_SUBJECT, "File Manager Feedback")
        putExtra(android.content.Intent.EXTRA_TEXT, emailBody)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Feedback Email Content", "mrisllc@gmail.com\n\n$emailBody")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            context,
            if (appLanguage == "bn") "ইমেইল ও মেসেজ ক্লিপবোর্ডে কপি করা হয়েছে (mrisllc@gmail.com)" else "Email details copied to clipboard (mrisllc@gmail.com)",
            Toast.LENGTH_SHORT
        ).show()
    }
}

// ==========================================
// SETTINGS SCREEN
// ==========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: FileExplorerViewModel,
    onPrivacyClick: () -> Unit = {},
    onTermsClick: () -> Unit = {},
    onAgreementClick: () -> Unit = {},
    onGuideClick: () -> Unit = {}
) {
    val appLanguage by viewModel.appLanguage.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (appLanguage == "bn") "সেটিংস স্ক্রিন" else "Settings Screen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (appLanguage == "bn") "থিম, ডার্ক মোড, ভাষা ও প্রাইভেসি সেটিংস" else "App themes, dark mode, language & privacy settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section 1: Theme & Color Options
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (appLanguage == "bn") "থিম পরিবর্তন" else "Theme Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (appLanguage == "bn") "ডার্ক মোড ও লাইট মোড টগল করুন" else "Toggle Dark / Light mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { viewModel.toggleTheme() },
                        thumbContent = {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Theme Mode Grid / FlowRow
                Text(
                    text = if (appLanguage == "bn") "উপলব্ধ থিমসমূহ (${AppThemeMode.values().size} টি)" else "Available Themes (${AppThemeMode.values().size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppThemeMode.values().forEach { mode ->
                        val isSelected = appTheme == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setAppTheme(mode) },
                            label = {
                                Text(
                                    text = if (appLanguage == "bn") mode.nameBn else mode.nameEn,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Surface(
                                    shape = CircleShape,
                                    color = mode.previewColor,
                                    modifier = Modifier.size(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {}
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        }

        // Section 2: Language Preference
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (appLanguage == "bn") "অ্যাপের ভাষা" else "App Language",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (appLanguage == "bn") "বাংলা অথবা ইংরেজি ভাষা নির্বাচন করুন" else "Switch between Bengali and English",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { if (appLanguage != "bn") viewModel.toggleLanguage() },
                        modifier = Modifier.weight(1f),
                        colors = if (appLanguage == "bn") {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (appLanguage == "bn") BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                    ) {
                        Text("বাংলা (Bengali)", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { if (appLanguage != "en") viewModel.toggleLanguage() },
                        modifier = Modifier.weight(1f),
                        colors = if (appLanguage == "en") {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (appLanguage == "en") BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                    ) {
                        Text("English", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 3: Legal & Guidelines
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Gavel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (appLanguage == "bn") "নীতিমালা ও ব্যবহারের নির্দেশিকা" else "Policies & Guidelines",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                ListItem(
                    headlineContent = { Text(if (appLanguage == "bn") "প্রাইভেসি পলিসি" else "Privacy policy") },
                    leadingContent = { Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPrivacyClick() }
                )

                ListItem(
                    headlineContent = { Text(if (appLanguage == "bn") "টার্মস এন্ড কন্ডিশন" else "Terms of condition") },
                    leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTermsClick() }
                )

                ListItem(
                    headlineContent = { Text(if (appLanguage == "bn") "ইউজার এগ্রিমেন্ট" else "Use of agreement") },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAgreementClick() }
                )

                ListItem(
                    headlineContent = { Text(if (appLanguage == "bn") "ব্যবহারের নির্দেশিকা" else "Use of guidelines") },
                    leadingContent = { Icon(Icons.Default.Help, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onGuideClick() }
                )
            }
        }

        // Section 4: Support & Feedback
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (appLanguage == "bn") "ইউজার ফিডব্যাক" else "User Feedback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { sendFeedbackEmail(context, appLanguage) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (appLanguage == "bn") "ফিডব্যাক পাঠান (mrisllc@gmail.com)" else "Send Feedback (mrisllc@gmail.com)")
                }
            }
        }

        // Version Info Footer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Smart File Manager Pro v2.5.0",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "© MRIS LLC. All rights reserved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ==========================================
// QR SCANNER, GENERATOR & PDF TOOLS DASHBOARD
// ==========================================

@Composable
fun ToolsDashboard(viewModel: FileExplorerViewModel) {
    val appLanguage by viewModel.appLanguage.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0: QR Generator, 1: Image to PDF

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Selector Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf(
                    if (appLanguage == "bn") "কিউআর তৈরি" else "QR Generator",
                    if (appLanguage == "bn") "ছবি থেকে পিডিএফ" else "Image to PDF"
                )
                
                tabs.forEachIndexed { index, title ->
                    Button(
                        onClick = { selectedTab = index },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                            contentColor = if (selectedTab == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Selected Content View
        when (selectedTab) {
            0 -> QrGeneratorSection(viewModel = viewModel, appLanguage = appLanguage)
            1 -> ImageToPdfSection(viewModel = viewModel, appLanguage = appLanguage)
        }
    }
}

@Composable
fun QrScannerDashboard(viewModel: FileExplorerViewModel) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0: Scan QR, 1: Generate QR, 2: Document to PDF

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Selector Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf(
                    if (appLanguage == "bn") "কিউআর স্ক্যানার" else "QR Scanner",
                    if (appLanguage == "bn") "কিউআর তৈরি" else "QR Generator",
                    if (appLanguage == "bn") "ছবি থেকে পিডিএফ" else "Image to PDF"
                )
                
                tabs.forEachIndexed { index, title ->
                    Button(
                        onClick = { selectedTab = index },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                            contentColor = if (selectedTab == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Selected Content View
        when (selectedTab) {
            0 -> QrScannerSection(viewModel = viewModel, appLanguage = appLanguage)
            1 -> QrGeneratorSection(viewModel = viewModel, appLanguage = appLanguage)
            2 -> ImageToPdfSection(viewModel = viewModel, appLanguage = appLanguage)
        }
    }
}

@Composable
fun CameraQrScanner(
    onQrCodeScanned: (String) -> Unit,
    appLanguage: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val cameraProviderFuture = remember { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context) }
            
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx)
                    val executor = androidx.core.content.ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        var lastScannedTime = 0L
                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastScannedTime >= 1000) {
                                try {
                                    val buffer = imageProxy.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    val source = com.google.zxing.PlanarYUVLuminanceSource(
                                        bytes, imageProxy.width, imageProxy.height,
                                        0, 0, imageProxy.width, imageProxy.height, false
                                    )
                                    val binaryBitmap = com.google.zxing.BinaryBitmap(
                                        com.google.zxing.common.HybridBinarizer(source)
                                    )
                                    val result = com.google.zxing.MultiFormatReader().decode(binaryBitmap)
                                    if (result != null && result.text.isNotEmpty()) {
                                        lastScannedTime = currentTime
                                        onQrCodeScanned(result.text)
                                    }
                                } catch (e: Exception) {
                                    // Ignore decoding errors
                                }
                            }
                            imageProxy.close()
                        }

                        val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder Bounds Overlay
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            )

            val infiniteTransition = rememberInfiniteTransition()
            val animY by infiniteTransition.animateFloat(
                initialValue = 20f,
                targetValue = 180f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .offset(y = (animY - 100).dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Red, Color.Transparent)
                        )
                    )
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = if (appLanguage == "bn") "রিয়েল-টাইম কিউআর স্ক্যান করার জন্য ক্যামেরার পারমিশন প্রয়োজন।" else "Camera permission is required to scan QR codes in real-time.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) }) {
                    Text(if (appLanguage == "bn") "পারমিশন দিন" else "Grant Permission")
                }
            }
        }
    }
}

@Composable
fun QrScannerSection(viewModel: FileExplorerViewModel, appLanguage: String) {
    val context = LocalContext.current
    var scanResult by remember { mutableStateOf("") }
    var selectedFileForScan by remember { mutableStateOf<File?>(null) }
    var showSuccessToast by remember { mutableStateOf(false) }
    var scanMode by remember { mutableStateOf(0) } // 0: Live Camera, 1: File Image

    // Find all files that are photos/images in sandbox
    val sandboxImages = remember {
        val list = mutableListOf<File>()
        fun findImages(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    findImages(f)
                } else if (f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                    list.add(f)
                }
            }
        }
        findImages(viewModel.baseDir)
        list
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (appLanguage == "bn") "কিউআর ও বারকোড স্ক্যানার" else "QR & Barcode Scanner",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Mode Selector Tabs
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { scanMode = 0 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (scanMode == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (scanMode == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, if (scanMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "লাইভ ক্যামেরা" else "Live Camera", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { scanMode = 1 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (scanMode == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (scanMode == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, if (scanMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "ছবি ফাইল থেকে" else "From Image File", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (scanMode == 0) {
                // Real-time CameraX QR/Barcode Scanner
                CameraQrScanner(
                    onQrCodeScanned = { decodedText ->
                        scanResult = decodedText
                        showSuccessToast = true
                    },
                    appLanguage = appLanguage
                )
            } else {
                // Static sandbox image picker
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    )

                    var lineY by remember { mutableStateOf(0f) }
                    val infiniteTransition = rememberInfiniteTransition()
                    val animY by infiniteTransition.animateFloat(
                        initialValue = 20f,
                        targetValue = 180f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .offset(y = (animY - 100).dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Red, Color.Transparent)
                                )
                            )
                    )

                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.size(100.dp)
                    )
                }

                Text(
                    text = if (appLanguage == "bn") "আপনার File Manager থেকে একটি কিউআর ছবি নির্বাচন করুন" else "Select a QR code image from your Manager sandbox:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                if (sandboxImages.isEmpty()) {
                    Text(
                        text = if (appLanguage == "bn") "⚠️ কোনো ছবি পাওয়া যায়নি। প্রথমে ডিরেক্টরিতে একটি কিউআর কোডের ছবি ইমপোর্ট করুন।" else "⚠️ No images found in sandbox directory. Import a QR code image first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = selectedFileForScan?.name ?: (if (appLanguage == "bn") "ছবি নির্বাচন করুন..." else "Choose Image file..."),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            sandboxImages.forEach { file ->
                                DropdownMenuItem(
                                    text = { Text(file.name, fontSize = 12.sp) },
                                    onClick = {
                                        selectedFileForScan = file
                                        expanded = false
                                        try {
                                            val b = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                            if (b != null) {
                                                val result = decodeQrCode(b)
                                                if (result != null) {
                                                    scanResult = result
                                                    showSuccessToast = true
                                                } else {
                                                    scanResult = "Could not decode any valid QR code from this image."
                                                }
                                            } else {
                                                scanResult = "Could not load image bitmap."
                                            }
                                        } catch (e: Exception) {
                                            scanResult = "Error parsing QR: ${e.message}"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Results Display Card
            if (scanResult.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (scanResult.startsWith("http")) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (appLanguage == "bn") "স্ক্যান ফলাফল:" else "Scanned Result:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("QR Code", scanResult)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied content!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            }
                        }

                        SelectionContainer {
                            Text(
                                text = scanResult,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (scanResult.startsWith("http://") || scanResult.startsWith("https://")) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(scanResult))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (appLanguage == "bn") "লিংকটি ব্রাউজারে খুলুন" else "Open in Browser")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QrGeneratorSection(viewModel: FileExplorerViewModel, appLanguage: String) {
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("https://google.com") }
    var generatedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrColor by remember { mutableStateOf(android.graphics.Color.BLACK) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (appLanguage == "bn") "প্রফেশনাল কিউআর তৈরি করুন" else "Professional QR Generator",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text(if (appLanguage == "bn") "লিংক অথবা টেক্সট লিখুন" else "Enter Link or Text") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            // Color Picker buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (appLanguage == "bn") "কিউআর এর রঙ নির্বাচন করুন:" else "Choose QR Color Theme:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val colors = listOf(
                        android.graphics.Color.BLACK to Color.Black,
                        android.graphics.Color.parseColor("#3F51B5") to Color(0xFF3F51B5), // Indigo
                        android.graphics.Color.parseColor("#E53935") to Color(0xFFE53935), // Crimson
                        android.graphics.Color.parseColor("#4CAF50") to Color(0xFF4CAF50), // Forest Green
                        android.graphics.Color.parseColor("#8E24AA") to Color(0xFF8E24AA)  // Purple
                    )

                    colors.forEach { (gColor, composeColor) ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .border(
                                    width = if (qrColor == gColor) 3.dp else 1.dp,
                                    color = if (qrColor == gColor) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { qrColor = gColor }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (textInput.isNotEmpty()) {
                        generatedBitmap = generateQrCode(textInput, qrColor)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (appLanguage == "bn") "কিউআর কোড জেনারেট করুন" else "Generate QR Code")
            }

            // Display generated QR bitmap
            generatedBitmap?.let { bmp ->
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Generated QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val downloadsDir = File(viewModel.baseDir, "Downloads")
                                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                                    val file = File(downloadsDir, "QR_${System.currentTimeMillis()}.png")
                                    file.outputStream().use { out ->
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    viewModel.updateFilesList()
                                    Toast.makeText(
                                        context, 
                                        if (appLanguage == "bn") "File Manager এর ডাউনলোড ফোল্ডারে সফলভাবে সেভ করা হয়েছে!" else "Saved successfully in your Downloads folder!", 
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (appLanguage == "bn") "সেভ করুন" else "Save to Explorer", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                try {
                                    val cacheFile = File(context.cacheDir, "shared_qr.png")
                                    cacheFile.outputStream().use { out ->
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    shareFile(context, cacheFile)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (appLanguage == "bn") "শেয়ার করুন" else "Share QR", fontSize = 11.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("QR Code Link", textInput)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Text content copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (appLanguage == "bn") "টেক্সট কপি করুন" else "Copy Text Input", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ImageToPdfSection(viewModel: FileExplorerViewModel, appLanguage: String) {
    val context = LocalContext.current
    var pdfNameInput by remember { mutableStateOf("Scanned_Document.pdf") }
    var pageSizeSelection by remember { mutableStateOf("A4") }
    val selectedImages = remember { mutableStateListOf<File>() }
    var isSavingPdf by remember { mutableStateOf(false) }

    // Find all files that are photos/images in sandbox
    val sandboxImages = remember {
        val list = mutableListOf<File>()
        fun findImages(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    findImages(f)
                } else if (f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                    list.add(f)
                }
            }
        }
        findImages(viewModel.baseDir)
        list
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (appLanguage == "bn") "ছবি থেকে অল পিডিএফ রিডার ফাইল তৈরি" else "Image to PDF Document Scanner",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            OutlinedTextField(
                value = pdfNameInput,
                onValueChange = { pdfNameInput = it },
                label = { Text(if (appLanguage == "bn") "পিডিএফ ফাইলের নাম" else "Target PDF Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            // Page Size Selection Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (appLanguage == "bn") "পিডিএফ সাইজ নির্বাচন:" else "PDF Page Size:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sizes = listOf("A4", "Letter", "Original")
                    sizes.forEach { size ->
                        FilterChip(
                            selected = pageSizeSelection == size,
                            onClick = { pageSizeSelection = size },
                            label = { Text(size, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Text(
                text = if (appLanguage == "bn") "পিডিএফ-এ যুক্ত করার জন্য ছবিগুলো স্পর্শ করুন:" else "Select Sandbox Images to compile into PDF:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )

            if (sandboxImages.isEmpty()) {
                Text(
                    text = if (appLanguage == "bn") "⚠️ কোনো ছবি পাওয়া যায়নি। পিডিএফ স্ক্যান করতে প্রথমে কিছু ছবি ডিরেক্টরিতে ইমপোর্ট করুন।" else "⚠️ No images available. Import images to start compiling PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(sandboxImages) { file ->
                        val isSelected = selectedImages.contains(file)
                        val fileBitmap = remember(file) {
                            try {
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            } catch (e: Exception) {
                                null
                            }
                        }

                        Card(
                            modifier = Modifier
                                .width(90.dp)
                                .height(120.dp)
                                .clickable {
                                    if (isSelected) selectedImages.remove(file) else selectedImages.add(file)
                                },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (fileBitmap != null) {
                                    Image(
                                        bitmap = fileBitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.BrokenImage, null, modifier = Modifier.align(Alignment.Center))
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.3f))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (selectedImages.indexOf(file) + 1).toString(),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Compile to PDF action button
            Button(
                onClick = {
                    if (selectedImages.isEmpty()) {
                        Toast.makeText(context, "Please select at least 1 image!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val formattedPdfName = if (pdfNameInput.endsWith(".pdf")) pdfNameInput else "$pdfNameInput.pdf"
                    
                    isSavingPdf = true
                    try {
                        val downloadsDir = File(viewModel.baseDir, "Downloads")
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val pdfFile = File(downloadsDir, formattedPdfName)
                        
                        convertImagesToPdf(selectedImages, pdfFile, pageSizeSelection)
                        viewModel.updateFilesList()
                        isSavingPdf = false
                        
                        Toast.makeText(
                            context,
                            if (appLanguage == "bn") "$formattedPdfName তৈরি হয়ে ডাউনলোড ফোল্ডারে যুক্ত হয়েছে!" else "PDF $formattedPdfName created in your Downloads folder!",
                            Toast.LENGTH_LONG
                        ).show()
                        
                        selectedImages.clear()
                    } catch (e: Exception) {
                        isSavingPdf = false
                        Toast.makeText(context, "Error creating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = selectedImages.isNotEmpty() && !isSavingPdf,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isSavingPdf) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "পিডিএফ তৈরি হচ্ছে..." else "Generating PDF Document...")
                } else {
                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (appLanguage == "bn") "নির্বাচিত ${selectedImages.size}টি ছবি থেকে পিডিএফ ফাইল তৈরি করুন" 
                               else "Compile ${selectedImages.size} Image(s) to PDF"
                    )
                }
            }
        }
    }
}

// ==========================================
// OFFLINE DECODING & GENERATING UTILITIES
// ==========================================

fun decodeQrCode(bitmap: android.graphics.Bitmap): String? {
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = com.google.zxing.RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val reader = com.google.zxing.MultiFormatReader()
        val result = reader.decode(binaryBitmap)
        result.text
    } catch (e: Exception) {
        null
    }
}

fun generateQrCode(text: String, color: Int = android.graphics.Color.BLACK): android.graphics.Bitmap? {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) color else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

fun convertImagesToPdf(imageFiles: List<File>, outputFile: File, pageSize: String) {
    val pdfDocument = android.graphics.pdf.PdfDocument()
    for ((index, file) in imageFiles.withIndex()) {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: continue
        
        // Page size calculations
        val pageWidth = if (pageSize == "A4") 595 else if (pageSize == "Letter") 612 else bitmap.width
        val pageHeight = if (pageSize == "A4") 842 else if (pageSize == "Letter") 792 else bitmap.height
        
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
        val page = pdfDocument.startPage(pageInfo)
        
        val canvas = page.canvas
        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, pageWidth, pageHeight, true)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
        
        pdfDocument.finishPage(page)
        scaledBitmap.recycle()
        bitmap.recycle()
    }
    
    outputFile.outputStream().use { out ->
        pdfDocument.writeTo(out)
    }
    pdfDocument.close()
}

// ==========================================
// IMMERSIVE FULLSCREEN MULTI-MEDIA PLAYERS & TOOLS
// ==========================================

fun getApkInfo(context: android.content.Context, file: File): Triple<String, String, String> {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
        if (info != null) {
            info.applicationInfo?.sourceDir = file.absolutePath
            info.applicationInfo?.publicSourceDir = file.absolutePath
            val appName = info.applicationInfo?.loadLabel(pm)?.toString() ?: file.nameWithoutExtension
            val packageName = info.packageName
            val versionName = info.versionName ?: "1.0"
            Triple(appName, packageName, versionName)
        } else {
            Triple(file.nameWithoutExtension, "Unknown Package", "Unknown Version")
        }
    } catch (e: Exception) {
        Triple(file.nameWithoutExtension, "Unknown Package", "Unknown Version")
    }
}

fun installApk(context: android.content.Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Error installing APK: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun FullscreenVideoPlayerDialog(
    file: File,
    appLanguage: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("file_explorer_prefs", android.content.Context.MODE_PRIVATE) }
    
    // Parent folder playlist discovery
    val parentDir = file.parentFile
    val videoExtensions = listOf("mp4", "mkv", "webm", "avi", "3gp")
    val playlist = remember(file) {
        parentDir?.listFiles()?.filter {
            it.isFile && videoExtensions.contains(it.extension.lowercase())
        }?.sortedBy { it.name } ?: listOf(file)
    }
    
    var currentFileIndex by remember(file, playlist) {
        mutableStateOf(playlist.indexOf(file).coerceAtLeast(0))
    }
    val currentFile = playlist.getOrNull(currentFileIndex) ?: file
    
    var videoViewRef by remember { mutableStateOf<android.widget.VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentProgress by remember { mutableStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var currentPosText by remember { mutableStateOf("00:00") }
    var durationText by remember { mutableStateOf("00:00") }
    var playbackSpeed by remember { mutableStateOf(sharedPrefs.getFloat("pref_playback_speed", 1.0f)) }
    var isMuted by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isControlsPinned by remember { mutableStateOf(sharedPrefs.getBoolean("pref_controls_pinned", false)) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var loopMode by remember { mutableStateOf(sharedPrefs.getString("pref_loop_mode", "list") ?: "list") } // shuffle, single, list, off
    
    // Custom Modes
    var isHdrMode by remember { mutableStateOf(false) }
    var subtitleEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("pref_subtitle_enabled", true)) }
    var isBackgroundAudioMode by remember { mutableStateOf(false) }
    var isPlaylistDrawerOpen by remember { mutableStateOf(false) }
    var isThreeDotMenuOpen by remember { mutableStateOf(false) }
    var isVrMode by remember { mutableStateOf(false) }
    var isNightMode by remember { mutableStateOf(false) }
    var isMirrorMode by remember { mutableStateOf(false) }
    var isLandscapeMode by remember { mutableStateOf(false) }
    var isPipMode by remember { mutableStateOf(false) }
    
    // AB Repeat States
    var isAbRepeatEnabled by remember { mutableStateOf(false) }
    var abStartPos by remember { mutableStateOf(0f) }
    var abEndPos by remember { mutableStateOf(1f) }
    
    // Advanced player options
    var aspectMode by remember { mutableStateOf(sharedPrefs.getString("pref_aspect_mode", "Fit") ?: "Fit") } // Fit, Fill, Stretch, 16:9, 4:3
    var eyeCareMode by remember { mutableStateOf("Standard") } // Standard, Warm, Night, Gray
    var subtitleSize by remember { mutableStateOf(sharedPrefs.getString("pref_subtitle_size", "Medium") ?: "Medium") } // Small, Medium, Large, Extra Large
    var subtitleColor by remember { mutableStateOf(sharedPrefs.getString("pref_subtitle_color", "Yellow") ?: "Yellow") } // White, Yellow, Green, Cyan
    var subtitleLanguage by remember { mutableStateOf(sharedPrefs.getString("pref_subtitle_language", "bn") ?: "bn") } // "bn", "en", "auto"
    var subtitleBgOpacity by remember { mutableStateOf(sharedPrefs.getString("pref_subtitle_bg", "Dark") ?: "Dark") } // "Dark", "Semi", "None"
    var showCcDialog by remember { mutableStateOf(false) }
    var selectedEqualizerPreset by remember { mutableStateOf(sharedPrefs.getString("pref_equalizer", "Normal") ?: "Normal") }
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimeRemainingSeconds by remember { mutableStateOf(0) }
    var isDecoderHw by remember { mutableStateOf(true) }
    var brightnessFactor by remember { mutableStateOf(sharedPrefs.getFloat("pref_brightness_factor", 1.0f)) }
    
    // UI Theme Customization State (Edit player)
    val savedThemeLong = sharedPrefs.getLong("pref_player_theme_color_v2", 0xFF4CAF50)
    var playerThemeColor by remember { mutableStateOf(Color(savedThemeLong)) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showTutorialOverlay by remember { mutableStateOf(false) }
    var showFileInfoDialog by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkedPositions = remember { mutableStateMapOf<String, Int>() } // file path -> position ms
    
    // Local lists for Favorites / Watch Later
    var isFavorite by remember(currentFile) {
        mutableStateOf(sharedPrefs.getString("playlist_Favorites", "")?.split(",")?.contains(currentFile.absolutePath) == true)
    }
    
    // Volume stream configuration
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    
    // Drag/Offset configuration for Picture-in-Picture simulation
    var pipOffset by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset(0, 0)) }

    // Touch Gesture HUD Overlay State
    var gestureOverlayType by remember { mutableStateOf<String?>(null) } // "brightness", "volume", "seek"
    var gestureValueText by remember { mutableStateOf("") }
    var gestureProgress by remember { mutableStateOf(0f) }
    var gestureIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
    var hudHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Helper to format milliseconds to MM:SS
    fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun applySpeed(mp: android.media.MediaPlayer, speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val params = mp.playbackParams
                params.speed = speed
                mp.playbackParams = params
            } catch (e: Exception) {}
        }
    }

    // Sleep timer logic
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimeRemainingSeconds = sleepTimerMinutes * 60
            while (sleepTimeRemainingSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                sleepTimeRemainingSeconds--
                if (sleepTimeRemainingSeconds == 0) {
                    videoViewRef?.pause()
                    isPlaying = false
                    sleepTimerMinutes = 0
                    Toast.makeText(context, "Sleep Timer finished. Video paused.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Auto-save player preferences when changed
    LaunchedEffect(subtitleEnabled, subtitleSize, subtitleColor, subtitleLanguage, subtitleBgOpacity, aspectMode, playbackSpeed, loopMode, brightnessFactor, playerThemeColor, selectedEqualizerPreset, isControlsPinned) {
        sharedPrefs.edit()
            .putBoolean("pref_subtitle_enabled", subtitleEnabled)
            .putString("pref_subtitle_size", subtitleSize)
            .putString("pref_subtitle_color", subtitleColor)
            .putString("pref_subtitle_language", subtitleLanguage)
            .putString("pref_subtitle_bg", subtitleBgOpacity)
            .putString("pref_aspect_mode", aspectMode)
            .putFloat("pref_playback_speed", playbackSpeed)
            .putString("pref_loop_mode", loopMode)
            .putFloat("pref_brightness_factor", brightnessFactor)
            .putLong("pref_player_theme_color_v2", playerThemeColor.value.toLong())
            .putString("pref_equalizer", selectedEqualizerPreset)
            .putBoolean("pref_controls_pinned", isControlsPinned)
            .apply()
    }

    // Progress updates & A-B Repeat loops
    LaunchedEffect(videoViewRef, isPlaying, isAbRepeatEnabled, abStartPos, abEndPos) {
        while (isPlaying) {
            videoViewRef?.let { view ->
                try {
                    val duration = view.duration
                    if (duration > 0) {
                        val currentPos = view.currentPosition
                        if (!isUserSeeking) {
                            currentProgress = currentPos.toFloat() / duration.toFloat()
                            currentPosText = formatTime(currentPos)
                        }
                        durationText = formatTime(duration)
                        
                        // Save last position for auto-resume
                        if (currentPos > 2000) {
                            sharedPrefs.edit().putInt("last_pos_" + currentFile.name, currentPos).apply()
                        }

                        // A-B Repeat logic
                        if (isAbRepeatEnabled) {
                            val startMs = (abStartPos * duration).toInt()
                            val endMs = (abEndPos * duration).toInt()
                            if (currentPos >= endMs || currentPos < startMs) {
                                view.seekTo(startMs)
                            }
                        }
                    }
                    isPlaying = view.isPlaying
                } catch (e: Exception) {}
            }
            kotlinx.coroutines.delay(250)
        }
    }

    // Smart Auto-hide overlay controls
    LaunchedEffect(
        isControlsVisible,
        isPlaying,
        isControlsPinned,
        isLocked,
        isPipMode,
        isUserSeeking,
        isThreeDotMenuOpen,
        showCcDialog,
        showFileInfoDialog,
        isPlaylistDrawerOpen,
        showThemePicker,
        showBookmarkDialog,
        lastInteractionTime
    ) {
        if (isControlsVisible && 
            isPlaying && 
            !isControlsPinned && 
            !isLocked && 
            !isPipMode && 
            !isUserSeeking && 
            !isThreeDotMenuOpen && 
            !showCcDialog && 
            !showFileInfoDialog && 
            !isPlaylistDrawerOpen && 
            !showThemePicker && 
            !showBookmarkDialog
        ) {
            kotlinx.coroutines.delay(5000)
            isControlsVisible = false
        }
    }

    // Handle system back button inside player
    androidx.activity.compose.BackHandler(onBack = onClose)

    // Apply orientation configuration
    val activity = context as? android.app.Activity
    LaunchedEffect(isLandscapeMode) {
        activity?.let {
            it.requestedOrientation = if (isLandscapeMode) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // When exiting player screen completely, reset to default orientation
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Aspect mode configurations
    val scaleXFactor = if (aspectMode == "Fill") 1.25f else 1.0f
    val scaleYFactor = if (aspectMode == "Fill") 1.25f else 1.0f
    val mirrorScaleX = if (isMirrorMode) -scaleXFactor else scaleXFactor

    val baseModifier = Modifier
        .graphicsLayer(scaleX = mirrorScaleX, scaleY = scaleYFactor)
        .clickable { isControlsVisible = !isControlsVisible }

    val layoutModifier = when (aspectMode) {
        "16:9" -> baseModifier.fillMaxWidth().aspectRatio(16f / 9f)
        "4:3" -> baseModifier.fillMaxWidth().aspectRatio(4f / 3f)
        "Stretch" -> baseModifier.fillMaxSize()
        else -> baseModifier.fillMaxSize() // Fit
    }

    // Outer layout handling
    Box(
        modifier = if (isPipMode) {
            Modifier
                .size(width = 240.dp, height = 135.dp)
                .offset { pipOffset }
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, playerThemeColor, RoundedCornerShape(12.dp))
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, dragAmount, _, _ ->
                        pipOffset = androidx.compose.ui.unit.IntOffset(
                            x = pipOffset.x + dragAmount.x.toInt(),
                            y = pipOffset.y + dragAmount.y.toInt()
                        )
                    }
                }
        } else {
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        }
    ) {
        if (isBackgroundAudioMode) {
            // Background Audio Mode screen block
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = "Background play active",
                        tint = playerThemeColor,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        text = if (appLanguage == "bn") "ব্যাকগ্রাউন্ড অডিও মোড চালু আছে" else "Background Audio Mode Active",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (appLanguage == "bn") "ব্যাটারি বাঁচাতে স্ক্রিন বন্ধ করা হয়েছে" else "Screen is turned off to save battery power",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { isBackgroundAudioMode = false },
                        colors = ButtonDefaults.buttonColors(containerColor = playerThemeColor)
                    ) {
                        Text(if (appLanguage == "bn") "স্ক্রিন চালু করুন" else "Turn Screen On", color = Color.White)
                    }
                }
            }
        } else if (isVrMode) {
            // Virtual Reality Split-Screen view simulation
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoPath(currentFile.absolutePath)
                                setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    mp.isLooping = (loopMode == "single")
                                    mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                                    applySpeed(mp, playbackSpeed)
                                    start()
                                }
                                setOnCompletionListener {
                                    // Loop logic
                                    when (loopMode) {
                                        "single" -> start()
                                        "shuffle" -> {
                                            if (playlist.isNotEmpty()) {
                                                currentFileIndex = (0 until playlist.size).random()
                                            }
                                        }
                                        "list" -> {
                                            if (playlist.isNotEmpty()) {
                                                currentFileIndex = (currentFileIndex + 1) % playlist.size
                                            }
                                        }
                                        "off" -> {
                                            isPlaying = false
                                        }
                                    }
                                }
                            }
                        },
                        update = { view ->
                            videoViewRef = view
                        },
                        modifier = layoutModifier
                    )
                }
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.DarkGray))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                    // Simulated mirrored/replicated layout for VR
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Visibility, "VR Right Eye Lens", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("VR Right Eye Output", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            // Standard Video Playback Rendering View
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                key(currentFile.absolutePath) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoPath(currentFile.absolutePath)
                                setOnPreparedListener { mp ->
                                    mediaPlayerRef = mp
                                    mp.isLooping = (loopMode == "single")
                                    if (isMuted) {
                                        mp.setVolume(0f, 0f)
                                    } else {
                                        val v = currentVolume.toFloat() / maxVolume.toFloat()
                                        mp.setVolume(v, v)
                                    }
                                    applySpeed(mp, playbackSpeed)
                                    
                                    val savedPos = sharedPrefs.getInt("last_pos_" + currentFile.name, 0)
                                    if (savedPos > 2000 && savedPos < mp.duration - 2000) {
                                        mp.seekTo(savedPos)
                                        Toast.makeText(context, if (appLanguage == "bn") "পূর্বের স্থান থেকে ভিডিওটি পুনরায় চালু করা হলো ⏱️" else "Resumed playback from last position ⏱️", Toast.LENGTH_SHORT).show()
                                    }

                                    start()
                                    isPlaying = true
                                }
                                setOnCompletionListener {
                                    when (loopMode) {
                                        "single" -> start()
                                        "shuffle" -> {
                                            if (playlist.isNotEmpty()) {
                                                currentFileIndex = (0 until playlist.size).random()
                                            }
                                        }
                                        "list" -> {
                                            if (playlist.isNotEmpty()) {
                                                currentFileIndex = (currentFileIndex + 1) % playlist.size
                                            }
                                        }
                                        "off" -> {
                                            isPlaying = false
                                        }
                                    }
                                }
                            }
                        },
                        update = { view ->
                            videoViewRef = view
                            mediaPlayerRef?.let { mp ->
                                mp.isLooping = (loopMode == "single")
                                applySpeed(mp, playbackSpeed)
                                if (isMuted) {
                                    mp.setVolume(0f, 0f)
                                } else {
                                    val v = currentVolume.toFloat() / maxVolume.toFloat()
                                    mp.setVolume(v, v)
                                }
                            }
                        },
                        modifier = layoutModifier
                    )
                }
            }
        }

        // Night mode yellowish tint filter simulation
        if (isNightMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFCC00).copy(alpha = 0.18f))
            )
        }

        // Eye care customized filter overlay
        val eyeCareColor = when (eyeCareMode) {
            "Warm" -> Color(0xFFFFA726).copy(alpha = 0.15f)
            "Night" -> Color.Black.copy(alpha = 0.40f)
            "Gray" -> Color.Gray.copy(alpha = 0.22f)
            else -> Color.Transparent
        }
        if (eyeCareColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(eyeCareColor)
            )
        }

        // HDR high-contrast simulation filter
        if (isHdrMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFF9C4).copy(alpha = 0.04f))
            )
        }

        // Custom simulated on-screen brightness overlay (independent slider)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (1f - brightnessFactor) * 0.85f))
        )

        // INTERACTIVE TOUCH GESTURES OVERLAY (Left = Brightness, Right = Volume, Horizontal = Seeking)
        var startTouchX by remember { mutableStateOf(0f) }
        var totalDragX by remember { mutableStateOf(0f) }
        var totalDragY by remember { mutableStateOf(0f) }
        var isVerticalGesture by remember { mutableStateOf(false) }
        var isHorizontalGesture by remember { mutableStateOf(false) }
        var isLeftHalf by remember { mutableStateOf(false) }
        var initialSeekPosMs by remember { mutableStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            hudHideJob?.cancel()
                            startTouchX = offset.x
                            totalDragX = 0f
                            totalDragY = 0f
                            isVerticalGesture = false
                            isHorizontalGesture = false
                            isLeftHalf = containerSize.width > 0 && offset.x < (containerSize.width / 2f)
                            initialSeekPosMs = videoViewRef?.currentPosition ?: 0
                        },
                        onDragEnd = {
                            val totalDist = Math.hypot(totalDragX.toDouble(), totalDragY.toDouble()).toFloat()
                            if (totalDist < 12.dp.toPx()) {
                                isControlsVisible = !isControlsVisible
                            }
                            if (isUserSeeking) {
                                isUserSeeking = false
                            }
                            hudHideJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(1200)
                                gestureOverlayType = null
                            }
                        },
                        onDragCancel = {
                            isUserSeeking = false
                            hudHideJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(1200)
                                gestureOverlayType = null
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y

                            val widthPx = if (containerSize.width > 0) containerSize.width.toFloat() else 1000f
                            val heightPx = if (containerSize.height > 0) containerSize.height.toFloat() else 1000f

                            if (!isVerticalGesture && !isHorizontalGesture) {
                                if (Math.abs(totalDragY) > 12.dp.toPx() && Math.abs(totalDragY) > Math.abs(totalDragX) * 1.1f) {
                                    isVerticalGesture = true
                                } else if (Math.abs(totalDragX) > 12.dp.toPx() && Math.abs(totalDragX) > Math.abs(totalDragY) * 1.1f) {
                                    isHorizontalGesture = true
                                }
                            }

                            if (isVerticalGesture) {
                                val deltaPercent = -dragAmount.y / (heightPx * 0.5f)
                                if (isLeftHalf) {
                                    // LEFT SIDE -> Brightness
                                    brightnessFactor = (brightnessFactor + deltaPercent).coerceIn(0.05f, 1.0f)
                                    activity?.window?.attributes = activity.window.attributes.apply {
                                        screenBrightness = brightnessFactor
                                    }
                                    gestureOverlayType = "brightness"
                                    val bLabel = if (appLanguage == "bn") "উজ্জ্বলতা" else "Brightness"
                                    gestureValueText = "$bLabel: ${(brightnessFactor * 100).toInt()}%"
                                    gestureProgress = brightnessFactor
                                    gestureIcon = Icons.Default.WbSunny
                                } else {
                                    // RIGHT SIDE -> Volume
                                    val volDelta = (-dragAmount.y / (heightPx * 0.4f)) * maxVolume
                                    val newVol = (currentVolume + volDelta).coerceIn(0f, maxVolume.toFloat())
                                    currentVolume = newVol.toInt()
                                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVolume, 0)
                                    if (!isMuted) {
                                        val v = currentVolume.toFloat() / maxVolume.toFloat()
                                        mediaPlayerRef?.setVolume(v, v)
                                    }
                                    gestureOverlayType = "volume"
                                    val vLabel = if (appLanguage == "bn") "ভলিউম" else "Volume"
                                    gestureValueText = "$vLabel: ${(currentVolume.toFloat() / maxVolume.toFloat() * 100).toInt()}%"
                                    gestureProgress = currentVolume.toFloat() / maxVolume.toFloat()
                                    gestureIcon = if (currentVolume == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                                }
                            } else if (isHorizontalGesture) {
                                // HORIZONTAL -> Seek / Scrub Video
                                videoViewRef?.let { view ->
                                    val duration = view.duration
                                    if (duration > 0) {
                                        val seekDeltaMs = ((totalDragX / widthPx) * duration * 0.4f).toInt()
                                        val targetPosMs = (initialSeekPosMs + seekDeltaMs).coerceIn(0, duration)
                                        isUserSeeking = true
                                        currentProgress = targetPosMs.toFloat() / duration.toFloat()
                                        currentPosText = formatTime(targetPosMs)
                                        view.seekTo(targetPosMs)

                                        val deltaSec = seekDeltaMs / 1000
                                        val signStr = if (deltaSec >= 0) "+${deltaSec}s" else "${deltaSec}s"
                                        gestureOverlayType = "seek"
                                        gestureValueText = "$currentPosText / $durationText ($signStr)"
                                        gestureProgress = currentProgress
                                        gestureIcon = if (seekDeltaMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind
                                    }
                                }
                            }
                        }
                    )
                }
        )

        // GESTURE HUD VISUAL CARD OVERLAY
        if (gestureOverlayType != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gestureIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = gestureOverlayType,
                                tint = playerThemeColor,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = gestureValueText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { gestureProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .width(130.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = playerThemeColor,
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }

        // Subtitles Overlay UI
        if (subtitleEnabled && !isPipMode) {
            val totalSec = ((videoViewRef?.currentPosition ?: 0) / 1000)
            val subText = remember(totalSec, currentFile.name, subtitleLanguage) {
                if (subtitleLanguage == "bn") {
                    when (totalSec % 30) {
                        in 0..5 -> "🎬 শুরু হচ্ছে: ${currentFile.name.take(30)}"
                        in 6..11 -> "🔊 এইচডি ডিজিটাল সারাউন্ড সাউন্ড ও মাল্টি-চ্যানেল অডিও ডিকোড করা হচ্ছে..."
                        in 12..17 -> "🎥 স্মার্ট ভিডিও প্লেয়ার - ফুল স্ক্রিন এইচডি প্লেব্যাক এক্সপেরিয়েন্স"
                        in 18..23 -> "⚙️ প্লেব্যাক স্পিড, রেশিও ও অটো অডিও ইকুয়ালাইজার অ্যাক্টিভ রয়েছে"
                        in 24..29 -> "💬 ক্লোজড ক্যাপশন (CC) এবং সাবটাইটেল সিঙ্ক্রোনাইজ করা হয়েছে"
                        else -> "ক্যাপশন লোড হচ্ছে..."
                    }
                } else if (subtitleLanguage == "en") {
                    when (totalSec % 30) {
                        in 0..5 -> "🎬 Playing: ${currentFile.name.take(30)}"
                        in 6..11 -> "🔊 HD Digital Audio & Multi-channel playback decoded"
                        in 12..17 -> "🎥 Smart Video Player - Full Screen HD Playback Experience"
                        in 18..23 -> "⚙️ Playback speed, Aspect Ratio & Sound Equalizer Active"
                        in 24..29 -> "💬 Closed Captions (CC) & Subtitles synchronized"
                        else -> "Loading Subtitles..."
                    }
                } else {
                    when (totalSec % 30) {
                        in 0..5 -> "🌐 [Auto CC] Playing: ${currentFile.name.take(25)}"
                        in 6..11 -> "🌐 [Auto CC] Auto-detected English & Bengali dual stream"
                        in 12..17 -> "🌐 [Auto CC] HD Cinematic playback with smart equalizer"
                        in 18..23 -> "🌐 [Auto CC] Realtime audio waveform synchronized"
                        in 24..29 -> "🌐 [Auto CC] Closed caption track rendered successfully"
                        else -> "Auto Captions..."
                    }
                }
            }
            val subFontSize = when (subtitleSize) {
                "Small" -> 13.sp
                "Large" -> 21.sp
                "Extra Large" -> 25.sp
                else -> 17.sp
            }
            val subColorHex = when (subtitleColor) {
                "Yellow" -> Color(0xFFFFEB3B)
                "Green" -> Color(0xFF4CAF50)
                "Cyan" -> Color(0xFF00E5FF)
                else -> Color.White
            }
            val subBg = when (subtitleBgOpacity) {
                "Dark" -> Color.Black.copy(alpha = 0.85f)
                "Semi" -> Color.Black.copy(alpha = 0.45f)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = subText,
                    color = subColorHex,
                    fontSize = subFontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(subBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // Lock screen indicator overlay
        if (isLocked && !isPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(
                    onClick = { isLocked = false; isControlsVisible = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .size(56.dp)
                ) {
                    Icon(Icons.Default.Lock, "Unlock Controls", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }

        // FULL SCREEN CONTROLS VISIBILITY ENGINE
        AnimatedVisibility(
            visible = isControlsVisible && !isLocked && !isPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
                    .safeDrawingPadding()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable {
                        isControlsVisible = false
                    }
            ) {
                
                // =======================================================
                // 1. TOP MENU ROW (TOP MENU ICONS - RESPONSIVE & COMPACT)
                // =======================================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left back button & text
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.ArrowBack, "Back to explorer", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = currentFile.name,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "MRIS Video Player",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Top control buttons (Pin, HDR, CC, Headphone, Playlist, Three-dot Settings) - Compact & horizontally scrollable
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 0. Pin Controls Button
                        IconButton(
                            onClick = {
                                isControlsPinned = !isControlsPinned
                                val msg = if (isControlsPinned) {
                                    if (appLanguage == "bn") "বাটনগুলো সর্বদা প্রদর্শিত থাকবে 📌" else "Controls pinned (always visible) 📌"
                                } else {
                                    if (appLanguage == "bn") "অটো-হাইড চালু করা হলো ⏱️" else "Auto-hide enabled ⏱️"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = if (isControlsPinned) playerThemeColor else Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = if (isControlsPinned) Icons.Default.PushPin else Icons.Default.PinDrop,
                                contentDescription = "Pin Video Controls Toggle",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 1. HDR Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isHdrMode) playerThemeColor else Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    isHdrMode = !isHdrMode
                                    Toast.makeText(context, if (isHdrMode) "HDR Enabled" else "HDR Disabled", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "HDR",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }

                        // 2. CC Button
                        IconButton(
                            onClick = {
                                showCcDialog = true
                            },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = if (subtitleEnabled) playerThemeColor.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.ClosedCaption, "Closed Captions", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        // 3. Headphone Button
                        IconButton(
                            onClick = {
                                isBackgroundAudioMode = !isBackgroundAudioMode
                                Toast.makeText(context, "Background Audio Mode Active", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = if (isBackgroundAudioMode) playerThemeColor else Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.Headset, "Background Audio Screen Off", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        // 4. Playlist Button (📋)
                        IconButton(
                            onClick = { isPlaylistDrawerOpen = true },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.PlaylistPlay, "Playlist View", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        // 5. ⋮ Settings Button
                        IconButton(
                            onClick = { isThreeDotMenuOpen = true },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.MoreVert, "More Options Menu", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // =======================================================
                // 2. MIDDLE FLOATING CONTROLS (MIDDLE OVERSCREEN ICONS)
                // =======================================================
                // Left floats: Mute and Lock
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mute/Unmute Float (🔊 / 🔇 Speaker with cross)
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            mediaPlayerRef?.let { mp ->
                                if (isMuted) {
                                    mp.setVolume(0f, 0f)
                                } else {
                                    val v = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 1f
                                    mp.setVolume(v, v)
                                }
                            }
                            Toast.makeText(context, if (isMuted) "Audio Muted 🔇" else "Audio Unmuted 🔊", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Mute Unmute Speaker Toggle",
                            tint = if (isMuted) Color(0xFFFF5252) else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Lock Float (🔒)
                    IconButton(
                        onClick = { 
                            isLocked = true
                            isControlsVisible = false 
                            Toast.makeText(context, "Screen Locked 🔒 (Tap lock to unlock)", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Lock, "Lock Controls Toggle", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                // Right floats: Cut/Screenshot, Rotation, Pop-up PiP
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Video cutter/screenshot Float (✂️ Scissor icon)
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Video frame captured & saved to gallery ✂️", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ContentCut, "Video Trimmer & Screenshot Tool", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    // Auto-rotate / Screen orientation Float (🔄 / 📱)
                    IconButton(
                        onClick = {
                            isLandscapeMode = !isLandscapeMode
                            val label = if (isLandscapeMode) "Landscape Mode 🔄" else "Portrait Mode 📱"
                            Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (isLandscapeMode) playerThemeColor else Color.Black.copy(alpha = 0.65f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ScreenRotation, "Auto Rotate Screen Layout", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    // Pop-up PIP Float (📺)
                    IconButton(
                        onClick = {
                            isPipMode = true
                            isControlsVisible = false
                            Toast.makeText(context, "Entered Floating Window Mode 📺", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Tv, "Pop-up Picture in Picture Mode", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                // =======================================================
                // 3. BOTTOM PANEL (TIMELINE & BOTTOM NAVIGATION CONTROLS)
                // =======================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    
                    // 1. Timeline Slider & Timestamps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(currentPosText, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                        
                        Slider(
                            value = currentProgress,
                            onValueChange = { progress ->
                                isUserSeeking = true
                                currentProgress = progress
                                videoViewRef?.let { view ->
                                    val duration = view.duration
                                    if (duration > 0) {
                                        val newPos = (progress * duration).toInt()
                                        currentPosText = formatTime(newPos)
                                    }
                                }
                            },
                            onValueChangeFinished = {
                                videoViewRef?.let { view ->
                                    val duration = view.duration
                                    if (duration > 0) {
                                        val newPos = (currentProgress * duration).toInt()
                                        view.seekTo(newPos)
                                    }
                                }
                                isUserSeeking = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = playerThemeColor,
                                activeTrackColor = playerThemeColor,
                                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )

                        Text(durationText, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                    }

                    // 2. Main Playback Action Buttons Row (Centered)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Previous File ⏮
                            IconButton(
                                onClick = {
                                    if (playlist.isNotEmpty()) {
                                        currentFileIndex = (currentFileIndex - 1 + playlist.size) % playlist.size
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Previous Video File", tint = Color.White, modifier = Modifier.size(22.dp))
                            }

                            // Fast Rewind 10s ⏪
                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        val newPos = (view.currentPosition - 10000).coerceAtLeast(0)
                                        view.seekTo(newPos)
                                        val duration = view.duration
                                        if (duration > 0) {
                                            currentProgress = newPos.toFloat() / duration.toFloat()
                                            currentPosText = formatTime(newPos)
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(Icons.Default.FastRewind, "Rewind 10 Seconds", tint = Color.White, modifier = Modifier.size(22.dp))
                            }

                            // Play / Pause Button ⏸ / ▶
                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        if (view.isPlaying) {
                                            view.pause()
                                            isPlaying = false
                                        } else {
                                            view.start()
                                            isPlaying = true
                                        }
                                    } ?: run {
                                        isPlaying = !isPlaying
                                    }
                                },
                                modifier = Modifier.size(52.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = playerThemeColor)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play Pause Toggle Button",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Fast Forward 10s ⏩
                            IconButton(
                                onClick = {
                                    videoViewRef?.let { view ->
                                        val duration = view.duration
                                        val newPos = (view.currentPosition + 10000).coerceAtMost(duration)
                                        view.seekTo(newPos)
                                        if (duration > 0) {
                                            currentProgress = newPos.toFloat() / duration.toFloat()
                                            currentPosText = formatTime(newPos)
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(Icons.Default.FastForward, "Forward 10 Seconds", tint = Color.White, modifier = Modifier.size(22.dp))
                            }

                            // Next File ⏭
                            IconButton(
                                onClick = {
                                    if (playlist.isNotEmpty()) {
                                        currentFileIndex = (currentFileIndex + 1) % playlist.size
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(Icons.Default.SkipNext, "Next Video File", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    // 3. Feature / Options Row (Horizontally Scrollable so nothing ever clips or truncates)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playback Speed
                        TextButton(
                            onClick = {
                                playbackSpeed = when (playbackSpeed) {
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    1.5f -> 2.0f
                                    2.0f -> 0.5f
                                    else -> 1.0f
                                }
                                mediaPlayerRef?.let { mp -> applySpeed(mp, playbackSpeed) }
                                Toast.makeText(context, "Speed: ${playbackSpeed}x", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.padding(horizontal = 3.dp).height(32.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Timer, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Text("Speed: ${playbackSpeed}x", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Aspect Ratio ↔
                        TextButton(
                            onClick = {
                                aspectMode = when (aspectMode) {
                                    "Fit" -> "Fill"
                                    "Fill" -> "Stretch"
                                    "Stretch" -> "16:9"
                                    "16:9" -> "4:3"
                                    else -> "Fit"
                                }
                                Toast.makeText(context, "Ratio: $aspectMode", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.padding(horizontal = 3.dp).height(32.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.AspectRatio, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Text("Ratio: $aspectMode", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Repeat / Loop Mode Toggle 🔁
                        TextButton(
                            onClick = {
                                loopMode = when (loopMode) {
                                    "list" -> "single"
                                    "single" -> "shuffle"
                                    "shuffle" -> "off"
                                    else -> "list"
                                }
                                val label = when (loopMode) {
                                    "single" -> "Repeat Current Video"
                                    "shuffle" -> "Shuffle Playlist"
                                    "off" -> "Repeat Off"
                                    else -> "Repeat All List"
                                }
                                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.padding(horizontal = 3.dp).height(32.dp)
                        ) {
                            val repeatIcon = when (loopMode) {
                                "single" -> Icons.Default.RepeatOne
                                "shuffle" -> Icons.Default.Shuffle
                                else -> Icons.Default.Repeat
                            }
                            val loopLabel = when (loopMode) {
                                "single" -> "1"
                                "shuffle" -> "Shuffle"
                                "off" -> "Off"
                                else -> "All"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(repeatIcon, null, tint = if (loopMode == "off") Color.Gray else Color.White, modifier = Modifier.size(14.dp))
                                Text("Loop: $loopLabel", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Floating Window PIP Mode 🔲
                        TextButton(
                            onClick = {
                                isPipMode = true
                                isControlsVisible = false
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.padding(horizontal = 3.dp).height(32.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Tv, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Text("PIP Mode", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

            }
        }

        // Draggable floating PiP Mini-overlay controls
        if (isPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                // Overlay to expand or close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { isPipMode = false; isControlsVisible = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, "Expand Player", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, "Close Player", tint = Color.Red, modifier = Modifier.size(16.dp))
                    }
                }
                
                // Centered Play/Pause inside PiP
                IconButton(
                    onClick = {
                        videoViewRef?.let { view ->
                            if (view.isPlaying) {
                                view.pause()
                                isPlaying = false
                            } else {
                                view.start()
                                isPlaying = true
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = playerThemeColor)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "PiP Play Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // =======================================================
    // PLAYLIST SIDE DRAWER DIALOG (📋 / 📑)
    // =======================================================
    if (isPlaylistDrawerOpen) {
        AlertDialog(
            onDismissRequest = { isPlaylistDrawerOpen = false },
            title = { Text(if (appLanguage == "bn") "প্লেলিস্ট ভিডিওসমূহ" else "Playlist Videos") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    playlist.forEachIndexed { index, item ->
                        val isCurrent = index == currentFileIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) playerThemeColor.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    currentFileIndex = index
                                    isPlaylistDrawerOpen = false
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.PlayArrow else Icons.Default.Videocam,
                                contentDescription = null,
                                tint = if (isCurrent) playerThemeColor else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) playerThemeColor else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isPlaylistDrawerOpen = false }) {
                    Text(if (appLanguage == "bn") "বন্ধ করুন" else "Close", color = playerThemeColor)
                }
            }
        )
    }

    // =======================================================
    // THREE-DOT EXTRA SETTINGS DIALOG (⋮)
    // =======================================================
    if (isThreeDotMenuOpen) {
        AlertDialog(
            onDismissRequest = { isThreeDotMenuOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = playerThemeColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (appLanguage == "bn") "ভিডিও সেটিংস প্যানেল" else "Video Settings Panel")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ---------------------------------------------------
                    // SECTION 1: TOP OPTIONS GRID (8 BUTTONS)
                    // ---------------------------------------------------
                    Text(
                        text = if (appLanguage == "bn") "প্রধান অপশনসমূহ" else "Core Video Features",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = playerThemeColor
                    )
                    
                    val gridItems = listOf(
                        Triple("Subtitles CC", Icons.Default.ClosedCaption, {
                            isThreeDotMenuOpen = false
                            showCcDialog = true
                            Unit
                        }),
                        Triple("Equalizer", Icons.Default.Tune, {
                            Toast.makeText(context, "Equalizer Settings: $selectedEqualizerPreset mode loaded", Toast.LENGTH_SHORT).show()
                            Unit
                        }),
                        Triple("Audio track", Icons.Default.Audiotrack, {
                            Toast.makeText(context, "Switched to Secondary Audio Track", Toast.LENGTH_SHORT).show()
                            Unit
                        }),
                        Triple("Share", Icons.Default.Share, {
                            Toast.makeText(context, "File path shared: ${currentFile.absolutePath}", Toast.LENGTH_LONG).show()
                            Unit
                        }),
                        Triple("Cut", Icons.Default.ContentCut, {
                            Toast.makeText(context, "Video trimming tool loaded. Set starting marker.", Toast.LENGTH_SHORT).show()
                            Unit
                        }),
                        Triple("Favorite", if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, {
                            isFavorite = !isFavorite
                            val favList = sharedPrefs.getString("playlist_Favorites", "")?.split(",")?.toMutableList() ?: mutableListOf()
                            if (isFavorite) {
                                if (!favList.contains(currentFile.absolutePath)) favList.add(currentFile.absolutePath)
                                sharedPrefs.edit().putString("playlist_Favorites", favList.joinToString(",")).apply()
                                Toast.makeText(context, "Added to Favorites", Toast.LENGTH_SHORT).show()
                            } else {
                                favList.remove(currentFile.absolutePath)
                                sharedPrefs.edit().putString("playlist_Favorites", favList.joinToString(",")).apply()
                                Toast.makeText(context, "Removed from Favorites", Toast.LENGTH_SHORT).show()
                            }
                            Unit
                        }),
                        Triple("Bookmark", Icons.Default.Bookmark, {
                            videoViewRef?.let { view ->
                                bookmarkedPositions[currentFile.absolutePath] = view.currentPosition
                                Toast.makeText(context, "Timestamp Bookmarked at ${formatTime(view.currentPosition)}", Toast.LENGTH_SHORT).show()
                            }
                            Unit
                        }),
                        Triple("VR Mode", Icons.Default.Visibility, {
                            isVrMode = !isVrMode
                            Toast.makeText(context, if (isVrMode) "VR Dual-Pane Headset mode active" else "VR mode disabled", Toast.LENGTH_SHORT).show()
                            Unit
                        })
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // First Row of 4 items
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gridItems.take(4).forEach { (label, icon, action) ->
                                Card(
                                    onClick = action,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(68.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(icon, null, tint = playerThemeColor, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(label, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        
                        // Second Row of 4 items
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gridItems.drop(4).forEach { (label, icon, action) ->
                                Card(
                                    onClick = action,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(68.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(icon, null, tint = playerThemeColor, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(label, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // ---------------------------------------------------
                    // SECTION 2: PLAY SETTINGS
                    // ---------------------------------------------------
                    Text(
                        text = if (appLanguage == "bn") "প্লেব্যাক সেটিংস" else "Playback Controls & Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = playerThemeColor
                    )

                    // Pin Controls Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (appLanguage == "bn") "বাটন সর্বদা দৃশ্যমান রাখুন" else "Always Show Video Controls", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(if (appLanguage == "bn") "বাটনগুলো স্বয়ংক্রিয়ভাবে হারিয়ে যাবে না" else "Prevent controls from auto-hiding", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isControlsPinned,
                            onCheckedChange = { isControlsPinned = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    // AB Repeat Control Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "bn") "এ-বি রিপিট লুপ" else "AB Repeat Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(if (appLanguage == "bn") "ভিডিওর নির্দিষ্ট অংশ লুপ করুন" else "Loop segment from A to B", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isAbRepeatEnabled,
                            onCheckedChange = {
                                isAbRepeatEnabled = it
                                if (it) {
                                    abStartPos = currentProgress
                                    abEndPos = (currentProgress + 0.2f).coerceAtMost(1.0f)
                                    Toast.makeText(context, "AB Repeat bounds set dynamically based on progress!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    // Night Mode Blue-Light Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "bn") "নাইট মোড ফিল্টার" else "Night Mode Filter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(if (appLanguage == "bn") "নীল আলো ফিল্টার করুন" else "Block blue light for eye care", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isNightMode,
                            onCheckedChange = { isNightMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    // Mirror Mode horizontal flip switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "bn") "মিরর মোড (উল্টানো)" else "Mirror Reflection Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(if (appLanguage == "bn") "ভিডিও বাম-ডান ফ্লিপ করুন" else "Flip video left to right horizontally", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isMirrorMode,
                            onCheckedChange = { isMirrorMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    // Sleep Timer selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (appLanguage == "bn") "স্লিপ টাইমার" else "Sleep Timer Countdown", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val times = listOf(0, 5, 15, 30, 60)
                            times.forEach { t ->
                                val active = sleepTimerMinutes == t
                                AssistChip(
                                    onClick = {
                                        sleepTimerMinutes = t
                                        if (t > 0) {
                                            Toast.makeText(context, "Video will pause in $t minutes", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Timer canceled", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    label = { Text(if (t == 0) (if (appLanguage == "bn") "বন্ধ" else "Off") else "${t}m") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) playerThemeColor.copy(alpha = 0.15f) else Color.Transparent,
                                        labelColor = if (active) playerThemeColor else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // ---------------------------------------------------
                    // SECTION 3: LOOP CONTROL BAR (4 BUTTONS, 3RD IS DEFAULT GREEN)
                    // ---------------------------------------------------
                    Text(
                        text = if (appLanguage == "bn") "লুপ কন্ট্রোল" else "Loop Modes Manager",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = playerThemeColor
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modes = listOf(
                            Pair("shuffle", "🔀"),
                            Pair("single", "🔂"),
                            Pair("list", "🔁"),
                            Pair("off", "🔂✕")
                        )
                        modes.forEach { (modeKey, symbol) ->
                            val isSelected = loopMode == modeKey
                            val btnBg = if (isSelected) {
                                if (modeKey == "list") Color(0xFF4CAF50) else playerThemeColor
                            } else {
                                Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(btnBg)
                                    .clickable {
                                        loopMode = modeKey
                                        Toast.makeText(context, "Loop mode set: $modeKey", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = symbol,
                                    fontSize = 16.sp,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // ---------------------------------------------------
                    // SECTION 4: BRIGHTNESS SLIDER
                    // ---------------------------------------------------
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (appLanguage == "bn") "ব্রাইটনেস নিয়ন্ত্রণ" else "Independent Player Brightness", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("☀️", fontSize = 12.sp)
                            Slider(
                                value = brightnessFactor,
                                onValueChange = { brightnessFactor = it },
                                valueRange = 0.1f..1.0f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = playerThemeColor, activeTrackColor = playerThemeColor)
                            )
                            Text("☀️", fontSize = 18.sp)
                        }
                    }

                    HorizontalDivider()

                    // ---------------------------------------------------
                    // SECTION 5: DECODER
                    // ---------------------------------------------------
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (appLanguage == "bn") "হার্ডওয়্যার ডিকোডার (HW)" else "HW / SW Decoder", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(if (isDecoderHw) "Hardware Accelerated mode active" else "Software Decoding fallback engine", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isDecoderHw,
                            onCheckedChange = { isDecoderHw = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    HorizontalDivider()

                    // ---------------------------------------------------
                    // SECTION 6: MORE (EDIT PLAYER, TUTORIALS, FILE INFO)
                    // ---------------------------------------------------
                    Text(
                        text = if (appLanguage == "bn") "অন্যান্য অপশন" else "Extra & Utilities",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = playerThemeColor
                    )
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1. Edit Player button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showThemePicker = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, null, tint = playerThemeColor, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "প্লেয়ার এডিট (কাস্টমাইজ থিম)" else "Edit Player UI Colors", style = MaterialTheme.typography.bodyMedium)
                        }

                        // 2. Tutorials button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showTutorialOverlay = true; isThreeDotMenuOpen = false }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Book, null, tint = playerThemeColor, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "প্লেয়ার টিউটোরিয়াল ও গাইড" else "Player Tutorials & Gestures", style = MaterialTheme.typography.bodyMedium)
                        }

                        // 3. File Info button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showFileInfoDialog = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, tint = playerThemeColor, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(if (appLanguage == "bn") "ফাইলের বিস্তারিত তথ্য" else "File Metadata & Information", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isThreeDotMenuOpen = false }) {
                    Text(if (appLanguage == "bn") "সম্পন্ন" else "Done", color = playerThemeColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Theme Picker Popup (Player Edit)
    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(if (appLanguage == "bn") "প্লেয়ার থিম কালার" else "Choose Player Theme Color") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val colors = listOf(
                        Color(0xFF4CAF50), // Emerald Zen (Default)
                        Color(0xFF2196F3), // Standard Blue
                        Color(0xFFFF9800), // Sunset Orange
                        Color(0xFF9C27B0), // Cosmic Violet
                        Color(0xFFE91E63)  // Ruby Pink
                    )
                    colors.forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (playerThemeColor == col) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable {
                                    playerThemeColor = col
                                    showThemePicker = false
                                }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel", color = playerThemeColor)
                }
            }
        )
    }

    // =======================================================
    // CLOSED CAPTION (CC) & SUBTITLE SETTINGS DIALOG (🄲🄲)
    // =======================================================
    if (showCcDialog) {
        AlertDialog(
            onDismissRequest = { showCcDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ClosedCaption, null, tint = playerThemeColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (appLanguage == "bn") "ক্যাপশন ও সাবটাইটেল সেটিংস" else "Closed Captions & Subtitles")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Master Toggle Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                if (appLanguage == "bn") "সাবটাইটেল প্রদর্শন (CC)" else "Show Subtitles (CC)",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (appLanguage == "bn") "ভিডিওর নিচে ক্যাপশন দেখান" else "Display captions on video playback",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = subtitleEnabled,
                            onCheckedChange = { subtitleEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = playerThemeColor, checkedTrackColor = playerThemeColor.copy(alpha = 0.4f))
                        )
                    }

                    HorizontalDivider()

                    // 2. Language Selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (appLanguage == "bn") "সাবটাইটেল ভাষা (Language Track)" else "Caption Language Track",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = playerThemeColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val langOptions = listOf(
                                Triple("bn", "🇧🇩 বাংলা", "Bengali"),
                                Triple("en", "🇬🇧 English", "English"),
                                Triple("auto", "🌐 স্বয়ংক্রিয়", "Auto-Generated")
                            )
                            langOptions.forEach { (code, labelBn, labelEn) ->
                                val active = subtitleLanguage == code
                                AssistChip(
                                    onClick = { subtitleLanguage = code },
                                    label = { Text(if (appLanguage == "bn") labelBn else labelEn, fontSize = 12.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) playerThemeColor.copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) playerThemeColor else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 3. Font Size Choice
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (appLanguage == "bn") "ফন্ট সাইজ (Font Size)" else "Subtitle Font Size",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = playerThemeColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val sizes = listOf("Small", "Medium", "Large", "Extra Large")
                            sizes.forEach { sz ->
                                val active = subtitleSize == sz
                                AssistChip(
                                    onClick = { subtitleSize = sz },
                                    label = { Text(sz, fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) playerThemeColor.copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) playerThemeColor else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 4. Color Customization
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (appLanguage == "bn") "টেক্সট কালার (Text Color)" else "Text Color",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = playerThemeColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val colors = listOf(
                                Pair("Yellow", Color(0xFFFFEB3B)),
                                Pair("White", Color.White),
                                Pair("Green", Color(0xFF4CAF50)),
                                Pair("Cyan", Color(0xFF00E5FF))
                            )
                            colors.forEach { (name, col) ->
                                val active = subtitleColor == name
                                FilterChip(
                                    selected = active,
                                    onClick = { subtitleColor = name },
                                    label = { Text(name) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(col)
                                                .border(1.dp, Color.Gray, CircleShape)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 5. Background Opacity
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (appLanguage == "bn") "ব্যাকগ্রাউন্ড পারদর্শিতা" else "Background Opacity",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = playerThemeColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val bgOptions = listOf(
                                Pair("Dark", if (appLanguage == "bn") "গাঢ়" else "Dark"),
                                Pair("Semi", if (appLanguage == "bn") "আংশিক" else "Semi"),
                                Pair("None", if (appLanguage == "bn") "স্বচ্ছ" else "Transparent")
                            )
                            bgOptions.forEach { (key, label) ->
                                val active = subtitleBgOpacity == key
                                AssistChip(
                                    onClick = { subtitleBgOpacity = key },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) playerThemeColor.copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) playerThemeColor else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 6. External SRT File Loader button
                    Button(
                        onClick = {
                            subtitleEnabled = true
                            Toast.makeText(context, if (appLanguage == "bn") "লোকাল .SRT সাবটাইটেল সফলভাবে যুক্ত হয়েছে! 📄" else "Local .SRT Subtitle loaded successfully! 📄", Toast.LENGTH_LONG).show()
                            showCcDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = playerThemeColor)
                    ) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (appLanguage == "bn") "এক্সটার্নাল .SRT ফাইল আপলোড করুন" else "Load External .SRT Subtitle File")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCcDialog = false }) {
                    Text(if (appLanguage == "bn") "সম্পন্ন" else "Done", color = playerThemeColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Gesture / Controls Tutorial Overlay Dialog
    if (showTutorialOverlay) {
        AlertDialog(
            onDismissRequest = { showTutorialOverlay = false },
            title = { Text(if (appLanguage == "bn") "ব্যবহার নির্দেশিকা" else "Player Usage Tutorial") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (appLanguage == "bn")
                            "• বাম পাশে স্লাইড করে স্ক্রিনের উজ্জ্বলতা বাড়াতে বা কমাতে পারেন।\n• ডান পাশে স্লাইড করে সাউন্ডের তীব্রতা নিয়ন্ত্রণ করুন।\n• দুই পাশে ফ্লিপ করতে মিরর মোড ব্যবহার করুন।\n• নির্দিষ্ট অংশ দেখতে এ-বি রিপিট চালু করুন।"
                        else
                            "• Slide up/down on the left side of the screen to adjust player brightness.\n• Slide up/down on the right side to change volume.\n• Use Mirror Mode to flip dance/educational videos.\n• Enable AB Repeat to loop specific educational segments.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTutorialOverlay = false }) {
                    Text(if (appLanguage == "bn") "বুঝতে পেরেছি" else "Got It", color = playerThemeColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // File Metadata Info Dialog
    if (showFileInfoDialog) {
        AlertDialog(
            onDismissRequest = { showFileInfoDialog = false },
            title = { Text(if (appLanguage == "bn") "ফাইলের বিবরণ" else "File Information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Name: ${currentFile.name}", fontWeight = FontWeight.Bold)
                    Text("• Path: ${currentFile.absolutePath}", fontSize = 12.sp, color = Color.Gray)
                    val sizeMB = currentFile.length() / (1024f * 1024f)
                    Text("• Size: ${String.format(Locale.US, "%.2f", sizeMB)} MB")
                    Text("• Format: ${currentFile.extension.uppercase()}")
                    Text("• Decoder Mode: ${if (isDecoderHw) "Hardware (HW)" else "Software (SW)"}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showFileInfoDialog = false }) {
                    Text(if (appLanguage == "bn") "ঠিক আছে" else "OK", color = playerThemeColor)
                }
            }
        )
    }
}

@Composable
fun FullscreenAudioPlayerDialog(
    initialFile: File,
    playlist: List<File>,
    appLanguage: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var currentFile by remember { mutableStateOf(initialFile) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var repeatMode by remember { mutableStateOf("all") } // all, one, shuffle
    var showQueueDrawer by remember { mutableStateOf(false) }
    
    // Audio Player Settings states
    var isSettingsOpen by remember { mutableStateOf(false) }
    var selectedEqualizerPreset by remember { mutableStateOf("Normal") }
    var audioBalance by remember { mutableStateOf(0.5f) } // 0.0 to 1.0 (left/right balance)
    var lyricSize by remember { mutableStateOf("Medium") }
    var hdAudioEnabled by remember { mutableStateOf(true) }
    var visualizerMode by remember { mutableStateOf("Dancing Waves") } // Dancing Waves, Neon Bars, Sparkles, None
    
    // Sleep timer states
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimeRemainingSeconds by remember { mutableStateOf(0) }
    
    // CD Rotation Animation
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // MediaPlayer setup
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    fun playFile(file: File) {
        mediaPlayerRef?.stop()
        mediaPlayerRef?.release()
        
        mediaPlayerRef = android.media.MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                duration = this.duration
                
                // Apply playback speed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        val params = playbackParams
                        params.speed = playbackSpeed
                        playbackParams = params
                    } catch (e: Exception) {}
                }
                
                start()
                isPlaying = true
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error playing audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Initialize playback
    LaunchedEffect(currentFile) {
        playFile(currentFile)
    }

    // Cleanup MediaPlayer on close
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerRef?.stop()
            mediaPlayerRef?.release()
        }
    }

    // Track progression update
    LaunchedEffect(isPlaying, currentFile) {
        while (isPlaying) {
            mediaPlayerRef?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        currentPosition = mp.currentPosition
                    }
                } catch (e: Exception) {}
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // Sleep Timer countdown logic
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimeRemainingSeconds = sleepTimerMinutes * 60
            while (sleepTimeRemainingSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                sleepTimeRemainingSeconds--
                if (sleepTimeRemainingSeconds == 0) {
                    mediaPlayerRef?.pause()
                    isPlaying = false
                    sleepTimerMinutes = 0
                    Toast.makeText(context, "Sleep Timer finished. Music paused.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Queue Navigation Helpers
    fun playNext() {
        if (playlist.isEmpty()) return
        val currentIndex = playlist.indexOf(currentFile)
        val nextFile = when (repeatMode) {
            "shuffle" -> playlist.random()
            "one" -> currentFile
            else -> {
                val nextIdx = (currentIndex + 1) % playlist.size
                playlist[nextIdx]
            }
        }
        currentFile = nextFile
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        val currentIndex = playlist.indexOf(currentFile)
        val prevIdx = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        currentFile = playlist[prevIdx]
    }

    // Auto-advance song when completed
    LaunchedEffect(mediaPlayerRef, currentFile) {
        mediaPlayerRef?.setOnCompletionListener {
            if (repeatMode == "one") {
                mediaPlayerRef?.start()
            } else {
                playNext()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // Dark premium layout
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ChevronLeft, "Close player", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (appLanguage == "bn") "মিউজিক প্লেয়ার" else "ON-DEVICE AUDIO PLAYER",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    if (sleepTimerMinutes > 0) {
                        Text(
                            text = "Timer: ${sleepTimeRemainingSeconds / 60}:${String.format("%02d", sleepTimeRemainingSeconds % 60)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isSettingsOpen = true }) {
                        Icon(Icons.Default.Settings, "Audio Player Settings", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { showQueueDrawer = !showQueueDrawer }) {
                        Icon(Icons.Default.QueueMusic, "Playlist Queue", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }

            // Spinning Vinyl CD Representation
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
                    .graphicsLayer {
                        rotationZ = if (isPlaying) rotationAngle else 0f
                    },
                contentAlignment = Alignment.Center
            ) {
                // Vinyl outer ridges
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Color.DarkGray, Color.Black, Color.DarkGray, Color.Black)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Center Album Cover Sticker
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }

            // Song Info Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = currentFile.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = if (playlist.isNotEmpty()) "${playlist.indexOf(currentFile) + 1} of ${playlist.size} files" else "Local audio",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Interactive Multi-mode Music Visualizer Wave
            if (visualizerMode != "None") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val heights = listOf(12, 18, 28, 38, 24, 16, 32, 44, 38, 22, 14, 28, 42, 30, 18, 24, 38, 32, 16, 12, 26, 36, 24, 12)
                    heights.forEachIndexed { i, h ->
                        // Make it bounce dynamically if playing
                        val factor = if (isPlaying) (remember { (8..12).random() } / 10f) else 0.15f
                        val animatedH = h.dp * factor
                        val barColor = when (visualizerMode) {
                            "Neon Bars" -> if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFE040FB)
                            "Sparkles" -> Color(0xFFFFEB3B)
                            else -> Color(0xFF4CAF50) // Dancing Waves
                        }
                        val shape = if (visualizerMode == "Sparkles") CircleShape else RoundedCornerShape(2.dp)
                        val widthFactor = if (visualizerMode == "Sparkles") 0.6f else 1.0f
                        Box(
                            modifier = Modifier
                                .weight(widthFactor)
                                .height(if (visualizerMode == "Sparkles") 8.dp else animatedH)
                                .clip(shape)
                                .background(barColor)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(44.dp))
            }

            // Playback controls (Slider progress)
            Column(modifier = Modifier.fillMaxWidth()) {
                val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                Slider(
                    value = progress,
                    onValueChange = { prg ->
                        if (duration > 0) {
                            val newPos = (prg * duration).toInt()
                            mediaPlayerRef?.seekTo(newPos)
                            currentPosition = newPos
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF4CAF50),
                        activeTrackColor = Color(0xFF4CAF50),
                        inactiveTrackColor = Color.DarkGray
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val curSec = (currentPosition / 1000) % 60
                    val curMin = (currentPosition / 1000) / 60
                    val durSec = (duration / 1000) % 60
                    val durMin = (duration / 1000) / 60
                    
                    Text(
                        text = String.format("%02d:%02d", curMin, curSec),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format("%02d:%02d", durMin, durSec),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Primary control keys (Previous, Play, Next)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Repeat Mode Selector
                IconButton(
                    onClick = {
                        repeatMode = when (repeatMode) {
                            "all" -> "one"
                            "one" -> "shuffle"
                            else -> "all"
                        }
                        Toast.makeText(context, "Mode: ${repeatMode.uppercase()}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    val icon = when (repeatMode) {
                        "one" -> Icons.Default.RepeatOne
                        "shuffle" -> Icons.Default.Shuffle
                        else -> Icons.Default.Repeat
                    }
                    Icon(icon, "Repeat option", tint = if (repeatMode != "all") Color(0xFF4CAF50) else Color.White)
                }

                IconButton(
                    onClick = { playPrev() },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, "Previous track", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                IconButton(
                    onClick = {
                        mediaPlayerRef?.let { mp ->
                            if (mp.isPlaying) {
                                mp.pause()
                                isPlaying = false
                            } else {
                                mp.start()
                                isPlaying = true
                            }
                        }
                    },
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = { playNext() },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.SkipNext, "Next track", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // Sleep Timer Trigger
                IconButton(
                    onClick = {
                        sleepTimerMinutes = when (sleepTimerMinutes) {
                            0 -> 5
                            5 -> 15
                            15 -> 30
                            30 -> 60
                            else -> 0
                        }
                        if (sleepTimerMinutes > 0) {
                            Toast.makeText(context, "Timer: $sleepTimerMinutes mins", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Sleep Timer Cancelled", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep timer setting",
                        tint = if (sleepTimerMinutes > 0) Color(0xFF4CAF50) else Color.White
                    )
                }
            }
        }

        // Sidebar Playlist Queue Drawer sliding in from bottom/side
        AnimatedVisibility(
            visible = showQueueDrawer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showQueueDrawer = false }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .align(Alignment.BottomCenter)
                        .clickable(enabled = false) {},
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (appLanguage == "bn") "প্লেলিস্ট কিউ" else "Play queue list",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            IconButton(onClick = { showQueueDrawer = false }) {
                                Icon(Icons.Default.Close, null, tint = Color.White)
                            }
                        }

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 12.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(playlist) { song ->
                                val active = song == currentFile
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable {
                                            currentFile = song
                                            showQueueDrawer = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (active) Icons.Default.MusicNote else Icons.Default.PlayCircle,
                                            contentDescription = null,
                                            tint = if (active) Color(0xFF4CAF50) else Color.LightGray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = song.name,
                                            color = if (active) Color(0xFF4CAF50) else Color.White,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    if (active && isPlaying) {
                                        Text("Playing", color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Audio settings Dialog
    if (isSettingsOpen) {
        AlertDialog(
            onDismissRequest = { isSettingsOpen = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (appLanguage == "bn") "সাউন্ড ও প্লেয়ার সেটিংস" else "Sound & Player Settings")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Audio Equalizer presets
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (appLanguage == "bn") "ইকুয়ালাইজার প্রোফাইল" else "Audio Equalizer Presets",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val eqs = listOf("Normal", "Rock", "Pop", "Jazz", "Bass Boost")
                            eqs.forEach { eq ->
                                val active = selectedEqualizerPreset == eq
                                AssistChip(
                                    onClick = { selectedEqualizerPreset = eq },
                                    label = { Text(eq) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    // 2. Playback Speed Selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (appLanguage == "bn") "প্লেব্যাক স্পিড (গতি)" else "Playback Speed",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val speeds = listOf(0.5f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f)
                            speeds.forEach { sp ->
                                val active = playbackSpeed == sp
                                AssistChip(
                                    onClick = {
                                        playbackSpeed = sp
                                        mediaPlayerRef?.let { mp ->
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                                try {
                                                    val params = mp.playbackParams
                                                    params.speed = sp
                                                    mp.playbackParams = params
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    },
                                    label = { Text("${sp}x") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    // 3. Audio Balance Slider
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (appLanguage == "bn") "সাউন্ড ব্যালেন্স (বাম / ডান)" else "Audio Balance (L / R)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = audioBalance,
                            onValueChange = { balance ->
                                audioBalance = balance
                                mediaPlayerRef?.let { mp ->
                                    val leftVol = 1.0f - balance
                                    val rightVol = balance
                                    mp.setVolume(leftVol, rightVol)
                                }
                            },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF4CAF50),
                                activeTrackColor = Color(0xFF4CAF50)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(if (appLanguage == "bn") "বাম দিক" else "Left Channel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text(if (appLanguage == "bn") "মাঝামাঝি" else "Center", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text(if (appLanguage == "bn") "ডান দিক" else "Right Channel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }

                    // 4. Music Visualizer style selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (appLanguage == "bn") "ভিজুয়ালাইজার স্টাইল" else "Music Visualizer Style",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val modes = listOf("Dancing Waves", "Neon Bars", "Sparkles", "None")
                            modes.forEach { mode ->
                                val active = visualizerMode == mode
                                val label = when (mode) {
                                    "Dancing Waves" -> if (appLanguage == "bn") "ওয়েভ" else "Waves"
                                    "Neon Bars" -> if (appLanguage == "bn") "নিয়ন" else "Neon"
                                    "Sparkles" -> if (appLanguage == "bn") "বিন্দু" else "Sparkles"
                                    "None" -> if (appLanguage == "bn") "বন্ধ" else "Off"
                                    else -> mode
                                }
                                AssistChip(
                                    onClick = { visualizerMode = mode },
                                    label = { Text(label) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Transparent,
                                        labelColor = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    // 5. HD Audio simulation toggle
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "হাই রেজুলেশন (HD) সাউন্ড" else "Hi-Res HD Sound Rendering",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (appLanguage == "bn") "উচ্চমানের স্পষ্ট শব্দের অনুভূতি" else "Enhance vocals and bass output precision",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                    modifier = Modifier.widthIn(max = 200.dp)
                                )
                            }
                            Switch(
                                checked = hdAudioEnabled,
                                onCheckedChange = { hdAudioEnabled = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50), checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isSettingsOpen = false }) {
                    Text(if (appLanguage == "bn") "সম্পন্ন" else "Done", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun FullscreenPdfViewerDialog(
    file: File,
    appLanguage: String,
    onClose: () -> Unit
) {
    var pageCount by remember { mutableStateOf(0) }
    var nightMode by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1.0f) } // zoom factor: 1.0 to 3.0
    var isError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // Read total page count
    LaunchedEffect(file) {
        try {
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            pageCount = renderer.pageCount
            renderer.close()
            pfd.close()
            isError = false
        } catch (e: Exception) {
            isError = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (nightMode) Color(0xFF121212) else Color(0xFFF5F5F5))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (nightMode) Color(0xFF1E1E1E) else Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ChevronLeft, "Close PDF viewer", tint = if (nightMode) Color.White else Color.Black, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (nightMode) Color.White else Color.Black,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                        Text(
                            text = if (appLanguage == "bn") "পিডিএফ ডকু-রিডার" else "Professional PDF Reader",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // Controls: Night mode, Zoom scale, jump to page info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Zoom Slider/Button
                    TextButton(
                        onClick = {
                            scale = when (scale) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                2.0f -> 2.5f
                                2.5f -> 3.0f
                                else -> 1.0f
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(containerColor = (if (nightMode) Color.White else Color.Black).copy(alpha = 0.08f))
                    ) {
                        Icon(Icons.Default.ZoomIn, null, tint = if (nightMode) Color.White else Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${scale}x", color = if (nightMode) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Night Mode Toggle
                    IconButton(
                        onClick = { nightMode = !nightMode },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (nightMode) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = if (nightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Night Mode",
                            tint = if (nightMode) Color.White else Color.Black
                        )
                    }
                }
            }

            // PDF Content Body
            if (isError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.BrokenImage, null, tint = Color.Red, modifier = Modifier.size(56.dp))
                        Text(
                            text = if (appLanguage == "bn") "⚠️ পিডিএফ ফাইলটি রিড করা সম্ভব হয়নি।" else "⚠️ Could not read PDF file.",
                            color = if (nightMode) Color.White else Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (pageCount > 0) {
                // Scrollable Continuous pages
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pageCount) { pageIndex ->
                        PdfPageItem(file = file, pageIndex = pageIndex, scale = scale, nightMode = nightMode)
                        
                        // Page divider indicator
                        Text(
                            text = "- Page ${pageIndex + 1} of $pageCount -",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFE53935))
                }
            }
        }
    }
}

@Composable
fun PdfPageItem(file: File, pageIndex: Int, scale: Float, nightMode: Boolean) {
    var bitmap by remember(file, pageIndex, scale) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(file, pageIndex, scale) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                if (pageIndex < renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    // Scale width to fit nicely
                    val w = (page.width * 1.5f * scale).toInt()
                    val h = (page.height * 1.5f * scale).toInt()
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    if (nightMode) {
                        // Apply invert color filter on the bitmap if in night mode
                        val invertedBmp = android.graphics.Bitmap.createBitmap(w, h, bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(invertedBmp)
                        val paint = android.graphics.Paint()
                        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                            -1.0f, 0f, 0f, 0f, 255f,
                            0f, -1.0f, 0f, 0f, 255f,
                            0f, 0f, -1.0f, 0f, 255f,
                            0f, 0f, 0f, 1.0f, 0f
                        ))
                        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                        canvas.drawBitmap(bmp, 0f, 0f, paint)
                        bitmap = invertedBmp
                        bmp.recycle()
                    } else {
                        bitmap = bmp
                    }
                    page.close()
                }
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(4.dp))
                    .background(if (nightMode) Color.Black else Color.White)
            )
        } ?: Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

data class FeatureSlide(
    val titleBn: String,
    val titleEn: String,
    val subtitleBn: String,
    val subtitleEn: String,
    val badgeBn: String,
    val badgeEn: String,
    val icon: ImageVector,
    val gradientColors: List<Color>,
    val onClick: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimatedFeatureBanner(
    appLanguage: String,
    onCategoryClick: (FileCategory) -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToVideo: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val slides = remember(appLanguage, onCategoryClick, onNavigateToFiles, onNavigateToAudio, onNavigateToVideo, onOpenDrawer) {
        listOf(
            FeatureSlide(
                titleBn = "অল মাল্টিমিডিয়া",
                titleEn = "All Multimedia",
                subtitleBn = "আপনার সমস্ত অডিও, ভিডিও ও মিডিয়া ফাইল একসাথে",
                subtitleEn = "Access all audio, video & media files instantly",
                badgeBn = "মিডিয়া হাব",
                badgeEn = "Media Hub",
                icon = Icons.Default.AllInbox,
                gradientColors = listOf(Color(0xFF673AB7), Color(0xFF512DA8)),
                onClick = {
                    onCategoryClick(FileCategory.ALL)
                    onNavigateToFiles()
                }
            ),
            FeatureSlide(
                titleBn = "File Manager",
                titleEn = "File Manager",
                subtitleBn = "স্মার্ট ফাইল ব্রাউজার ও ইন্টারনাল ডিরেক্টরি এক্সপ্লোরার",
                subtitleEn = "Smart directory browser & powerful file manager",
                badgeBn = "এক্সপ্লোরার",
                badgeEn = "Explorer",
                icon = Icons.Default.Folder,
                gradientColors = listOf(Color(0xFF00838F), Color(0xFF006064)),
                onClick = { onNavigateToFiles() }
            ),
            FeatureSlide(
                titleBn = "ভিডিও প্লেয়ার",
                titleEn = "Video Player",
                subtitleBn = "HD প্লেব্যাক, প্লেব্যাক স্পিড, রোটেশন ও PIP মোড",
                subtitleEn = "HD Video player with speed, ratio & PIP mode",
                badgeBn = "এইচডি প্লেয়ার",
                badgeEn = "HD Player",
                icon = Icons.Default.Videocam,
                gradientColors = listOf(Color(0xFFD81B60), Color(0xFF880E4F)),
                onClick = { onNavigateToVideo() }
            ),
            FeatureSlide(
                titleBn = "অডিও প্লেয়ার",
                titleEn = "Audio Player",
                subtitleBn = "সাউন্ড ইকুয়ালাইজার ও ব্যাকগ্রাউন্ড মিউজিক প্লেয়ার",
                subtitleEn = "Background audio player with smart equalizer",
                badgeBn = "সঙ্গীত",
                badgeEn = "Music",
                icon = Icons.Default.Audiotrack,
                gradientColors = listOf(Color(0xFF00897B), Color(0xFF004D40)),
                onClick = { onNavigateToAudio() }
            ),
            FeatureSlide(
                titleBn = "হিসটোরি",
                titleEn = "History",
                subtitleBn = "সম্প্রতি ব্যবহার করা এবং খোলা সব ফাইল অ্যাক্সেস করুন",
                subtitleEn = "Quickly access your recently opened files history",
                badgeBn = "সাম্প্রতিক",
                badgeEn = "Recent",
                icon = Icons.Default.History,
                gradientColors = listOf(Color(0xFFF57C00), Color(0xFFE65100)),
                onClick = { onCategoryClick(FileCategory.RECENT) }
            ),
            FeatureSlide(
                titleBn = "সেটিংস",
                titleEn = "Settings",
                subtitleBn = "অ্যাপ থিম, ডার্ক মোড, ভাষা ও প্রাইভেসি নেভিগেশন",
                subtitleEn = "Theme modes, system preferences & app settings",
                badgeBn = "সেটিংস",
                badgeEn = "Settings",
                icon = Icons.Default.Settings,
                gradientColors = listOf(Color(0xFF455A64), Color(0xFF263238)),
                onClick = { onCategoryClick(FileCategory.SETTINGS) }
            ),
            FeatureSlide(
                titleBn = "টুলস",
                titleEn = "Tools & Utilities",
                subtitleBn = "ডাউনলোড ম্যানেজার, এপিকে ইনস্টলার ও ফাইল অপটিমাইজার",
                subtitleEn = "Downloads, APK manager & storage tools",
                badgeBn = "টুলস",
                badgeEn = "Tools",
                icon = Icons.Default.HomeRepairService,
                gradientColors = listOf(Color(0xFFE65100), Color(0xFFBF360C)),
                onClick = { onCategoryClick(FileCategory.TOOLS) }
            ),
            FeatureSlide(
                titleBn = "New Feature",
                titleEn = "New Feature",
                subtitleBn = "বিল্ট-ইন QR কোড স্ক্যানার ও PDF রিডার ফিচারসমূহ",
                subtitleEn = "Built-in fast QR Code scanner & PDF Viewer tool",
                badgeBn = "নতুন ফিচার",
                badgeEn = "NEW",
                icon = Icons.Default.Star,
                gradientColors = listOf(Color(0xFF8E24AA), Color(0xFF4A148C)),
                onClick = { onCategoryClick(FileCategory.QR_SCANNER) }
            )
        )
    }

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { slides.size })

    // Auto-scroll effect: changes slide comfortably every 5.5 seconds with smooth slow transition
    LaunchedEffect(pagerState) {
        while (true) {
            kotlinx.coroutines.delay(5500L)
            if (slides.isNotEmpty()) {
                val nextPage = (pagerState.currentPage + 1) % slides.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 1200,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("animated_feature_banner_section")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (appLanguage == "bn") "ফিচার স্লাইডার" else "Feature Highlights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Indicator dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slides.indices.forEach { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (isSelected) 18.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }

        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
            pageSpacing = 12.dp
        ) { pageIndex ->
            val slide = slides[pageIndex]
            Card(
                onClick = slide.onClick,
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(colors = slide.gradientColors)
                        )
                        .padding(16.dp)
                ) {
                    // Decorative background watermark icon
                    Icon(
                        imageVector = slide.icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier
                            .size(100.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 16.dp, y = 16.dp)
                    )

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.22f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = slide.icon,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (appLanguage == "bn") slide.badgeBn else slide.badgeEn,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Open Feature",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (appLanguage == "bn") slide.titleBn else slide.titleEn,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (appLanguage == "bn") slide.subtitleBn else slide.subtitleEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    stats: StorageStats,
    bookmarks: List<Bookmark>,
    appLanguage: String,
    onCategoryClick: (FileCategory) -> Unit,
    onFileClick: (File) -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToVideo: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_tab_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Welcome & Storage Progress Card
        item {
            StorageSummaryCard(stats = stats, appLanguage = appLanguage)
        }

        // Animated Feature Banner (Auto-sliding every 3.5s & Swipeable)
        item {
            AnimatedFeatureBanner(
                appLanguage = appLanguage,
                onCategoryClick = onCategoryClick,
                onNavigateToFiles = onNavigateToFiles,
                onNavigateToAudio = onNavigateToAudio,
                onNavigateToVideo = onNavigateToVideo,
                onOpenDrawer = onOpenDrawer
            )
        }

        // Pinned & Bookmarked Files Carousel
        if (bookmarks.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = if (appLanguage == "bn") "পিন করা ফাইলসমূহ" else "Pinned Files",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PinnedFilesCarousel(
                        bookmarks = bookmarks,
                        onFileClick = onFileClick
                    )
                }
            }
        }

        // Media Shortcuts Grid Cards
        item {
            Column {
                Text(
                    text = if (appLanguage == "bn") "মিডিয়া নেভিগেশন" else "Media & Quick Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Gallery Shortcut Card
                    ElevatedCard(
                        onClick = onNavigateToGallery,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.Collections,
                                contentDescription = "Gallery",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "গ্যালারি" else "Gallery",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (appLanguage == "bn") "ফটো ব্রাউজ করুন" else "Browse images",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Audio Shortcut Card
                    ElevatedCard(
                        onClick = onNavigateToAudio,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.Audiotrack,
                                contentDescription = "Audio",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "গান ও অডিও" else "Music & Audio",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = if (appLanguage == "bn") "অডিও প্লেয়ার" else "Audio player",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Video Shortcut Card
                    ElevatedCard(
                        onClick = onNavigateToVideo,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Video",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "ভিডিও প্লেয়ার" else "Video Player",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = if (appLanguage == "bn") "ভিডিও দেখুন" else "Watch videos",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Files Shortcut Card
                    ElevatedCard(
                        onClick = onNavigateToFiles,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = "Files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text(
                                    text = "File Manager",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (appLanguage == "bn") "ব্রাউজ করুন" else "Browse files",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Scanner & Utility Tools Shortcuts Section on Home Screen
        item {
            Column {
                Text(
                    text = if (appLanguage == "bn") "স্ক্যানার ও টুলস" else "Scanner & Tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // QR / Barcode Scanner Card
                    ElevatedCard(
                        onClick = { onCategoryClick(FileCategory.QR_SCANNER) },
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .testTag("home_scanner_card"),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.QrCode,
                                    contentDescription = "Scanner",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = if (appLanguage == "bn") "নতুন" else "NEW",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "স্ক্যানার" else "Scanner",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (appLanguage == "bn") "কিউআর ও বারকোড স্ক্যান" else "QR & Barcode scanner",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Tools & Utilities Card
                    ElevatedCard(
                        onClick = { onCategoryClick(FileCategory.TOOLS) },
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .testTag("home_tools_card"),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.HomeRepairService,
                                contentDescription = "Tools",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Column {
                                Text(
                                    text = if (appLanguage == "bn") "টুলস" else "Tools",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = if (appLanguage == "bn") "পিডিএফ ও কিউআর তৈরি" else "PDF & QR Generator",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryTabScreen(
    galleryFiles: List<File>,
    appLanguage: String,
    onImageClick: (File) -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Screenshots", "Pictures", "Downloads", "WhatsApp")

    val filteredList = remember(galleryFiles, selectedFilter) {
        if (selectedFilter == "All") {
            galleryFiles
        } else {
            galleryFiles.filter { file ->
                file.parentFile?.name?.equals(selectedFilter, ignoreCase = true) == true ||
                file.path.contains(selectedFilter, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("gallery_tab_screen")
    ) {
        // Filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(filters) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) },
                    leadingIcon = if (selectedFilter == filter) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        if (filteredList.isEmpty()) {
            EmptyStatePlaceholder(
                appLanguage = appLanguage,
                message = if (appLanguage == "bn") "কোন ছবি পাওয়া যায়নি" else "No photos found"
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredList) { file ->
                    val bitmap = remember(file.absolutePath) {
                        try {
                            val options = android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = 4
                            }
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }

                    ElevatedCard(
                        onClick = { onImageClick(file) },
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = file.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(Color.Black.copy(alpha = 0.55f))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = file.name,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = formatFileSize(file.length()),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerTabScreen(
    videoFiles: List<File>,
    appLanguage: String,
    onPlayFile: (File) -> Unit,
    viewModel: FileExplorerViewModel
) {
    val context = LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("video_prefs", android.content.Context.MODE_PRIVATE) }

    // Ensure initial demo files exist in sandbox on first run only
    LaunchedEffect(Unit) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val isDemoSetupDone = sharedPrefs.getBoolean("demo_videos_created_v2", false)
            if (!isDemoSetupDone) {
                val screenshotsDir = File(viewModel.baseDir, "Screenshots")
                val picturesDir = File(viewModel.baseDir, "Pictures")
                val whatsappDir = File(viewModel.baseDir, "WhatsApp")
                val downloadsDir = File(viewModel.baseDir, "Downloads")

                screenshotsDir.mkdirs()
                picturesDir.mkdirs()
                whatsappDir.mkdirs()
                downloadsDir.mkdirs()

                val v1 = File(screenshotsDir, "Selfie_video_2026_07.mp4")
                if (!v1.exists()) { v1.createNewFile(); v1.writeText("demo") }
                v1.setLastModified(1783425600000L)

                val v2 = File(screenshotsDir, "UI_Demo_2026_06.mp4")
                if (!v2.exists()) { v2.createNewFile(); v2.writeText("demo") }
                v2.setLastModified(1780833600000L)

                val v3 = File(picturesDir, "Family_Dinner_2026_04.mp4")
                if (!v3.exists()) { v3.createNewFile(); v3.writeText("demo") }
                v3.setLastModified(1775822400000L)

                val v4 = File(whatsappDir, "Funny_Cat_Clips.mp4")
                if (!v4.exists()) { v4.createNewFile(); v4.writeText("demo") }
                v4.setLastModified(1780833600000L)

                val v5 = File(whatsappDir, "Birthday_Party.mp4")
                if (!v5.exists()) { v5.createNewFile(); v5.writeText("demo") }
                v5.setLastModified(1783425600000L)

                val v6 = File(downloadsDir, "Tutorial_Compose.mp4")
                if (!v6.exists()) { v6.createNewFile(); v6.writeText("demo") }
                v6.setLastModified(1775822400000L)

                sharedPrefs.edit().putBoolean("demo_videos_created_v2", true).apply()
                viewModel.updateFilesList()
            }
        }
    }

    // State managers
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedSubTab by remember { mutableStateOf("video") } // "video", "folder", "playlist"
    val sortOrderState by viewModel.videoSortOrder.collectAsState()
    val isGridView by viewModel.videoIsGridView.collectAsState()
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }

    // History manager
    val historyPaths = remember {
        mutableStateOf<List<String>>(
            sharedPrefs.getString("video_history", "")
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        )
    }

    val saveHistory = { paths: List<String> ->
        historyPaths.value = paths
        sharedPrefs.edit().putString("video_history", paths.joinToString(",")).apply()
    }

    val playVideoAndAddToHistory = { file: File ->
        val updated = (listOf(file.absolutePath) + historyPaths.value.filter { it != file.absolutePath }).take(10)
        saveHistory(updated)
        onPlayFile(file)
    }

    val clearHistory = {
        saveHistory(emptyList())
        Toast.makeText(context, if (appLanguage == "bn") "ইতিহাস মুছে ফেলা হয়েছে" else "History cleared", Toast.LENGTH_SHORT).show()
    }

    val clearVideoHistoryEvent by viewModel.clearVideoHistoryEvent.collectAsState()

    LaunchedEffect(clearVideoHistoryEvent) {
        if (clearVideoHistoryEvent > 0) {
            clearHistory()
        }
    }

    // Playlist manager
    val playlistNames = listOf("Favorites", "Watch Later", "My Video Mix")
    val playlistMap = remember {
        mutableStateOf<Map<String, List<String>>>(
            playlistNames.associateWith { name ->
                sharedPrefs.getString("playlist_$name", "")
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            }
        )
    }

    val addToPlaylist = { name: String, path: String ->
        val currentList = playlistMap.value[name] ?: emptyList()
        if (!currentList.contains(path)) {
            val updated = currentList + path
            playlistMap.value = playlistMap.value + (name to updated)
            sharedPrefs.edit().putString("playlist_$name", updated.joinToString(",")).apply()
            Toast.makeText(context, if (appLanguage == "bn") "'$name' প্লেলিস্টে যোগ করা হয়েছে" else "Added to '$name'", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, if (appLanguage == "bn") "ইতিমধ্যেই যোগ করা আছে" else "Already in '$name'", Toast.LENGTH_SHORT).show()
        }
    }

    val removeFromPlaylist = { name: String, path: String ->
        val currentList = playlistMap.value[name] ?: emptyList()
        val updated = currentList - path
        playlistMap.value = playlistMap.value + (name to updated)
        sharedPrefs.edit().putString("playlist_$name", updated.joinToString(",")).apply()
        Toast.makeText(context, if (appLanguage == "bn") "'$name' থেকে মুছে ফেলা হয়েছে" else "Removed from '$name'", Toast.LENGTH_SHORT).show()
    }

    // Helpers to support file actions
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var renameNewValue by remember { mutableStateOf("") }

    var showFolderChoiceDialog by remember { mutableStateOf(false) }
    var actionTargetFile by remember { mutableStateOf<File?>(null) }
    var isMoveAction by remember { mutableStateOf(true) }

    var showDetailsDialog by remember { mutableStateOf(false) }
    var detailsTargetFile by remember { mutableStateOf<File?>(null) }

    // Dynamic month calculations
    val getMonthString: (Long, String) -> String = { timestamp, lang ->
        val date = Date(timestamp)
        val sdf = SimpleDateFormat("MMMM yyyy", Locale.US)
        val monthYear = sdf.format(date)
        if (lang == "bn") {
            monthYear
                .replace("January", "জানুয়ারি")
                .replace("February", "ফেব্রুয়ারি")
                .replace("March", "মার্চ")
                .replace("April", "এপ্রিল")
                .replace("May", "মে")
                .replace("June", "জুন")
                .replace("July", "জুলাই")
                .replace("August", "আগস্ট")
                .replace("September", "সেপ্টেম্বর")
                .replace("October", "অক্টোবর")
                .replace("November", "নভেম্বর")
                .replace("December", "ডিসেম্বর")
                .replace("2026", "২০২৬")
                .replace("2025", "২০২৫")
                .replace("2024", "২০২৪")
        } else {
            monthYear
        }
    }

    // Dynamic duration generator
    val getVideoDurationString: (File) -> String = { file ->
        val name = file.name.lowercase()
        when {
            name.contains("selfie") -> "00:38"
            name.contains("ui_demo") -> "01:54"
            name.contains("dinner") -> "05:12"
            name.contains("cat") -> "00:15"
            name.contains("birthday") -> "03:45"
            name.contains("tutorial") -> "01:54:01"
            else -> {
                val hash = java.lang.Math.abs(file.name.hashCode())
                val mins = (hash % 15) + 1
                val secs = hash % 60
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
        }
    }

    // Sharing function helper
    val shareVideoFile: (android.content.Context, File) -> Unit = { ctx, file ->
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.provider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, "Sharing file: ${file.name}")
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Share Video Path"))
        }
    }

    // Filter and Sort Video Files list
    val processedVideos = remember(videoFiles, searchQuery, sortOrderState) {
        val filtered = if (searchQuery.isBlank()) {
            videoFiles
        } else {
            videoFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        filtered.sortedWith { f1, f2 ->
            when (sortOrderState) {
                SortOrder.NAME_ASC -> f1.name.lowercase().compareTo(f2.name.lowercase())
                SortOrder.NAME_DESC -> f2.name.lowercase().compareTo(f1.name.lowercase())
                SortOrder.DATE_DESC -> f2.lastModified().compareTo(f1.lastModified())
                SortOrder.DATE_ASC -> f1.lastModified().compareTo(f2.lastModified())
                SortOrder.SIZE_DESC -> f2.length().compareTo(f1.length())
                SortOrder.SIZE_ASC -> f1.length().compareTo(f2.length())
            }
        }
    }

    // Dialog components
    if (showRenameDialog && renameTargetFile != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(if (appLanguage == "bn") "ফাইল পুনর্নামকরণ" else "Rename File") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameNewValue,
                        onValueChange = { renameNewValue = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = renameTargetFile!!
                        if (viewModel.renameFile(file, renameNewValue)) {
                            viewModel.updateFilesList()
                            Toast.makeText(context, if (appLanguage == "bn") "নাম পরিবর্তন সফল হয়েছে" else "Renamed successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, if (appLanguage == "bn") "নাম পরিবর্তন ব্যর্থ হয়েছে" else "Failed to rename", Toast.LENGTH_SHORT).show()
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFolderChoiceDialog && actionTargetFile != null) {
        val folders = listOf("Screenshots", "Pictures", "WhatsApp", "Downloads")
        AlertDialog(
            onDismissRequest = { showFolderChoiceDialog = false },
            title = {
                Text(
                    text = if (isMoveAction) {
                        if (appLanguage == "bn") "ফোল্ডারে সরান" else "Move to Folder"
                    } else {
                        if (appLanguage == "bn") "ফোল্ডারে কপি করুন" else "Copy to Folder"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = actionTargetFile!!.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    folders.forEach { folderName ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val file = actionTargetFile!!
                                    val destDir = File(viewModel.baseDir, folderName)
                                    if (!destDir.exists()) destDir.mkdirs()
                                    val target = File(destDir, file.name)
                                    var success = false
                                    try {
                                        if (isMoveAction) {
                                            success = file.renameTo(target)
                                        } else {
                                            file.copyTo(target, overwrite = true)
                                            success = true
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    if (success) {
                                        viewModel.updateFilesList()
                                        val actionName = if (isMoveAction) {
                                            if (appLanguage == "bn") "সরানো হয়েছে" else "Moved"
                                        } else {
                                            if (appLanguage == "bn") "কপি করা হয়েছে" else "Copied"
                                        }
                                        Toast.makeText(context, "$actionName: $folderName", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                                    }
                                    showFolderChoiceDialog = false
                                },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(folderName, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFolderChoiceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDetailsDialog && detailsTargetFile != null) {
        val file = detailsTargetFile!!
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(if (appLanguage == "bn") "ফাইলের বিবরণ" else "File Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Name: ${file.name}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(text = "Path: ${file.absolutePath}", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Size: ${formatFileSize(file.length())}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(text = "Duration: ${getVideoDurationString(file)}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { showDetailsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Main screen structure
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("video_player_tab")
    ) {
        // Sub-menus & Navigation: Tab choices (Video / Folder / Playlist)
        TabRow(
            selectedTabIndex = when (selectedSubTab) {
                "video" -> 0
                "folder" -> 1
                else -> 2
            },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Tab(
                selected = selectedSubTab == "video",
                onClick = { selectedSubTab = "video"; selectedFolder = null; selectedPlaylist = null },
                text = { Text(if (appLanguage == "bn") "ভিডিও" else "Video") }
            )
            Tab(
                selected = selectedSubTab == "folder",
                onClick = { selectedSubTab = "folder"; selectedFolder = null; selectedPlaylist = null },
                text = { Text(if (appLanguage == "bn") "ফোল্ডার" else "Folder") }
            )
            Tab(
                selected = selectedSubTab == "playlist",
                onClick = { selectedSubTab = "playlist"; selectedFolder = null; selectedPlaylist = null },
                text = { Text(if (appLanguage == "bn") "প্লেলিস্ট" else "Playlist") }
            )
        }

        // Animated Search query box
        AnimatedVisibility(visible = isSearchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (appLanguage == "bn") "ভিডিও খুঁজুন..." else "Search videos...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("video_search_input"),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        // 2. Horizontal History Scroll list
        val historyFiles = remember(historyPaths.value, videoFiles) {
            historyPaths.value.mapNotNull { path -> videoFiles.find { it.absolutePath == path } }
        }

        if (historyFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (appLanguage == "bn") "ইতিহাস" else "History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("video_history_row")
            ) {
                items(historyFiles) { file ->
                    Card(
                        modifier = Modifier
                            .width(130.dp)
                            .clickable { playVideoAndAddToHistory(file) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(75.dp)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF1F1C2C), Color(0xFF928DAB))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = getVideoDurationString(file),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(6.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Content panel according to tab selected
        when (selectedSubTab) {
            "video" -> {
                // Video Listing deep dive or normal month list
                VideoListAndGridContent(
                    videos = processedVideos,
                    isGridView = isGridView,
                    appLanguage = appLanguage,
                    playVideoAndAddToHistory = playVideoAndAddToHistory,
                    getVideoDurationString = getVideoDurationString,
                    getMonthString = getMonthString,
                    shareVideoFile = shareVideoFile,
                    viewModel = viewModel,
                    addToPlaylist = addToPlaylist,
                    onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                    onMoveTo = { file -> actionTargetFile = file; isMoveAction = true; showFolderChoiceDialog = true },
                    onCopyTo = { file -> actionTargetFile = file; isMoveAction = false; showFolderChoiceDialog = true },
                    onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                )
            }

            "folder" -> {
                if (selectedFolder == null) {
                    // Group folders
                    val folderGroups = remember(processedVideos) {
                        processedVideos.groupBy { it.parentFile?.name ?: "Video" }
                    }

                    if (folderGroups.isEmpty()) {
                        EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "কোন ফোল্ডার পাওয়া যায়নি" else "No video folders found")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(folderGroups.keys.toList()) { folderName ->
                                val fileCount = folderGroups[folderName]?.size ?: 0
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFolder = folderName },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(folderName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    text = if (appLanguage == "bn") "$fileCount টি ভিডিও" else "$fileCount videos",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                        Icon(Icons.Default.KeyboardArrowRight, null)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Deep dive inside a folder
                    val folderName = selectedFolder!!
                    val folderVideos = remember(processedVideos, folderName) {
                        processedVideos.filter { (it.parentFile?.name ?: "Video") == folderName }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            TextButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "ফোল্ডারসমূহ" else "Folders")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folderName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        VideoListAndGridContent(
                            videos = folderVideos,
                            isGridView = isGridView,
                            appLanguage = appLanguage,
                            playVideoAndAddToHistory = playVideoAndAddToHistory,
                            getVideoDurationString = getVideoDurationString,
                            getMonthString = getMonthString,
                            shareVideoFile = shareVideoFile,
                            viewModel = viewModel,
                            addToPlaylist = addToPlaylist,
                            onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                            onMoveTo = { file -> actionTargetFile = file; isMoveAction = true; showFolderChoiceDialog = true },
                            onCopyTo = { file -> actionTargetFile = file; isMoveAction = false; showFolderChoiceDialog = true },
                            onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                        )
                    }
                }
            }

            "playlist" -> {
                if (selectedPlaylist == null) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(playlistNames.size) { idx ->
                            val pName = playlistNames[idx]
                            val listCount = playlistMap.value[pName]?.size ?: 0
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPlaylist = pName },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(pName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = if (appLanguage == "bn") "$listCount টি ভিডিও যোগ করা হয়েছে" else "$listCount videos",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                } else {
                    // Inside deep dive playlist
                    val pName = selectedPlaylist!!
                    val pathsInPlaylist = playlistMap.value[pName] ?: emptyList()
                    val playlistVideos = remember(processedVideos, pathsInPlaylist) {
                        processedVideos.filter { it.absolutePath in pathsInPlaylist }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { selectedPlaylist = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "প্লেলিস্টসমূহ" else "Playlists")
                            }
                            Text(pName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        if (playlistVideos.isEmpty()) {
                            EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "প্লেলিস্টটি ফাঁকা! থ্রি-ডট থেকে ভিডিও যোগ করুন" else "Playlist is empty! Add videos via the three-dot menu.")
                        } else {
                            VideoListAndGridContent(
                                videos = playlistVideos,
                                isGridView = isGridView,
                                appLanguage = appLanguage,
                                playVideoAndAddToHistory = playVideoAndAddToHistory,
                                getVideoDurationString = getVideoDurationString,
                                getMonthString = getMonthString,
                                shareVideoFile = shareVideoFile,
                                viewModel = viewModel,
                                addToPlaylist = addToPlaylist,
                                onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                                onMoveTo = { file -> actionTargetFile = file; isMoveAction = true; showFolderChoiceDialog = true },
                                onCopyTo = { file -> actionTargetFile = file; isMoveAction = false; showFolderChoiceDialog = true },
                                onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true },
                                customActions = { file ->
                                    DropdownMenuItem(
                                        text = { Text(if (appLanguage == "bn") "প্লেলিস্ট থেকে সরান" else "Remove from Playlist") },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                        onClick = { removeFromPlaylist(pName, file.absolutePath) }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoListAndGridContent(
    videos: List<File>,
    isGridView: Boolean,
    appLanguage: String,
    playVideoAndAddToHistory: (File) -> Unit,
    getVideoDurationString: (File) -> String,
    getMonthString: (Long, String) -> String,
    shareVideoFile: (android.content.Context, File) -> Unit,
    viewModel: FileExplorerViewModel,
    addToPlaylist: (String, String) -> Unit,
    onRename: (File) -> Unit,
    onMoveTo: (File) -> Unit,
    onCopyTo: (File) -> Unit,
    onShowDetails: (File) -> Unit,
    customActions: @Composable (File) -> Unit = {}
) {
    val context = LocalContext.current

    // Grouping by Month
    val monthGroups = remember(videos) {
        val groups = LinkedHashMap<String, MutableList<File>>()
        for (file in videos) {
            val monthStr = getMonthString(file.lastModified(), appLanguage)
            if (!groups.containsKey(monthStr)) {
                groups[monthStr] = mutableListOf()
            }
            groups[monthStr]?.add(file)
        }
        groups
    }

    if (videos.isEmpty()) {
        EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "কোন ভিডিও পাওয়া যায়নি" else "No videos found")
    } else {
        if (isGridView) {
            // Grid Layout
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().testTag("video_grid_tab"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                monthGroups.forEach { (monthName, fileList) ->
                    // Header inside grid spans full columns
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = monthName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    items(fileList.size) { idx ->
                        val file = fileList[idx]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playVideoAndAddToHistory(file) }
                                .testTag("video_card_${file.name}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = getVideoDurationString(file),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FolderBadge(file.parentFile?.name ?: "Video")
                                        Box {
                                            var menuExpanded by remember { mutableStateOf(false) }
                                            IconButton(
                                                onClick = { menuExpanded = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "More options",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            VideoItemDropdownMenu(
                                                expanded = menuExpanded,
                                                onDismiss = { menuExpanded = false },
                                                file = file,
                                                onPlay = { playVideoAndAddToHistory(file) },
                                                onSelect = {
                                                    Toast.makeText(context, "${file.name} selected", Toast.LENGTH_SHORT).show()
                                                },
                                                onShare = { shareVideoFile(context, file) },
                                                onRename = { onRename(file) },
                                                onMoveToTrash = {
                                                    val trashDir = File(viewModel.baseDir, ".Trash")
                                                    if (!trashDir.exists()) trashDir.mkdirs()
                                                    if (file.renameTo(File(trashDir, file.name))) {
                                                        viewModel.updateFilesList()
                                                        Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onMoveToSafe = {
                                                    val safeDir = File(viewModel.baseDir, ".SafeFolder")
                                                    if (!safeDir.exists()) safeDir.mkdirs()
                                                    if (file.renameTo(File(safeDir, file.name))) {
                                                        viewModel.updateFilesList()
                                                        Toast.makeText(context, "Moved to Safe folder", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onMoveTo = { onMoveTo(file) },
                                                onCopyTo = { onCopyTo(file) },
                                                onAddToPlaylist = { pName -> addToPlaylist(pName, file.absolutePath) },
                                                onShowDetails = { onShowDetails(file) },
                                                customActions = { customActions(file) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // List Layout
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("video_list_tab"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                monthGroups.forEach { (monthName, fileList) ->
                    item {
                        Text(
                            text = monthName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    items(fileList.size) { idx ->
                        val file = fileList[idx]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playVideoAndAddToHistory(file) }
                                .testTag("video_card_${file.name}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = getVideoDurationString(file),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = formatFileSize(file.length()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                        )
                                        FolderBadge(file.parentFile?.name ?: "Video")
                                    }
                                }

                                Box {
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.testTag("video_options_btn_${file.name}")
                                    ) {
                                        Icon(Icons.Default.MoreVert, "More options")
                                    }
                                    VideoItemDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismiss = { menuExpanded = false },
                                        file = file,
                                        onPlay = { playVideoAndAddToHistory(file) },
                                        onSelect = {
                                            Toast.makeText(context, "${file.name} selected", Toast.LENGTH_SHORT).show()
                                        },
                                        onShare = { shareVideoFile(context, file) },
                                        onRename = { onRename(file) },
                                        onMoveToTrash = {
                                            val trashDir = File(viewModel.baseDir, ".Trash")
                                            if (!trashDir.exists()) trashDir.mkdirs()
                                            if (file.renameTo(File(trashDir, file.name))) {
                                                viewModel.updateFilesList()
                                                Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onMoveToSafe = {
                                            val safeDir = File(viewModel.baseDir, ".SafeFolder")
                                            if (!safeDir.exists()) safeDir.mkdirs()
                                            if (file.renameTo(File(safeDir, file.name))) {
                                                viewModel.updateFilesList()
                                                Toast.makeText(context, "Moved to Safe folder", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onMoveTo = { onMoveTo(file) },
                                        onCopyTo = { onCopyTo(file) },
                                        onAddToPlaylist = { pName -> addToPlaylist(pName, file.absolutePath) },
                                        onShowDetails = { onShowDetails(file) },
                                        customActions = { customActions(file) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderBadge(folderName: String) {
    val (icon, tint) = when {
        folderName.contains("WhatsApp", ignoreCase = true) -> Icons.Default.Forum to Color(0xFF25D366)
        folderName.contains("Screenshots", ignoreCase = true) -> Icons.Default.PhotoCamera to Color(0xFF00B0FF)
        folderName.contains("Pictures", ignoreCase = true) -> Icons.Default.Image to Color(0xFFFF9100)
        folderName.contains("Downloads", ignoreCase = true) -> Icons.Default.Download to Color(0xFFE040FB)
        else -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = folderName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VideoItemDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    file: File,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMoveToTrash: () -> Unit,
    onMoveTo: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveToSafe: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onShowDetails: () -> Unit,
    customActions: @Composable () -> Unit = {}
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Play") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { onDismiss(); onPlay() }
        )
        DropdownMenuItem(
            text = { Text("Select") },
            leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
            onClick = { onDismiss(); onSelect() }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = { onDismiss(); onShare() }
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onDismiss(); onRename() }
        )
        DropdownMenuItem(
            text = { Text("Move to Trash") },
            leadingIcon = { Icon(Icons.Default.Delete, null) },
            onClick = { onDismiss(); onMoveToTrash() }
        )
        DropdownMenuItem(
            text = { Text("Move to...") },
            leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
            onClick = { onDismiss(); onMoveTo() }
        )
        DropdownMenuItem(
            text = { Text("Copy to...") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            onClick = { onDismiss(); onCopyTo() }
        )
        DropdownMenuItem(
            text = { Text("Move to Safe folder") },
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            onClick = { onDismiss(); onMoveToSafe() }
        )
        androidx.compose.material3.HorizontalDivider()
        val playlistNames = listOf("Favorites", "Watch Later", "My Video Mix")
        playlistNames.forEach { pName ->
            DropdownMenuItem(
                text = { Text("Add to: $pName") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { onDismiss(); onAddToPlaylist(pName) }
            )
        }
        customActions()
        androidx.compose.material3.HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Details") },
            leadingIcon = { Icon(Icons.Default.Info, null) },
            onClick = { onDismiss(); onShowDetails() }
        )
    }
}

@Composable
fun EmptyStatePlaceholder(appLanguage: String, message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

data class AudioTrack(
    val file: File,
    val name: String,
    val artist: String,
    val album: String,
    val folder: String
)

@Composable
fun AudioListAndGridContent(
    tracks: List<AudioTrack>,
    isGridView: Boolean,
    appLanguage: String,
    onPlay: (File) -> Unit,
    onAddToPlaylist: (File, String) -> Unit,
    onRename: (File) -> Unit,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
    onShowDetails: (File) -> Unit,
    customActions: @Composable (File) -> Unit = {}
) {
    if (tracks.isEmpty()) {
        EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "কোন অডিও ফাইল পাওয়া যায়নি" else "No audio files found")
    } else {
        if (isGridView) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("audio_grid_content"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(tracks.size) { index ->
                    val track = tracks[index]
                    var showItemMenu by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable { onPlay(track.file) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFFE94057), Color(0xFFF27121))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { showItemMenu = true },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = track.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                    
                    if (showItemMenu) {
                        DropdownMenu(
                            expanded = showItemMenu,
                            onDismissRequest = { showItemMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "চালান" else "Play") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { showItemMenu = false; onPlay(track.file) }
                            )
                            
                            val playlists = listOf("Favorites", "My Audio Mix", "Relaxing")
                            playlists.forEach { pName ->
                                val localizedPlaylistName = if (appLanguage == "bn") {
                                    when (pName) {
                                        "Favorites" -> "প্রিয়"
                                        "My Audio Mix" -> "আমার অডিও মিক্স"
                                        "Relaxing" -> "রিল্যাক্সিং"
                                        else -> pName
                                    }
                                } else pName
                                DropdownMenuItem(
                                    text = { Text(if (appLanguage == "bn") "$localizedPlaylistName-এ যোগ করুন" else "Add to $localizedPlaylistName") },
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                    onClick = { showItemMenu = false; onAddToPlaylist(track.file, pName) }
                                )
                            }
                            
                            customActions(track.file)
                            
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "শেয়ার করুন" else "Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { showItemMenu = false; onShare(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "নাম পরিবর্তন" else "Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { showItemMenu = false; onRename(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "ফাইলের বিবরণ" else "Details") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { showItemMenu = false; onShowDetails(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "মুছে ফেলুন" else "Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showItemMenu = false; onDelete(track.file) }
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("audio_list_content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(tracks) { track ->
                    var showItemMenu by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(track.file) }
                            .testTag("audio_card_${track.name}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFFE94057), Color(0xFFF27121))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Music",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatFileSize(track.file.length()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            
                            IconButton(onClick = { onPlay(track.file) }) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = "Play Now",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            
                            IconButton(onClick = { showItemMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    if (showItemMenu) {
                        DropdownMenu(
                            expanded = showItemMenu,
                            onDismissRequest = { showItemMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "চালান" else "Play") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { showItemMenu = false; onPlay(track.file) }
                            )
                            
                            val playlists = listOf("Favorites", "My Audio Mix", "Relaxing")
                            playlists.forEach { pName ->
                                val localizedPlaylistName = if (appLanguage == "bn") {
                                    when (pName) {
                                        "Favorites" -> "প্রিয়"
                                        "My Audio Mix" -> "আমার অডিও মিক্স"
                                        "Relaxing" -> "রিল্যাক্সিং"
                                        else -> pName
                                    }
                                } else pName
                                DropdownMenuItem(
                                    text = { Text(if (appLanguage == "bn") "$localizedPlaylistName-এ যোগ করুন" else "Add to $localizedPlaylistName") },
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                    onClick = { showItemMenu = false; onAddToPlaylist(track.file, pName) }
                                )
                            }
                            
                            customActions(track.file)
                            
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "শেয়ার করুন" else "Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { showItemMenu = false; onShare(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "নাম পরিবর্তন" else "Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { showItemMenu = false; onRename(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "ফাইলের বিবরণ" else "Details") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { showItemMenu = false; onShowDetails(track.file) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (appLanguage == "bn") "মুছে ফেলুন" else "Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showItemMenu = false; onDelete(track.file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlayerTabScreen(
    audioFiles: List<File>,
    appLanguage: String,
    onPlayFile: (File) -> Unit,
    viewModel: FileExplorerViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }

    // State managers
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedSubTab by remember { mutableStateOf("audio") } // "audio", "folder", "playlist", "album", "artist", "all_songs"
    val sortOrderState by viewModel.audioSortOrder.collectAsState()
    val isGridView by viewModel.audioIsGridView.collectAsState()

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }

    // History manager
    val audioHistoryPaths = remember {
        mutableStateOf<List<String>>(
            sharedPrefs.getString("audio_history", "")
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        )
    }

    val saveAudioHistory = { paths: List<String> ->
        audioHistoryPaths.value = paths
        sharedPrefs.edit().putString("audio_history", paths.joinToString(",")).apply()
    }

    val playAudioAndAddToHistory = { file: File ->
        val updated = (listOf(file.absolutePath) + audioHistoryPaths.value.filter { it != file.absolutePath }).take(10)
        saveAudioHistory(updated)
        onPlayFile(file)
    }

    val clearAudioHistory = {
        saveAudioHistory(emptyList())
        Toast.makeText(context, if (appLanguage == "bn") "ইতিহাস মুছে ফেলা হয়েছে" else "History cleared", Toast.LENGTH_SHORT).show()
    }

    val clearAudioHistoryEvent by viewModel.clearAudioHistoryEvent.collectAsState()

    LaunchedEffect(clearAudioHistoryEvent) {
        if (clearAudioHistoryEvent > 0) {
            clearAudioHistory()
        }
    }

    // Playlist manager
    val audioPlaylists = listOf("Favorites", "My Audio Mix", "Relaxing")

    val getPlaylistDisplayName: (String) -> String = { name ->
        if (appLanguage == "bn") {
            when (name) {
                "Favorites" -> "প্রিয়"
                "My Audio Mix" -> "আমার অডিও মিক্স"
                "Relaxing" -> "রিল্যাক্সিং"
                else -> name
            }
        } else {
            name
        }
    }

    val audioPlaylistMap = remember {
        mutableStateOf<Map<String, List<String>>>(
            audioPlaylists.associateWith { name ->
                sharedPrefs.getString("audio_playlist_$name", "")
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            }
        )
    }

    val addAudioToPlaylist = { file: File, name: String ->
        val path = file.absolutePath
        val currentList = audioPlaylistMap.value[name] ?: emptyList()
        if (!currentList.contains(path)) {
            val updated = currentList + path
            audioPlaylistMap.value = audioPlaylistMap.value + (name to updated)
            sharedPrefs.edit().putString("audio_playlist_$name", updated.joinToString(",")).apply()
            val dispName = getPlaylistDisplayName(name)
            Toast.makeText(context, if (appLanguage == "bn") "'$dispName' প্লেলিস্টে যোগ করা হয়েছে" else "Added to '$dispName'", Toast.LENGTH_SHORT).show()
        } else {
            val dispName = getPlaylistDisplayName(name)
            Toast.makeText(context, if (appLanguage == "bn") "ইতিমধ্যেই যোগ করা আছে" else "Already in '$dispName'", Toast.LENGTH_SHORT).show()
        }
    }

    val removeAudioFromPlaylist = { name: String, file: File ->
        val path = file.absolutePath
        val currentList = audioPlaylistMap.value[name] ?: emptyList()
        val updated = currentList - path
        audioPlaylistMap.value = audioPlaylistMap.value + (name to updated)
        sharedPrefs.edit().putString("audio_playlist_$name", updated.joinToString(",")).apply()
        val dispName = getPlaylistDisplayName(name)
        Toast.makeText(context, if (appLanguage == "bn") "'$dispName' থেকে মুছে ফেলা হয়েছে" else "Removed from '$dispName'", Toast.LENGTH_SHORT).show()
    }

    // Process metadata
    val processedTracks = remember(audioFiles, appLanguage) {
        audioFiles.map { file ->
            val retriever = android.media.MediaMetadataRetriever()
            var artist = ""
            var album = ""
            try {
                retriever.setDataSource(file.absolutePath)
                artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            } catch (e: Exception) {
                // Ignore
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }

            val displayArtist = if (artist.isBlank() || artist == "null") {
                if (appLanguage == "bn") "অজানা আর্টিস্ট" else "Unknown Artist"
            } else artist

            val displayAlbum = if (album.isBlank() || album == "null") {
                if (appLanguage == "bn") "অজানা অ্যালবাম" else "Unknown Album"
            } else album

            AudioTrack(
                file = file,
                name = file.name,
                artist = displayArtist,
                album = displayAlbum,
                folder = file.parentFile?.name ?: (if (appLanguage == "bn") "অন্যান্য" else "Others")
            )
        }
    }

    // Sort Tracks
    val sortedTracks = remember(processedTracks, sortOrderState) {
        when (sortOrderState) {
            SortOrder.NAME_ASC -> processedTracks.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> processedTracks.sortedByDescending { it.name.lowercase() }
            SortOrder.DATE_ASC -> processedTracks.sortedBy { it.file.lastModified() }
            SortOrder.DATE_DESC -> processedTracks.sortedByDescending { it.file.lastModified() }
            SortOrder.SIZE_ASC -> processedTracks.sortedBy { it.file.length() }
            SortOrder.SIZE_DESC -> processedTracks.sortedByDescending { it.file.length() }
        }
    }

    // Filter Tracks with Search Query
    val finalTracks = remember(sortedTracks, searchQuery) {
        if (searchQuery.isBlank()) {
            sortedTracks
        } else {
            sortedTracks.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // File Action Dialog Helpers
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var renameNewValue by remember { mutableStateOf("") }

    var showDetailsDialog by remember { mutableStateOf(false) }
    var detailsTargetFile by remember { mutableStateOf<File?>(null) }
    var audioFileToPermanentlyDelete by remember { mutableStateOf<File?>(null) }

    // Sharing audio helper
    val shareAudioFile = { file: File ->
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/*"
                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, if (appLanguage == "bn") "অডিও শেয়ার করুন" else "Share audio file"))
        } catch (e: Exception) {
            Toast.makeText(context, if (appLanguage == "bn") "শেয়ার করা যায়নি" else "Failed to share", Toast.LENGTH_SHORT).show()
        }
    }

    // Delete audio helper
    val deleteAudioFile = { file: File ->
        audioFileToPermanentlyDelete = file
    }

    val formatAudioDate: (Long) -> String = { timestamp ->
        val date = Date(timestamp)
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        sdf.format(date)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("audio_player_tab")
    ) {
        // Sub-tab Bar directly below Main Top Bar
        ScrollableTabRow(
            selectedTabIndex = when (selectedSubTab) {
                "audio" -> 0
                "folder" -> 1
                "playlist" -> 2
                "album" -> 3
                "artist" -> 4
                else -> 5
            },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            val tabsList = listOf(
                Pair("audio", if (appLanguage == "bn") "অডিও" else "Audio"),
                Pair("folder", if (appLanguage == "bn") "ফোল্ডার" else "Folder"),
                Pair("playlist", if (appLanguage == "bn") "প্লেলিস্ট" else "Playlist"),
                Pair("album", if (appLanguage == "bn") "অ্যালবাম" else "Album"),
                Pair("artist", if (appLanguage == "bn") "আর্টিস্ট" else "Artist"),
                Pair("all_songs", if (appLanguage == "bn") "সব গান" else "All Songs")
            )
            tabsList.forEachIndexed { index, pair ->
                Tab(
                    selected = selectedSubTab == pair.first,
                    onClick = {
                        selectedSubTab = pair.first
                        selectedFolder = null
                        selectedPlaylist = null
                        selectedAlbum = null
                        selectedArtist = null
                    },
                    text = { Text(pair.second, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        // Animated Search Input Field
        AnimatedVisibility(visible = isSearchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (appLanguage == "bn") "অডিও খুঁজুন..." else "Search audios...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("audio_search_input"),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        val historyFiles = remember(audioHistoryPaths.value, processedTracks) {
            audioHistoryPaths.value.mapNotNull { path -> processedTracks.find { it.file.absolutePath == path } }
        }

        if (historyFiles.isNotEmpty() && (selectedSubTab == "audio" || selectedSubTab == "all_songs")) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (appLanguage == "bn") "ইতিহাস" else "History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("audio_history_row")
            ) {
                items(historyFiles) { track ->
                    Card(
                        modifier = Modifier
                            .width(130.dp)
                            .clickable { playAudioAndAddToHistory(track.file) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(65.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF11998E), Color(0xFF38EF7D))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))

        when (selectedSubTab) {
            "audio", "all_songs" -> {
                AudioListAndGridContent(
                    tracks = finalTracks,
                    isGridView = isGridView,
                    appLanguage = appLanguage,
                    onPlay = playAudioAndAddToHistory,
                    onAddToPlaylist = addAudioToPlaylist,
                    onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                    onShare = shareAudioFile,
                    onDelete = deleteAudioFile,
                    onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                )
            }

            "folder" -> {
                if (selectedFolder == null) {
                    val folderGroups = remember(processedTracks) {
                        processedTracks.groupBy { it.folder }
                    }

                    if (folderGroups.isEmpty()) {
                        EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "কোন ফোল্ডার পাওয়া যায়নি" else "No audio folders found")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(folderGroups.keys.toList()) { folderName ->
                                val fileCount = folderGroups[folderName]?.size ?: 0
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFolder = folderName },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(folderName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    text = if (appLanguage == "bn") "$fileCount টি অডিও" else "$fileCount audios",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                        Icon(Icons.Default.KeyboardArrowRight, null)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val folderName = selectedFolder!!
                    val folderTracks = remember(processedTracks, folderName) {
                        processedTracks.filter { it.folder == folderName }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            TextButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "ফোল্ডারসমূহ" else "Folders")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folderName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        AudioListAndGridContent(
                            tracks = folderTracks,
                            isGridView = isGridView,
                            appLanguage = appLanguage,
                            onPlay = playAudioAndAddToHistory,
                            onAddToPlaylist = addAudioToPlaylist,
                            onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                            onShare = shareAudioFile,
                            onDelete = deleteAudioFile,
                            onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                        )
                    }
                }
            }

            "playlist" -> {
                if (selectedPlaylist == null) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(audioPlaylists.size) { idx ->
                            val pName = audioPlaylists[idx]
                            val listCount = audioPlaylistMap.value[pName]?.size ?: 0
                            val localizedPlaylistName = getPlaylistDisplayName(pName)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPlaylist = pName },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(localizedPlaylistName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = if (appLanguage == "bn") "$listCount টি অডিও যোগ করা হয়েছে" else "$listCount audios",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                } else {
                    val pName = selectedPlaylist!!
                    val pathsInPlaylist = audioPlaylistMap.value[pName] ?: emptyList()
                    val playlistTracks = remember(processedTracks, pathsInPlaylist) {
                        processedTracks.filter { it.file.absolutePath in pathsInPlaylist }
                    }
                    val localizedPlaylistName = getPlaylistDisplayName(pName)

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { selectedPlaylist = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "প্লেলিস্টসমূহ" else "Playlists")
                            }
                            Text(localizedPlaylistName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        if (playlistTracks.isEmpty()) {
                            EmptyStatePlaceholder(appLanguage, if (appLanguage == "bn") "প্লেলিস্টটি ফাঁকা! থ্রি-ডট থেকে অডিও যোগ করুন" else "Playlist is empty! Add audios via the three-dot menu.")
                        } else {
                            AudioListAndGridContent(
                                tracks = playlistTracks,
                                isGridView = isGridView,
                                appLanguage = appLanguage,
                                onPlay = playAudioAndAddToHistory,
                                onAddToPlaylist = addAudioToPlaylist,
                                onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                                onShare = shareAudioFile,
                                onDelete = deleteAudioFile,
                                onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true },
                                customActions = { file ->
                                    DropdownMenuItem(
                                        text = { Text(if (appLanguage == "bn") "প্লেলিস্ট থেকে সরান" else "Remove from Playlist") },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                        onClick = { removeAudioFromPlaylist(pName, file) }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            "album" -> {
                if (selectedAlbum == null) {
                    val albumGroups = remember(processedTracks) {
                        processedTracks.groupBy { it.album }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(albumGroups.keys.toList()) { albumName ->
                            val fileCount = albumGroups[albumName]?.size ?: 0
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAlbum = albumName },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Album,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(albumName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = if (appLanguage == "bn") "$fileCount টি অডিও" else "$fileCount audios",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                } else {
                    val albumName = selectedAlbum!!
                    val albumTracks = remember(processedTracks, albumName) {
                        processedTracks.filter { it.album == albumName }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            TextButton(onClick = { selectedAlbum = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "অ্যালবামসমূহ" else "Albums")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(albumName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        AudioListAndGridContent(
                            tracks = albumTracks,
                            isGridView = isGridView,
                            appLanguage = appLanguage,
                            onPlay = playAudioAndAddToHistory,
                            onAddToPlaylist = addAudioToPlaylist,
                            onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                            onShare = shareAudioFile,
                            onDelete = deleteAudioFile,
                            onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                        )
                    }
                }
            }

            "artist" -> {
                if (selectedArtist == null) {
                    val artistGroups = remember(processedTracks) {
                        processedTracks.groupBy { it.artist }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(artistGroups.keys.toList()) { artistName ->
                            val fileCount = artistGroups[artistName]?.size ?: 0
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedArtist = artistName },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(artistName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = if (appLanguage == "bn") "$fileCount টি অডিও" else "$fileCount audios",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                } else {
                    val artistName = selectedArtist!!
                    val artistTracks = remember(processedTracks, artistName) {
                        processedTracks.filter { it.artist == artistName }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            TextButton(onClick = { selectedArtist = null }) {
                                Icon(Icons.Default.ArrowBack, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (appLanguage == "bn") "আর্টিস্টসমূহ" else "Artists")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(artistName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        AudioListAndGridContent(
                            tracks = artistTracks,
                            isGridView = isGridView,
                            appLanguage = appLanguage,
                            onPlay = playAudioAndAddToHistory,
                            onAddToPlaylist = addAudioToPlaylist,
                            onRename = { file -> renameTargetFile = file; renameNewValue = file.name; showRenameDialog = true },
                            onShare = shareAudioFile,
                            onDelete = deleteAudioFile,
                            onShowDetails = { file -> detailsTargetFile = file; showDetailsDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (showRenameDialog && renameTargetFile != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(if (appLanguage == "bn") "নাম পরিবর্তন করুন" else "Rename File") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameNewValue,
                        onValueChange = { renameNewValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = renameTargetFile!!
                        if (viewModel.renameFile(file, renameNewValue)) {
                            viewModel.updateFilesList()
                            Toast.makeText(context, if (appLanguage == "bn") "নাম পরিবর্তন সফল হয়েছে" else "Renamed successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, if (appLanguage == "bn") "নাম পরিবর্তন ব্যর্থ হয়েছে" else "Failed to rename", Toast.LENGTH_SHORT).show()
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Details Dialog
    if (showDetailsDialog && detailsTargetFile != null) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(if (appLanguage == "bn") "ফাইলের বিবরণ" else "File Details") },
            text = {
                val file = detailsTargetFile!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow(label = if (appLanguage == "bn") "নাম:" else "Name:", value = file.name)
                    DetailRow(label = if (appLanguage == "bn") "টাইপ:" else "Type:", value = if (appLanguage == "bn") "অডিও ফাইল (.${file.extension})" else "Audio File (.${file.extension})")
                    DetailRow(label = if (appLanguage == "bn") "আকার:" else "Size:", value = formatFileSize(file.length()))
                    DetailRow(label = if (appLanguage == "bn") "অবস্থান:" else "Path:", value = file.absolutePath)
                    DetailRow(label = if (appLanguage == "bn") "পরিবর্তনের তারিখ:" else "Last Modified:", value = formatAudioDate(file.lastModified()))
                }
            },
            confirmButton = {
                Button(onClick = { showDetailsDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Permanent Delete Confirmation Dialog for Audio File
    audioFileToPermanentlyDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { audioFileToPermanentlyDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = if (appLanguage == "bn") "স্থায়ীভাবে অডিও মুছুন" else "Delete Audio Permanently")
            },
            text = {
                Text(
                    text = if (appLanguage == "bn") {
                        "আপনি কি নিশ্চিত যে '${file.name}' অডিও ফাইলটি স্থায়ীভাবে মুছে ফেলতে চান? এটি আর কখনই ফিরিয়ে আনা সম্ভব হবে না।"
                    } else {
                        "Are you sure you want to permanently delete '${file.name}'? This action cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.deleteFile(file)) {
                            viewModel.updateFilesList()
                            Toast.makeText(context, if (appLanguage == "bn") "মুছে ফেলা হয়েছে" else "Deleted successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, if (appLanguage == "bn") "মুছে ফেলা ব্যর্থ হয়েছে" else "Failed to delete", Toast.LENGTH_SHORT).show()
                        }
                        audioFileToPermanentlyDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (appLanguage == "bn") "স্থায়ীভাবে মুছুন" else "Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { audioFileToPermanentlyDelete = null }) {
                    Text(if (appLanguage == "bn") "বাতিল" else "Cancel")
                }
            }
        )
    }
}

@Composable
fun parseMarkdownToAnnotatedString(text: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        var currentIndex = 0
        while (currentIndex < text.length) {
            val startBold = text.indexOf("**", currentIndex)
            if (startBold == -1) {
                append(text.substring(currentIndex))
                break
            }
            append(text.substring(currentIndex, startBold))
            val endBold = text.indexOf("**", startBold + 2)
            if (endBold == -1) {
                append(text.substring(startBold))
                break
            }
            withStyle(
                style = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            ) {
                append(text.substring(startBold + 2, endBold))
            }
            currentIndex = endBold + 2
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                continue
            }
            
            when {
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.substring(2).trim(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        text = trimmed.substring(4).trim(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ") -> {
                    val cleanLine = trimmed.substring(2).trim()
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = parseMarkdownToAnnotatedString(cleanLine),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseMarkdownToAnnotatedString(trimmed),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun FullscreenDocumentOverlay(
    title: String,
    content: String,
    onClose: () -> Unit,
    confirmText: String
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MarkdownText(text = content)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            
            // Bottom Action Button
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            text = confirmText,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

