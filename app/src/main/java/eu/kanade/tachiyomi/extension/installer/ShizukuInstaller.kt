package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.IShizukuInstallerService
import eu.kanade.tachiyomi.extension.installer.service.ShizukuInstallerService
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.getUriSize
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import rikka.shizuku.Shizuku
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class ShizukuInstaller(private val service: Service) : Installer(service) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        logcat { "Shizuku was killed prematurely" }
        Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        service.stopSelf()
    }

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ready = true
                    checkQueue()
                } else {
                    service.stopSelf()
                }
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(service.packageName, ShizukuInstallerService::class.java.name),
    )
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .processNameSuffix("shizuku_installer")
        .tag("ShizukuInstallerService")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            installerService = IShizukuInstallerService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            installerService = null
        }
    }

    private var installerService: IShizukuInstallerService? = null

    override var ready = false

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        scope.launch {
            try {
                val size = service.getUriSize(entry.uri) ?: throw IllegalStateException()
                val pipe = ParcelFileDescriptor.createPipe()
                val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                service.contentResolver.openInputStream(entry.uri)!!.use { apkInput ->
                    output.use {
                        apkInput.copyTo(it)
                    }
                    installerService!!.install(service.packageName, size.toInt(), pipe[0])

                    continueQueue(InstallStep.Installed)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to install extension ${entry.downloadId} ${entry.uri}" }
                continueQueue(InstallStep.Error)
            }
        }
    }

    // Don't cancel if entry is already started installing
    override fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

    override fun onDestroy() {
        installerService?.destroy()
        Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        scope.cancel()
        super.onDestroy()
    }

    init {
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        ready = if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                true
            } else {
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                false
            }
        } else {
            logcat(LogPriority.ERROR) { "Shizuku is not ready to use" }
            service.toast(MR.strings.ext_installer_shizuku_stopped)
            service.stopSelf()
            false
        }
    }
}

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
