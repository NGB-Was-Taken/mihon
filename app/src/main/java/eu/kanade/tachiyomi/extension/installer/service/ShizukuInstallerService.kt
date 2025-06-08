package eu.kanade.tachiyomi.extension.installer.service

import android.os.ParcelFileDescriptor
import android.os.Process
import eu.kanade.tachiyomi.IShizukuInstallerService
import kotlin.system.exitProcess

class ShizukuInstallerService : IShizukuInstallerService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun install(packageName: String, size: Int, apkInput: ParcelFileDescriptor?) {
        val userId = Process.myUserHandle().hashCode()
        val runtime = Runtime.getRuntime()
        var sessionId: String? = null

        try {
            val createSessionProcess = runtime.exec("pm install-create --user $userId -r -i $packageName -S $size")
            sessionId = SESSION_ID_REGEX.find(createSessionProcess.inputStream.bufferedReader().readText())?.value
                ?: throw throw RuntimeException("Failed to create install session")

            val writeProcess = runtime.exec("pm install-write -S $size $sessionId base -")
            val apkIn = ParcelFileDescriptor.AutoCloseInputStream(apkInput)
            apkIn.use {
                it.copyTo(writeProcess.outputStream)
            }
            writeProcess.outputStream.close()
            if (writeProcess.waitFor() != 0) {
                throw RuntimeException("Failed to write APK to session $sessionId")
            }

            val installProcess = runtime.exec("pm install-commit $sessionId")
            if (installProcess.waitFor() != 0) {
                throw RuntimeException("Failed to commit install session $sessionId")
            }
        } catch (e: Exception) {
            sessionId?.let { runtime.exec("pm install-abandon $sessionId").waitFor() }
            throw e
        }
    }
}

private val SESSION_ID_REGEX = Regex("(?<=\\[).+?(?=])")
