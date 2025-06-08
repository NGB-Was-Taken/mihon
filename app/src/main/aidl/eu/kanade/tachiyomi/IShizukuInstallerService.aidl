package eu.kanade.tachiyomi;

interface IShizukuInstallerService {

    void install(String packageName, int size, in ParcelFileDescriptor apkInput) = 1;

    void destroy() = 16777114; // Destroy method defined by Shizuku server
}
