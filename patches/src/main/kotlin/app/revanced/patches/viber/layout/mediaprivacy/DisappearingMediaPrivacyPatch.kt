package app.revanced.patches.viber.layout.mediaprivacy

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

/** Event type sent when a screenshot is taken. */
private const val SCREENSHOT_EVENT = 2

@Suppress("unused")
val disappearingMediaPrivacyPatch = bytecodePatch(
    name = "Disappearing media privacy",
    description = "(PORTED) Stops screenshot alerts for disappearing messages. " +
        "Opening reports are not ported yet. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val logger = Logger.getLogger(this::class.java.name)

        // The screenshot alert is not sent.
        val screenshotHandlers = findScreenshotTakenMethods()
        screenshotHandlers.forEach { firstMethod(it).returnEarly() }

        // Anything that sends a screenshot event is dropped, in case it is sent from somewhere else.
        val sender = findSecretChatEventSender()
        if (sender == null) {
            if (screenshotHandlers.isEmpty()) {
                throw PatchException("Could not find the screenshot alert. This Viber version is not supported.")
            }

            logger.warning("Could not find the secret chat event sender.")
            return@apply
        }

        val method = firstMethod(sender.method)

        // v0 is used as a temporary register, it must not be a parameter register.
        val parameterRegisters = method.parameterTypes.fold(1) { count, type ->
            count + if (type[0] == 'J' || type[0] == 'D') 2 else 1
        }
        if (method.implementation!!.registerCount - parameterRegisters < 1 || sender.eventTypeRegister > 15) {
            throw PatchException("No free register in the secret chat event sender.")
        }

        method.addInstructionsWithLabels(
            0,
            """
                const/4 v0, $SCREENSHOT_EVENT
                if-ne v${sender.eventTypeRegister}, v0, :original
                return-void
            """,
            ExternalLabel("original", method.getInstruction(0)),
        )
    }
}
