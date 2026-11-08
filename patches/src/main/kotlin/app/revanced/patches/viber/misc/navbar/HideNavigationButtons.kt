package app.revanced.patches.viber.misc.navbar

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import java.util.logging.Logger

@Suppress("unused")
val hideNavigationButtonsPatch = bytecodePatch(
    name = "Hide navigation buttons",
    description = "Permanently hides navigation bar buttons, such as Explore and Marketplace. " +
        "Works with Viber 23.x, 26.x and 29.x, and should keep working with newer versions.",
    use = false,
) {
    compatibleWith("com.viber.voip")

    val hideOptions = NavigationItem.entries.associateWith {
        booleanOption(
            name = it.optionName,
            default = it.defaultHideOption,
            description = it.description,
        )
    }

    apply {
        val logger = Logger.getLogger(this::class.java.name)

        val hiddenIds = hideOptions
            .filter { (_, option) -> option.value == true }
            .keys
            .flatMap { it.ids.toList() }
            .distinct()

        if (hiddenIds.isEmpty()) {
            return@apply logger.warning("No hide navigation buttons options are enabled. No changes applied.")
        }

        val visibilityMethods = listOfNotNull(
            findLegacyTabVisibilityMethod(),
            findStaticTabVisibilityMethod(),
        )

        if (visibilityMethods.isEmpty()) {
            throw PatchException("Could not find the navigation tabs method. This Viber version is not supported.")
        }

        visibilityMethods.forEach { reference ->
            firstMethod(reference).hideTabs(hiddenIds)

            // Viber 29.x only: the number of tabs must be in the range 3..5, otherwise the app crashes.
            findTabCountRange(reference.definingClass)?.let { range ->
                firstMethod(range.method).replaceInstruction(range.index, "const/4 v${range.register}, 0x2")
            }
        }

        // Viber doesn't build the bottom navigation with less than 3 tabs.
        val minimumTabCounts = findBottomNavigationMinimumTabCounts()
        if (minimumTabCounts.isEmpty()) {
            logger.warning("Could not find the minimum tab count check. Hiding more than 2 tabs may not work.")
        }

        minimumTabCounts.forEach { minimum ->
            firstMethod(minimum.method).replaceInstruction(minimum.index, "const/4 v${minimum.register}, 0x2")
        }
    }
}

/**
 * Makes the tab visibility method return false for the hidden tab ids.
 * Every other tab id (including tabs added by future Viber versions) keeps its original behavior.
 *
 * The tab id is always `p1`: `a(int tabId, int position)` (instance) or `b(ArrayList, int tabId, Config)` (static).
 */
private fun MutableMethod.hideTabs(ids: List<Int>) {
    val initialRegisters = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val parameterRegisters = parameterTypes.fold(initialRegisters) { count, type ->
        count + if (type[0] == 'J' || type[0] == 'D') 2 else 1
    }

    // v0 is used as a temporary register, it must not be a parameter register.
    if (implementation!!.registerCount - parameterRegisters < 1) {
        throw PatchException("No free register in $definingClass->$name")
    }

    val instructions = buildString {
        ids.forEachIndexed { index, id ->
            appendLine("const/16 v0, 0x${id.toString(16)}")

            // The last comparison jumps to the original code, the others to the code that hides the tab.
            if (index == ids.lastIndex) {
                appendLine("if-ne p1, v0, :original")
            } else {
                appendLine("if-eq p1, v0, :hide")
            }
        }

        appendLine(":hide")
        appendLine("const/4 v0, 0x0")
        appendLine("return v0")
    }

    addInstructionsWithLabels(0, instructions, ExternalLabel("original", getInstruction(0)))
}

/**
 * Navigation items taken from source code.
 * They appear in code like new NavigationItem(0, R.string.bottom_tab_chats, R.drawable.ic_tab_chats).
 *
 * Chats and More are not optional tabs in Viber 23.x - 26.x, hiding them has no effect there.
 * In Viber 29.x and newer hiding them can break the app.
 */
private enum class NavigationItem(
    val defaultHideOption: Boolean,
    private val itemName: String,
    vararg val ids: Int,
) {
    CHATS(false, "Chats", 0),
    CALLS(false, "Calls", 1, 7),
    EXPLORE(true, "Explore", 2),
    MORE(false, "More", 3),
    PAY(true, "Pay", 5),
    CAMERA(true, "Camera", 6),
    MARKETPLACE(true, "Marketplace", 8),
    SHORTS(true, "Shorts", 10),
    CHATGPT(true, "ChatGPT", 11, 12),
    ;

    val optionName = "Hide $itemName"
    val description = "Permanently hides the $itemName button."
}
