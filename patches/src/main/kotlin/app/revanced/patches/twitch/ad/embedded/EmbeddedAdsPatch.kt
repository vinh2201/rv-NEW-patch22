package app.revanced.patches.twitch.ad.embedded

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.InputType
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.shared.misc.settings.preference.TextPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

@Suppress("unused")
val blockEmbeddedAdsPatch = bytecodePatch(
    name = "Block embedded ads",
    description = "Routes the live stream playlist through a Luminous proxy to remove server-stitched " +
        "(SureStream) ads. Sends the channel name to the configured proxy host.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    // Hardcodes the R8-obfuscated class name `z4m`, which is reassigned on every rebuild.
    // Not general: must stay pinned to the exact version this was reverse-engineered against.
    compatibleWith("tv.twitch.android.app"("29.7.1"))

    apply {
        addResources("twitch", "ad.embedded.embeddedAdsPatch")

        PreferenceScreen.ADS.GENERAL.addPreferences(
            SwitchPreference("revanced_block_embedded_ads"),
            TextPreference("revanced_embedded_ads_proxy_host", inputType = InputType.TEXT),
        )

        rawManifestMethod.addInstructions(
            0,
            """
                iget-object v0, p0, Lz4m;->a:Ljava/lang/String;
                iget-object v1, p0, Lz4m;->c:Ltv/twitch/android/models/AccessTokenResponse;
                invoke-virtual { v1 }, Ltv/twitch/android/models/AccessTokenResponse;->getToken()Ljava/lang/String;
                move-result-object v1
                invoke-static { v0, v1 }, Lapp/revanced/extension/twitch/patches/EmbeddedAdsPatch;->proxyManifest(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )
    }
}
