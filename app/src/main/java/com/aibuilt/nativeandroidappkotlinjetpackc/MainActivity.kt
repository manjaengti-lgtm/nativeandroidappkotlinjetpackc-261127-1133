package com.aibuilt.nativeandroidappkotlinjetpackc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Context.prefs by androidx.datastore.preferences.preferencesDataStore(name = "pornplayer")
private val Accent = Color(0xFFFF9000)
private val AppBackground = Color(0xFF0E0E10)
private val AppSurface = Color(0xFF18181B)
private val MainText = Color(0xFFF5F5F5)
private val MutedText = Color(0xFFA1A1AA)

enum class Site(val label: String, val base: String) {
    Pornhub("Pornhub", "https://www.pornhub.com"),
    XVideos("XVideos", "https://www.xvideos.com"),
    XNXX("XNXX", "https://www.xnxx.com");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: Pornhub
    }
}

data class Video(
    val url: String,
    val title: String,
    val thumbnail: String = "",
    val duration: String = "",
    val channel: String = "",
    val views: String = "",
    val hd: Boolean = false,
    val source: String = Site.Pornhub.name
)

interface Source {
    val site: Site
    suspend fun categoryList(): List<Category>
    suspend fun feed(category: String, page: Int, sort: String): List<Video>
    suspend fun search(query: String, page: Int): List<Video>
    suspend fun videoDetails(url: String): Video
}

private class HtmlSource(override val site: Site) : Source {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    private suspend fun html(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Referer", site.base)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Network request failed (${response.code})" }
            response.body?.string() ?: error("The source returned an empty page")
        }
    }

    private fun parseVideos(markup: String): List<Video> {
        val doc = Jsoup.parse(markup)
        val result = linkedMapOf<String, Video>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.absUrl("href")
            if (href.isBlank() || !(href.contains("view_video.php") ||
                        href.contains("/video") || href.contains("/xnxx/"))) continue
            val title = anchor.attr("title").ifBlank { anchor.text().trim() }
            if (title.length < 3) continue
            val image = anchor.selectFirst("img")
            val thumbnail = image?.let {
                it.attr("data-src").ifBlank {
                    it.attr("data-thumb_url").ifBlank { it.absUrl("src") }
                }
            }.orEmpty()
            val cardText = anchor.parent()?.text().orEmpty()
            val duration = anchor.parent()
                ?.selectFirst(".duration, .videoDuration, [class*=duration]")?.text().orEmpty()
            result[href] = Video(
                href, title, thumbnail, duration, "", cardText.take(60),
                cardText.contains("HD", ignoreCase = true), site.name
            )
        }
        return result.values.take(60)
    }

    override suspend fun categoryList(): List<Category> {
        val doc = Jsoup.parse(html(site.base), site.base)
        val result = doc.select("a[href]").mapNotNull { anchor ->
            val title = anchor.text().trim()
            val href = anchor.absUrl("href")
            if (title.length in 2..35 && href.contains(Regex("categories|category|/tags/|/tag/"))) {
                Category(
                    url = href,
                    title = title,
                    thumbnail = anchor.selectFirst("img")?.absUrl("src").orEmpty()
                )
            } else null
        }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
        check(result.isNotEmpty()) { "Source changed, try another" }
        return result
    }

    override suspend fun feed(category: String, page: Int, sort: String): List<Video> {
        val url = if (category.startsWith("http")) category else when (site) {
            Site.Pornhub -> "${site.base}/video?o=${if (sort == "Most viewed") "mv" else "mr"}&page=$page"
            Site.XVideos -> "${site.base}/?k=${URLEncoder.encode(category, "UTF-8")}&p=${page - 1}"
            Site.XNXX -> "${site.base}/search/${URLEncoder.encode(category, "UTF-8")}/$page"
        }
        return parseVideos(html(url))
    }

    override suspend fun search(query: String, page: Int): List<Video> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (site) {
            Site.Pornhub -> "${site.base}/video/search?search=$encoded&page=$page"
            Site.XVideos -> "${site.base}/?k=$encoded&p=${page - 1}"
            Site.XNXX -> "${site.base}/search/$encoded/$page"
        }
        return parseVideos(html(url))
    }

    override suspend fun videoDetails(url: String): Video {
        val doc = Jsoup.parse(html(url), url)
        val title = doc.selectFirst("h1")?.text()?.trim()
            .orEmpty().ifBlank { url.substringAfterLast('/') }
        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return Video(
            url = url,
            title = title,
            thumbnail = thumbnail,
            channel = doc.selectFirst(".usernameWrap, .uploader, [class*=uploader]")?.text().orEmpty(),
            views = doc.selectFirst("[class*=view]")?.text().orEmpty(),
            source = site.name
        )
    }
}

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val url: String,
    val title: String,
    val thumbnail: String,
    val duration: String,
    val source: String
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val url: String,
    val title: String,
    val thumbnail: String,
    val source: String,
    val timestamp: Long,
    val position: Long
)

@Entity(tableName = "search_queries")
data class SearchEntity(@PrimaryKey val query: String, val timestamp: Long)

@Dao
interface AppDao {
    @Query("SELECT * FROM favourites ORDER BY title COLLATE NOCASE")
    fun favourites(): Flow<List<FavouriteEntity>>

    @Upsert
    suspend fun saveFavourite(row: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE url = :url")
    suspend fun removeFavourite(url: String)

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun history(): Flow<List<HistoryEntity>>

    @Upsert
    suspend fun saveHistory(row: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT * FROM search_queries ORDER BY timestamp DESC LIMIT 15")
    fun searches(): Flow<List<SearchEntity>>

    @Upsert
    suspend fun saveSearch(row: SearchEntity)

    @Query("DELETE FROM search_queries WHERE query = :query")
    suspend fun removeSearch(query: String)

    @Query("DELETE FROM search_queries")
    suspend fun clearSearches()
}

@Database(
    entities = [FavouriteEntity::class, HistoryEntity::class, SearchEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

class AppViewModel(private val appContext: Context) : ViewModel() {
    private val database = Room.databaseBuilder(
        appContext, AppDatabase::class.java, "pornplayer.db"
    ).build()
    val dao: AppDao = database.dao()

    private val _site = MutableStateFlow(Site.Pornhub)
    val site: StateFlow<Site> = _site.asStateFlow()
    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _remembered = MutableStateFlow(false)
    val remembered: StateFlow<Boolean> = _remembered.asStateFlow()
    private val _incognito = MutableStateFlow(false)
    val incognito: StateFlow<Boolean> = _incognito.asStateFlow()
    private val _quality = MutableStateFlow("Auto")
    val quality: StateFlow<String> = _quality.asStateFlow()
    private val _autoplay = MutableStateFlow(true)
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    private val _orientation = MutableStateFlow(false)
    val orientation: StateFlow<Boolean> = _orientation.asStateFlow()
    private val _recent = MutableStateFlow<List<SearchEntity>>(emptyList())
    val recent: StateFlow<List<SearchEntity>> = _recent.asStateFlow()

    private var page = 1
    private var currentCategory = ""

    init {
        viewModelScope.launch {
            val preferences = appContext.prefs.data.first()
            _remembered.value = preferences[androidx.datastore.preferences.core.booleanPreferencesKey("age_ok")] ?: false
            _site.value = Site.parse(preferences[androidx.datastore.preferences.core.stringPreferencesKey("source")])
            _quality.value = preferences[androidx.datastore.preferences.core.stringPreferencesKey("quality")] ?: "Auto"
            _autoplay.value = preferences[androidx.datastore.preferences.core.booleanPreferencesKey("autoplay")] ?: true
            _orientation.value = preferences[androidx.datastore.preferences.core.booleanPreferencesKey("orientation")] ?: false
            _incognito.value = preferences[androidx.datastore.preferences.core.booleanPreferencesKey("incognito")] ?: false
            launch { dao.searches().collect { _recent.value = it } }
        }
    }

    fun acceptAge(remember: Boolean) = viewModelScope.launch {
        if (remember) {
            appContext.prefs.edit {
                it[androidx.datastore.preferences.core.booleanPreferencesKey("age_ok")] = true
            }
        }
        _remembered.value = true
        load()
    }

    fun chooseSite(newSite: Site) = viewModelScope.launch {
        val previous = _site.value
        _site.value = newSite
        appContext.prefs.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("source")] = newSite.name
        }
        if (!load(currentCategory)) {
            _site.value = previous
            appContext.prefs.edit {
                it[androidx.datastore.preferences.core.stringPreferencesKey("source")] = previous.name
            }
        }
    }

    fun setPreference(key: String, value: Any) = viewModelScope.launch {
        appContext.prefs.edit {
            when (value) {
                is Boolean -> it[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value
                is String -> it[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
            }
        }
        when (key) {
            "incognito" -> _incognito.value = value as Boolean
            "quality" -> _quality.value = value as String
            "autoplay" -> _autoplay.value = value as Boolean
            "orientation" -> _orientation.value = value as Boolean
        }
    }

    suspend fun load(category: String = currentCategory): Boolean {
        currentCategory = category
        _loading.value = true
        _error.value = ""
        return try {
            _videos.value = HtmlSource(_site.value).feed(category, 1, "Newest")
            page = 1
            true
        } catch (failure: Exception) {
            _error.value = failure.message ?: "Unable to load videos"
            false
        } finally {
            _loading.value = false
        }
    }

    fun loadVideos() = viewModelScope.launch { load() }

    fun loadMore() = viewModelScope.launch {
        if (_loading.value) return@launch
        _loading.value = true
        try {
            _videos.value = _videos.value + HtmlSource(_site.value)
                .feed(currentCategory, page + 1, "Newest")
            page++
            _error.value = ""
        } catch (failure: Exception) {
            _error.value = failure.message ?: "Unable to load videos"
        } finally {
            _loading.value = false
        }
    }

    fun loadCategories() = viewModelScope.launch {
        _loading.value = true
        _error.value = ""
        try {
            _categories.value = HtmlSource(_site.value).categoryList()
        } catch (failure: Exception) {
            _error.value = failure.message ?: "Unable to load categories"
        } finally {
            _loading.value = false
        }
    }

    fun search(query: String) = viewModelScope.launch {
        if (query.isBlank()) return@launch
        _loading.value = true
        _error.value = ""
        try {
            _videos.value = HtmlSource(_site.value).search(query.trim(), 1)
            page = 1
            dao.saveSearch(SearchEntity(query.trim(), System.currentTimeMillis()))
        } catch (failure: Exception) {
            _error.value = failure.message ?: "Search failed"
        } finally {
            _loading.value = false
        }
    }

    fun clearSearches() = viewModelScope.launch { dao.clearSearches() }
    fun removeSearch(query: String) = viewModelScope.launch { dao.removeSearch(query) }
    fun clearHistory() = viewModelScope.launch { dao.clearHistory() }

    fun record(video: Video) = viewModelScope.launch {
        if (!_incognito.value) {
            dao.saveHistory(
                HistoryEntity(
                    video.url, video.title, video.thumbnail, video.source,
                    System.currentTimeMillis(), 0
                )
            )
        }
    }

    fun favourite(video: Video) = viewModelScope.launch {
dao.saveFavourite(
FavouriteEntity(video.url, video.title, video.thumbnail, video.duration, video.source)
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(14, 14, 16)
        window.navigationBarColor = android.graphics.Color.rgb(14, 14, 16)
        setContent { PornPlayerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PornPlayerApp() {
    val context = LocalContext.current
    val vm: AppViewModel = viewModel(
        factory = androidx.lifecycle.viewmodel.viewModelFactory {
            initializer {
                AppViewModel(context.applicationContext)
            }
        }
    )
    val accepted by vm.remembered.collectAsState()
    val nav = rememberNavController()
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color.Black,
        background = AppBackground,
        surface = AppSurface,
        onBackground = MainText,
        onSurface = MainText,
        secondary = Accent
    )) {
        if (!accepted) {
            AgeGate(vm::acceptAge)
        } else {
            Scaffold(
                containerColor = AppBackground,
                bottomBar = {
                    val entry by nav.currentBackStackEntryAsState()
                    val route = entry?.destination?.route ?: "home"
                    val routes = listOf("home", "categories", "search", "favourites", "settings")
                    val labels = listOf("Home", "Categories", "Search", "Favourites", "Settings")
                    val icons = listOf(
                        Icons.Default.Home, Icons.Default.Category, Icons.Default.Search,
                        Icons.Default.Star, Icons.Default.Settings
                    )
                    NavigationBar(containerColor = AppSurface) {
                        routes.indices.forEach { index ->
                            NavigationBarItem(
                                selected = route == routes[index],
                                onClick = {
                                    nav.navigate(routes[index]) {
                                        launchSingleTop = true
                                        popUpTo("home")
                                    }
                                },
                                icon = {
                                    BadgedBox(badge = {
                                        if (route == routes[index]) Badge(containerColor = Accent)
                                    }) {
                                        Icon(icons[index], contentDescription = labels[index])
                                    }
                                },
                                label = { Text(labels[index]) }
                            )
                        }
                    }
                }
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.padding(padding)
                ) {
                    composable("home") { HomeScreen(vm, nav) }
                    composable("categories") { CategoriesScreen(vm, nav) }
                    composable("search") { SearchScreen(vm, nav) }
                    composable("favourites") { FavouritesScreen(vm, nav) }
                    composable("settings") { SettingsScreen(vm, nav) }
                    composable("history") { HistoryScreen(vm, nav) }
                    composable(
                        "category/{url}/{title}",
                        arguments = listOf(
                            navArgument("url") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType }
                        )
                    ) { entry ->
                        val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
                        val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                        LaunchedEffect(url) { vm.load(url) }
                        VideoGrid(vm, nav, title)
                    }
                    composable(
                        "video/{url}",
                        arguments = listOf(navArgument("url") { type = NavType.StringType })
                    ) { entry ->
                        VideoScreen(Uri.decode(entry.arguments?.getString("url").orEmpty()), vm, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgeGate(onEnter: (Boolean) -> Unit) {
    var rememberMe by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(AppBackground).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("18+ only", color = MainText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "This app contains adult content. You must be at least 18 years old to continue.",
            color = MutedText
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberMe, { rememberMe = it })
            Text("Remember me", color = MainText)
        }
        Button(
            onClick = { onEnter(rememberMe) },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Enter", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(title: String, vm: AppViewModel, nav: NavController, history: Boolean = false) {
    val incognito by vm.incognito.collectAsState()
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold)
                if (incognito) {
                    Spacer(Modifier.size(8.dp))
                    Text("INCOGNITO", color = Accent, fontSize = 10.sp)
                }
            }
        },
        actions = {
            if (history) {
                IconButton(onClick = { nav.navigate("history") }) {
                    Icon(Icons.Default.History, contentDescription = "Open history")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AppBackground,
            titleContentColor = MainText
        )
    )
}

@Composable
private fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val site by vm.site.collectAsState()
    LaunchedEffect(Unit) { vm.loadVideos() }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        AppTopBar("PornPlayer", vm, nav, history = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Site.entries.forEach { item ->
                FilterChip(
                    selected = item == site,
                    onClick = { vm.chooseSite(item) },
                    label = { Text(item.label) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        VideoGrid(vm, nav, "Home")
    }
}

@Composable
private fun VideoGrid(vm: AppViewModel, nav: NavController, title: String) {
    val videos by vm.videos.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        if (title != "Home") AppTopBar(title, vm, nav)
        if (error.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Unable to load videos", color = MainText)
                Text(error, color = MutedText)
                Button(onClick = { vm.loadVideos() }) { Text("Retry") }
                TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
            }
        }
        if (videos.isEmpty() && loading) {
            LoadingTiles()
        } else if (videos.isEmpty() && error.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found", color = MutedText)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 165.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos, key = { it.url }) { video -> VideoCard(video, nav, vm) }
                item {
                    TextButton(onClick = { vm.loadMore() }) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
private fun LoadingTiles() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 165.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(8) {
            Column(Modifier.padding(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(105.dp)
                        .background(AppSurface, RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(.8f).height(14.dp).background(AppSurface))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(.45f).height(10.dp).background(AppSurface))
            }
        }
    }
}

@Composable
private fun VideoCard(video: Video, nav: NavController, vm: AppViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier.padding(6.dp).clickable { nav.navigate("video/${Uri.encode(video.url)}") }
    ) {
        Box {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = "Thumbnail: ${video.title}",
                modifier = Modifier.fillMaxWidth().height(105.dp)
                    .background(AppSurface, RoundedCornerShape(10.dp))
            )
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("•••", color = MainText)
            }
            if (video.hd) {
                Text(
                    "HD",
                    Modifier.align(Alignment.TopStart).padding(5.dp)
                        .background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
                    color = Color.Black
                )
            }
            if (video.duration.isNotBlank()) {
                Text(
                    video.duration,
                    Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(4.dp),
                    color = MainText
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = { menuOpen = false; nav.navigate("video/${Uri.encode(video.url)}") }
                )
                DropdownMenuItem(text = { Text("Favourite") }, onClick = {
                    vm.favourite(video)
                    menuOpen = false
                })
                DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                    menuOpen = false
                    openBrowser(context, video.url)
                })
                DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Video link", video.url))
                    menuOpen = false
                })
                DropdownMenuItem(text = { Text("Share") }, onClick = {
                    share(context, video.url)
                    menuOpen = false
                })
            }
        }
        Text(video.title, color = MainText, maxLines = 2, minLines = 2, fontSize = 13.sp)
        Text(
            "${video.channel.ifBlank { video.source }}  ${video.views}",
            color = MutedText,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun CategoriesScreen(vm: AppViewModel, nav: NavController) {
    val categories by vm.categories.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(Unit) { vm.loadCategories() }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        AppTopBar("Categories", vm, nav)
        when {
            error.isNotBlank() -> {
                Text(error, Modifier.padding(16.dp), color = MutedText)
                Button(onClick = { vm.loadCategories() }) { Text("Retry") }
                TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
            }
            loading && categories.isEmpty() -> CircularProgressIndicator(
                Modifier.align(Alignment.CenterHorizontally), color = Accent
            )
            categories.isEmpty() -> Text("No categories available", Modifier.padding(20.dp), color = MutedText)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(categories, key = { it.url }) { category ->
                    Card(
                        Modifier.padding(6.dp).height(110.dp).clickable {
                            nav.navigate("category/${Uri.encode(category.url)}/${Uri.encode(category.title)}")
                        },
                        colors = CardDefaults.cardColors(containerColor = AppSurface)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            AsyncImage(
                                category.thumbnail, contentDescription = category.title,
                                modifier = Modifier.fillMaxSize()
                            )
                            Text(
                                category.title,
                                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(10.dp),
                                color = MainText
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: AppViewModel, nav: NavController) {
    val recent by vm.recent.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val videos by vm.videos.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        AppTopBar("Search", vm, nav)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("Search videos") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                vm.search(query)
            }),
            trailingIcon = {
                IconButton(onClick = { keyboard?.hide(); vm.search(query) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Accent)
                }
            }
        )
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent searches", Modifier.padding(12.dp), color = MainText)
                TextButton(onClick = { vm.clearSearches() }) { Text("Clear all") }
            }
            recent.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        query = item.query
                        vm.search(item.query)
                    }.padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.query, color = MutedText)
                    TextButton(onClick = { vm.removeSearch(item.query) }) { Text("Remove") }
                }
            }
        } else if (loading) {
            LoadingTiles()
        } else if (error.isNotBlank()) {
            Column(Modifier.padding(20.dp)) {
                Text(error, color = MutedText)
                Button(onClick = { vm.search(query) }) { Text("Retry") }
                TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
            }
        } else if (videos.isEmpty()) {
            Text("Nothing found", Modifier.padding(20.dp), color = MutedText)
        } else {
            VideoGrid(vm, nav, "")
        }
    }
}

@Composable
private fun FavouritesScreen(vm: AppViewModel, nav: NavController) {
    val rows by vm.dao.favourites().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        AppTopBar("Favourites", vm, nav)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Your favourites will appear here", color = MutedText)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(165.dp), contentPadding = PaddingValues(8.dp)) {
                items(rows, key = { it.url }) { row ->
                    VideoCard(
                        Video(row.url, row.title, row.thumbnail, row.duration, source = row.source),
                        nav, vm
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(vm: AppViewModel, nav: NavController) {
    val rows by vm.dao.history().collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTopBar("History", vm, nav)
            TextButton(onClick = { confirm = true }) { Text("Clear all") }
        }
        if (rows.isEmpty()) {
            Text("No watch history", Modifier.padding(20.dp), color = MutedText)
        } else {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        nav.navigate("video/${Uri.encode(row.url)}")
                    }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        row.thumbnail, "Video thumbnail",
                        Modifier.size(88.dp, 54.dp)
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(row.title, color = MainText, maxLines = 2)
                        Text(row.source, color = MutedText)
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Accent.copy(alpha = .5f)))
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear history?") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirm = false }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val site by vm.site.collectAsState()
    val incognito by vm.incognito.collectAsState()
    val quality by vm.quality.collectAsState()
    val autoplay by vm.autoplay.collectAsState()
    val orientation by vm.orientation.collectAsState()
    var qualityExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(AppBackground).verticalScroll(rememberScrollState())
    ) {
        AppTopBar("Settings", vm, nav)
        Text("Source", Modifier.padding(16.dp), color = Accent, fontWeight = FontWeight.Bold)
        Site.entries.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.chooseSite(item) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(item == site, onClick = { vm.chooseSite(item) })
                Text(item.label, color = MainText)
            }
        }
        Text("Playback", Modifier.padding(16.dp), color = Accent, fontWeight = FontWeight.Bold)
        TextButton(onClick = { qualityExpanded = true }) { Text("Preferred quality: $quality") }
        DropdownMenu(qualityExpanded, { qualityExpanded = false }) {
            listOf("Auto", "1080p", "720p", "480p", "360p").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { vm.setPreference("quality", option); qualityExpanded = false }
                )
            }
        }
        SettingToggle("Autoplay related videos", autoplay) { vm.setPreference("autoplay", it) }
        SettingToggle("Orientation lock", orientation) { vm.setPreference("orientation", it) }
        Text("Privacy", Modifier.padding(16.dp), color = Accent, fontWeight = FontWeight.Bold)
        SettingToggle("Incognito", incognito) { vm.setPreference("incognito", it) }
        TextButton(onClick = { nav.navigate("history") }) { Text("Open history") }
        TextButton(onClick = { vm.clearHistory() }) { Text("Clear history") }
        TextButton(onClick = {
            Thread {
                runCatching { context.cacheDir.deleteRecursively() }
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.start()
        }) { Text("Clear cache") }
        Text("About", Modifier.padding(16.dp), color = Accent, fontWeight = FontWeight.Bold)
        Text("PornPlayer • videos belong to their sites", Modifier.padding(16.dp), color = MutedText)
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MainText)
        Switch(checked, onChange)
    }
}

@Composable
private fun VideoScreen(url: String, vm: AppViewModel, nav: NavController) {
    val context = LocalContext.current
    val site by vm.site.collectAsState()
    var video by remember(url) { mutableStateOf(Video(url, "Loading video…", source = site.name)) }
    var error by remember(url) { mutableStateOf("") }
    var web by remember(url) { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(url, site) {
        try {
            video = withContext(Dispatchers.IO) { HtmlSource(site).videoDetails(url) }
            vm.record(video)
            error = ""
        } catch (_: Exception) {
            error = "Unable to load video information"
        }
    }
    BackHandler(web) {
        if (webView?.canGoBack() == true) webView?.goBack() else web = false
    }

    Column(Modifier.fillMaxSize().background(AppBackground).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
        ) {
            if (web) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    return target.startsWith("market:") ||
                                        target.contains("play.google.com/store")
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    evaluateJavascript(
                                        "(function(){document.querySelectorAll('iframe[src*=ad],iframe[src*=track],[class*=banner],[class*=popup]').forEach(x=>x.remove());window.open=function(){};var v=document.querySelector('video');if(v){v.play().catch(function(){});}})();",
                                        null
                                    )
                                }
                            }
                            loadUrl(video.url, mapOf("Referer" to site.base))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                TextButton(
                    onClick = { webView?.reload() },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) { Text("Reload", color = MainText) }
                TextButton(
                    onClick = { openBrowser(context, video.url) },
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) { Text("Open in browser", color = MainText) }
            } else {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (error.isNotBlank()) error else "Play this video on its source website",
                        color = MainText
                    )
                    Button(onClick = { web = true }) { Text("Play video") }
                    if (error.isNotBlank()) {
                        TextButton(onClick = {
                            error = ""
                            web = true
                        }) { Text("Retry") }
                    }
                }
            }
        }
        Text(
            video.title, Modifier.padding(16.dp),
            color = MainText, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        Text("${video.channel}  ${video.views}", Modifier.padding(horizontal = 16.dp), color = MutedText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { vm.favourite(video) }) {
                Icon(Icons.Default.Star, contentDescription = "Favourite", tint = Accent)
            }
            IconButton(onClick = { share(context, video.url) }) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = MainText)
            }
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Video link", video.url))
            }) {
                Icon(Icons.Default.Category, contentDescription = "Copy link", tint = MainText)
            }
            IconButton(onClick = { openBrowser(context, video.url) }) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser", tint = MainText)
            }
            IconButton(onClick = { error = ""; web = false }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh source link", tint = MainText)
            }
        }
        Text("Related videos", Modifier.padding(16.dp), color = Accent)
        val related by vm.videos.collectAsState()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(165.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)
        ) {
            items(related.filter { it.url != url }.take(12), key = { it.url }) {
                VideoCard(it, nav, vm)
            }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun share(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share video"))
    }
}
