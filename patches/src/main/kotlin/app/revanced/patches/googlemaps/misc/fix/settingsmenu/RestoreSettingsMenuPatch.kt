package app.revanced.patches.googlemaps.misc.fix.settingsmenu

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.googlemaps.misc.gms.Constants.MAPS_PACKAGE_NAME
import app.revanced.patches.googlemaps.misc.gms.mapsActivityOnCreateMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/shared/patches/GmsSettingsButton;"

/**
 * Restores the settings and account switch buttons in the search bar.
 *
 * The profile avatar button (SelectedAccountDisc) is missing because GmsCore doesn't
 * implement the InAppReach GMS service. Without the avatar, there's no access to
 * Settings, account switching, or profile management.
 *
 * This patch:
 * - Adds a settings gear and account switch button in the avatar's ViewGroup container
 * - Intercepts onActivityResult to catch account picker selections and trigger Maps'
 *   native account switch dialog
 */
@Suppress("unused")
val restoreSettingsMenuPatch = bytecodePatch(
    name = "Restore settings menu",
    description = "Restores settings and account switch buttons that are missing when using GmsCore.",
) {
    compatibleWith(
        MAPS_PACKAGE_NAME(
            "26.05.05",
            "26.07.05",
            "26.08.02",
            "26.09.00",
            "26.09.03",
            "26.09.06",
            "26.11.03",
        ),
    )

    execute {
        // Resolve obfuscated class/method names and inject them into extension fields.

        // Dialog factory: static method that creates a SWITCH_ACCOUNTS dialog fragment.
        val dialogFactoryMethod = switchAccountsDialogFactoryMethod
        val dialogFactoryClassName = dialogFactoryMethod.definingClass
            .removePrefix("L").removeSuffix(";")
        val dialogFactoryMethodName = dialogFactoryMethod.name

        // The return type is the dialog fragment subclass.
        val dialogReturnType = dialogFactoryMethod.returnType

        // Settings fragment: found by "settingsVeneer" string.
        val settingsClassName = settingsFragmentMethod.definingClass
            .removePrefix("L").removeSuffix(";")

        // Build superclass chains for the activity and dialog to find the show fragment method.
        fun superclassChain(type: String): Set<String> {
            val chain = mutableSetOf(type)
            var current = type
            while (true) {
                val superType = classDefs.firstOrNull { it.type == current }?.superclass ?: break
                if (superType == "Ljava/lang/Object;") break
                chain.add(superType)
                current = superType
            }
            return chain
        }

        val activityChain = superclassChain(mapsActivityOnCreateMethod.definingClass)
        val fragmentChain = superclassChain(dialogReturnType)

        // The show fragment method is a static void method taking
        // (ActivitySuperclass, FragmentSuperclass) as parameters.
        val showFragmentMethod = classDefs.asSequence()
            .flatMap { it.methods.asSequence() }
            .first { method ->
                method.accessFlags and 0x8 != 0 && // ACC_STATIC
                    method.returnType == "V" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] in activityChain &&
                    method.parameterTypes[1] in fragmentChain
            }

        val fragmentShowerClassName = showFragmentMethod.definingClass
            .removePrefix("L").removeSuffix(";")
        val fragmentShowerMethodName = showFragmentMethod.name
        val param1ClassName = showFragmentMethod.parameterTypes[0].toString()
            .removePrefix("L").removeSuffix(";")
        val param2ClassName = showFragmentMethod.parameterTypes[1].toString()
            .removePrefix("L").removeSuffix(";")


        // Inject all class/method names into extension static fields.
        setupAccountDiscMethod.apply {
            addInstructions(
                0,
                """
                    const-string v0, "$dialogFactoryClassName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->DIALOG_FACTORY_CLASS_NAME:Ljava/lang/String;
                    const-string v0, "$dialogFactoryMethodName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->DIALOG_FACTORY_METHOD_NAME:Ljava/lang/String;
                    const-string v0, "$fragmentShowerClassName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->FRAGMENT_SHOWER_CLASS_NAME:Ljava/lang/String;
                    const-string v0, "$fragmentShowerMethodName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->FRAGMENT_SHOWER_METHOD_NAME:Ljava/lang/String;
                    const-string v0, "$param1ClassName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->FRAGMENT_SHOWER_PARAM1_CLASS_NAME:Ljava/lang/String;
                    const-string v0, "$param2ClassName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->FRAGMENT_SHOWER_PARAM2_CLASS_NAME:Ljava/lang/String;
                    const-string v0, "$settingsClassName"
                    sput-object v0, ${EXTENSION_CLASS_DESCRIPTOR}->SETTINGS_FRAGMENT_CLASS_NAME:Ljava/lang/String;
                    invoke-static { p1 }, ${EXTENSION_CLASS_DESCRIPTOR}->addSettingsButton(Landroid/view/ViewGroup;)V
                """,
            )
        }

        // Intercept onActivityResult for account picker.
        // After the user selects an account from the system picker, we catch the result
        // and trigger Maps' native account switch dialog.
        mapsActivityOnCreateMethod.classDef.methods.first {
            it.name == "onActivityResult" &&
                it.parameterTypes == listOf("I", "I", "Landroid/content/Intent;")
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static { p0, p1, p2, p3 }, ${EXTENSION_CLASS_DESCRIPTOR}->onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)Z
                    move-result v0
                    if-eqz v0, :not_handled
                    return-void
                    :not_handled
                    nop
                """,
            )
        }
    }
}
