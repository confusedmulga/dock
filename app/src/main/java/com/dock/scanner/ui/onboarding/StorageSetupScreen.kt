package com.dock.scanner.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dock.scanner.R
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.ui.LibraryViewModel

/**
 * First launch (or "Change storage folder"): asks where scans should be saved. Android
 * requires the user to confirm folder access in the system picker, so the recommended
 * option creates Documents/Dock first and opens the picker right inside it: one tap on
 * "Use this folder" finishes setup.
 */
@Composable
fun StorageSetupScreen(vm: LibraryViewModel, isChange: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && vm.onRootChosen(uri)) onDone()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(88.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (isChange) "Choose a new folder" else "Where should your scans go?",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Dock saves every scan as a PDF in a normal folder on your phone, so you can find it in " +
                "any file manager. Nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_folder_outline), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text("Documents › Dock", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Recommended. Next, Android asks you to allow access: tap “Use this folder”, then “Allow”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                val created = DockLibrary.ensureDefaultFolder()
                pickFolder.launch(if (created) DockLibrary.defaultFolderUri else DockLibrary.documentsFolderUri)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Use Documents › Dock") }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (isChange) TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = { pickFolder.launch(DockLibrary.documentsFolderUri) }) { Text("Choose another folder") }
        }
    }
}
