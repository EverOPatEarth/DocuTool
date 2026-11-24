
package com.example.docutools

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// FIXED: Use Tom Roush Android PDFBox packages
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.io.MemoryUsageSetting

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Android PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            DocuToolsScreen()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: (uri.lastPathSegment ?: "document")

    // DOCX to PDF conversion
    private suspend fun convertDocx(input: Uri, output: Uri) =
        withContext(Dispatchers.IO) {

            val paragraphs = contentResolver.openInputStream(input)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var xml: ByteArray? = null

                    while (true) {
                        val entry = zip.nextEntry ?: break

                        if (entry.name == "word/document.xml") {
                            xml = zip.readBytes()
                            break
                        }
                    }

                    requireNotNull(xml) {
                        "This file does not contain a valid Word document body."
                    }
                }
            } ?: error("Cannot open the selected DOCX file.")

            val parser = Xml.newPullParser()
            parser.setInput(paragraphs.inputStream(), "UTF-8")

            val result = mutableListOf<String>()
            val current = StringBuilder()

            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "p") current.clear()
                    }

                    XmlPullParser.TEXT -> {
                        current.append(parser.text)
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "p") {
                            val line = current.toString()
                                .replace(Regex("\\s+"), " ")
                                .trim()

                            result.add(line)
                        }
                    }
                }

                event = parser.next()
            }

            renderTextPdf(result, output)
        }

    // Render text into PDF pages
    private fun renderTextPdf(
        paragraphs: List<String>,
        output: Uri
    ) {
        val pdf = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 11f
            typeface = Typeface.create(
                "sans-serif",
                Typeface.NORMAL
            )
        }

        val lineHeight = 16f
        val maxWidth = pageWidth - margin * 2

        var pageNumber = 1

        var page = pdf.startPage(
            PdfDocument.PageInfo.Builder(
                pageWidth,
                pageHeight,
                pageNumber
            ).create()
        )

        var canvas = page.canvas
        var y = margin + 12

        fun newPage() {
            pdf.finishPage(page)

            pageNumber++

            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    pageNumber
                ).create()
            )

            canvas = page.canvas
            y = margin + 12
        }

        for (paragraph in paragraphs) {

            if (paragraph.isBlank()) {
                y += lineHeight * 0.65f
                continue
            }

            val words = paragraph.split(Regex("\\s+"))
            var line = ""

            for (word in words) {

                val candidate =
                    if (line.isEmpty()) word else "$line $word"

                if (
                    paint.measureText(candidate) > maxWidth &&
                    line.isNotEmpty()
                ) {
                    if (y + lineHeight > pageHeight - margin) {
                        newPage()
                    }

                    canvas.drawText(line, margin, y, paint)

                    y += lineHeight
                    line = word

                } else {
                    line = candidate
                }
            }

            if (line.isNotEmpty()) {

                if (y + lineHeight > pageHeight - margin) {
                    newPage()
                }

                canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }

            y += 7f
        }

        pdf.finishPage(page)

        try {
            contentResolver.openOutputStream(output, "w")?.use {
                pdf.writeTo(it)
            } ?: error("Cannot create the output PDF.")
        } finally {
            pdf.close()
        }
    }

    // Merge PDF files
    private suspend fun mergePdfs(
        inputs: List<Uri>,
        output: Uri
    ) = withContext(Dispatchers.IO) {

        val tempFiles = mutableListOf<File>()

        val target = File(
            cacheDir,
            "merged-${System.currentTimeMillis()}.pdf"
        )

        try {

            inputs.forEachIndexed { index, uri ->

                val temp = File(
                    cacheDir,
                    "merge-input-$index-${System.currentTimeMillis()}.pdf"
                )

                contentResolver.openInputStream(uri)?.use { source ->
                    temp.outputStream().use { destination ->
                        source.copyTo(destination)
                    }
                } ?: error("Cannot read one of the selected PDF files.")

                tempFiles.add(temp)
            }

            val merger = PDFMergerUtility()

            tempFiles.forEach {
                merger.addSource(it)
            }

            merger.destinationFileName = target.absolutePath

            merger.mergeDocuments(
                MemoryUsageSetting.setupMainMemoryOnly()
            )

            contentResolver.openOutputStream(output, "w")?.use { destination ->
                target.inputStream().use { source ->
                    source.copyTo(destination)
                }
            } ?: error("Cannot create the merged PDF.")

        } finally {
            tempFiles.forEach { it.delete() }
            target.delete()
        }
    }

    @Composable
    private fun DocuToolsScreen() {

        var selectedTab by remember { mutableStateOf(0) }
        var selectedDocx by remember { mutableStateOf<Uri?>(null) }
        var pdfs by remember { mutableStateOf(listOf<Uri>()) }

        var status by remember {
            mutableStateOf("Ready — files stay on this device.")
        }

        var busy by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()

        val docxPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) {
                selectedDocx = it

                if (it != null) {
                    status = "Selected: ${displayName(it)}"
                }
            }

        val pdfPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { chosen ->

                pdfs = (pdfs + chosen).distinct()

                status = "${pdfs.size} PDF file(s) selected."
            }

        val docxSaver =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/pdf")
            ) { out ->

                val input = selectedDocx

                if (out != null && input != null) {
                    scope.launch {
                        busy = true
                        status = "Converting DOCX to PDF…"

                        try {
                            convertDocx(input, out)
                            status = "Conversion completed successfully."
                        } catch (e: Exception) {
                            status =
                                "Conversion failed: ${e.message ?: "Unknown error"}"
                        } finally {
                            busy = false
                        }
                    }
                }
            }

        val pdfSaver =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/pdf")
            ) { out ->

                if (out != null && pdfs.isNotEmpty()) {
                    scope.launch {
                        busy = true
                        status = "Combining PDF files…"

                        try {
                            mergePdfs(pdfs, out)
                            status = "PDF files combined successfully."
                        } catch (e: Exception) {
                            status =
                                "Merge failed: ${e.message ?: "Unknown error"}"
                        } finally {
                            busy = false
                        }
                    }
                }
            }

        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Color(0xFF3157D5),
                background = Color(0xFFF5F7FB)
            )
        ) {

            Scaffold(
                containerColor = Color(0xFFF5F7FB),

                bottomBar = {
                    NavigationBar {

                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(Icons.Default.Description, null)
                            },
                            label = { Text("DOCX to PDF") }
                        )

                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(Icons.Default.MergeType, null)
                            },
                            label = { Text("PDF Merger") }
                        )
                    }
                }

            ) { padding ->

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(20.dp)
                ) {

                    Text(
                        "DocuTools",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF17233D)
                    )

                    Text(
                        "Offline document toolkit",
                        color = Color(0xFF68748A),
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(22.dp))

                    if (selectedTab == 0) {

                        ToolCard(
                            "DOCX to PDF",
                            "Convert a Word document into a PDF on your device.",
                            Icons.Default.Description
                        )

                        Spacer(Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = {
                                docxPicker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    )
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose DOCX file")
                        }

                        selectedDocx?.let {
                            Text(
                                "Selected: ${displayName(it)}",
                                modifier = Modifier.padding(top = 12.dp),
                                fontSize = 13.sp
                            )
                        }

                        Button(
                            onClick = {
                                docxSaver.launch(
                                    (
                                        selectedDocx?.let {
                                            displayName(it)
                                                .substringBeforeLast('.', "document")
                                        } ?: "converted"
                                    ) + ".pdf"
                                )
                            },
                            enabled = selectedDocx != null && !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                        ) {
                            Text("Convert and Save PDF")
                        }

                        InfoNote(
                            "The converter preserves readable paragraph text. Advanced Word formatting, images, and complex tables are not fully reproduced in this first version."
                        )

                    } else {

                        ToolCard(
                            "PDF Merger",
                            "Combine multiple PDF documents in your chosen order.",
                            Icons.Default.MergeType
                        )

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = {
                                pdfPicker.launch(arrayOf("application/pdf"))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add PDF files")
                        }

                        if (pdfs.isNotEmpty()) {

                            Text(
                                "Selected files (${pdfs.size})",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    top = 18.dp,
                                    bottom = 8.dp
                                )
                            )

                            LazyColumn(
                                Modifier
                                    .weight(1f, fill = false)
                                    .heightIn(max = 310.dp)
                            ) {

                                itemsIndexed(pdfs) { index, uri ->

                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp)
                                            .background(
                                                Color.White,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {

                                        Text(
                                            "${index + 1}. ${displayName(uri)}",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 12.sp,
                                            maxLines = 2
                                        )

                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    pdfs = pdfs.toMutableList().also {
                                                        val v = it.removeAt(index)
                                                        it.add(index - 1, v)
                                                    }
                                                }
                                            },
                                            enabled = index > 0
                                        ) {
                                            Icon(Icons.Default.ArrowUpward, "Move up")
                                        }

                                        IconButton(
                                            onClick = {
                                                if (index < pdfs.lastIndex) {
                                                    pdfs = pdfs.toMutableList().also {
                                                        val v = it.removeAt(index)
                                                        it.add(index + 1, v)
                                                    }
                                                }
                                            },
                                            enabled = index < pdfs.lastIndex
                                        ) {
                                            Icon(Icons.Default.ArrowDownward, "Move down")
                                        }

                                        IconButton(
                                            onClick = {
                                                pdfs = pdfs.toMutableList().also {
                                                    it.removeAt(index)
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                "Remove",
                                                tint = Color(0xFFB42318)
                                            )
                                        }
                                    }
                                }
                            }

                            TextButton(
                                onClick = {
                                    pdfs = emptyList()
                                    status = "PDF selection cleared."
                                }
                            ) {
                                Text("Clear all")
                            }

                        } else {

                            InfoNote(
                                "Add two or more PDF files. Use the arrows to arrange their merge order."
                            )
                        }

                        Button(
                            onClick = {
                                pdfSaver.launch("combined_document.pdf")
                            },
                            enabled = pdfs.isNotEmpty() && !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Text("Combine and Save PDF")
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE9EEFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Column(Modifier.padding(14.dp)) {

                            Text(
                                if (busy) "Working…" else "Status",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF233B82)
                            )

                            Text(
                                status,
                                fontSize = 13.sp,
                                color = Color(0xFF34446D),
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            if (busy) {
                                LinearProgressIndicator(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        "Privacy: no files are uploaded. Processing is performed locally.",
                        fontSize = 11.sp,
                        color = Color(0xFF7A8497)
                    )
                }
            }
        }
    }

    @Composable
    private fun ToolCard(
        title: String,
        subtitle: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector
    ) {

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    Modifier
                        .size(52.dp)
                        .background(
                            Color(0xFFE9EEFF),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {

                    Icon(
                        icon,
                        null,
                        tint = Color(0xFF3157D5),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF68748A)
                    )
                }
            }
        }
    }

    @Composable
    private fun InfoNote(text: String) {

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFEFF2F7)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
        ) {

            Text(
                text,
                Modifier.padding(14.dp),
                fontSize = 12.sp,
                color = Color(0xFF596579)
            )
        }
    }
}
