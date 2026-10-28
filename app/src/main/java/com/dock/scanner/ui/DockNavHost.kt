package com.dock.scanner.ui

import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dock.scanner.ui.camera.CameraScreen
import com.dock.scanner.ui.tools.PdfTool
import com.dock.scanner.ui.tools.ToolScreen
import com.dock.scanner.ui.tools.ToolsViewModel
import androidx.compose.foundation.layout.fillMaxWidth
import com.dock.scanner.ui.components.BackSwipe
import com.dock.scanner.ui.components.LocalSharedTransitions
import com.dock.scanner.ui.components.popEnter
import com.dock.scanner.ui.components.popExit
import com.dock.scanner.ui.components.pushEnter
import com.dock.scanner.ui.components.pushExit
import com.dock.scanner.ui.editor.PageEditorScreen
import com.dock.scanner.ui.main.MainShell
import com.dock.scanner.ui.onboarding.StorageSetupScreen
import com.dock.scanner.ui.preview.DocumentPreviewScreen
import com.dock.scanner.ui.review.ReviewScreen
import com.dock.scanner.ui.viewer.PdfViewerScreen

private object Routes {
    const val SETUP = "setup"
    const val CHANGE_FOLDER = "change-folder"
    const val MAIN = "main"
    const val CAMERA = "camera?retake={retake}"
    const val REVIEW = "review"
    const val PAGE = "page/{id}"
    const val VIEWER = "viewer"
    const val DOCUMENT = "document"
    const val TOOL = "tool"
    fun camera(retakePageId: String? = null) = if (retakePageId == null) "camera" else "camera?retake=$retakePageId"
    fun page(id: String) = "page/$id"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DockNavHost() {
    val nav = rememberNavController()
    // Created outside the NavHost, so they're scoped to the activity and shared by all screens.
    val scanVm: ScanViewModel = viewModel()
    val libraryVm: LibraryViewModel = viewModel()
    val toolsVm: ToolsViewModel = viewModel()
    val opening by scanVm.opening.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val root by libraryVm.root.collectAsStateWithLifecycle()
    val start = remember { if (libraryVm.root.value == null) Routes.SETUP else Routes.MAIN }

    LaunchedEffect(scanVm) {
        scanVm.events.collect { if (it is UiEvent.Message) Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(libraryVm) {
        libraryVm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // An unsaved scan (e.g. the camera was closed with back) mustn't vanish when something else
    // starts a new session: ask first.
    var replacingDraft by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun newSession(start: () -> Unit) {
        if (scanVm.pages.value.isEmpty()) start() else replacingDraft = start
    }
    replacingDraft?.let { start ->
        AlertDialog(
            onDismissRequest = { replacingDraft = null },
            title = { Text("Discard unsaved pages?") },
            text = { Text("You have pages that haven't been saved yet. Starting something new deletes them.") },
            confirmButton = {
                TextButton(onClick = {
                    replacingDraft = null
                    scanVm.clear()
                    start()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    replacingDraft = null
                    nav.navigate(Routes.REVIEW)
                }) { Text("Review pages") }
            },
        )
    }

    // Folder access can be revoked from system settings: go back to setup instead of failing silently.
    LaunchedEffect(root) {
        val current = nav.currentBackStackEntry?.destination?.route
        if (root == null && current != null && current != Routes.SETUP) {
            nav.navigate(Routes.SETUP) { popUpTo(0) }
        }
    }

    opening?.let { progress ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("Opening PDF") },
            text = {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
        )
    }

    // Pages and thumbnails morph between screens (list → viewer, Review → editor).
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitions provides this) {
            NavHost(
                navController = nav,
                startDestination = start,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                composable(Routes.SETUP) { entry ->
                    BackSwipe(nav, entry) {
                        StorageSetupScreen(
                            libraryVm,
                            isChange = false,
                            onDone = { nav.navigate(Routes.MAIN) { popUpTo(Routes.SETUP) { inclusive = true } } },
                            onCancel = {},
                        )
                    }
                }
                composable(Routes.CHANGE_FOLDER) { entry ->
                    BackSwipe(nav, entry) {
                        StorageSetupScreen(libraryVm, isChange = true, onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
                    }
                }
                composable(Routes.MAIN) { entry ->
                    BackSwipe(nav, entry) {
                        MainShell(
                            vm = libraryVm,
                            onScan = { folderId ->
                                scanVm.targetFolderId = folderId
                                nav.navigate(Routes.camera())
                            },
                            onOpenPdf = {
                                libraryVm.view(it)
                                nav.navigate(Routes.VIEWER)
                            },
                            onOpenDocument = {
                                libraryVm.view(it)
                                nav.navigate(Routes.DOCUMENT)
                            },
                            onEditPdf = { uri, item ->
                                newSession {
                                    scanVm.targetFolderId = item?.parentId
                                    scanVm.openPdf(uri, item) { nav.navigate(Routes.REVIEW) }
                                }
                            },
                            onImagesPicked = { uris ->
                                newSession {
                                    scanVm.clear()
                                    scanVm.importImages(uris)
                                    nav.navigate(Routes.REVIEW)
                                }
                            },
                            onOpenTool = { tool, item ->
                                toolsVm.start(tool, item)
                                nav.navigate(Routes.TOOL)
                            },
                            onChangeLocation = { nav.navigate(Routes.CHANGE_FOLDER) },
                        )
                    }
                }
                composable(
                    Routes.CAMERA,
                    arguments = listOf(navArgument("retake") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val retake = entry.arguments?.getString("retake")
                    BackSwipe(nav, entry) {
                        CameraScreen(vm = scanVm, retakePageId = retake, onDone = {
                            when {
                                retake != null -> nav.popBackStack()
                                // Came from Review to add pages: just go back to it.
                                nav.previousBackStackEntry?.destination?.route == Routes.REVIEW -> nav.popBackStack()
                                scanVm.pages.value.isEmpty() -> nav.popBackStack()
                                else -> nav.navigate(Routes.REVIEW) { popUpTo(Routes.CAMERA) { inclusive = true } }
                            }
                        }, onImported = {
                            if (nav.previousBackStackEntry?.destination?.route == Routes.REVIEW) nav.popBackStack()
                            else nav.navigate(Routes.REVIEW) { popUpTo(Routes.CAMERA) { inclusive = true } }
                        })
                    }
                }
                composable(Routes.REVIEW) { entry ->
                    BackSwipe(nav, entry) {
                        ReviewScreen(
                            scanVm = scanVm,
                            libraryVm = libraryVm,
                            onAddPages = { nav.navigate(Routes.camera()) },
                            onOpenPage = { nav.navigate(Routes.page(it)) },
                            onFinished = { nav.popBackStack(Routes.MAIN, inclusive = false) },
                        )
                    }
                }
                composable(Routes.PAGE) { entry ->
                    BackSwipe(nav, entry) {
                        PageEditorScreen(
                            vm = scanVm,
                            pageId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack(Routes.REVIEW, inclusive = false) },
                            onRetake = { nav.navigate(Routes.camera(retakePageId = it)) },
                        )
                    }
                }
                composable(Routes.TOOL) { entry ->
                    val pdfs by libraryVm.allPdfs.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { libraryVm.refresh() }
                    BackSwipe(nav, entry) {
                        ToolScreen(
                            vm = toolsVm,
                            pdfs = pdfs,
                            initialUri = { libraryVm.root.value?.let { libraryVm.library.documentUri(it, libraryVm.library.rootId(it)) } },
                            onBack = { nav.popBackStack() },
                            onOpenPdf = {
                                libraryVm.view(it)
                                nav.navigate(Routes.VIEWER)
                            },
                            onShowInShelf = { folder ->
                                libraryVm.showInShelf(folder)
                                nav.popBackStack(Routes.MAIN, inclusive = false)
                            },
                            onEditPdf = { uri, item ->
                                newSession {
                                    scanVm.targetFolderId = item?.parentId
                                    scanVm.openPdf(uri, item) { nav.navigate(Routes.REVIEW) { popUpTo(Routes.TOOL) { inclusive = true } } }
                                }
                            },
                            onLibraryChanged = libraryVm::refresh,
                        )
                    }
                }
                composable(Routes.DOCUMENT) { entry ->
                    BackSwipe(nav, entry) {
                        DocumentPreviewScreen(vm = libraryVm, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.VIEWER) { entry ->
                    BackSwipe(nav, entry) {
                        PdfViewerScreen(
                            vm = libraryVm,
                            onBack = { nav.popBackStack() },
                            onEdit = { item ->
                                newSession {
                                    scanVm.targetFolderId = item.parentId
                                    scanVm.openPdf(item.uri, item) { nav.navigate(Routes.REVIEW) }
                                }
                            },
                            onCompress = { item ->
                                toolsVm.start(PdfTool.Compress, item)
                                nav.navigate(Routes.TOOL)
                            },
                        )
                    }
                }
            }
        }
    }
}
