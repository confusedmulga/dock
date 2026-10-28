package com.dock.scanner.office

import java.io.Closeable
import java.io.File

/** The kinds of files the preview opens. */
enum class OfficeFormat(val label: String, val kind: Kind, vararg val extensions: String) {
    DOCX("Word document", Kind.Text, "docx", "docm", "dotx"),
    DOC("Word 97-2003 document", Kind.Text, "doc", "dot"),
    ODT("OpenDocument text", Kind.Text, "odt", "ott"),
    RTF("Rich text", Kind.Text, "rtf"),
    TXT("Text", Kind.Text, "txt", "text", "log", "md"),
    XLSX("Excel workbook", Kind.Sheet, "xlsx", "xlsm", "xltx"),
    XLS("Excel 97-2003 workbook", Kind.Sheet, "xls", "xlt"),
    ODS("OpenDocument spreadsheet", Kind.Sheet, "ods", "ots"),
    CSV("Comma-separated values", Kind.Sheet, "csv"),
    TSV("Tab-separated values", Kind.Sheet, "tsv", "tab"),
    PPTX("PowerPoint presentation", Kind.Slides, "pptx", "pptm", "ppsx", "potx"),
    PPT("PowerPoint 97-2003 presentation", Kind.Slides, "ppt", "pps", "pot"),
    ODP("OpenDocument presentation", Kind.Slides, "odp", "otp");

    enum class Kind { Text, Sheet, Slides }

    companion object {
        private val MIME = mapOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCX,
            "application/vnd.ms-word.document.macroenabled.12" to DOCX,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to DOCX,
            "application/msword" to DOC,
            "application/vnd.oasis.opendocument.text" to ODT,
            "application/rtf" to RTF,
            "text/rtf" to RTF,
            "text/plain" to TXT,
            "text/markdown" to TXT,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to XLSX,
            "application/vnd.ms-excel.sheet.macroenabled.12" to XLSX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to XLSX,
            "application/vnd.ms-excel" to XLS,
            "application/vnd.oasis.opendocument.spreadsheet" to ODS,
            "text/csv" to CSV,
            "text/comma-separated-values" to CSV,
            "text/tab-separated-values" to TSV,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to PPTX,
            "application/vnd.ms-powerpoint.presentation.macroenabled.12" to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.template" to PPTX,
            "application/vnd.ms-powerpoint" to PPT,
            "application/vnd.oasis.opendocument.presentation" to ODP,
        )

        /** Every MIME type the preview opens, for intent filters and file pickers. */
        val mimeTypes: Set<String> get() = MIME.keys

        /** The format of a file, by its MIME type or else its name. */
        fun of(name: String?, mimeType: String?): OfficeFormat? {
            mimeType?.lowercase()?.let { MIME[it] }?.let { return it }
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/**
 * An opened document. Pictures are read from the file while it's open, so [close] it when
 * the preview goes away.
 */
class OpenedDocument internal constructor(val format: OfficeFormat, val document: OfficeDocument, private val resource: AutoCloseable?) : Closeable {
    override fun close() {
        resource?.close()
    }
}

object OfficeReader {

    /**
     * Reads [file] as [format]. Does disk I/O and parsing: call it off the main thread.
     * @throws UnreadableDocumentException if the file is damaged, encrypted or not that format.
     */
    fun open(file: File, format: OfficeFormat): OpenedDocument = try {
        when (format) {
            OfficeFormat.DOCX -> zip(file, format) { DocxReader(it).read() }
            OfficeFormat.XLSX -> zip(file, format) { XlsxReader(it).read() }
            OfficeFormat.PPTX -> zip(file, format) { PptxReader(it).read() }
            OfficeFormat.ODT, OfficeFormat.ODS, OfficeFormat.ODP -> zip(file, format) { OdfReader(it).read() }
            OfficeFormat.DOC -> OpenedDocument(format, Cfb(file).use { DocReader(it).read() }, null)
            OfficeFormat.XLS -> OpenedDocument(format, Cfb(file).use { XlsReader(it).read() }, null)
            OfficeFormat.PPT -> OpenedDocument(format, Cfb(file).use { PptReader(it).read() }, null)
            OfficeFormat.RTF -> OpenedDocument(format, RtfReader.read(file), null)
            OfficeFormat.TXT -> OpenedDocument(format, PlainText.read(file), null)
            OfficeFormat.CSV -> OpenedDocument(format, Delimited.read(file, tabs = false), null)
            OfficeFormat.TSV -> OpenedDocument(format, Delimited.read(file, tabs = true), null)
        }
    } catch (e: UnreadableDocumentException) {
        throw e
    } catch (e: OutOfMemoryError) {
        throw UnreadableDocumentException("This document is too large to preview", e)
    } catch (e: Exception) {
        throw UnreadableDocumentException("This document can't be previewed", e)
    }

    private fun zip(file: File, format: OfficeFormat, read: (ZipPackage) -> OfficeDocument): OpenedDocument {
        // A password-protected Office file is a compound file with the encrypted package inside.
        if (Cfb.isCompoundFile(file)) throw UnreadableDocumentException("This document is password-protected")
        val pkg = ZipPackage(file)
        return try {
            OpenedDocument(format, read(pkg), pkg)
        } catch (e: Throwable) {
            pkg.close()
            throw e
        }
    }
}
