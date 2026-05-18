package app.revanced.patches.strava.route.export

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstImmutableClassDef
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.strava.misc.extension.sharedExtensionPatch

private const val ROUTE_EXPORT_CLASS_DESCRIPTOR = "Lapp/revanced/extension/strava/AddRouteExportPatch;"

@Suppress("unused")
val addRouteExportPatch = bytecodePatch(
    name = "Add route export",
    description = "Extends the route menu with items to export them in GPX or TCX formats.",
) {
    compatibleWith("com.strava")

    dependsOn(
        resourceMappingPatch,
        sharedExtensionPatch,
    )

    apply {
        // --- HOOK SUR L'OUVERTURE DU MENU DE PARTAGE ---
        // Strava utilise une activité dédiée pour afficher le BottomSheet de partage : ShareSheetActivity
        val shareSheetActivityClass = firstImmutableClassDef {
            type == "Lcom/strava/sharing/view/ShareSheetActivity;"
        }

        // On intercepte le onCreate pour afficher notre Dialog par-dessus !
        shareSheetActivityClass.firstMethodDeclaratively {
            name("onCreate")
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {p0}, $ROUTE_EXPORT_CLASS_DESCRIPTOR->showExportDialog(Landroid/app/Activity;)V
                """
            )
        }
    }
}
