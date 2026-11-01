package com.dock.scanner.ui.about

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/confusedmulga/Dock"
private const val PRIVACY_URL = "https://github.com/confusedmulga/Dock/blob/main/PRIVACY.md"

/** The app's version, links to its source and privacy policy, and the open-source software in it. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<LicenseText?>(null) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        item(key = "header") {
            Column {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
                }
                LargeTitle("About", subtitle = "Dock $version")
            }
        }
        item(key = "intro") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Dock is open source under the MIT License. It has no internet access: scanning, text " +
                        "recognition and everything else run on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { openLink(context, SOURCE_URL) }) { Text("Source code") }
                    TextButton(onClick = { openLink(context, PRIVACY_URL) }) { Text("Privacy policy") }
                    TextButton(onClick = { reading = LicenseText("Dock: MIT License", "licenses/Dock-LICENSE.txt") }) { Text("License") }
                }
            }
        }
        item(key = "oss-title") {
            SectionHeader("Open-source software")
        }
        item(key = "oss-intro") {
            Text(
                "Dock is built on the work below. Each is used under its license; tap one to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        items(openSourceSoftware, key = { it.name }) { item ->
            OpenSourceRow(item, onRead = { reading = it })
        }
    }

    reading?.let { text -> LicenseSheet(text, onDismiss = { reading = null }) }
}

@Composable
private fun OpenSourceRow(item: OpenSourceItem, onRead: (LicenseText) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Read the license") { onRead(item.texts.first()) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(item.name, style = MaterialTheme.typography.titleMedium)
        Text(item.use, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${item.copyright}. ${item.license}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        item.changes?.let {
            Text(it + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        }
        if (item.texts.size > 1) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                item.texts.forEach { text ->
                    TextButton(onClick = { onRead(text) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(text.title) }
                }
            }
        }
    }
}

/** A license text, which can be long (ONNX Runtime's notices are ~340 KB), read lazily in paragraphs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(text: LicenseText, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState(emptyList<String>(), text.asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(text.asset).bufferedReader().use { it.readText() } }
                .getOrDefault("This text couldn't be loaded.")
                .replace("\r\n", "\n")
                .split(Regex("\n\\s*\n"))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(text.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(paragraphs) { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
