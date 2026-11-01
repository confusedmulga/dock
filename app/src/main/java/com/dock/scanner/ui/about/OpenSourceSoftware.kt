package com.dock.scanner.ui.about

/** A license or notice text shipped in the app's assets. */
class LicenseText(val title: String, val asset: String)

/**
 * Third-party software or a model that ships inside Dock. [changes] says how Dock altered it,
 * which the Apache License asks to state for modified files.
 */
class OpenSourceItem(
    val name: String,
    val use: String,
    val copyright: String,
    val license: String,
    val texts: List<LicenseText>,
    val changes: String? = null,
)

private val apache = LicenseText("Apache License 2.0", "licenses/Apache-2.0.txt")

/** Everything in the app that someone else made, and the terms Dock uses it under. */
val openSourceSoftware = listOf(
    OpenSourceItem(
        name = "PaddleOCR PP-OCRv5 mobile models",
        use = "Text detection and Latin-script text recognition for searchable PDFs",
        copyright = "Copyright (c) 2016 PaddlePaddle Authors",
        license = "Apache License 2.0",
        texts = listOf(LicenseText("License", "ocr/LICENSE")),
        changes = "Weights stored at half precision and converted to ONNX Runtime's format by Dock. " +
            "Source: huggingface.co/PaddlePaddle (PP-OCRv5_mobile_det, latin_PP-OCRv5_mobile_rec)",
    ),
    OpenSourceItem(
        name = "DocAligner corner model (lcnet100)",
        use = "Double-checks the page corners the classic edge detector finds",
        copyright = "Copyright DocsaidLab",
        license = "Apache License 2.0",
        texts = listOf(LicenseText("License", "scanner/LICENSE")),
        changes = "Weights stored at half precision and converted to ONNX Runtime's format by Dock. " +
            "Source: github.com/DocsaidLab/DocAligner",
    ),
    OpenSourceItem(
        name = "ONNX Runtime 1.31.0",
        use = "Runs the text recognition and corner models on the phone",
        copyright = "Copyright (c) Microsoft Corporation",
        license = "MIT License",
        texts = listOf(
            LicenseText("MIT License", "licenses/onnxruntime-LICENSE.txt"),
            LicenseText("Third-party notices", "licenses/onnxruntime-ThirdPartyNotices.txt"),
        ),
        changes = "Built from source by Dock with only the operators its models use",
    ),
    OpenSourceItem(
        name = "OpenCV 4.12.0",
        use = "Edge detection, perspective correction and image filters",
        copyright = "Copyright (C) 2000-2023 Intel Corporation, Willow Garage, NVIDIA, AMD, OpenCV Foundation, Itseez, " +
            "Xperience AI and others",
        license = "Apache License 2.0; bundled libraries under their own licenses",
        texts = listOf(apache, LicenseText("Copyrights and bundled libraries", "licenses/OpenCV-notices.txt")),
    ),
    OpenSourceItem(
        name = "PdfBox-Android 2.0.27.0",
        use = "Merging, splitting, compressing PDFs and their text layer",
        copyright = "Copyright Tom Roush and contributors; based on Apache PDFBox, Copyright The Apache Software Foundation",
        license = "Apache License 2.0; Liberation Sans under the SIL Open Font License 1.1",
        texts = listOf(apache, LicenseText("Notices", "licenses/PdfBox-Android-notices.txt")),
    ),
    OpenSourceItem(
        name = "Android Jetpack, Jetpack Compose, CameraX and Material components",
        use = "The app's interface, camera and navigation",
        copyright = "Copyright The Android Open Source Project; Copyright Google LLC",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
    OpenSourceItem(
        name = "Kotlin, kotlinx.coroutines and Compose Multiplatform libraries",
        use = "The language and its libraries",
        copyright = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
    OpenSourceItem(
        name = "Coil 3",
        use = "Loading images",
        copyright = "Copyright 2025 Coil Contributors",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
    OpenSourceItem(
        name = "Okio",
        use = "File input and output for Coil",
        copyright = "Copyright 2013 Square, Inc.",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
    OpenSourceItem(
        name = "Haze",
        use = "The blurred glass behind the navigation island",
        copyright = "Copyright 2023 Chris Banes",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
    OpenSourceItem(
        name = "Guava (ListenableFuture), AutoValue annotations, Accompanist, JSpecify",
        use = "Small libraries the components above depend on",
        copyright = "Copyright Google LLC; Copyright The JSpecify Authors",
        license = "Apache License 2.0",
        texts = listOf(apache),
    ),
)
