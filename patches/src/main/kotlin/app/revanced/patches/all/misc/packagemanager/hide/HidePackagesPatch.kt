package app.revanced.patches.all.misc.packagemanager.hide

import app.revanced.com.android.tools.smali.dexlib2.iface.value.MutableStringEncodedValue
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringsOption
import app.revanced.patches.all.misc.transformation.IMethodCall
import app.revanced.patches.all.misc.transformation.filterMapInstructionVirtual
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue

private const val EXTENSION_CLASS_DESCRIPTOR_PREFIX =
    "Lapp/revanced/extension/all/misc/packagemanager/hide/HidePackagesPatch"

private const val EXTENSION_CLASS_DESCRIPTOR = "$EXTENSION_CLASS_DESCRIPTOR_PREFIX;"

private const val HIDDEN_PACKAGES_FIELD_NAME = "HIDDEN_PACKAGES_CSV"

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

private fun List<String?>?.validatePackageNames(): Boolean =
    this == null || all { it.isNullOrBlank() || PACKAGE_NAME_REGEX.matches(it.trim()) }

@Suppress("unused")
val hidePackagesPatch = bytecodePatch(
    name = "Hide packages",
    description = "Makes apps invisible to queries.",
    use = false,
) {
    extendWith("extensions/all/misc/packagemanager/hide/hide-packages.rve")

    val hiddenPackages by stringsOption(
        name = "Packages to hide",
        description = "List of package names to hide from the patched app.",
        default = emptyList(),
        required = true,
        validator = { it.validatePackageNames() },
    )

    val includeUnsafePackages by booleanOption(
        name = "Extra known unsafe packages",
        description = "Add predefined package names to the " +
            "hidden list in addition to any custom packages above. " +
            "Separated by category.",
        default = false,
    )

    val rootManagerPackages by stringsOption(
        name = "Root managers",
        default = listOf(
            "com.topjohnwu.magisk",  // Magisk Manager
            "io.github.huskydg.magisk",  // Magisk Delta
            "io.github.vvb2060.magisk",  // Magisk Alpha
            "me.bmax.apatch",  // APatch
            "me.weishu.kernelsu",  // KernelSU
            "com.rifsxd.ksunext",  // KernelSU Next
            "com.twj.wksu",  // Wild KernelSU
            "eu.chainfire.supersu",  // SuperSU
            "com.noshufou.android.su",  // Superuser (ChainsDD)
            "com.noshufou.android.su.elite",  // Superuser Elite
            "com.koushikdutta.superuser",  // Superuser (Koush)
            "com.thirdparty.superuser",  // Superuser (Koush, PlayStore Version)
            "com.yellowes.su",  // Superuser (YellowES)
            "com.kingo.root",  // Kingo Root
            "com.kingroot.kinguser",  // KingRoot
            "com.smedialink.oneclickroot",  // OneClickRoot
            "com.zhiqupk.root.global",  // Root Dashi
            "com.alephzain.framaroot",  // Framaroot
            "me.phh.superuser",  // phh's SuperUser
            "com.geohot.towelroot",  // Towelroot
            "com.qihoo.permmgr",  // 360 Root
            "com.shuame.rootgenius",  // Root Genius
            "com.baidusu.superuser",  // Baidu SuperUser
            "com.saurik.substrate",  // Cydia Substrate
        ),
        validator = { it.validatePackageNames() },
    )

    val shizukuPackages by stringsOption(
        name = "Shizuku",
        default = listOf(
            "moe.shizuku.privileged.api",  // Shizuku (Rikka)
            "com.rosan.dhizuku",  // Dhizuku
            "af.shizuku.manager",  // ShizukuPlus
            "rikka.sui",  // Sui (Rikka)
        ),
        validator = { it.validatePackageNames() },
    )

    val xposedPackages by stringsOption(
        name = "Xposed / LSPosed",
        default = listOf(
            "de.robv.android.xposed.installer",  // Xposed Installer
            "org.lsposed.manager",  // LSPosed Manager
            "io.github.lsposed.manager",  // LSPosed Manager
            "org.lsposed.lspatch",  // LSPatch Manager
            "io.va.exposed",  // VirtualXposed
            "org.meowcat.edxposed.manager",  // EdXposed Manager
            "me.weishu.exp",  // TaiChi
            "top.canyie.dreamland.manager",  // Dreamland
            "com.solohsu.android.edxp.manager",  // Solohsu's EdXposed Manager
        ),
        validator = { it.validatePackageNames() },
    )

    val moduleManagerPackages by stringsOption(
        name = "Module managers",
        default = listOf(
            "com.fox2code.mmm",  // Androidacy Module Manager
            "com.fox2code.mmm.fdroid",  // Androidacy Module Manager (F-froid Version)
            "com.dergoogler.mmrl",  // Magisk Module Repo Loader
            "io.github.sanmer.mrepo",  // MRepo
        ),
        validator = { it.validatePackageNames() },
    )

    val rootCheckerPackages by stringsOption(
        name = "Root checkers",
        default = listOf(
            "io.github.a13e300.momo",  // Momo
            "com.eltavine.duckdetector",  // Duck Detector
            "com.joeykrim.rootcheck",  // Root Checker
            "com.joeykrim.rootcheck.basic",  // Root Checker Basic
            "stericson.busybox",  // BusyBox
            "stericson.busybox.free",  // BusyBox (Ads)
            "eu.thedarken.sdm",  // Root Toolbox PRO
            "com.jrummyapps.rootchecker",  // JRummy's Root Check
            "com.scottyab.rootbeer.sample",  // RootBeer Sample App
            "com.kimchangyoun.rootbeerFresh.sample",  // RootBeer Fresh Sample
            "com.devadvance.rootcloak",  // RootCloak
            "com.devadvance.rootcloakplus",  // RootCloak Plus
            "com.amphoras.hidemyroot",  // HideRoot
            "com.formyhm.hideapkinstall",  // Hide APK Install
        ),
        validator = { it.validatePackageNames() },
    )

    val playIntegrityPackages by stringsOption(
        name = "Play integrity",
        default = listOf(
            "io.github.chiteroman.playintegrityfix",  // PlayIntegrityFix
            "io.github.osipxd.playintegrityfix",  // PlayIntegrityFix Fork
            "app.zygisk.inject",  // ZygiskNext
            "io.github.vvb2060.keyattestation",  // KeyAttestation
            "io.github.vvb2060.zygisk",  // Zygisk
            "com.tsng.hidemyapplist",  // Hide My Applist / HMA
            "io.github.xiaotong6666.keydetector",  // KeyDetector
            "rikka.safetynetchecker",  // Yet Another SafetyNet Checker
            "gr.nikolasspyr.integritycheck",  // Play Integrity API Checker
            "com.draco.hidemyapps",  // Hide MyApps
        ),
        validator = { it.validatePackageNames() },
    )

    dependsOn(
        bytecodePatch {
            apply {
                forEachInstructionAsSequence(
                    match = { classDef, _, instruction, instructionIndex ->
                        filterMapInstructionVirtual<MethodCall>(
                            EXTENSION_CLASS_DESCRIPTOR,
                            EXTENSION_CLASS_DESCRIPTOR_PREFIX,
                            classDef,
                            instruction,
                            instructionIndex,
                        )
                    },
                    transform = { method, handler -> handler(method) },
                )
            }
        },
    )

    apply {
        val presetPackages = if (includeUnsafePackages == true) {
            listOf(
                rootManagerPackages,
                shizukuPackages,
                xposedPackages,
                moduleManagerPackages,
                rootCheckerPackages,
                playIntegrityPackages,
            ).flatMap { it ?: emptyList() }
        } else {
            emptyList()
        }

        val csv = (hiddenPackages ?: emptyList())
            .asSequence()
            .plus(presetPackages)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
            .joinToString(separator = "\n")

        if (csv.isEmpty()) throw PatchException("No packages to hide")

        firstClassDef { type == EXTENSION_CLASS_DESCRIPTOR }.apply {
            staticFields.first { it.name == HIDDEN_PACKAGES_FIELD_NAME }.initialValue =
                MutableStringEncodedValue(ImmutableStringEncodedValue(csv))
        }
    }
}

@Suppress("unused")
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    GetPackageInfo(
        "Landroid/content/pm/PackageManager;",
        "getPackageInfo",
        arrayOf("Ljava/lang/String;", "I"),
        "Landroid/content/pm/PackageInfo;",
    ),
    GetPackageInfoWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getPackageInfo",
        arrayOf("Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$PackageInfoFlags;"),
        "Landroid/content/pm/PackageInfo;",
    ),
    GetApplicationInfo(
        "Landroid/content/pm/PackageManager;",
        "getApplicationInfo",
        arrayOf("Ljava/lang/String;", "I"),
        "Landroid/content/pm/ApplicationInfo;",
    ),
    GetApplicationInfoWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getApplicationInfo",
        arrayOf("Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$ApplicationInfoFlags;"),
        "Landroid/content/pm/ApplicationInfo;",
    ),
    GetPackageUid(
        "Landroid/content/pm/PackageManager;",
        "getPackageUid",
        arrayOf("Ljava/lang/String;", "I"),
        "I",
    ),
    GetPackageUidWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getPackageUid",
        arrayOf("Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$PackageInfoFlags;"),
        "I",
    ),
    GetPackageGids(
        "Landroid/content/pm/PackageManager;",
        "getPackageGids",
        arrayOf("Ljava/lang/String;", "I"),
        "[I",
    ),
    GetPackageGidsWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getPackageGids",
        arrayOf("Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$PackageInfoFlags;"),
        "[I",
    ),
    GetInstalledPackages(
        "Landroid/content/pm/PackageManager;",
        "getInstalledPackages",
        arrayOf("I"),
        "Ljava/util/List;",
    ),
    GetInstalledPackagesWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getInstalledPackages",
        arrayOf($$"Landroid/content/pm/PackageManager$PackageInfoFlags;"),
        "Ljava/util/List;",
    ),
    GetInstalledApplications(
        "Landroid/content/pm/PackageManager;",
        "getInstalledApplications",
        arrayOf("I"),
        "Ljava/util/List;",
    ),
    GetInstalledApplicationsWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getInstalledApplications",
        arrayOf($$"Landroid/content/pm/PackageManager$ApplicationInfoFlags;"),
        "Ljava/util/List;",
    ),
    GetPackagesHoldingPermissions(
        "Landroid/content/pm/PackageManager;",
        "getPackagesHoldingPermissions",
        arrayOf("[Ljava/lang/String;", "I"),
        "Ljava/util/List;",
    ),
    GetPackagesHoldingPermissionsWithFlags(
        "Landroid/content/pm/PackageManager;",
        "getPackagesHoldingPermissions",
        arrayOf("[Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$PackageInfoFlags;"),
        "Ljava/util/List;",
    ),
    QueryIntentActivities(
        "Landroid/content/pm/PackageManager;",
        "queryIntentActivities",
        arrayOf("Landroid/content/Intent;", "I"),
        "Ljava/util/List;",
    ),
    QueryIntentActivitiesWithFlags(
        "Landroid/content/pm/PackageManager;",
        "queryIntentActivities",
        arrayOf("Landroid/content/Intent;", $$"Landroid/content/pm/PackageManager$ResolveInfoFlags;"),
        "Ljava/util/List;",
    ),
    QueryIntentServices(
        "Landroid/content/pm/PackageManager;",
        "queryIntentServices",
        arrayOf("Landroid/content/Intent;", "I"),
        "Ljava/util/List;",
    ),
    QueryIntentServicesWithFlags(
        "Landroid/content/pm/PackageManager;",
        "queryIntentServices",
        arrayOf("Landroid/content/Intent;", $$"Landroid/content/pm/PackageManager$ResolveInfoFlags;"),
        "Ljava/util/List;",
    ),
    QueryBroadcastReceivers(
        "Landroid/content/pm/PackageManager;",
        "queryBroadcastReceivers",
        arrayOf("Landroid/content/Intent;", "I"),
        "Ljava/util/List;",
    ),
    QueryBroadcastReceiversWithFlags(
        "Landroid/content/pm/PackageManager;",
        "queryBroadcastReceivers",
        arrayOf("Landroid/content/Intent;", $$"Landroid/content/pm/PackageManager$ResolveInfoFlags;"),
        "Ljava/util/List;",
    ),
    QueryContentProviders(
        "Landroid/content/pm/PackageManager;",
        "queryContentProviders",
        arrayOf("Ljava/lang/String;", "I", "I"),
        "Ljava/util/List;",
    ),
    QueryContentProvidersWithFlags(
        "Landroid/content/pm/PackageManager;",
        "queryContentProviders",
        arrayOf("Ljava/lang/String;", "I", $$"Landroid/content/pm/PackageManager$ComponentInfoFlags;"),
        "Ljava/util/List;",
    ),
    ResolveActivity(
        "Landroid/content/pm/PackageManager;",
        "resolveActivity",
        arrayOf("Landroid/content/Intent;", "I"),
        "Landroid/content/pm/ResolveInfo;",
    ),
    ResolveActivityWithFlags(
        "Landroid/content/pm/PackageManager;",
        "resolveActivity",
        arrayOf("Landroid/content/Intent;", $$"Landroid/content/pm/PackageManager$ResolveInfoFlags;"),
        "Landroid/content/pm/ResolveInfo;",
    ),
    ResolveService(
        "Landroid/content/pm/PackageManager;",
        "resolveService",
        arrayOf("Landroid/content/Intent;", "I"),
        "Landroid/content/pm/ResolveInfo;",
    ),
    ResolveServiceWithFlags(
        "Landroid/content/pm/PackageManager;",
        "resolveService",
        arrayOf("Landroid/content/Intent;", $$"Landroid/content/pm/PackageManager$ResolveInfoFlags;"),
        "Landroid/content/pm/ResolveInfo;",
    ),
    ResolveContentProvider(
        "Landroid/content/pm/PackageManager;",
        "resolveContentProvider",
        arrayOf("Ljava/lang/String;", "I"),
        "Landroid/content/pm/ProviderInfo;",
    ),
    ResolveContentProviderWithFlags(
        "Landroid/content/pm/PackageManager;",
        "resolveContentProvider",
        arrayOf("Ljava/lang/String;", $$"Landroid/content/pm/PackageManager$ComponentInfoFlags;"),
        "Landroid/content/pm/ProviderInfo;",
    ),
    GetLaunchIntentForPackage(
        "Landroid/content/pm/PackageManager;",
        "getLaunchIntentForPackage",
        arrayOf("Ljava/lang/String;"),
        "Landroid/content/Intent;",
    ),
    GetLeanbackLaunchIntentForPackage(
        "Landroid/content/pm/PackageManager;",
        "getLeanbackLaunchIntentForPackage",
        arrayOf("Ljava/lang/String;"),
        "Landroid/content/Intent;",
    ),
    GetPackagesForUid(
        "Landroid/content/pm/PackageManager;",
        "getPackagesForUid",
        arrayOf("I"),
        "[Ljava/lang/String;",
    ),
    GetNameForUid(
        "Landroid/content/pm/PackageManager;",
        "getNameForUid",
        arrayOf("I"),
        "Ljava/lang/String;",
    ),
    GetInstallerPackageName(
        "Landroid/content/pm/PackageManager;",
        "getInstallerPackageName",
        arrayOf("Ljava/lang/String;"),
        "Ljava/lang/String;",
    ),
    GetInstallSourceInfo(
        "Landroid/content/pm/PackageManager;",
        "getInstallSourceInfo",
        arrayOf("Ljava/lang/String;"),
        "Landroid/content/pm/InstallSourceInfo;",
    ),
    CheckSignaturesPackage(
        "Landroid/content/pm/PackageManager;",
        "checkSignatures",
        arrayOf("Ljava/lang/String;", "Ljava/lang/String;"),
        "I",
    ),
    CheckSignaturesUid(
        "Landroid/content/pm/PackageManager;",
        "checkSignatures",
        arrayOf("I", "I"),
        "I",
    ),
    GetInstalledPackagesAsUser(
        "Landroid/content/pm/PackageManager;",
        "getInstalledPackagesAsUser",
        arrayOf("I", "I"),
        "Ljava/util/List;",
    ),
    GetInstalledApplicationsAsUser(
        "Landroid/content/pm/PackageManager;",
        "getInstalledApplicationsAsUser",
        arrayOf("I", "I"),
        "Ljava/util/List;",
    ),
    GetPackageInfoAsUser(
        "Landroid/content/pm/PackageManager;",
        "getPackageInfoAsUser",
        arrayOf("Ljava/lang/String;", "I", "I"),
        "Landroid/content/pm/PackageInfo;",
    ),
    GetApplicationInfoAsUser(
        "Landroid/content/pm/PackageManager;",
        "getApplicationInfoAsUser",
        arrayOf("Ljava/lang/String;", "I", "I"),
        "Landroid/content/pm/ApplicationInfo;",
    ),
}
