package com.example.jellybrowser
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.core.view.WindowCompat
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.foundation.rememberScrollState
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.min
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

class MainActivity : ComponentActivity() {

    companion object {
        private const val A4_WIDTH = 595
        private const val A4_HEIGHT = 842
        private const val MAX_PDF_RENDER_SIZE = 2500
        private const val STATE_CURRENT_PAGE_URL = "current_page_url"
    }

    private lateinit var webView: WebView

    private var blockAds = true
    private var blockedCount = 0

    private val imageList = mutableStateListOf<PageImage>()
    private val filteredImages = mutableStateListOf<PageImage>()

    private var currentPageUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(assets)

        webView = WebView(this)

        setupWebView()

        val restoredWebViewState =
            savedInstanceState?.let { webView.restoreState(it) }

        val savedPageUrl =
            savedInstanceState?.getString(STATE_CURRENT_PAGE_URL).orEmpty()
        currentPageUrl =
            webView.url
                ?: restoredWebViewState?.currentItem?.url
                ?: savedPageUrl

        if (restoredWebViewState == null && savedInstanceState != null && savedPageUrl.isNotBlank()) {
            webView.loadUrl(savedPageUrl)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    }
                }
            }
        )

        WindowCompat.setDecorFitsSystemWindows(
            window,
            true
        )

        setContent {
            JellyBrowserScreen()
        }

        if (savedInstanceState == null) {
            webView.loadUrl("https://www.google.com")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString(
            STATE_CURRENT_PAGE_URL,
            webView.url ?: currentPageUrl
        )
    }

    private fun setupWebView() {

        webView.settings.apply {

            javaScriptEnabled = true
            domStorageEnabled = true

            allowFileAccess = false
            allowContentAccess = false

            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            builtInZoomControls = true
            displayZoomControls = false

            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString =
                WebSettings.getDefaultUserAgent(this@MainActivity)
        }

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {

                val uri = request.url

                return if (
                    uri.scheme == "http" ||
                    uri.scheme == "https"
                ) {
                    false
                } else {
                    true
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                Log.w("WEB_SSL", "SSL error: ${error.url}")
                handler.cancel()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {

                val host =
                    request.url.host
                        ?.lowercase(Locale.ROOT)
                        ?.trimEnd('.')
                        ?: return null

                if (
                    AdBlocker.enabled &&
                    AdBlocker.isBlockedHost(host)
                ) {

                    Log.d(
                        "WEB_REQ",
                        "$host [BLOCK]"
                    )

                    blockedCount++

                    return AdBlocker.emptyResponse()
                }

                Log.d(
                    "WEB_REQ",
                    "$host [ALLOW]"
                )

                return null
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {
                super.onPageFinished(view, url)

                currentPageUrl = url
            }
        }
    }

    private fun navigate(input: String) {

        val text = input.trim()

        if (text.isEmpty()) return

        val url = when {

            text.startsWith("http://") ||
                    text.startsWith("https://") -> {
                text
            }

            text.contains(".") &&
                    !text.contains(" ") -> {
                "https://$text"
            }

            else -> {
                "https://www.google.com/search?q=" +
                        Uri.encode(text)
            }
        }

        webView.loadUrl(url)
    }

    private fun getImages(
        onResult: (Int) -> Unit
    ) {

        val js = """
            (function() {
                const images = [...document.images]
                    .map(img => ({
                        url: img.currentSrc || img.src,
                        width: img.naturalWidth || img.width,
                        height: img.naturalHeight || img.height
                    }))
                    .filter(img => img.url);
                return JSON.stringify(images);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->

            try {

                val jsonString =
                    JSONTokener(result).nextValue() as String

                val jsonArray =
                    JSONArray(jsonString)

                imageList.clear()

                for (i in 0 until jsonArray.length()) {

                    val obj =
                        jsonArray.getJSONObject(i)

                    val url =
                        obj.optString("url")

                    val width =
                        obj.optInt("width")

                    val height =
                        obj.optInt("height")

                    if (url.isNotBlank()) {

                        imageList.add(
                            PageImage(
                                url = url,
                                width = width,
                                height = height,
                                checked = true
                            )
                        )
                    }
                }

                filteredImages.clear()
                filteredImages.addAll(imageList)

                onResult(imageList.size)

            } catch (e: Exception) {

                Log.e(
                    "IMAGE_GET",
                    "画像取得エラー",
                    e
                )

                imageList.clear()
                filteredImages.clear()

                onResult(0)
            }
        }
    }

    private fun filterImages(
        minWidth: Int,
        minHeight: Int,
        nameFilter: String,
        urlFilter: String,
        duplicateName: Boolean
    ) {

        filteredImages.clear()

        val nameText =
            nameFilter
                .replace("*", "")
                .trim()
                .lowercase(Locale.ROOT)

        val urlText =
            urlFilter
                .replace("*", "")
                .trim()
                .lowercase(Locale.ROOT)

        val usedNames =
            mutableSetOf<String>()

        for (image in imageList) {

            if (image.width < minWidth) continue
            if (image.height < minHeight) continue

            val fileName =
                getFileName(image.url)

            if (
                nameText.isNotEmpty() &&
                !fileName
                    .lowercase(Locale.ROOT)
                    .contains(nameText)
            ) {
                continue
            }

            if (
                urlText.isNotEmpty() &&
                !image.url
                    .lowercase(Locale.ROOT)
                    .contains(urlText)
            ) {
                continue
            }

            if (duplicateName) {

                val key =
                    fileName
                        .lowercase(Locale.ROOT)

                if (!usedNames.add(key)) {
                    continue
                }
            }

            filteredImages.add(image)
        }
    }

    private fun getFileName(
        url: String
    ): String {

        return try {

            val uri = Uri.parse(url)

            val path =
                uri.path ?: ""

            val name =
                path.substringAfterLast('/')

            if (name.isBlank()) {
                "image"
            } else {
                name
            }

        } catch (_: Exception) {
            "image"
        }
    }

    private fun getSelectedImages(): List<PageImage> {

        return filteredImages
            .filter { it.checked }
    }

    private fun createPdf(
        images: List<PageImage>,
        fileName: String,
        onProgress: (Int, Int) -> Unit
    ): String? {

        if (images.isEmpty()) {
            return null
        }

        val document =
            PdfDocument()

        try {

            val total =
                images.size

            for ((index, image) in images.withIndex()) {

                val bitmap =
                    downloadPdfBitmap(image.url)
                        ?: continue

                try {
                    addImagePage(document, bitmap)
                } finally {
                    bitmap.recycle()
                }

                onProgress(
                    index + 1,
                    total
                )
            }

            val resolver =
                contentResolver

            val safeName =
                if (
                    fileName
                        .lowercase(Locale.ROOT)
                        .endsWith(".pdf")
                ) {
                    fileName
                } else {
                    "$fileName.pdf"
                }

            val values =
                ContentValues().apply {

                    put(
                        MediaStore.Downloads.DISPLAY_NAME,
                        safeName
                    )

                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        "application/pdf"
                    )

                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS +
                                "/JellyBrowser"
                    )
                }

            val uri =
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null

            resolver
                .openOutputStream(uri)
                ?.use { output ->
                    document.writeTo(output)
                }

            return safeName

        } catch (e: Exception) {

            Log.e(
                "PDF",
                "PDF作成エラー",
                e
            )

            return null

        } finally {
            document.close()
        }
    }

    /** Adds one image on a centered A4 page while preserving its aspect ratio. */
    private fun addImagePage(
        document: PdfDocument,
        bitmap: Bitmap
    ) {
        val landscape = bitmap.width > bitmap.height
        val pageWidth = if (landscape) A4_HEIGHT else A4_WIDTH
        val pageHeight = if (landscape) A4_WIDTH else A4_HEIGHT
        val pageInfo = PdfDocument.PageInfo.Builder(
            pageWidth,
            pageHeight,
            document.pages.size + 1
        ).create()
        val page = document.startPage(pageInfo)

        try {
            page.canvas.drawColor(Color.WHITE)

            val margin = 20f
            val availableWidth = pageWidth - margin * 2
            val availableHeight = pageHeight - margin * 2
            val scale = min(
                availableWidth / bitmap.width,
                availableHeight / bitmap.height
            )
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (pageWidth - drawWidth) / 2f
            val top = (pageHeight - drawHeight) / 2f
            val destination = android.graphics.RectF(
                left,
                top,
                left + drawWidth,
                top + drawHeight
            )

            page.canvas.drawBitmap(bitmap, null, destination, null)
        } finally {
            document.finishPage(page)
        }
    }

    /** Copies existing PDF pages first, then appends the selected images. */
    private fun appendImagesToPdf(
        sourceUri: Uri,
        sourceName: String,
        images: List<PageImage>,
        onProgress: (String) -> Unit
    ): String? {
        if (images.isEmpty()) return null

        val document = PdfDocument()
        var sourceFile: ParcelFileDescriptor? = null

        try {
            sourceFile = contentResolver.openFileDescriptor(sourceUri, "r")
                ?: throw IllegalStateException("PDFファイルを開けません")
            val renderer = PdfRenderer(sourceFile)

            try {
                for (index in 0 until renderer.pageCount) {
                    val sourcePage = renderer.openPage(index)
                    var bitmap: Bitmap? = null

                    try {
                        val pageWidth = sourcePage.width
                        val pageHeight = sourcePage.height
                        require(pageWidth > 0 && pageHeight > 0) {
                            "PDFページのサイズが不正です"
                        }

                        val renderScale = min(
                            1f,
                            MAX_PDF_RENDER_SIZE.toFloat() /
                                maxOf(pageWidth, pageHeight)
                        )
                        val bitmapWidth = (pageWidth * renderScale).toInt().coerceAtLeast(1)
                        val bitmapHeight = (pageHeight * renderScale).toInt().coerceAtLeast(1)
                        bitmap = Bitmap.createBitmap(
                            bitmapWidth,
                            bitmapHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)

                        val matrix = Matrix().apply {
                            setScale(renderScale, renderScale)
                        }
                        sourcePage.render(
                            bitmap,
                            null,
                            matrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        )

                        val outputPage = document.startPage(
                            PdfDocument.PageInfo.Builder(
                                pageWidth,
                                pageHeight,
                                document.pages.size + 1
                            ).create()
                        )
                        try {
                            outputPage.canvas.drawBitmap(
                                bitmap,
                                null,
                                android.graphics.RectF(
                                    0f,
                                    0f,
                                    pageWidth.toFloat(),
                                    pageHeight.toFloat()
                                ),
                                null
                            )
                        } finally {
                            document.finishPage(outputPage)
                        }
                        onProgress("既存PDF ${index + 1} / ${renderer.pageCount}")
                    } finally {
                        bitmap?.recycle()
                        sourcePage.close()
                    }
                }
            } finally {
                renderer.close()
            }

            for ((index, image) in images.withIndex()) {
                val bitmap = downloadPdfBitmap(image.url)
                    ?: throw IllegalStateException("画像を取得できませんでした")
                try {
                    addImagePage(document, bitmap)
                } finally {
                    bitmap.recycle()
                }
                onProgress("画像 ${index + 1} / ${images.size}")
            }

            return savePdfWithoutOverwrite(document, sourceName)
        } catch (e: Exception) {
            Log.e("PDF_APPEND", "PDFへの画像追加に失敗しました", e)
            return null
        } finally {
            sourceFile?.close()
            document.close()
        }
    }

    /** Saves as name_追加.pdf and chooses a free name when it already exists. */
    private fun savePdfWithoutOverwrite(
        document: PdfDocument,
        sourceName: String
    ): String? {
        val baseName = sourceName
            .substringBeforeLast('.', sourceName)
            .ifBlank { "document" }
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/JellyBrowser/"
        val resolver = contentResolver
        var suffix = 0

        while (suffix < Int.MAX_VALUE) {
            val extra = if (suffix == 0) "" else "_$suffix"
            val outputName = "${baseName}_追加$extra.pdf"
            val exists = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(outputName, relativePath),
                null
            )?.use { it.moveToFirst() } ?: false

            if (!exists) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val outputUri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null

                try {
                    resolver.openOutputStream(outputUri)?.use { output ->
                        document.writeTo(output)
                    } ?: throw IllegalStateException("保存先を開けません")
                    val published = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(outputUri, published, null, null)
                    return outputName
                } catch (e: Exception) {
                    resolver.delete(outputUri, null, null)
                    throw e
                }
            }
            suffix++
        }
        return null
    }

    private fun getPdfDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "PDF"
        } catch (e: Exception) {
            Log.w("PDF_PICKER", "PDF名を取得できません", e)
            uri.lastPathSegment ?: "PDF"
        }
    }

    private fun downloadPdfBitmap(
        imageUrl: String
    ): Bitmap? {

        var connection: HttpURLConnection? = null

        return try {

            connection =
                URL(imageUrl)
                    .openConnection()
                        as HttpURLConnection

            connection.instanceFollowRedirects = true

            connection.connectTimeout =
                10_000

            connection.readTimeout =
                20_000

            connection.setRequestProperty(
                "User-Agent",
                WebSettings.getDefaultUserAgent(this)
            )

            val cookie =
                CookieManager
                    .getInstance()
                    .getCookie(imageUrl)

            if (!cookie.isNullOrBlank()) {

                connection.setRequestProperty(
                    "Cookie",
                    cookie
                )
            }

            connection.setRequestProperty(
                "Referer",
                currentPageUrl
            )

            connection.connect()

            if (
                connection.responseCode !in 200..299
            ) {
                return null
            }

            val options =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

            connection.inputStream.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    options
                )
            }

            val width =
                options.outWidth

            val height =
                options.outHeight

            if (width <= 0 || height <= 0) {
                return null
            }

            val maxSize =
                2500

            var sample =
                1

            while (
                width / sample > maxSize ||
                height / sample > maxSize
            ) {
                sample *= 2
            }

            connection.disconnect()

            connection =
                URL(imageUrl)
                    .openConnection()
                        as HttpURLConnection

            connection.instanceFollowRedirects = true

            connection.connectTimeout =
                10_000

            connection.readTimeout =
                20_000

            connection.setRequestProperty(
                "User-Agent",
                WebSettings.getDefaultUserAgent(this)
            )

            if (!cookie.isNullOrBlank()) {

                connection.setRequestProperty(
                    "Cookie",
                    cookie
                )
            }

            connection.setRequestProperty(
                "Referer",
                currentPageUrl
            )

            connection.connect()

            val decodeOptions =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                }

            connection.inputStream.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    decodeOptions
                )
            }

        } catch (e: Exception) {

            Log.e(
                "PDF_IMAGE",
                "画像取得失敗: $imageUrl",
                e
            )

            null

        } finally {
            connection?.disconnect()
        }
    }

    @Composable
    private fun JellyBrowserScreen() {

        var urlText by remember {
            mutableStateOf(
                currentPageUrl.ifBlank { "https://www.google.com" }
            )
        }

        var panelOpen by remember {
            mutableStateOf(false)
        }

        var minWidthText by remember {
            mutableStateOf("")
        }

        var minHeightText by remember {
            mutableStateOf("")
        }

        var nameFilter by remember {
            mutableStateOf("")
        }

        var urlFilter by remember {
            mutableStateOf("")
        }

        var duplicateName by remember {
            mutableStateOf(false)
        }

        var pdfName by remember {
            mutableStateOf("images")
        }

        var pdfRunning by remember {
            mutableStateOf(false)
        }

        var pdfProgress by remember {
            mutableStateOf("")
        }

        val pdfPreferences = remember {
            getSharedPreferences("pdf_append_selection", MODE_PRIVATE)
        }
        var selectedPdfUri by rememberSaveable {
            mutableStateOf(pdfPreferences.getString("uri", null))
        }
        var selectedPdfName by rememberSaveable {
            mutableStateOf(pdfPreferences.getString("name", null))
        }

        var appendToExistingPdf by rememberSaveable {
            mutableStateOf(false)
        }

        val pdfPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val persisted = try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    true
                } catch (e: Exception) {
                    // Some document providers do not offer persistable grants.
                    Log.w("PDF_PICKER", "PDFアクセス権を保持できません", e)
                    false
                }
                selectedPdfUri = uri.toString()
                selectedPdfName = getPdfDisplayName(uri)
                pdfPreferences.edit().apply {
                    if (persisted) {
                        putString("uri", selectedPdfUri)
                        putString("name", selectedPdfName)
                    } else {
                        remove("uri")
                        remove("name")
                    }
                }.apply()
                pdfProgress = "PDFを選択しました"
            }
        }

        var imageCount by remember {
            mutableIntStateOf(0)
        }

        var blockedDisplay by remember {
            mutableIntStateOf(0)
        }

        val scope =
            rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {

            Column(
                modifier = Modifier.fillMaxSize()
            ) {

                AndroidView(
                    factory = {
                        webView
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                )

                AnimatedVisibility(
                    visible = panelOpen,
                    enter =
                        expandVertically(),
                    exit =
                        shrinkVertically()
                ) {

                    ControlPanel(
                        urlText = urlText,
                        onUrlChange = {
                            urlText = it
                        },
                        onNavigate = {
                            navigate(urlText)
                        },
                        onBack = {
                            if (webView.canGoBack()) {
                                webView.goBack()
                            }
                        },
                        onForward = {
                            if (webView.canGoForward()) {
                                webView.goForward()
                            }
                        },
                        onRefresh = {
                            webView.reload()
                        },
                        blockAds = blockAds,
                        blockedCount = blockedDisplay,
                        onToggleAds = {

                            blockAds = !blockAds

                            AdBlocker.enabled =
                                blockAds

                            blockedDisplay =
                                blockedCount
                        },
                        imageCount =
                            filteredImages.size,
                        totalImageCount =
                            imageList.size,
                        duplicateName =
                            duplicateName,
                        onDuplicateChange = {
                            duplicateName = it
                        },
                        minWidth =
                            minWidthText,
                        minHeight =
                            minHeightText,
                        nameFilter =
                            nameFilter,
                        urlFilter =
                            urlFilter,
                        onMinWidthChange = {
                            minWidthText = it
                        },
                        onMinHeightChange = {
                            minHeightText = it
                        },
                        onNameFilterChange = {
                            nameFilter = it
                        },
                        onUrlFilterChange = {
                            urlFilter = it
                        },
                        onGetImages = {

                            getImages {
                                imageCount =
                                    imageList.size
                            }
                        },
                        onFilter = {

                            filterImages(
                                minWidth =
                                    minWidthText
                                        .toIntOrNull()
                                        ?: 0,
                                minHeight =
                                    minHeightText
                                        .toIntOrNull()
                                        ?: 0,
                                nameFilter =
                                    nameFilter,
                                urlFilter =
                                    urlFilter,
                                duplicateName =
                                    duplicateName
                            )

                            imageCount =
                                filteredImages.size
                        },
                        pdfName =
                            pdfName,
                        onPdfNameChange = {
                            pdfName = it
                        },
                        pdfRunning =
                            pdfRunning,
                        pdfProgress =
                            pdfProgress,
                        selectedPdfName =
                            selectedPdfName,
                        appendToExistingPdf =
                            appendToExistingPdf,
                        onAppendModeChange = {
                            appendToExistingPdf = it
                        },
                        onChoosePdf = {
                            pdfPicker.launch(arrayOf("application/pdf"))
                        },
                        onCreatePdf = {

                            val selected =
                                getSelectedImages()

                            if (selected.isEmpty()) {
                                pdfProgress =
                                    "選択画像がありません"
                                return@ControlPanel
                            }

                            if (appendToExistingPdf && selectedPdfUri == null) {
                                pdfProgress = "追加するPDFを選択してください"
                                return@ControlPanel
                            }

                            pdfRunning = true
                            pdfProgress = if (appendToExistingPdf) {
                                "PDFを読み込んでいます"
                            } else {
                                "0 / ${selected.size}"
                            }

                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        if (appendToExistingPdf) {
                                            appendImagesToPdf(
                                                Uri.parse(requireNotNull(selectedPdfUri)),
                                                selectedPdfName ?: "document.pdf",
                                                selected
                                            ) { progress ->
                                                runOnUiThread {
                                                    pdfProgress = progress
                                                }
                                            }
                                        } else {
                                            createPdf(selected, pdfName) { current, total ->
                                                runOnUiThread {
                                                    pdfProgress = "画像 $current / $total"
                                                }
                                            }
                                        }
                                    }
                                    pdfProgress = if (result != null) {
                                        "完了: $result"
                                    } else {
                                        "PDF作成に失敗しました。ファイルとアクセス権を確認してください"
                                    }
                                } catch (e: Exception) {
                                    Log.e("PDF", "PDF作成に失敗しました", e)
                                    pdfProgress = "PDFを開けないか、保存できませんでした"
                                } finally {
                                    pdfRunning = false
                                }
                            }
                        },
                        onClose = {
                            panelOpen = false
                        },
                        images = filteredImages,
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }
            }

            if (!panelOpen) {
                IconButton(
                    onClick = { panelOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            RoundedCornerShape(50)
                        )
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "操作パネルを開く"
                    )
                }
            }
        }
    }

    @Composable
    private fun ControlPanel(
        urlText: String,
        onUrlChange: (String) -> Unit,
        onNavigate: () -> Unit,
        onBack: () -> Unit,
        onForward: () -> Unit,
        onRefresh: () -> Unit,
        blockAds: Boolean,
        blockedCount: Int,
        onToggleAds: () -> Unit,
        imageCount: Int,
        totalImageCount: Int,
        duplicateName: Boolean,
        onDuplicateChange: (Boolean) -> Unit,
        minWidth: String,
        minHeight: String,
        nameFilter: String,
        urlFilter: String,
        onMinWidthChange: (String) -> Unit,
        onMinHeightChange: (String) -> Unit,
        onNameFilterChange: (String) -> Unit,
        onUrlFilterChange: (String) -> Unit,
        onGetImages: () -> Unit,
        onFilter: () -> Unit,
        pdfName: String,
        onPdfNameChange: (String) -> Unit,
        pdfRunning: Boolean,
        pdfProgress: String,
        selectedPdfName: String?,
        appendToExistingPdf: Boolean,
        onAppendModeChange: (Boolean) -> Unit,
        onChoosePdf: () -> Unit,
        onCreatePdf: () -> Unit,
        onClose: () -> Unit,
        images: List<PageImage>,
        modifier: Modifier = Modifier
    ) {

        Column(
            modifier =
                modifier
                    .background(
                        MaterialTheme.colorScheme.surface
                    )
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(
                        horizontal = 8.dp,
                        vertical = 5.dp
                    ),
            verticalArrangement =
                Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onClose
                ) {
                    Text("閉じる")
                }
            }
            // ==================================================
            // ブラウザ操作
            // ==================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "戻る"
                    )
                }

                IconButton(
                    onClick = onForward,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "進む"
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "更新"
                    )
                }

                OutlinedTextField(
                    value = urlText,
                    onValueChange = onUrlChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text("URL")
                    }
                )

                TextButton(
                    onClick = onNavigate
                ) {
                    Text("移動")
                }
            }

            // ==================================================
            // 画像操作
            // ==================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Button(
                    onClick = onGetImages,
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text("画像取得")
                }

                Spacer(
                    modifier =
                        Modifier.width(5.dp)
                )

                Button(
                    onClick = onFilter,
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        "適用",
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(
                    modifier =
                        Modifier.width(5.dp)
                )

                Column(
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Button(
                        onClick = onToggleAds
                    ) {
                        Text(
                            if (blockAds) {
                                "広告 ON"
                            } else {
                                "広告 OFF"
                            }
                        )
                    }

                    if (blockAds) {

                        Text(
                            text =
                                "ブロック $blockedCount",
                            style =
                                MaterialTheme.typography.labelSmall,
                            textAlign =
                                TextAlign.Center
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text =
                        "画像 $imageCount / $totalImageCount 件",
                    modifier =
                        Modifier.weight(1f)
                )

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Checkbox(
                        checked =
                            duplicateName,
                        onCheckedChange =
                            onDuplicateChange
                    )

                    Text(
                        "同名除外",
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ==================================================
            // サイズフィルター
            // ==================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(5.dp)
            ) {

                OutlinedTextField(
                    value = minWidth,
                    onValueChange =
                        onMinWidthChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("最小幅")
                    }
                )

                OutlinedTextField(
                    value = minHeight,
                    onValueChange =
                        onMinHeightChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("最小高さ")
                    }
                )
            }

            // ==================================================
            // 名前 / URLフィルター
            // ==================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(5.dp)
            ) {

                OutlinedTextField(
                    value = nameFilter,
                    onValueChange =
                        onNameFilterChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("ファイル名")
                    }
                )

                OutlinedTextField(
                    value = urlFilter,
                    onValueChange =
                        onUrlFilterChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("URL/DIR")
                    }
                )
            }

            // ==================================================
            // PDF
            // ==================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                OutlinedTextField(
                    value = pdfName,
                    onValueChange =
                        onPdfNameChange,
                    modifier =
                        Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("PDFファイル名")
                    }
                )

                Spacer(
                    modifier =
                        Modifier.width(5.dp)
                )

                Button(
                    onClick = onCreatePdf,
                    enabled = !pdfRunning
                ) {
                    Text(
                        if (pdfRunning) {
                            "作成中"
                        } else {
                            "PDF作成"
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = appendToExistingPdf,
                    onCheckedChange = onAppendModeChange,
                    enabled = !pdfRunning
                )
                Text(
                    text = "PDF追加",
                    modifier = Modifier.padding(end = 8.dp),
                    color = if (appendToExistingPdf) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                Text(
                    text = selectedPdfName ?: "PDF未選択",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    color = if (appendToExistingPdf) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                TextButton(
                    onClick = onChoosePdf,
                    enabled = appendToExistingPdf && !pdfRunning
                ) {
                    Text(if (selectedPdfName == null) "選択" else "変更")
                }
            }

            if (pdfProgress.isNotBlank()) {

                Text(
                    text = pdfProgress,
                    style =
                        MaterialTheme.typography.labelSmall,
                    modifier =
                        Modifier.padding(
                            start = 4.dp
                        )
                )
            }

            if (images.isNotEmpty()) {

                Text(
                    text = "画像一覧",
                    style = MaterialTheme.typography.titleSmall
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {

                    items(images) { img ->

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Checkbox(
                                checked = img.checked,
                                onCheckedChange = {
                                    img.checked = it
                                }
                            )

                            AsyncImage(
                                model = img.url,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp)
                            )

                            Spacer(
                                modifier = Modifier.width(8.dp)
                            )

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {

                                Text(
                                    text =
                                        img.url.substringAfterLast("/"),
                                    maxLines = 1
                                )

                                Text(
                                    text =
                                        "${img.width} x ${img.height}",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(32.dp)
            )
        }
    }

    override fun onDestroy() {

        webView.stopLoading()
        webView.destroy()

        super.onDestroy()
    }
}

data class PageImage(
    val url: String,
    val width: Int,
    val height: Int,
    var checked: Boolean = true
)

