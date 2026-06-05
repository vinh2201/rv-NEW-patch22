package app.revanced.extension.all.misc.packagemanager.hide

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@Suppress("unused", "MemberVisibilityCanBePrivate")
object HidePackagesPatch {
    @JvmField
    var HIDDEN_PACKAGES_CSV: String? = null

    private val hiddenPackages: Set<String> by lazy {
        HIDDEN_PACKAGES_CSV
            ?.takeIf { it.isNotEmpty() }
            ?.splitToSequence('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toHashSet()
            ?: emptySet()
    }

    private fun isHidden(name: String?): Boolean =
        name != null && name in hiddenPackages

    @JvmStatic
    @Throws(NameNotFoundException::class)
    fun getPackageInfo(pm: PackageManager, packageName: String?, flags: Int): PackageInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageInfo(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Throws(NameNotFoundException::class)
    fun getPackageInfo(
        pm: PackageManager,
        packageName: String?,
        flags: PackageManager.PackageInfoFlags,
    ): PackageInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageInfo(packageName!!, flags)
    }

    @JvmStatic
    @Throws(NameNotFoundException::class)
    fun getApplicationInfo(pm: PackageManager, packageName: String?, flags: Int): ApplicationInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getApplicationInfo(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Throws(NameNotFoundException::class)
    fun getApplicationInfo(
        pm: PackageManager,
        packageName: String?,
        flags: PackageManager.ApplicationInfoFlags,
    ): ApplicationInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getApplicationInfo(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.N)
    @Throws(NameNotFoundException::class)
    fun getPackageUid(pm: PackageManager, packageName: String?, flags: Int): Int {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageUid(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Throws(NameNotFoundException::class)
    fun getPackageUid(
        pm: PackageManager,
        packageName: String?,
        flags: PackageManager.PackageInfoFlags,
    ): Int {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageUid(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.N)
    @Throws(NameNotFoundException::class)
    fun getPackageGids(pm: PackageManager, packageName: String?, flags: Int): IntArray {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageGids(packageName!!, flags)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Throws(NameNotFoundException::class)
    fun getPackageGids(
        pm: PackageManager,
        packageName: String?,
        flags: PackageManager.PackageInfoFlags,
    ): IntArray {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getPackageGids(packageName!!, flags) ?: throw NameNotFoundException(packageName)
    }

    @JvmStatic
    fun getInstalledPackages(pm: PackageManager, flags: Int): MutableList<PackageInfo> =
        pm.getInstalledPackages(flags).stripByPackage()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun getInstalledPackages(
        pm: PackageManager,
        flags: PackageManager.PackageInfoFlags,
    ): MutableList<PackageInfo> = pm.getInstalledPackages(flags).stripByPackage()

    @JvmStatic
    fun getInstalledApplications(pm: PackageManager, flags: Int): MutableList<ApplicationInfo> =
        pm.getInstalledApplications(flags).stripByApplication()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun getInstalledApplications(
        pm: PackageManager,
        flags: PackageManager.ApplicationInfoFlags,
    ): MutableList<ApplicationInfo> = pm.getInstalledApplications(flags).stripByApplication()

    @JvmStatic
    fun getPackagesHoldingPermissions(
        pm: PackageManager,
        permissions: Array<String>,
        flags: Int,
    ): MutableList<PackageInfo> = pm.getPackagesHoldingPermissions(permissions, flags).stripByPackage()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun getPackagesHoldingPermissions(
        pm: PackageManager,
        permissions: Array<String>,
        flags: PackageManager.PackageInfoFlags,
    ): MutableList<PackageInfo> = pm.getPackagesHoldingPermissions(permissions, flags).stripByPackage()

    @JvmStatic
    fun queryIntentActivities(
        pm: PackageManager,
        intent: Intent,
        flags: Int,
    ): MutableList<ResolveInfo> = pm.queryIntentActivities(intent, flags).stripByResolveInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun queryIntentActivities(
        pm: PackageManager,
        intent: Intent,
        flags: PackageManager.ResolveInfoFlags,
    ): MutableList<ResolveInfo> = pm.queryIntentActivities(intent, flags).stripByResolveInfo()

    @JvmStatic
    fun queryIntentServices(
        pm: PackageManager,
        intent: Intent,
        flags: Int,
    ): MutableList<ResolveInfo> = pm.queryIntentServices(intent, flags).stripByResolveInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun queryIntentServices(
        pm: PackageManager,
        intent: Intent,
        flags: PackageManager.ResolveInfoFlags,
    ): MutableList<ResolveInfo> = pm.queryIntentServices(intent, flags).stripByResolveInfo()

    @JvmStatic
    fun queryBroadcastReceivers(
        pm: PackageManager,
        intent: Intent,
        flags: Int,
    ): MutableList<ResolveInfo> = pm.queryBroadcastReceivers(intent, flags).stripByResolveInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun queryBroadcastReceivers(
        pm: PackageManager,
        intent: Intent,
        flags: PackageManager.ResolveInfoFlags,
    ): MutableList<ResolveInfo> = pm.queryBroadcastReceivers(intent, flags).stripByResolveInfo()

    @JvmStatic
    fun queryContentProviders(
        pm: PackageManager,
        processName: String?,
        uid: Int,
        flags: Int,
    ): MutableList<ProviderInfo> = pm.queryContentProviders(processName, uid, flags).stripByProviderInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun queryContentProviders(
        pm: PackageManager,
        processName: String?,
        uid: Int,
        flags: PackageManager.ComponentInfoFlags,
    ): MutableList<ProviderInfo> = pm.queryContentProviders(processName, uid, flags).stripByProviderInfo()

    @JvmStatic
    fun resolveActivity(pm: PackageManager, intent: Intent, flags: Int): ResolveInfo? =
        pm.resolveActivity(intent, flags).scrubResolveInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolveActivity(
        pm: PackageManager,
        intent: Intent,
        flags: PackageManager.ResolveInfoFlags,
    ): ResolveInfo? = pm.resolveActivity(intent, flags).scrubResolveInfo()

    @JvmStatic
    fun resolveService(pm: PackageManager, intent: Intent, flags: Int): ResolveInfo? =
        pm.resolveService(intent, flags).scrubResolveInfo()

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolveService(
        pm: PackageManager,
        intent: Intent,
        flags: PackageManager.ResolveInfoFlags,
    ): ResolveInfo? = pm.resolveService(intent, flags).scrubResolveInfo()

    @JvmStatic
    fun resolveContentProvider(pm: PackageManager, authority: String?, flags: Int): ProviderInfo? =
        authority?.let { pm.resolveContentProvider(it, flags).scrubProviderInfo() }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolveContentProvider(
        pm: PackageManager,
        authority: String?,
        flags: PackageManager.ComponentInfoFlags,
    ): ProviderInfo? = authority?.let { pm.resolveContentProvider(it, flags).scrubProviderInfo() }

    @JvmStatic
    fun getLaunchIntentForPackage(pm: PackageManager, packageName: String?): Intent? {
        if (isHidden(packageName)) return null
        return pm.getLaunchIntentForPackage(packageName!!)
    }

    @JvmStatic
    fun getLeanbackLaunchIntentForPackage(pm: PackageManager, packageName: String?): Intent? {
        if (isHidden(packageName)) return null
        return pm.getLeanbackLaunchIntentForPackage(packageName!!)
    }

    @JvmStatic
    fun getPackagesForUid(pm: PackageManager, uid: Int): Array<String>? {
        val packages = pm.getPackagesForUid(uid) ?: return null
        if (packages.isEmpty()) return packages
        val hidden = hiddenPackages
        if (hidden.isEmpty()) return packages
        val kept = packages.filterNot { it in hidden }
        return when {
            kept.size == packages.size -> packages
            kept.isEmpty() -> null
            else -> kept.toTypedArray()
        }
    }

    @JvmStatic
    fun getNameForUid(pm: PackageManager, uid: Int): String? {
        val name = pm.getNameForUid(uid)
        // getNameForUid may return a shared-uid name like "shared:uid:..." rather than a
        // package name. Filtering by exact match means a shared-uid process name wont be
        // suppressed even if a constituent package is listed.
        return if (isHidden(name)) null else name
    }

    @JvmStatic
    fun getInstallerPackageName(pm: PackageManager, packageName: String?): String? {
        if (isHidden(packageName)) return null
        return pm.getInstallerPackageName(packageName!!)
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(NameNotFoundException::class)
    fun getInstallSourceInfo(pm: PackageManager, packageName: String?): InstallSourceInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return pm.getInstallSourceInfo(packageName!!)
    }

    @JvmStatic
    fun checkSignatures(pm: PackageManager, pkg1: String?, pkg2: String?): Int {
        if (isHidden(pkg1) || isHidden(pkg2)) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
        return pm.checkSignatures(pkg1!!, pkg2!!)
    }

    @JvmStatic
    fun checkSignatures(pm: PackageManager, uid1: Int, uid2: Int): Int {
        val h = hiddenPackages
        if (h.isNotEmpty()) {
            fun uidIntersectsHidden(uid: Int) = pm.getPackagesForUid(uid)?.any { it in h } == true
            if (uidIntersectsHidden(uid1) || uidIntersectsHidden(uid2)) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            }
        }
        return pm.checkSignatures(uid1, uid2)
    }

    private fun reflectPmMethod(name: String, vararg paramTypes: Class<*>): Method? = try {
        PackageManager::class.java.getMethod(name, *paramTypes)
    } catch (_: Exception) { null }

    private val installedPackagesAsUserMethod: Method? by lazy {
        reflectPmMethod("getInstalledPackagesAsUser", Int::class.java, Int::class.java)
    }

    private val installedApplicationsAsUserMethod: Method? by lazy {
        reflectPmMethod("getInstalledApplicationsAsUser", Int::class.java, Int::class.java)
    }

    private val packageInfoAsUserMethod: Method? by lazy {
        reflectPmMethod("getPackageInfoAsUser", String::class.java, Int::class.java, Int::class.java)
    }

    private val applicationInfoAsUserMethod: Method? by lazy {
        reflectPmMethod("getApplicationInfoAsUser", String::class.java, Int::class.java, Int::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Method?.invokeReflect(receiver: Any?, vararg args: Any?): T? = try {
        this?.invoke(receiver, *args) as? T
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    } catch (_: Exception) { null }

    @JvmStatic
    fun getInstalledPackagesAsUser(pm: PackageManager, flags: Int, userId: Int): MutableList<PackageInfo> =
        (installedPackagesAsUserMethod.invokeReflect<MutableList<PackageInfo>>(pm, flags, userId)
            ?: ArrayList(0)).stripByPackage()

    @JvmStatic
    fun getInstalledApplicationsAsUser(pm: PackageManager, flags: Int, userId: Int): MutableList<ApplicationInfo> =
        (installedApplicationsAsUserMethod.invokeReflect<MutableList<ApplicationInfo>>(pm, flags, userId)
            ?: ArrayList(0)).stripByApplication()

    @JvmStatic
    @Throws(NameNotFoundException::class)
    fun getPackageInfoAsUser(pm: PackageManager, packageName: String?, flags: Int, userId: Int): PackageInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return packageInfoAsUserMethod.invokeReflect<PackageInfo>(pm, packageName, flags, userId)
            ?: throw NameNotFoundException(packageName)
    }

    @JvmStatic
    @Throws(NameNotFoundException::class)
    fun getApplicationInfoAsUser(pm: PackageManager, packageName: String?, flags: Int, userId: Int): ApplicationInfo {
        if (isHidden(packageName)) throw NameNotFoundException(packageName)
        return applicationInfoAsUserMethod.invokeReflect<ApplicationInfo>(pm, packageName, flags, userId)
            ?: throw NameNotFoundException(packageName)
    }

    private inline fun <T> MutableList<T>?.stripBy(crossinline getName: (T) -> String?): MutableList<T> {
        val list = this ?: return ArrayList(0)
        if (list.isEmpty()) return list
        val hidden = hiddenPackages
        if (hidden.isEmpty()) return list
        list.removeAll { val name = getName(it); name != null && name in hidden }
        return list
    }

    private fun MutableList<PackageInfo>?.stripByPackage(): MutableList<PackageInfo> =
        stripBy { it.packageName }

    private fun MutableList<ApplicationInfo>?.stripByApplication(): MutableList<ApplicationInfo> =
        stripBy { it.packageName }

    private fun MutableList<ResolveInfo>?.stripByResolveInfo(): MutableList<ResolveInfo> =
        stripBy { it.packageNameOrNull() }

    private fun MutableList<ProviderInfo>?.stripByProviderInfo(): MutableList<ProviderInfo> =
        stripBy { it.packageName }

    private fun ResolveInfo?.scrubResolveInfo(): ResolveInfo? =
        this?.takeUnless { it.packageNameOrNull() in hiddenPackages }

    private fun ProviderInfo?.scrubProviderInfo(): ProviderInfo? =
        this?.takeUnless { it.packageName in hiddenPackages }

    private fun ResolveInfo?.packageNameOrNull(): String? {
        if (this == null) return null
        return activityInfo?.packageName
            ?: serviceInfo?.packageName
            ?: providerInfo?.packageName
            // resolvePackageName is set for forwarded-component resolves (different from activityInfo.packageName)
            ?: resolvePackageName
    }
}
