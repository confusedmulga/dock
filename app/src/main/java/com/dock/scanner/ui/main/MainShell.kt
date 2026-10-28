package com.dock.scanner.ui.main

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dock.scanner.PreviewActivity
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.office.OfficeFormat
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.GlassIsland
import com.dock.scanner.ui.components.ItemDialogs
import com.dock.scanner.ui.components.LocalSharedOrigin
import com.dock.scanner.ui.components.MainTab
import com.dock.scanner.ui.components.PdfActions
import com.dock.scanner.ui.components.StatusBarBlur
import com.dock.scanner.ui.components.rememberItemDialogState
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.components.viewFile
import com.dock.scanner.ui.files.FilesScreen
import com.dock.scanner.ui.home.HomeScreen
import com.dock.scanner.ui.home.HomeTool
import com.dock.scanner.ui.images.ImagesScreen
import com.dock.scanner.ui.images.ImagesViewModel
import com.dock.scanner.ui.shelf.ShelfScreen
import com.dock.scanner.ui.tools.PdfTool
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Home (tools), Images (the image resizer), Files (every PDF and document) and Shelf
 * (folders), swiped between or picked on the floating glass island, plus the scan button.
 * Content scrolls underneath the island, which blurs whatever passes behind it.
 */
@Composable
fun MainShell(
    vm: LibraryViewModel,
    onScan: (folderId: String?) -> Unit,
    onOpenPdf: (LibraryItem) -> Unit,
    onOpenDocument: (LibraryItem) -> Unit,
    onEditPdf: (Uri, LibraryItem?) -> Unit,
    onImagesPicked: (List<Uri>) -> Unit,
    onOpenTool: (PdfTool, LibraryItem?) -> Unit,
    onChangeLocation: () -> Unit,
) {
    val context = LocalContext.current
    val tab by vm.tab.collectAsStateWithLifecycle()
    val hazeState = remember { HazeState() }
    val imagesVm: ImagesViewModel = viewModel()
    val pager = rememberPagerState(initialPage = tab.ordinal) { MainTab.entries.size }
    // Tab changes made elsewhere (the island, "See all", a tool opening the Shelf) move the pager...
    LaunchedEffect(tab) {
        if (pager.targetPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal, animationSpec = tween(280, easing = EaseOutCubic))
    }
    // ...and a swipe that settles on a page makes it the tab.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { vm.tab.value = MainTab.entries[it] }
    }
    val dialogs = rememberItemDialogState()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val shelfPath by vm.shelfPath.collectAsStateWithLifecycle()

    // Files may have changed in a file manager while we were away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(tab) { vm.refresh() }

    // Other tabs → Home on back (the Shelf first steps out of any open folder).
    BackHandler(enabled = tab != MainTab.Home && !(tab == MainTab.Shelf && shelfPath.isNotEmpty())) { vm.tab.value = MainTab.Home }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onImagesPicked(uris)
    }
    // Documents from anywhere on the phone open in the preview.
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.startActivity(PreviewActivity.intent(context, uri, null))
    }

    val actions = remember(vm) {
        PdfActions(
            onOpen = {
                when {
                    it.isPdf -> onOpenPdf(it)
                    it.isDocument -> onOpenDocument(it)
                    else -> viewFile(context, it)
                }
            },
            onShare = { shareDocument(context, it.uri, it.name, it.mimeType.ifEmpty { "application/pdf" }) },
            onRename = { dialogs.renaming = it },
            onMove = { dialogs.moving = it },
            onDelete = { dialogs.deleting = it },
            onEdit = { onEditPdf(it.uri, it) },
            onTool = onOpenTool,
        )
    }
    // Island (~58 dp) + its 18 dp gap + a little breathing room, above the gesture bar.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Swipe between tabs, as in braim: springy snapping, neighbours kept ready.
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = spring(dampingRatio = 0.86f, stiffness = 250f)),
                key = { MainTab.entries[it].name },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val current = MainTab.entries[page]
                CompositionLocalProvider(LocalSharedOrigin provides current.name) {
                    when (current) {
                        MainTab.Home -> HomeScreen(
                            vm = vm,
                            actions = actions,
                            bottomInset = bottomInset,
                            onTool = { tool ->
                                when (tool) {
                                    HomeTool.Scan -> onScan(null)
                                    HomeTool.Edit -> onOpenTool(PdfTool.Edit, null)
                                    HomeTool.Compress -> onOpenTool(PdfTool.Compress, null)
                                    HomeTool.ToImages -> onOpenTool(PdfTool.ToImages, null)
                                    HomeTool.Merge -> onOpenTool(PdfTool.Merge, null)
                                    HomeTool.Split -> onOpenTool(PdfTool.Split, null)
                                    HomeTool.ImagesToPdf -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    HomeTool.ViewDocuments -> pickDocument.launch(OfficeFormat.mimeTypes.toTypedArray())
                                }
                            },
                            onSeeAllFiles = { vm.tab.value = MainTab.Files },
                        )
                        MainTab.Images -> ImagesScreen(imagesVm, bottomInset, onShowFolder = vm::showInShelf)
                        MainTab.Files -> FilesScreen(vm, actions, bottomInset)
                        MainTab.Shelf -> ShelfScreen(vm, actions, dialogs, bottomInset, onChangeLocation)
                    }
                }
            }
        }

        StatusBarBlur(hazeState, Modifier.align(Alignment.TopCenter))

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The highlight moves as soon as a swipe or tap commits to a tab.
            GlassIsland(hazeState, selected = MainTab.entries[pager.targetPage], onSelect = { vm.tab.value = it })
            Spacer(Modifier.width(10.dp))
            GlassBubble(
                hazeState,
                painterResource(R.drawable.ic_camera),
                contentDescription = "Scan a document",
                onClick = { onScan(if (tab == MainTab.Shelf) vm.currentFolderId else null) },
            )
        }
    }

    ItemDialogs(dialogs, vm, destinations)
}
