package app.revanced.patches.facebook.ads.mainfeed

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

/*
 * Hide 'Sponsored Stories'  (sponsored / promoted posts in the news feed)  -  one self-contained file.
 * Delete the old mainfeed/Fingerprints.kt: it is no longer used.
 *
 * Covers Facebook 490.0.0.63.82 .. 585.0.0.47.54.
 *
 *   Layer 1 (preferred): the feed's single funnel. Every news-feed edge goes through
 *            FeedUnitCollectionManager.addNewEdgeToCollection(ImmutableList$Builder, GraphQLFeedUnitEdge, Object)Z
 *            (Redex keeps its name; present on 490, 521 and, up to 585). A guard at its start asks
 *            a small static helper added to GraphQLFeedUnitEdge whether the edge's story category (the enum
 *            GraphQLFeedStoryCategory) is SPONSORED or PROMOTION and, if so, returns false = "edge not added", an outcome
 *            the app already handles. Nothing downstream sees the ad: no gap, no impression.
 *            From 583 on, the funnel only wraps a method of its own class (static on 583, private instance on 585) that
 *            the feed's batch add also calls, so the guard then goes on that method.
 *
 *   Layer 2 (fallback, only when layer 1 applied nothing): the earlier approach. The static method that returns the
 *            visibility String of a story (found by the string "This should not be called for base class object" and a
 *            3x instance-of/if-nez shape) is made to return "GONE" when GraphQLStory has non-null sponsored data. The
 *            sponsored-data accessor is read from the SponsoredImpression method (it holds the same marker string).
 *
 * Verified on the decompiled smali of 490.0.0.63.82 and 521.0.0.42.97 (Android 11 builds).
 * On the Android 8 builds the classes live in Facebook's Dextricks stores, which the patcher cannot see.
 */

private const val EDGE = "Lcom/facebook/graphql/model/GraphQLFeedUnitEdge;"
private const val BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"
private const val CATEGORY_SUFFIX = "/GraphQLFeedStoryCategory;"
private const val FUNNEL_NAME = "addNewEdgeToCollection"
private const val HELPER_NAME = "isSponsoredEdge"

private const val STORY = "Lcom/facebook/graphql/model/GraphQLStory;"
private const val STORY_VISIBILITY_MARKER = "This should not be called for base class object"
private const val SPONSORED_IMPRESSION = "Lcom/facebook/graphql/model/SponsoredImpression;"
private const val STORY_HELPER_NAME = "getSponsoredData"

private val LOG: Logger = Logger.getLogger("HideSponsoredStories")

private fun CharSequence.isWide() = toString() == "J" || toString() == "D"

private fun registersOfParameters(types: List<CharSequence>): Int = types.fold(0) { total, type -> total + if (type.isWide()) 2 else 1 }

/** Number of the first register that holds a parameter (the same numbering invoke instructions use). */
private fun firstParameterRegister(method: Method): Int {
    val registers = method.implementation?.registerCount ?: return -1
    val receiver = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    return registers - registersOfParameters(method.parameterTypes) - receiver
}

/** Free local registers at the start of the method (v0 .. v(n-1) are untouched before the first instruction). */
private fun localRegisters(method: Method): Int = firstParameterRegister(method)

/** Smali name (pN) of the parameter at [index]. */
private fun parameterName(method: Method, index: Int): String {
    var number = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    for (i in 0 until index) number += if (method.parameterTypes[i].isWide()) 2 else 1
    return "p$number"
}

/** Absolute register number of the parameter at [index]. */
private fun parameterRegisterNumber(method: Method, index: Int): Int {
    var offset = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    for (i in 0 until index) offset += if (method.parameterTypes[i].isWide()) 2 else 1
    return firstParameterRegister(method) + offset
}

private fun argumentRegisters(invoke: Instruction): List<Int> = when (invoke) {
    is RegisterRangeInstruction -> (invoke.startRegister until invoke.startRegister + invoke.registerCount).toList()
    is FiveRegisterInstruction ->
        listOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF, invoke.registerG)
            .take(invoke.registerCount)
    else -> emptyList()
}

/**
 * The method of the funnel's own class that the funnel only wraps, or null when the funnel does the work itself.
 * Wrapper = no branch, no throw, exactly one call to a method of its own class that takes the edge and answers a
 * boolean, made first and passed the funnel's own edge, then move-result + return of that answer.
 */
private fun outlinedBody(funnel: Method): MethodReference? {
    val code = funnel.implementation?.instructions?.toList() ?: return null
    if (code.any { it is OffsetInstruction || it.opcode == Opcode.THROW }) return null
    val instance = !AccessFlags.STATIC.isSet(funnel.accessFlags)
    val invoke = code.firstOrNull() ?: return null
    val call = (invoke as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val isStatic = invoke.opcode == Opcode.INVOKE_STATIC || invoke.opcode == Opcode.INVOKE_STATIC_RANGE
    val isDirect = instance && (invoke.opcode == Opcode.INVOKE_DIRECT || invoke.opcode == Opcode.INVOKE_DIRECT_RANGE)
    if (!isStatic && !isDirect) return null
    if (call.definingClass != funnel.definingClass || call.returnType != "Z") return null
    if (call.parameterTypes.none { it.toString() == EDGE }) return null
    val result = code.getOrNull(1)
    val returned = code.getOrNull(2)
    if (result?.opcode != Opcode.MOVE_RESULT || returned?.opcode != Opcode.RETURN || code.lastIndex != 2) return null
    if ((result as OneRegisterInstruction).registerA != (returned as OneRegisterInstruction).registerA) return null

    // The edge handed over must be the funnel's own edge parameter.
    val passed = argumentRegisters(invoke)
    val receiver = if (isStatic) 0 else 1
    if (receiver == 1 && passed.firstOrNull() != firstParameterRegister(funnel)) return null // 'this'
    val edgeInCall = call.parameterTypes.indexOfFirst { it.toString() == EDGE }
    val passedEdge = passed.getOrNull(receiver + registersOfParameters(call.parameterTypes.take(edgeInCall)))
    val ownEdge = funnel.parameterTypes.indexOfFirst { it.toString() == EDGE }
    if (ownEdge < 0 || passedEdge != parameterRegisterNumber(funnel, ownEdge)) return null
    return call
}

// ---------------------------------------------------------------------------------------------------------------------
// Layer 1
// ---------------------------------------------------------------------------------------------------------------------

private data class FunnelTarget(val definingClass: String, val name: String, val parameterTypes: List<String>, val returnType: String)

private fun Method.toFunnelTarget() = FunnelTarget(definingClass, name, parameterTypes.map { it.toString() }, returnType)

private fun MethodReference.toFunnelTarget() = FunnelTarget(definingClass, name, parameterTypes.map { it.toString() }, returnType)

/** Returns how many funnel guards were installed (0 = this layer does not apply to the build). */
private fun BytecodePatchContext.installFeedFunnelGuard(): Int {
    val edgeClass = classDefs[EDGE] ?: run {
        LOG.info("Hide 'Sponsored Stories': layer 1: $EDGE not in this build")
        return 0
    }

    val categoryGetters = edgeClass.methods.filter {
        it.parameterTypes.isEmpty() && it.returnType.endsWith(CATEGORY_SUFFIX) && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val categoryGetter = categoryGetters.singleOrNull() ?: run {
        LOG.warning("Hide 'Sponsored Stories': layer 1: expected 1 GraphQLFeedStoryCategory getter on the edge, found ${categoryGetters.size}")
        return 0
    }
    // The helper calls Enum.name() on the category: only safe when the category really is an enum.
    val categoryClass = classDefs[categoryGetter.returnType]
    if (categoryClass == null || categoryClass.superclass != "Ljava/lang/Enum;") {
        LOG.warning("Hide 'Sponsored Stories': layer 1: ${categoryGetter.returnType} is not a java.lang.Enum subclass")
        return 0
    }

    // Find the funnel(s). Read only; nothing is changed until all targets are known.
    val funnels = classDefs.toList().flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == FUNNEL_NAME &&
                method.returnType == "Z" &&
                method.implementation != null &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].toString() == BUILDER &&
                method.parameterTypes[1].toString() == EDGE &&
                method.parameterTypes[2].toString().startsWith("L")
        }
    }
    if (funnels.isEmpty()) {
        LOG.info("Hide 'Sponsored Stories': layer 1: no $FUNNEL_NAME(Builder, Edge, Object)Z in this build")
        return 0
    }

    val targets = LinkedHashSet<FunnelTarget>()
    funnels.forEach { funnel ->
        val body = outlinedBody(funnel)
        if (body != null) {
            LOG.info("Hide 'Sponsored Stories': layer 1: ${funnel.definingClass}->$FUNNEL_NAME wraps ${body.name}; guarding the body")
            targets += body.toFunnelTarget()
        } else {
            targets += funnel.toFunnelTarget()
        }
    }

    // ---- Change ------------------------------------------------------------------------------------------------
    val mutableEdge = classDefs.getOrReplaceMutable(edgeClass)
    if (mutableEdge.methods.none { it.name == HELPER_NAME }) {
        mutableEdge.methods.add(sponsoredEdgeHelper(categoryGetter))
    }

    var installed = 0
    targets.forEach { target ->
        val classDef = classDefs[target.definingClass]
        val method = classDef?.let { classDefs.getOrReplaceMutable(it) }?.methods?.firstOrNull {
            it.name == target.name && it.returnType == target.returnType &&
                it.parameterTypes.map { type -> type.toString() } == target.parameterTypes
        }
        if (method == null) {
            LOG.warning("Hide 'Sponsored Stories': layer 1: ${target.definingClass}->${target.name} not found when patching")
            return@forEach
        }
        val edgeIndex = method.parameterTypes.indexOfFirst { it.toString() == EDGE }
        if (edgeIndex < 0 || localRegisters(method) < 1) {
            LOG.warning("Hide 'Sponsored Stories': layer 1: ${target.definingClass}->${target.name} has no edge parameter or no free local register")
            return@forEach
        }
        val edge = parameterName(method, edgeIndex)
        // invoke-static/range because the edge parameter can sit above v15. v0 is free at index 0.
        // :fx_keep is placed on a nop that falls through to the method's original first instruction.
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {$edge .. $edge}, $EDGE->$HELPER_NAME($EDGE)Z
                move-result v0
                if-eqz v0, :fx_keep
                const/4 v0, 0x0
                return v0
                :fx_keep
                nop
            """,
        )
        installed++
        LOG.info("Hide 'Sponsored Stories': layer 1: guard installed in ${target.definingClass}->${target.name}")
    }
    return installed
}

/** static boolean isSponsoredEdge(GraphQLFeedUnitEdge): category name is SPONSORED or PROMOTION. */
private fun sponsoredEdgeHelper(categoryGetter: Method): MutableMethod =
    ImmutableMethod(
        EDGE,
        HELPER_NAME,
        listOf(ImmutableMethodParameter(EDGE, null, null)),
        "Z",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-virtual {p0}, $EDGE->${categoryGetter.name}()${categoryGetter.returnType}
                move-result-object v0
                if-eqz v0, :not_sponsored
                invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
                move-result-object v0
                const-string v1, "SPONSORED"
                invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :sponsored
                const-string v1, "PROMOTION"
                invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :sponsored
                :not_sponsored
                const/4 v0, 0x0
                return v0
                :sponsored
                const/4 v0, 0x1
                return v0
            """,
        )
    }

// ---------------------------------------------------------------------------------------------------------------------
// Layer 2 (fallback): story visibility -> "GONE"
// ---------------------------------------------------------------------------------------------------------------------

private val VISIBILITY_SHAPE = listOf(
    Opcode.INSTANCE_OF, Opcode.IF_NEZ, Opcode.INSTANCE_OF, Opcode.IF_NEZ, Opcode.INSTANCE_OF, Opcode.IF_NEZ,
)

private fun isConst(opcode: Opcode) = opcode == Opcode.CONST || opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16

private fun hasVisibilityShape(method: Method): Boolean {
    val ops = method.implementation?.instructions?.map { it.opcode } ?: return false
    val n = VISIBILITY_SHAPE.size
    for (i in 0..ops.size - n - 1) {
        if (ops.subList(i, i + n) == VISIBILITY_SHAPE && isConst(ops[i + n])) return true
    }
    return false
}

private fun BytecodePatchContext.installVisibilityFallback(): Int {
    val holders = classDefs.methodsByString[STORY_VISIBILITY_MARKER].orEmpty()

    val visibility = holders.filter {
        it.returnType == "Ljava/lang/String;" &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            hasVisibilityShape(it)
    }.singleOrNull() ?: run {
        LOG.warning("Hide 'Sponsored Stories': layer 2: expected 1 story visibility method, found a different number")
        return 0
    }
    val impression = holders.filter {
        it.returnType == SPONSORED_IMPRESSION && AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    }.firstOrNull() ?: run {
        LOG.warning("Hide 'Sponsored Stories': layer 2: no SponsoredImpression method holding the marker string")
        return 0
    }

    // GraphQLStory accessor used for the sponsored data: first no-argument invoke-virtual on GraphQLStory after the
    // check-cast GraphQLStory.
    val code = impression.implementation!!.instructions.toList()
    val castIndex = code.indexOfFirst {
        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == STORY
    }
    if (castIndex < 0) {
        LOG.warning("Hide 'Sponsored Stories': layer 2: no check-cast GraphQLStory in the SponsoredImpression method")
        return 0
    }
    val accessor = code.drop(castIndex + 1).firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference
            ?: return@firstNotNullOfOrNull null
        reference.takeIf {
            it.definingClass == STORY && it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
        }
    } ?: run {
        LOG.warning("Hide 'Sponsored Stories': layer 2: no sponsored-data accessor on GraphQLStory")
        return 0
    }

    val owner = classDefs[visibility.definingClass] ?: return 0
    val mutableOwner = classDefs.getOrReplaceMutable(owner)
    val method = mutableOwner.methods.firstOrNull {
        it.name == visibility.name && it.returnType == visibility.returnType &&
            it.parameterTypes.map { type -> type.toString() } == visibility.parameterTypes.map { type -> type.toString() }
    } ?: return 0
    if (localRegisters(method) < 1) {
        LOG.warning("Hide 'Sponsored Stories': layer 2: visibility method has no free local register")
        return 0
    }

    val helper = ImmutableMethod(
        visibility.definingClass,
        STORY_HELPER_NAME,
        listOf(ImmutableMethodParameter(STORY, null, null)),
        accessor.returnType,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructions(
            """
                invoke-virtual {p0}, ${accessor.definingClass}->${accessor.name}()${accessor.returnType}
                move-result-object v0
                return-object v0
            """,
        )
    }
    if (mutableOwner.methods.none { it.name == STORY_HELPER_NAME }) mutableOwner.methods.add(helper)

    // p0 is the first parameter of the (static) visibility method; it is only a GraphQLStory when instance-of says so.
    method.addInstructionsWithLabels(
        0,
        """
            instance-of v0, p0, $STORY
            if-eqz v0, :resume_normal
            move-object v0, p0
            check-cast v0, $STORY
            invoke-static {v0}, ${visibility.definingClass}->$STORY_HELPER_NAME($STORY)${accessor.returnType}
            move-result-object v0
            if-eqz v0, :resume_normal
            const-string v0, "GONE"
            return-object v0
            :resume_normal
            nop
        """,
    )
    LOG.info("Hide 'Sponsored Stories': layer 2: ${visibility.definingClass}->${visibility.name} now returns GONE for sponsored stories")
    return 1
}

@Suppress("unused")
val hideSponsoredStoriesPatch = bytecodePatch(
    name = "Hide 'Sponsored Stories'",
    description = "Hides sponsored and promoted posts in the Facebook feed. Works from 490 up to 585.",
) {
    // No version restriction: the CLI must not refuse when Facebook changes version.
    compatibleWith("com.facebook.katana")

    apply {
        var applied = installFeedFunnelGuard()
        if (applied == 0) {
            LOG.info("Hide 'Sponsored Stories': layer 1 did not apply, trying layer 2")
            applied = installVisibilityFallback()
        }
        if (applied == 0) {
            throw PatchException(
                "Hide 'Sponsored Stories': neither the feed funnel ($FUNNEL_NAME) nor the story visibility method exists in " +
                    "this build. Send the smali of GraphQLFeedUnitEdge and of the method holding \"$STORY_VISIBILITY_MARKER\".",
            )
        }
    }
}
