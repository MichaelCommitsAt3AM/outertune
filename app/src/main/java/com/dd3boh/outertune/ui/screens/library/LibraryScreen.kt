package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.*
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.*
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.*
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.Screens.LibraryFilter
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val context = LocalContext.current

    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    var viewType by rememberEnumPreference(LibraryViewTypeKey, LibraryViewType.GRID)
    val enabledFilters by rememberPreference(EnabledFiltersKey, defaultValue = DEFAULT_ENABLED_FILTERS)
    var filter by rememberEnumPreference(LibraryFilterKey, LibraryFilter.ALL)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)

    val (sortType, onSortTypeChange) = rememberEnumPreference(LibrarySortTypeKey, LibrarySortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LibrarySortDescendingKey, true)
    val (showLikedAndDownloadedPlaylist) = rememberPreference(ShowLikedAndDownloadedPlaylist, true)

    val allItems by viewModel.allItems.collectAsState()

    val isSyncingRemotePlaylists by viewModel.isSyncingRemotePlaylists.collectAsState()
    val isSyncingRemoteAlbums by viewModel.isSyncingRemoteAlbums.collectAsState()
    val isSyncingRemoteArtists by viewModel.isSyncingRemoteArtists.collectAsState()
    val isSyncingRemoteSongs by viewModel.isSyncingRemoteSongs.collectAsState()
    val isSyncingRemoteLikedSongs by viewModel.isSyncingRemoteLikedSongs.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val likedPlaylist = PlaylistEntity(id = "liked", name = stringResource(id = R.string.liked_songs))
    val downloadedPlaylist = PlaylistEntity(id = "downloaded", name = stringResource(id = R.string.downloaded_songs))

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop = backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    val filterString = when (filter) {
        LibraryFilter.ALBUMS -> stringResource(R.string.albums)
        LibraryFilter.ARTISTS -> stringResource(R.string.artists)
        LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
        LibraryFilter.SONGS -> stringResource(R.string.songs)
        LibraryFilter.FOLDERS -> stringResource(R.string.folders)
        LibraryFilter.ALL -> ""
    }

    val defaultFilter: Collection<Pair<LibraryFilter, String>> = Screens.getFilters(enabledFilters).map {
        when (it) {
            LibraryFilter.ALBUMS -> LibraryFilter.ALBUMS to stringResource(R.string.albums)
            LibraryFilter.ARTISTS -> LibraryFilter.ARTISTS to stringResource(R.string.artists)
            LibraryFilter.PLAYLISTS -> LibraryFilter.PLAYLISTS to stringResource(R.string.playlists)
            LibraryFilter.SONGS -> LibraryFilter.SONGS to stringResource(R.string.songs)
            LibraryFilter.FOLDERS -> LibraryFilter.FOLDERS to stringResource(R.string.folders)
            else -> LibraryFilter.ALL to stringResource(R.string.home)
        }
    }.filterNot { it.first == LibraryFilter.ALL }

    val chips = remember { SnapshotStateList<Pair<LibraryFilter, String>>() }
    var filterSelected by remember { mutableStateOf(filter) }

    LaunchedEffect(Unit) {
        if (filter == LibraryFilter.ALL) chips.addAll(defaultFilter) else chips.add(filter to filterString)
    }

    LaunchedEffect(filter) {
        if (filter == LibraryFilter.ALL) {
            defaultFilter.forEachIndexed { index, it ->
                if (!chips.contains(it)) chips.add(index, it)
            }
            filterSelected = LibraryFilter.ALL
        } else {
            filterSelected = filter
            chips.filter { it.first != filter }.onEach { if (chips.contains(it)) chips.remove(it) }
        }
    }

    val filterContent = @Composable {
        var showStoragePerm by remember {
            mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
        }

        Column {
            if (localLibEnable && showStoragePerm) {
                TextButton(
                    onClick = {
                        showStoragePerm = false
                        (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                    },
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        text = stringResource(R.string.missing_media_permission_warning),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            Row {
                ChipsLazyRow(
                    chips = chips,
                    currentValue = filter,
                    onValueUpdate = {
                        filter = if (filter == LibraryFilter.ALL) it else LibraryFilter.ALL
                    },
                    modifier = Modifier.weight(1f),
                    selected = { it == filterSelected },
                    isLoading = { filter ->
                        (filter == LibraryFilter.PLAYLISTS && isSyncingRemotePlaylists)
                                || (filter == LibraryFilter.ALBUMS && isSyncingRemoteAlbums)
                                || (filter == LibraryFilter.ARTISTS && isSyncingRemoteArtists)
                                || (filter == LibraryFilter.SONGS && (isSyncingRemoteSongs || isSyncingRemoteLikedSongs))
                    }
                )

                if (filter != LibraryFilter.SONGS && filter != LibraryFilter.FOLDERS) {
                    IconButton(
                        onClick = { viewType = viewType.toggle() },
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Icon(
                            imageVector = when (viewType) {
                                LibraryViewType.LIST -> Icons.AutoMirrored.Rounded.List
                                LibraryViewType.GRID -> Icons.Rounded.GridView
                            },
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }

    val headerContent = @Composable {
        SortHeader(
            sortType = sortType,
            sortDescending = sortDescending,
            onSortTypeChange = onSortTypeChange,
            onSortDescendingChange = onSortDescendingChange,
            sortTypeText = { sortType ->
                when (sortType) {
                    LibrarySortType.CREATE_DATE -> R.string.sort_by_create_date
                    LibrarySortType.NAME -> R.string.sort_by_name
                }
            },
            modifier = Modifier.padding(start = 16.dp)
        )
    }

    if (filter != LibraryFilter.ALL) {
        BackHandler { filter = LibraryFilter.ALL }
    }

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            when (viewType) {
                LibraryViewType.LIST -> lazyListState.animateScrollToItem(0)
                LibraryViewType.GRID -> lazyGridState.animateScrollToItem(0)
            }
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = pullRefreshState,
                isRefreshing = isSyncingRemotePlaylists || isSyncingRemoteAlbums || isSyncingRemoteArtists
                        || isSyncingRemoteSongs || isSyncingRemoteLikedSongs,
                onRefresh = { viewModel.syncAll(true) }
            ),
    ) {
        when (filter) {
            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(navController, libraryFilterContent = filterContent)
            LibraryFilter.ARTISTS -> LibraryArtistsScreen(navController, libraryFilterContent = filterContent)
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, libraryFilterContent = filterContent)
            LibraryFilter.SONGS -> LibrarySongsScreen(navController, libraryFilterContent = filterContent)
            LibraryFilter.FOLDERS -> LibraryFoldersScreen(navController, scrollBehavior, filterContent = filterContent)
            LibraryFilter.ALL -> {
                ScrollToTopManager(navController, lazyListState)
                when (viewType) {
                    LibraryViewType.LIST -> {
                        LazyColumn(
                            state = lazyListState,
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                        ) {
                            item(key = "filter", contentType = CONTENT_TYPE_HEADER) { filterContent() }
                            item(key = "header", contentType = CONTENT_TYPE_HEADER) { headerContent() }

                            if (showLikedAndDownloadedPlaylist) {
                                item(key = likedPlaylist.id) {
                                    AutoPlaylistListItem(
                                        playlist = likedPlaylist,
                                        thumbnail = Icons.Rounded.Favorite,
                                        modifier = Modifier.fillMaxWidth().clickable { navController.navigate("auto_playlist/${likedPlaylist.id}") }.animateItem()
                                    )
                                }
                                item(key = downloadedPlaylist.id) {
                                    AutoPlaylistListItem(
                                        playlist = downloadedPlaylist,
                                        thumbnail = Icons.Rounded.CloudDownload,
                                        modifier = Modifier.fillMaxWidth().clickable { navController.navigate("auto_playlist/${downloadedPlaylist.id}") }.animateItem()
                                    )
                                }
                            }

                            itemsIndexed(
                                items = allItems,
                                key = { _, item -> item.hashCode() }
                            ) { index, item ->
                                if (index >= allItems.size - 1) {
                                    LaunchedEffect(allItems.size) {
                                        viewModel.loadMoreItems()
                                    }
                                }

                                when (item) {
                                    is Playlist -> LibraryPlaylistListItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        playlist = item,
                                        modifier = Modifier.animateItem()
                                    )
                                    is Album -> LibraryAlbumListItem(
                                        navController = navController,
                                        menuState = menuState,
                                        album = item,
                                        isActive = item.id == mediaMetadata?.album?.id,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.animateItem()
                                    )
                                    is Artist -> LibraryArtistListItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        artist = item,
                                        modifier = Modifier.animateItem()
                                    )
                                    else -> {} // Handle Song or other types if necessary
                                }
                            }

                            item(key = "loading_indicator") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        LazyColumnScrollbar(state = lazyListState)
                    }

                    LibraryViewType.GRID -> {
                        LazyVerticalGrid(
                            state = lazyGridState,
                            columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                        ) {
                            item(key = "filter", span = { GridItemSpan(maxLineSpan) }) { filterContent() }
                            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { headerContent() }

                            if (showLikedAndDownloadedPlaylist) {
                                item(key = likedPlaylist.id) {
                                    AutoPlaylistGridItem(
                                        playlist = likedPlaylist,
                                        thumbnail = Icons.Rounded.Favorite,
                                        fillMaxWidth = true,
                                        modifier = Modifier.clickable { navController.navigate("auto_playlist/${likedPlaylist.id}") }.animateItem()
                                    )
                                }
                                item(key = downloadedPlaylist.id) {
                                    AutoPlaylistGridItem(
                                        playlist = downloadedPlaylist,
                                        thumbnail = Icons.Rounded.CloudDownload,
                                        fillMaxWidth = true,
                                        modifier = Modifier.clickable { navController.navigate("auto_playlist/${downloadedPlaylist.id}") }.animateItem()
                                    )
                                }
                            }

                            itemsIndexed(
                                items = allItems,
                                key = { _, item -> item.hashCode() }
                            ) { index, item ->
                                if (index >= allItems.size - 1) {
                                    LaunchedEffect(allItems.size) {
                                        viewModel.loadMoreItems()
                                    }
                                }

                                when (item) {
                                    is Playlist -> LibraryPlaylistGridItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        playlist = item,
                                        modifier = Modifier.animateItem()
                                    )
                                    is Album -> LibraryAlbumGridItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        album = item,
                                        isActive = item.id == mediaMetadata?.album?.id,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.animateItem()
                                    )
                                    is Artist -> LibraryArtistGridItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        artist = item,
                                        modifier = Modifier.animateItem()
                                    )
                                    else -> {} // Handle other types
                                }
                            }

                            item(key = "loading_indicator", span = { GridItemSpan(maxLineSpan) }) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        LazyVerticalGridScrollbar(state = lazyGridState)
                    }
                }
            }
        }

        Indicator(
            isRefreshing = isSyncingRemotePlaylists || isSyncingRemoteAlbums || isSyncingRemoteArtists
                    || isSyncingRemoteSongs || isSyncingRemoteLikedSongs,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }
}