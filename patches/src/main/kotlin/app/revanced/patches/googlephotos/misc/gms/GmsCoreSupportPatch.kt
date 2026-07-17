package app.revanced.patches.googlephotos.misc.gms

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.Option
import app.revanced.patches.googlephotos.misc.extension.extensionPatch
import app.revanced.patches.googlephotos.misc.gms.Constants.PHOTOS_PACKAGE_NAME
import app.revanced.patches.googlephotos.misc.gms.Constants.REVANCED_PHOTOS_PACKAGE_NAME
import app.revanced.patches.shared.misc.gms.gmsCoreSupportPatch
import app.revanced.util.findFreeRegister
import app.revanced.util.indexOfFirstInstructionOrThrow

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = PHOTOS_PACKAGE_NAME,
    toPackageName = REVANCED_PHOTOS_PACKAGE_NAME,
    getMainActivityOnCreateMethodToGetInsertIndex = BytecodePatchContext::homeActivityOnCreateMethod::get to {
        val index = homeActivityOnCreateMethod.indexOfFirstInstructionOrThrow {
            methodReference?.name == "getApplicationContext"
        }

        // Below the move-result-object instruction,
        // because the extension patch is used by the GmsCore support patch
        // which hooks the getApplicationContext call.
        index + 2
    },
    extensionPatch = extensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
    executeBlock = {
        val getAccountsWithFeatures =
            googleAuthGetAccountsMethod.immutableClassDef.getGoogleAuthGetAccountsWithFeaturesMethod()
        googleAuthGetAccountsMethod.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->getGoogleAccounts(Landroid/content/Context;)[Landroid/accounts/Account;
                move-result-object p0
                return-object p0
            """,
        )
        getAccountsWithFeatures.apply {
            val resultRegister = findFreeRegister(1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->getGoogleAccountsForFeatures(Landroid/content/Context;[Ljava/lang/String;)[Landroid/accounts/Account;
                    move-result-object v$resultRegister
                    if-eqz v$resultRegister, :stock_feature_query
                    return-object v$resultRegister
                """,
                ExternalLabel("stock_feature_query", getInstruction(0)),
            )
        }
    },
) {
    compatibleWith(PHOTOS_PACKAGE_NAME)
}

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/googlephotos/GmsCoreSupportPatch;"

private fun gmsCoreSupportResourcePatch(
    gmsCoreVendorGroupIdOption: Option<String>,
) = app.revanced.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
    fromPackageName = PHOTOS_PACKAGE_NAME,
    toPackageName = REVANCED_PHOTOS_PACKAGE_NAME,
    spoofedPackageSignature = "24bb24c05e47e0aefa68a58a766179d9b613a600",
    gmsCoreVendorGroupIdOption = gmsCoreVendorGroupIdOption,
)
