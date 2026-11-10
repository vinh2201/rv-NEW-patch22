package app.revanced.patches.viber.misc.analytics

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val FIREBASE_ANALYTICS_KEY = "firebase_analytics_collection_enabled"
private const val ADID_COLLECTION_KEY = "google_analytics_adid_collection_enabled"

/** Crashlytics: logs the source of the data collection setting ("firebase_crashlytics_collection_enabled manifest flag"). */
internal val BytecodePatchContext.crashlyticsAnchor by gettingFirstImmutableMethodOrNull(
    "firebase_crashlytics_collection_enabled manifest flag",
)

/** Registry of the Viber trackers (Mixpanel, Adjust, Braze...). */
internal val BytecodePatchContext.trackerRegistryAnchor by gettingFirstImmutableMethodOrNull(
    "There is no tracker for event: ",
)

private fun Instruction.isMethodCallNamed(name: String, returnType: String): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_INTERFACE) return false

    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return reference.name == name && reference.returnType == returnType
}

/**
 * Viber 23.x - 29.x: `public final ()Z` of the class that enables the trackers. It returns true when
 * the carrier is not a zero rate carrier, and trackers are only enabled when it returns true:
 * ```
 * return !SOME_FEATURE.isEnabled();
 * ```
 * Only one method has this shape in each version.
 */
internal fun BytecodePatchContext.findTrackerAllowedMethod(): Method? {
    val candidates = mutableListOf<Method>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != "Z" || method.parameterTypes.isNotEmpty()) continue

            val flags = method.accessFlags
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.FINAL.isSet(flags) || AccessFlags.STATIC.isSet(flags)) {
                continue
            }

            val instructions = method.implementation?.instructions ?: continue
            if (instructions.any { it.opcode == Opcode.XOR_INT_LIT8 } &&
                instructions.any { it.isMethodCallNamed("isEnabled", "Z") }
            ) {
                candidates += method
            }
        }
    }

    if (candidates.size <= 1) return candidates.firstOrNull()

    // Several matches: the right class refreshes the state of the trackers 300 (seconds / milliseconds) after a change.
    return candidates.singleOrNull { candidate ->
        classDefs.first { it.type == candidate.definingClass }.methods.any { method ->
            method.implementation?.instructions?.any {
                (it as? WideLiteralInstruction)?.wideLiteral == 300L
            } == true
        }
    }
}

/** `Lcom/snapchat/kit/sdk/SnapKitInitProvider;->onCreate()Z` */
internal fun BytecodePatchContext.findSnapKitInitMethod(): Method? {
    val provider = classDefs.firstOrNull { it.type == "Lcom/snapchat/kit/sdk/SnapKitInitProvider;" } ?: return null

    return provider.methods.firstOrNull {
        it.name == "onCreate" && it.returnType == "Z" && it.parameterTypes.isEmpty()
    }
}

/**
 * Several methods contain the strings of Firebase (the static initializer that declares them, the getters...),
 * so the method is searched by its signature: it returns `Boolean` and has the right parameters.
 */
private fun BytecodePatchContext.findBooleanObjectMethodWithString(
    string: String,
    hasParameters: (Method) -> Boolean,
): Method? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != "Ljava/lang/Boolean;" || !hasParameters(method)) continue

            val containsString = method.implementation?.instructions?.any {
                it is ReferenceInstruction && (it.reference as? StringReference)?.string == string
            } == true

            if (containsString) return method
        }
    }

    return null
}

/** Firebase Sessions: `Boolean isSessionsEnabled()`. */
internal fun BytecodePatchContext.findFirebaseSessionsMethod(): Method? =
    findBooleanObjectMethodWithString("firebase_sessions_enabled") { it.parameterTypes.isEmpty() }

/** Reads a boolean from the manifest metadata of Firebase / Google Analytics: `Boolean read(String key)`. */
private fun BytecodePatchContext.findFirebaseMetadataReader(): Method? =
    findBooleanObjectMethodWithString("Failed to load metadata: Metadata bundle is null") {
        it.parameterTypes.size == 1 && it.parameterTypes[0] == "Ljava/lang/String;"
    }

/**
 * `boolean isDataCollectionEnabled()` of Crashlytics.
 * In Viber 26.x and newer the string is inside this method, in 23.x it is in the method that logs the
 * setting, so the method is searched in the class.
 */
internal fun BytecodePatchContext.findCrashlyticsEnabledMethod(): Method? {
    val anchor = crashlyticsAnchor ?: return null
    if (anchor.returnType == "Z" && anchor.parameterTypes.isEmpty()) return anchor

    val crashlyticsClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null
    return crashlyticsClass.methods.singleOrNull {
        it.returnType == "Z" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
}

internal class TrackerConsent(val method: Method, val setEnabled: MethodReference)

/**
 * Viber 29.x: the method of the tracker registry that enables or disables a tracker depending on the
 * consent of the user: `void a(Tracker)`, it calls `tracker.setEnabled(boolean)`,
 * `tracker.isEnabled()` and `tracker.setEnabled(boolean)`.
 * Older versions do this in another place and are covered by the tracker allowed method.
 */
internal fun BytecodePatchContext.findTrackerConsent(): TrackerConsent? {
    val anchor = trackerRegistryAnchor ?: return null
    val registryClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    for (method in registryClass.methods) {
        val flags = method.accessFlags
        if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.FINAL.isSet(flags)) continue
        if (method.returnType != "V" || method.parameterTypes.size != 1 || !method.parameterTypes[0].startsWith("L")) {
            continue
        }

        val calls = method.implementation?.instructions
            ?.filter { it.opcode == Opcode.INVOKE_INTERFACE }
            ?.mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            ?: continue

        // setEnabled(boolean), then isEnabled(), then setEnabled(boolean).
        val firstSetEnabled = calls.indexOfFirst { it.isSetEnabled() }
        if (firstSetEnabled < 0) continue

        val isEnabled = (firstSetEnabled + 1 until calls.size).firstOrNull {
            calls[it].parameterTypes.isEmpty() && calls[it].returnType == "Z"
        } ?: continue
        if ((isEnabled + 1 until calls.size).none { calls[it].isSetEnabled() }) continue

        val setEnabled = calls[firstSetEnabled]
        if (setEnabled.definingClass != method.parameterTypes[0].toString()) continue

        return TrackerConsent(method, setEnabled)
    }

    return null
}

private fun MethodReference.isSetEnabled() =
    returnType == "V" && parameterTypes.size == 1 && parameterTypes[0] == "Z"

internal class MetadataFlag(val method: Method, val moveResultIndex: Int, val register: Int)

/**
 * Places that read `firebase_analytics_collection_enabled` and `google_analytics_adid_collection_enabled`
 * from the manifest:
 * ```
 * const-string v0, "firebase_analytics_collection_enabled"
 * invoke-virtual { p0, v0 }, Reader->readBoolean(String)Boolean
 * move-result-object v0
 * ```
 * There are one or two places for each key, depending on the Viber version.
 */
internal fun BytecodePatchContext.findMetadataFlags(): List<MetadataFlag> {
    val reader = findFirebaseMetadataReader() ?: return emptyList()

    val result = mutableListOf<MetadataFlag>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            for (index in 0 until instructions.size - 2) {
                val instruction = instructions[index]
                if (instruction !is ReferenceInstruction) continue

                val string = (instruction.reference as? StringReference)?.string ?: continue
                if (string != FIREBASE_ANALYTICS_KEY && string != ADID_COLLECTION_KEY) continue

                val call = instructions[index + 1]
                val moveResult = instructions[index + 2]
                if (call !is ReferenceInstruction || call.reference != reader) continue
                if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) continue

                result += MetadataFlag(method, index + 2, (moveResult as OneRegisterInstruction).registerA)
            }
        }
    }

    return result
}
