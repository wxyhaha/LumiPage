package com.lumipage.di

import android.content.Context
import com.lumipage.core.common.DefaultDispatcherProvider
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.data.DocumentRepository
import com.lumipage.core.data.FileStorageManager
import com.lumipage.core.data.LumiPageDatabase
import com.lumipage.core.imaging.ImagingEngine
import com.lumipage.core.imaging.OpenCvInitializer
import com.lumipage.core.pdf.PdfService

/**
 * 手动依赖注入容器，按模块边界装配核心服务。
 */
class AppContainer(
    private val appContext: Context,
) {
    val fileProviderAuthority: String = "${appContext.packageName}.fileprovider"
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    val isOpenCvReady: Boolean by lazy { OpenCvInitializer.ensureInitialized() }
    val imagingEngine: ImagingEngine by lazy {
        ImagingEngine(
            dispatchers = dispatchers,
            context = appContext,
        )
    }
    val database: LumiPageDatabase by lazy { LumiPageDatabase.getInstance(appContext) }
    val fileStorageManager: FileStorageManager by lazy {
        FileStorageManager(
            context = appContext,
            dispatchers = dispatchers,
            imagingEngine = imagingEngine,
        )
    }
    val documentRepository: DocumentRepository by lazy {
        DocumentRepository(
            database = database,
            storage = fileStorageManager,
            dispatchers = dispatchers,
        )
    }
    val pdfService: PdfService by lazy {
        PdfService(
            context = appContext,
            dispatchers = dispatchers,
        )
    }
}
