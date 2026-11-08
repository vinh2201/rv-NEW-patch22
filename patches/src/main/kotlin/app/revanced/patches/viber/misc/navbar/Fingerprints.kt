package app.revanced.patches.viber.misc.navbar

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val BOTTOM_NAVIGATION_VIEW = "Lcom/viber/voip/ui/bottomnavigation/BottomNavigationView;"

/**
 * Constructor of the class that wraps the "shouldShowTabId" lambda (Viber 23.x - 26.x).
 */
internal val BytecodePatchContext.shouldShowTabIdAnchorMethod by gettingFirstImmutableMethodOrNull("shouldShowTabId")

/**
 * Method that builds the list of tabs (Viber 29.x and newer).
 * It throws an exception with the message "Home tabs count(" if the number of tabs is not valid.
 */
internal val BytecodePatchContext.homeTabsCountAnchorMethod by gettingFirstImmutableMethodOrNull("Home tabs count(")

/**
 * Viber 23.x - 26.x: `a(tabId, position)Z` of the class that holds the "shouldShowTabId" function.
 * It is only called for the optional tabs (Explore, Marketplace, Pay, Camera).
 */
internal fun BytecodePatchContext.findLegacyTabVisibilityMethod(): MethodReference? {
    val anchor = shouldShowTabIdAnchorMethod ?: return null
    val tabConfigClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return tabConfigClass.methods.singleOrNull { method ->
        method.returnType == "Z" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == "I" &&
            method.parameterTypes[1] == "I"
    }
}

/**
 * Viber 29.x and newer: `static b(ArrayList, int tabId, Config)Z`, called by the method that builds
 * the tab list once for every tab. Returns true if the tab should be added to the list.
 */
internal fun BytecodePatchContext.findStaticTabVisibilityMethod(): MethodReference? {
    val anchor = homeTabsCountAnchorMethod ?: return null
    val instructions = anchor.implementation?.instructions ?: return null

    return instructions.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null

        val reference = (instruction as ReferenceInstruction).reference as? MethodReference
            ?: return@firstNotNullOfOrNull null

        val parameters = reference.parameterTypes
        if (reference.returnType == "Z" &&
            parameters.size == 3 &&
            parameters[0] == "Ljava/util/ArrayList;" &&
            parameters[1] == "I"
        ) {
            reference
        } else {
            null
        }
    }
}

/**
 * A `const/4 vX, 0x3` that is the lower limit of the number of tabs.
 */
internal class MinimumTabCount(val method: Method, val index: Int, val register: Int)

/**
 * Viber 29.x: the `<clinit>` of the tab visibility class creates `IntRange(3, 5)`: the allowed number of tabs.
 */
internal fun BytecodePatchContext.findTabCountRange(ownerType: String): MinimumTabCount? {
    val owner = classDefs.firstOrNull { it.type == ownerType } ?: return null
    val staticInitializer = owner.methods.firstOrNull { it.name == "<clinit>" } ?: return null
    val instructions = staticInitializer.implementation?.instructions?.toList() ?: return null

    for (index in 0 until instructions.size - 2) {
        val newInstance = instructions[index]
        if (newInstance.opcode != Opcode.NEW_INSTANCE) continue
        if (((newInstance as ReferenceInstruction).reference as? TypeReference)?.type != "Lkotlin/ranges/IntRange;") continue

        val lower = instructions[index + 1]
        val upper = instructions[index + 2]
        if (lower.opcode == Opcode.CONST_4 && (lower as NarrowLiteralInstruction).narrowLiteral == 3 &&
            upper.opcode == Opcode.CONST_4 && (upper as NarrowLiteralInstruction).narrowLiteral == 5
        ) {
            return MinimumTabCount(staticInitializer, index + 1, (lower as OneRegisterInstruction).registerA)
        }
    }

    return null
}

/**
 * All versions: the code that inflates the bottom navigation does not build it with less than 3 tabs:
 * ```
 * const/4 v0, 0x3
 * if-gt v0, vSize, :skip
 * ```
 */
internal fun BytecodePatchContext.findBottomNavigationMinimumTabCounts(): List<MinimumTabCount> {
    val result = mutableListOf<MinimumTabCount>()

    classDefs.forEach { classDef ->
        classDef.methods.forEach methods@{ method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@methods

            val referencesBottomNavigation = instructions.any { instruction ->
                instruction is ReferenceInstruction &&
                    (instruction.reference as? TypeReference)?.type == BOTTOM_NAVIGATION_VIEW
            }
            if (!referencesBottomNavigation) return@methods

            for (index in 0 until instructions.size - 1) {
                val literal = instructions[index]
                val comparison = instructions[index + 1]

                if (literal.opcode == Opcode.CONST_4 &&
                    (literal as NarrowLiteralInstruction).narrowLiteral == 3 &&
                    comparison.opcode == Opcode.IF_GT &&
                    (comparison as TwoRegisterInstruction).registerA == (literal as OneRegisterInstruction).registerA
                ) {
                    result += MinimumTabCount(method, index, (literal as OneRegisterInstruction).registerA)
                }
            }
        }
    }

    return result
}
