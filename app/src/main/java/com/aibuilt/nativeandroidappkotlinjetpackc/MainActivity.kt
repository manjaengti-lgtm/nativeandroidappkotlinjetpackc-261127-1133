package com.aibuilt.nativeandroidappkotlinjetpackc

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.room.Upsert
import androidx.room.Room
import androidx.room.RoomDatabase
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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

private val android.content.Context.prefs by preferencesDataStore(name = "pornplayer")
private val Orange = Color(0xFFFF9000)
private val Back = Color(0xFF0E0E10)
private val SurfaceDark = Color(0xFF18181B)
private val MainText = Color(0xFFF5F5F5)
private val Muted = Color(0xFFA1A1AA)

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

data class Category(val title: String, val url: String, val thumbnail: String = "")

interface Source {
    val site: Site
    suspend fun categoryList(): List<Category>
    suspend fun feed(category: String, page: Int, sort: String): List<Video>
    suspend fun search(query: String, page: Int): List<Video>
    suspend fun videoDetails(url: String): Video
}

private class HtmlSource(override val site: Site) : Source {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun html(url: String, referer: String = site.base): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                .header("Referer", referer).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string() ?: error("Empty response")
            }
        }

    private fun parseVideos(markup: String): List<Video> {
        val doc = Jsoup.parse(markup)
        val result = linkedMapOf<String, Video>()
        val anchors = doc.select("a[href]")
        for (a in anchors) {
            val href = a.attr("abs:href")
            if (!href.contains("/view_video.php") && !href.contains("/video") && !href.contains("/xnxx/")) continue
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (title.length < 3 || href.isBlank()) continue
            val image = a.selectFirst("img")
            val thumb = image?.let {
                it.attr("data-src").ifBlank { it.attr("data-thumb_url").ifBlank { it.attr("src") } }
            }.orEmpty()
            val card = a.parent()
            val text = card?.text().orEmpty()
            val duration = card?.selectFirst(".duration, .videoDuration, [class*=duration]")?.text().orEmpty()
            result[href] = Video(href, title, thumb, duration, "", text.take(40), text.contains("HD"), site.name)
        }
        return result.values.take(60)
    }

    override suspend fun categoryList(): List<Category> {
        val doc = Jsoup.parse(html(site.base))
        val found = doc.select("a[href]").mapNotNull { a ->
            val title = a.text().trim()
            val href = a.attr("abs:href")
            if (title.length in 2..35 && (href.contains("categories") || href.contains("category") ||
                        href.contains("/tags/") || href.contains("/tag/"))) {
                Category(title, href, a.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
                }.orEmpty())
            } else null
        }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
        if (found.isEmpty()) error("Source changed")
        return found
    }

    override suspend fun feed(category: String, page: Int, sort: String): List<Video> {
        val url = if (category.startsWith("http")) category else when (site) {
            Site.Pornhub -> "${site.base}/video?o=${if (sort == "Most viewed") "mv" else "mr"}&page=$page"
            Site.XVideos -> "${site.base}/?k=${URLEncoder.encode(category, "UTF-8")}&p=${page - 1}"
            Site.XNXX -> "${site.base}/search/${URLEncoder.encode(category, "UTF-8")}/$page"
        }
        val videos = parseVideos(html(url))
        if (videos.isEmpty()) error("Source changed")
        return videos
    }

    override suspend fun search(query: String, page: Int): List<Video> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = when (site) {
            Site.Pornhub -> "${site.base}/video/search?search=$q&page=$page"
            Site.XVideos -> "${site.base}/?k=$q&p=${page - 1}"
            Site.XNXX -> "${site.base}/search/$q/$page"
        }
        return parseVideos(html(url))
    }

    override suspend fun videoDetails(url: String): Video {
        val doc = Jsoup.parse(html(url, site.base), url)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { url.substringAfterLast('/') }
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val stream = doc.selectFirst("meta[property=og:video], meta[property=og:video:url]")?.attr("content")
        return Video(url, title, image, channel = doc.selectFirst(".usernameWrap, .uploader, [class*=uploader]")?.text().orEmpty(),
            views = doc.selectFirst("[class*=view]")?.text().orEmpty(), source = site.name).copy(
            thumbnail = image.ifBlank { stream.orEmpty() }
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
    fun favourites(): kotlinx.coroutines.flow.Flow<List<FavouriteEntity>>
    @Upsert suspend fun saveFavourite(row: FavouriteEntity)
    @Query("DELETE FROM favourites WHERE url = :url") suspend fun removeFavourite(url: String)
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun history(): kotlinx.coroutines.flow.Flow<List<HistoryEntity>>
    @Upsert suspend fun saveHistory(row: HistoryEntity)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("SELECT * FROM search_queries ORDER BY timestamp DESC LIMIT 15")
    fun searches(): kotlinx.coroutines.flow.Flow<List<SearchEntity>>
    @Upsert suspend fun saveSearch(row: SearchEntity)
    @Query("DELETE FROM search_queries WHERE query = :query") suspend fun removeSearch(query: String)
    @Query("DELETE FROM search_queries") suspend fun clearSearches()
}

@Database(entities = [FavouriteEntity::class, HistoryEntity::class, SearchEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

class AppViewModel(private val context: android.content.Context) : ViewModel() {
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "pornplayer.db").build()
    val dao = database.dao()
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
            val values = context.prefs.data.first()
            _remembered.value = values[booleanPreferencesKey("age_ok")] ?: false
            _site.value = Site.parse(values[stringPreferencesKey("source")])
            _quality.value = values[stringPreferencesKey("quality")] ?: "Auto"
            _autoplay.value = values[booleanPreferencesKey("autoplay")] ?: true
            _orientation.value = values[booleanPreferencesKey("orientation")] ?: false
            _incognito.value = values[booleanPreferencesKey("incognito")] ?: false
            launch { dao.searches().collect { _recent.value = it } }
        }
    }

    fun acceptAge(remember: Boolean) = viewModelScope.launch {
        if (remember) context.prefs.edit { it[booleanPreferencesKey("age_ok")] = true }
        _remembered.value = remember
        load()
    }

    fun chooseSite(site: Site) = viewModelScope.launch {
        val old = _site.value
        _site.value = site
        context.prefs.edit { it[stringPreferencesKey("source")] = site.name }
        if (!load()) {
            _site.value = old
            context.prefs.edit { it[stringPreferencesKey("source")] = old.name }
        }
    }

    fun setPreference(key: String, value: Any) = viewModelScope.launch {
        context.prefs.edit {
            when (value) {
                is Boolean -> it[booleanPreferencesKey(key)] = value
                is String -> it[stringPreferencesKey(key)] = value
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
            val rows = HtmlSource(_site.value).feed(category, 1, "Newest")
            _videos.value = rows
            page = 1
            _loading.value = false
            true
        } catch (e: Exception) {
            _error.value = e.message ?: "Unable to load videos"
            _loading.value = false
            false
        }
    }

    fun load() = viewModelScope.launch { load(currentCategory) }
    fun more() = viewModelScope.launch {
        if (_loading.value) return@launch
        _loading.value = true
        try {
            val rows = HtmlSource(_site.value).feed(currentCategory, page + 1, "Newest")
            _videos.value = _videos.value + rows
            page++
        } catch (e: Exception) { _error.value = e.message ?: "Unable to load videos" }
        _loading.value = false
    }

    fun categories() = viewModelScope.launch {
        _loading.value = true
        _error.value = ""
        try { _categories.value = HtmlSource(_site.value).categoryList() }
        catch (e: Exception) { _error.value = e.message ?: "Unable to load categories" }
        _loading.value = false
    }

    fun search(query: String) = viewModelScope.launch {
        if (query.isBlank()) return@launch
        _loading.value = true
        _error.value = ""
        try {
            _videos.value = HtmlSource(_site.value).search(query, 1)
            page = 1
            dao.saveSearch(SearchEntity(query.trim(), System.currentTimeMillis()))
        } catch (e: Exception) { _error.value = e.message ?: "Search failed" }
        _loading.value = false
    }

    fun record(video: Video) = viewModelScope.launch {
        if (!_incognito.value) dao.saveHistory(
            HistoryEntity(video.url, video.title, video.thumbnail, video.source, System.currentTimeMillis(), 0)
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

@Composable
fun PornPlayerApp() {
    val context = LocalContext.current
    val vm: AppViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.Factory {
        AppViewModel(context.applicationContext)
    })
    val remembered by vm.remembered.collectAsState()
    val nav = rememberNavController()
    val colors = darkColorScheme(
        primary = Orange, onPrimary = Color.Black, background = Back, surface = SurfaceDark,
        onBackground = MainText, onSurface = MainText, secondary = Orange
    )
    MaterialTheme(colorScheme = colors) {
        if (!remembered) AgeGate { vm.acceptAge(it) }
        else {
            Scaffold(
                containerColor = Back,
                bottomBar = {
                    val entry by nav.currentBackStackEntryAsState()
                    val route = entry?.destination?.route ?: "home"
                    NavigationBar(containerColor = SurfaceDark) {
                        listOf("Home", "Categories", "Search", "Favourites", "Settings").forEachIndexed { index, label ->
                            val routes = listOf("home", "categories", "search", "favourites", "settings")
                            val icons = listOf(Icons.Default.Home, Icons.Default.Category, Icons.Default.Search,
                                Icons.Default.Star, Icons.Default.Settings)
                            NavigationBarItem(
                                selected = route == routes[index],
                                onClick = { nav.navigate(routes[index]) { launchSingleTop = true; popUpTo("home") } },
                                icon = { BadgedBox(badge = { if (route == routes[index]) Badge(containerColor = Orange) {} }) {
                                    Icon(icons[index], contentDescription = label)
                                } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { HomeScreen(vm, nav) }
                    composable("categories") { CategoriesScreen(vm, nav) }
                    composable("search") { SearchScreen(vm, nav) }
                    composable("favourites") { FavouritesScreen(vm, nav) }
                    composable("settings") { SettingsScreen(vm, nav) }
                    composable("history") { HistoryScreen(vm, nav) }
                    composable("category/{url}/{title}", arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType }
                    )) { entry ->
                        val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
                        val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                        LaunchedEffect(url) { vm.load(url) }
                        VideoGrid(vm, nav, title)
                    }
                    composable("video/{url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
                        VideoScreen(Uri.decode(entry.arguments?.getString("url").orEmpty()), vm, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgeGate(onEnter: (Boolean) -> Unit) {
    var remember by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Back).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("18+ only", color = MainText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("This app contains adult content. You must be at least 18 years old to continue.",
            color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(remember, { remember = it }, colors = CheckboxDefaults.colors(checkedColor = Orange))
            Text("Remember me", color = MainText)
        }
        Button(onClick = { onEnter(remember) }, modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange), shape = RoundedCornerShape(14.dp)) {
            Text("Enter", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppTopBar(title: String, vm: AppViewModel, nav: androidx.navigation.NavController, history: Boolean = false) {
    val site by vm.site.collectAsState()
    val incognito by vm.incognito.collectAsState()
    TopAppBar(
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold)
            if (incognito) { Spacer(Modifier.width(8.dp)); Text("INCOGNITO", color = Orange, fontSize = 10.sp) }
        } },
        actions = {
            if (history) IconButton(onClick = { nav.navigate("history") }) {
                Icon(Icons.Default.History, contentDescription = "Open history", tint = MainText)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Back, titleContentColor = MainText)
    )
}

@Composable
private fun HomeScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val site by vm.site.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    Column(Modifier.fillMaxSize().background(Back)) {
        AppTopBar("PornPlayer", vm, nav, true)
        Row(Modifier.fillMaxWidth().horizontalScrollCompat().padding(horizontal = 8.dp)) {
            Site.entries.forEach {
                FilterChip(selected = it == site, onClick = { vm.chooseSite(it) }, label = { Text(it.label) },
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        VideoGrid(vm, nav, "Home")
    }
}

private fun Modifier.horizontalScrollCompat(): Modifier = this.then(
    Modifier
)

@Composable
private fun VideoGrid(vm: AppViewModel, nav: androidx.navigation.NavController, title: String) {
    val videos by vm.videos.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize().background(Back)) {
        val scrollState = rememberScrollState()
        if (title != "Home") AppTopBar(title, vm, nav)
        if (error.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Unable to load videos", color = MainText)
                Text(error, color = Muted)
                Button(onClick = { vm.load() }) { Text("Retry") }
                TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
            }
        }
        if (videos.isEmpty() && loading) LoadingTiles()
        else if (videos.isEmpty() && error.isBlank()) Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No videos found", color = Muted)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 165.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos, key = { it.url }) { video -> VideoCard(video, nav) }
                item { TextButton(onClick = { vm.more() }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(video: Video, nav: androidx.navigation.NavController, favourite: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(6.dp).combinedClickable(onClick = {
        nav.navigate("video/${Uri.encode(video.url)}")
    }, onLongClick = { menu = true })) {
        Box {
            AsyncImage(model = video.thumbnail, contentDescription = "Thumbnail: ${video.title}",
                modifier = Modifier.fillMaxWidth().height(105.dp).background(SurfaceDark, RoundedCornerShape(10.dp)))
            if (video.hd) Text("HD", Modifier.align(Alignment.TopStart).padding(5.dp)
                .background(Orange, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp), color = Color.Black)
            if (video.duration.isNotBlank()) Text(video.duration, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(4.dp), color = MainText)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; nav.navigate("video/${Uri.encode(video.url)}") })
                DropdownMenuItem(text = { Text("Favourite") }, onClick = {
                    menu = false
                    favourite?.invoke()
                })
                DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                    menu = false
                    nav.contextOpen(video.url)
                })
                DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false })
            }
        }
        Text(video.title, color = MainText, maxLines = 2, minLines = 2, fontSize = 13.sp)
        Text("${video.channel.ifBlank { video.source }}  ${video.views}", color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun LoadingTiles() {
    LazyVerticalGrid(columns = GridCells.Adaptive(165.dp), contentPadding = PaddingValues(8.dp)) {
        items(8) {
            Column(Modifier.padding(6.dp)) {
                Box(Modifier.fillMaxWidth().height(105.dp).background(SurfaceDark, RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(.8f).height(14.dp).background(SurfaceDark))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(.45f).height(10.dp).background(SurfaceDark))
            }
        }
    }
}

@Composable
private fun CategoriesScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val categories by vm.categories.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(Unit) { vm.categories() }
    Column(Modifier.fillMaxSize().background(Back)) {
        AppTopBar("Categories", vm, nav)
        if (error.isNotBlank()) {
            Text(error, Modifier.padding(16.dp), color = Muted)
            Button(onClick = { vm.categories() }, Modifier.padding(16.dp)) { Text("Retry") }
            TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
        } else if (loading && categories.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Orange)
        else if (categories.isEmpty()) Text("No categories available", Modifier.padding(20.dp), color = Muted)
        else LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp)) {
            items(categories) { category ->
                Card(Modifier.padding(6.dp).height(110.dp).clickable {
                    nav.navigate("category/${Uri.encode(category.url)}/${Uri.encode(category.title)}")
                }, colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Box(Modifier.fillMaxSize(), Alignment.BottomStart) {
                        AsyncImage(category.thumbnail, contentDescription = category.title, modifier = Modifier.fillMaxSize())
                        Text(category.title, Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(10.dp), color = MainText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val recent by vm.recent.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Back)) {
        AppTopBar("Search", vm, nav)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("Search videos") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(query) }),
            trailingIcon = { IconButton(onClick = { keyboard?.hide(); vm.search(query) }) {
                Icon(Icons.Default.Search, "Search", tint = Orange)
            } })
        val loading by vm.loading.collectAsState()
        val error by vm.error.collectAsState()
        val videos by vm.videos.collectAsState()
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent searches", Modifier.padding(12.dp), color = MainText)
                TextButton(onClick = { vm.viewModelScopeSafeClearSearches() }) { Text("Clear all") }
            }
            recent.forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { query = item.query; vm.search(item.query) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.query, color = Muted)
                }
            }
        } else if (loading) LoadingTiles()
        else if (error.isNotBlank()) Column(Modifier.padding(20.dp)) {
            Text(error, color = Muted)
            Button(onClick = { vm.search(query) }) { Text("Retry") }
            TextButton(onClick = { nav.navigate("settings") }) { Text("Switch source") }
        } else if (videos.isEmpty()) Text("Nothing found", Modifier.padding(20.dp), color = Muted)
        else VideoGrid(vm, nav, "")
    }
}

private fun AppViewModel.viewModelScopeSafeClearSearches() {
    // The UI calls the public method below; kept separate to avoid coupling the view to storage.
    clearQueries()
}

fun AppViewModel.clearQueries() {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { dao.clearSearches() }
}

@Composable
private fun FavouritesScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val rows by vm.dao.favourites().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().background(Back)) {
        AppTopBar("Favourites", vm, nav)
        if (rows.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Your favourites will appear here", color = Muted) }
        else LazyVerticalGrid(columns = GridCells.Adaptive(165.dp), contentPadding = PaddingValues(8.dp)) {
            items(rows, key = { it.url }) { row ->
                VideoCard(Video(row.url, row.title, row.thumbnail, row.duration, source = row.source), nav) {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        vm.dao.saveFavourite(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val rows by vm.dao.history().collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Back)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTopBar("History", vm, nav)
            TextButton(onClick = { confirm = true }) { Text("Clear all") }
        }
        if (rows.isEmpty()) Text("No watch history", Modifier.padding(20.dp), color = Muted)
        else rows.forEach { row ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("video/${Uri.encode(row.url)}") }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(row.thumbnail, "Video thumbnail", Modifier.size(88.dp, 54.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(row.title, color = MainText, maxLines = 2)
                    Text(row.source, color = Muted)
                    Box(Modifier.fillMaxWidth().height(3.dp).background(SurfaceDark))
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear history?") },
        confirmButton = { TextButton(onClick = { kotlinx.coroutines.GlobalScope.launch { vm.dao.clearHistory() }; confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun SettingsScreen(vm: AppViewModel, nav: androidx.navigation.NavController) {
    val site by vm.site.collectAsState()
    val incognito by vm.incognito.collectAsState()
    val quality by vm.quality.collectAsState()
    val autoplay by vm.autoplay.collectAsState()
    val orientation by vm.orientation.collectAsState()
    Column(Modifier.fillMaxSize().background(Back).verticalScroll(rememberScrollState())) {
        AppTopBar("Settings", vm, nav)
        Text("Source", Modifier.padding(16.dp), color = Orange, fontWeight = FontWeight.Bold)
        Site.entries.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable { vm.chooseSite(item) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(item == site, onClick = { vm.chooseSite(item) }, colors = RadioButtonDefaults.colors(selectedColor = Orange))
                Text(item.label, color = MainText)
            }
        }
        HorizontalDivider()
        Text("Playback", Modifier.padding(16.dp), color = Orange, fontWeight = FontWeight.Bold)
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = true }) { Text("Preferred quality: $quality") }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("Auto", "1080p", "720p", "480p", "360p").forEach { q ->
                DropdownMenuItem(text = { Text(q) }, onClick = { vm.setPreference("quality", q); expanded = false })
            }
        }
        SettingToggle("Autoplay related videos", autoplay) { vm.setPreference("autoplay", it) }
        SettingToggle("Orientation lock", orientation) { vm.setPreference("orientation", it) }
        HorizontalDivider()
        Text("Privacy", Modifier.padding(16.dp), color = Orange, fontWeight = FontWeight.Bold)
        SettingToggle("Incognito", incognito) { vm.setPreference("incognito", it) }
        TextButton(onClick = { nav.navigate("history") }) { Text("Open history") }
        TextButton(onClick = { kotlinx.coroutines.GlobalScope.launch { vm.dao.clearHistory() } }) { Text("Clear history") }
        TextButton(onClick = { (contextForSettings.current as? ComponentActivity)?.cacheDir?.deleteRecursively() }) { Text("Clear cache") }
        HorizontalDivider()
        Text("About", Modifier.padding(16.dp), color = Orange, fontWeight = FontWeight.Bold)
        Text("PornPlayer • videos belong to their sites", Modifier.padding(16.dp), color = Muted)
    }
}

private val contextForSettings = compositionLocalOf<android.content.Context> {
    error("Context unavailable")
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MainText)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Orange))
    }
}

@Composable
private fun VideoScreen(url: String, vm: AppViewModel, nav: androidx.navigation.NavController) {
    val site by vm.site.collectAsState()
    var video by remember(url) { mutableStateOf(Video(url, "Loading video…", source = site.name)) }
    var error by remember(url) { mutableStateOf("") }
    var web by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, site) {
        try {
            video = withContext(Dispatchers.IO) { HtmlSource(site).videoDetails(url) }
            vm.record(video)
        } catch (e: Exception) { error = "Unable to load video information" }
    }
    BackHandler(web) { web = false }
    Column(Modifier.fillMaxSize().background(Back).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (web) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url?.toString().orEmpty()
                                return target.contains("market://") || target.contains("play.google.com/store")
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                evaluateJavascript(
                                    "(function(){document.querySelectorAll('iframe[src*=ad],iframe[src*=track],[class*=banner],[class*=popup]').forEach(x=>x.remove());window.open=function(){};var v=document.querySelector('video');if(v){v.play().catch(()=>{});v.requestFullscreen&&v.requestFullscreen().catch(()=>{});}})();",
                                    null
                                )
                            }
                        }
                        loadUrl(video.url, mapOf("Referer" to site.base, "User-Agent" to "Mozilla/5.0 Android"))
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (error.isNotBlank()) error else "Stream is provided by the source website", color = MainText)
                    Button(onClick = { web = true }) { Text("Play video") }
                }
            }
        }
        Text(video.title, Modifier.padding(16.dp), color = MainText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${video.channel}  ${video.views}", Modifier.padding(horizontal = 16.dp), color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = {
                kotlinx.coroutines.GlobalScope.launch {
                    vm.dao.saveFavourite(FavouriteEntity(video.url, video.title, video.thumbnail, video.duration, video.source))
                }
            }) { Icon(Icons.Default.Star, "Favourite", tint = Orange) }
            IconButton(onClick = { contextShare(video.url) }) { Icon(Icons.Default.Share, "Share", tint = MainText) }
            IconButton(onClick = { web = true }) { Icon(Icons.Default.OpenInBrowser, "Open in browser", tint = MainText) }
            IconButton(onClick = { error = ""; web = false }) { Icon(Icons.Default.Refresh, "Refresh source link", tint = MainText) }
        }
        if (error.isNotBlank()) TextButton(onClick = { error = ""; web = true }) { Text("Retry") }
        Text("Related videos", Modifier.padding(16.dp), color = Orange)
        val videos by vm.videos.collectAsState()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(165.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)
        ) {
            items(videos.filter { it.url != url }.take(12), key = { it.url }) { related ->
                VideoCard(related, nav)
            }
        }
    }
}

private fun androidx.navigation.NavController.contextOpen(url: String) {
    val context = contextForShare
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private val contextForShare = android.app.Application()

private fun contextShare(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        contextForShare.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}olumns = GridCells.Adaptive(165.dp), modifier = Modifier.height(500.dp), contentPadding = PaddingValues(8.dp)) {
            items(videos.filterNot { it.url == url }) { VideoCard(it, nav) }
        }
    }
}

private fun androidx.navigation.NavController.contextOpen(url: String) {
    // A safe browser intent is sent through the currently active Android context by the helper.
    ExternalActions.open(url)
}

private fun contextShare(url: String) = ExternalActions.share(url)

private object ExternalActions {
    var opener: ((String, Boolean) -> Unit)? = null
    fun open(url: String) { opener?.invoke(url, false) }
    fun share(url: String) { opener?.invoke(url, true) }
}
