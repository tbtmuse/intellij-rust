/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.download.DownloadableFileDescription
import com.intellij.util.download.DownloadableFileService
import com.intellij.util.io.Decompressor
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBBinUrlProvider
import org.rust.debugger.RsDebuggerToolchainService.BinaryInfo.Companion.LLDB_FRAMEWORK
import org.rust.debugger.RsDebuggerToolchainService.BinaryInfo.Companion.LLDB_FRONTEND
import org.rust.debugger.settings.RsDebuggerSettings
import org.rust.openapiext.plugin
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.*

class RsDebuggerToolchainService {

    fun getLLDBStatus(lldbPath: String? = RsDebuggerSettings.getInstance().lldbPath): LLDBStatus {
        if (lldbPath.isNullOrEmpty()) return LLDBStatus.NeedToDownload

        val (frameworkPath, frontendPath) = when {
            SystemInfo.isMac -> "LLDB.framework" to "LLDBFrontend"
            SystemInfo.isUnix -> "lib/liblldb.so" to "bin/LLDBFrontend"
            SystemInfo.isWindows -> {
                val binaryDir = "${if (SystemInfo.is32Bit) "x86" else "x64"}/bin"
                "$binaryDir/liblldb.dll" to "$binaryDir/LLDBFrontend.exe"
            }
            else -> return LLDBStatus.Unavailable
        }

        val frameworkFile = File(FileUtil.join(lldbPath, LLDB_FRAMEWORK.dirName, frameworkPath))
        val frontendFile = File(FileUtil.join(lldbPath, LLDB_FRONTEND.dirName, frontendPath))
        if (!frameworkFile.exists() || !frontendFile.exists()) return LLDBStatus.NeedToDownload

        val versions = loadLLDBVersions()
        val (lldbFrameworkUrl, lldbFrontendUrl) = lldbUrls ?: return LLDBStatus.Unavailable

        val lldbFrameworkVersion = fileNameWithoutExtension(lldbFrameworkUrl.toString())
        val lldbFrontendVersion = fileNameWithoutExtension(lldbFrontendUrl.toString())

        if (versions[LLDB_FRAMEWORK.propertyName] != lldbFrameworkVersion ||
            versions[LLDB_FRONTEND.propertyName] != lldbFrontendVersion) return LLDBStatus.NeedToUpdate

        return LLDBStatus.Binaries(frameworkFile, frontendFile)
    }

    fun downloadDebugger(
        onSuccess: (File) -> Unit,
        onFailure: () -> Unit
    ) {
        val (lldbFrameworkUrl, lldbFrontendUrl) = lldbUrls ?: run {
            runInEdt { onFailure() }
            return
        }

        val task: Task.Backgroundable = object : Task.Backgroundable(null, "Download debugger") {
            override fun shouldStartInBackground(): Boolean = false
            override fun run(indicator: ProgressIndicator) {
                try {
                    val lldbDir = downloadAndUnarchive(lldbFrameworkUrl.toString(), lldbFrontendUrl.toString())
                    runInEdt {
                        Notifications.Bus.notify(Notification(
                            RUST_DEBUGGER_GROUP_ID,
                            "Debugger",
                            "Debugger successfully downloaded",
                            NotificationType.INFORMATION
                        ))
                        onSuccess(lldbDir)
                    }
                } catch (e: IOException) {
                    LOG.warn("Can't download debugger", e)
                    runInEdt {
                        Notifications.Bus.notify(Notification(
                            RUST_DEBUGGER_GROUP_ID,
                            "Debugger",
                            "Debugger downloading failed",
                            NotificationType.ERROR
                        ))
                        onFailure()
                    }
                }
            }
        }
        val processIndicator = BackgroundableProcessIndicator(task)
        processIndicator.isIndeterminate = false
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, processIndicator)
    }

    private val lldbUrls: Pair<URL, URL>?
        get() {
            return when {
                SystemInfo.isMac -> LLDBBinUrlProvider.lldb.macX64 to LLDBBinUrlProvider.lldbFrontend.macX64
                SystemInfo.isLinux -> LLDBBinUrlProvider.lldb.linuxX64 to LLDBBinUrlProvider.lldbFrontend.linuxX64
                SystemInfo.isWindows -> {
                    if (SystemInfo.is64Bit) {
                        LLDBBinUrlProvider.lldb.winX64 to LLDBBinUrlProvider.lldbFrontend.winX64
                    } else {
                        LLDBBinUrlProvider.lldb.winX86 to LLDBBinUrlProvider.lldbFrontend.winX86
                    }
                }
                else -> return null
            }
        }

    @Throws(IOException::class)
    private fun downloadAndUnarchive(lldbFrameworkUrl: String, lldbFrontendUrl: String): File {
        val service = DownloadableFileService.getInstance()

        val lldbDir = File(lldbPath())
        if (!lldbDir.exists()) {
            lldbDir.mkdirs()
        }

        val versions = loadLLDBVersions()
        val descriptions = mutableListOf<DownloadableFileDescription>()

        fun addFileDescriptorIfNeeded(info: BinaryInfo, url: String) {
            val version = versions[info.propertyName]?.toString()
            if (version != fileNameWithoutExtension(url)) {
                if (version != null) {
                    File(lldbDir, info.dirName).deleteRecursively()
                }
                descriptions += service.createFileDescription(url)
            }
        }

        addFileDescriptorIfNeeded(LLDB_FRAMEWORK, lldbFrameworkUrl)
        addFileDescriptorIfNeeded(LLDB_FRONTEND, lldbFrontendUrl)

        val downloader = service.createDownloader(descriptions, "Debugger downloading")
        val downloadDirectory = File(downloadPath())
        val downloadResults = downloader.download(downloadDirectory)

        for (result in downloadResults) {
            val downloadUrl = result.second.downloadUrl
            val (propertyName, dirName) = if (downloadUrl == lldbFrameworkUrl) LLDB_FRAMEWORK else LLDB_FRONTEND
            val archiveFile = result.first
            val dstDir = File(lldbDir, dirName)
            Unarchiver.unarchive(archiveFile, dstDir)
            archiveFile.delete()
            versions[propertyName] = fileNameWithoutExtension(downloadUrl)
        }

        saveLLDBVersions(versions)

        return lldbDir
    }

    private fun DownloadableFileService.createFileDescription(url: String): DownloadableFileDescription {
        val fileName = url.substringAfterLast("/")
        return createFileDescription(url, fileName)
    }

    private fun fileNameWithoutExtension(url: String): String {
        return url.substringAfterLast("/").removeSuffix(".zip").removeSuffix(".tar.gz")
    }

    private fun loadLLDBVersions(): Properties {
        val versions = Properties()
        val versionsFile = File(lldbPath(), LLDB_VERSIONS)

        if (versionsFile.exists()) {
            try {
                versionsFile.bufferedReader().use { versions.load(it) }
            } catch (e: IOException) {
                LOG.warn("Failed to load `$LLDB_VERSIONS`", e)
            }
        }

        return versions
    }

    private fun saveLLDBVersions(versions: Properties) {
        try {
            versions.store(File(lldbPath(), LLDB_VERSIONS).bufferedWriter(), "")
        } catch (e: IOException) {
            LOG.warn("Failed to save `$LLDB_VERSIONS`")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(RsDebuggerToolchainService::class.java)

        private const val LLDB_VERSIONS: String = "versions.properties"

        const val RUST_DEBUGGER_GROUP_ID = "Rust Debugger"

        private fun downloadPath(): String = PathManager.getTempPath()
        private fun lldbPath(): String = plugin().pluginPath.resolve("lldb").toString()

        fun getInstance(): RsDebuggerToolchainService = service()
    }

    private enum class Unarchiver {
        ZIP {
            override val extension: String = "zip"
            override fun createDecompressor(file: File): Decompressor = Decompressor.Zip(file)
        },
        TAR {
            override val extension: String = "tar.gz"
            override fun createDecompressor(file: File): Decompressor = Decompressor.Tar(file)
        };

        protected abstract val extension: String
        protected abstract fun createDecompressor(file: File): Decompressor

        companion object {
            @Throws(IOException::class)
            fun unarchive(archivePath: File, dst: File) {
                val unarchiver = values().find { archivePath.name.endsWith(it.extension) }
                    ?: error("Unexpected archive type: $archivePath")
                unarchiver.createDecompressor(archivePath).extract(dst)
            }
        }
    }

    private data class BinaryInfo(val propertyName: String, val dirName: String) {
        companion object {
            val LLDB_FRAMEWORK = BinaryInfo("lldb", "framework")
            val LLDB_FRONTEND = BinaryInfo("lldbFrontend", "frontend")
        }
    }

    sealed class LLDBStatus {
        object Unavailable : LLDBStatus()
        object NeedToDownload : LLDBStatus()
        object NeedToUpdate : LLDBStatus()
        data class Binaries(val frameworkFile: File, val frontendFile: File) : LLDBStatus()
    }
}