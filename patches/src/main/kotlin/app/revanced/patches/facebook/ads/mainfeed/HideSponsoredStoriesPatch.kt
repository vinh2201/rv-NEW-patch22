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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val HELPER_NAME = "getSponsoredData"

/**
 * Chien luoc A (goc, ban 490): doc 2 hang so id trong ham mau cua GraphQLFBMultiAdsFeedUnit
 * roi goi ham BaseModelWithTree (Class, I, I) tren GraphQLStory.
 */
private fun BytecodePatchContext.helperFromTemplate(ownerType: String): MutableMethod {
    val template = getSponsoredDataModelTemplateMethod
    val mapper = baseModelMapperMethod
    val baseModelWithTreeType = mapper.returnType

    val ids = template.implementation!!.instructions
        .filterIsInstance<Instruction31i>()
        .take(2)
        .map { it.narrowLiteral }
    if (ids.size < 2) throw PatchException("Ham mau khong du 2 hang so id (tim thay ${ids.size}).")

    return ImmutableMethod(
        ownerType,
        HELPER_NAME,
        listOf(ImmutableMethodParameter(GRAPHQL_STORY_DESCRIPTOR, null, null)),
        baseModelWithTreeType,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(4),
    ).toMutable().apply {
        addInstructions(
            """
                const-class v2, $baseModelWithTreeType
                const v1, ${ids[0]}
                const v0, ${ids[1]}
                invoke-virtual {p0, v2, v1, v0}, $mapper
                move-result-object v0
                check-cast v0, $baseModelWithTreeType
                return-object v0
            """,
        )
    }
}

/**
 * Chien luoc B (ban 521+): trong ham tra ve SponsoredImpression, nhanh GraphQLStory co dang
 *   check-cast vX, GraphQLStory;  ...  invoke-virtual {vX}, GraphQLStory;->ACCESSOR()LKieu;
 * ACCESSOR chinh la ham lay du lieu "sponsored" cua story (non-null => story la quang cao).
 */
private fun BytecodePatchContext.helperFromAccessor(ownerType: String): MutableMethod {
    val instructions = sponsoredImpressionMethod.implementation!!.instructions.toList()

    val castIndex = instructions.indexOfFirst {
        it.opcode == Opcode.CHECK_CAST &&
            ((it as ReferenceInstruction).reference as? TypeReference)?.type == GRAPHQL_STORY_DESCRIPTOR
    }
    if (castIndex < 0) throw PatchException("Khong thay check-cast GraphQLStory trong ham SponsoredImpression.")

    var accessor: MethodReference? = null
    for (i in castIndex + 1 until instructions.size) {
        val instruction = instructions[i]
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) continue
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
        if (reference.definingClass == GRAPHQL_STORY_DESCRIPTOR &&
            reference.parameterTypes.isEmpty() &&
            reference.returnType.startsWith("L")
        ) {
            accessor = reference
            break
        }
    }
    val found = accessor ?: throw PatchException("Khong thay ham truy cap sponsored data tren GraphQLStory.")

    return ImmutableMethod(
        ownerType,
        HELPER_NAME,
        listOf(ImmutableMethodParameter(GRAPHQL_STORY_DESCRIPTOR, null, null)),
        found.returnType,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructions(
            """
                invoke-virtual {p0}, ${found.definingClass}->${found.name}()${found.returnType}
                move-result-object v0
                return-object v0
            """,
        )
    }
}

@Suppress("unused")
val hideSponsoredStoriesPatch = bytecodePatch(
    name = "Hide 'Sponsored Stories'",
    description = "Hides sponsored stories in the Facebook feed. Works on 490 (strategy A) and 521+ (strategy B).",
) {
    // Khong rang buoc phien ban: de CLI khong chan khi Facebook doi phien ban.
    compatibleWith("com.facebook.katana")

    apply {
        val log = Logger.getLogger("HideSponsoredStories")
        val visibility = getStoryVisibilityMethodMatch
        val ownerType = visibility.immutableClassDef.type

        val helper = runCatching { helperFromTemplate(ownerType) }
            .onSuccess { log.info("Hide Sponsored Stories: dung chien luoc A (ham mau GraphQLFBMultiAdsFeedUnit).") }
            .onFailure { log.info("Hide Sponsored Stories: chien luoc A that bai (${it.message}), thu chien luoc B.") }
            .getOrNull()
            ?: runCatching { helperFromAccessor(ownerType) }
                .onSuccess { log.info("Hide Sponsored Stories: dung chien luoc B (ham truy cap GraphQLStory).") }
                .onFailure { log.warning("Hide Sponsored Stories: chien luoc B that bai (${it.message}).") }
                .getOrNull()
            ?: throw PatchException(
                "Khong tim thay cach lay du lieu sponsored tren phien ban nay. " +
                    "Hay gui lai smali cua GraphQLStory va ham chua chuoi \"$STORY_VISIBILITY_MARKER\".",
            )

        visibility.classDef.methods.add(helper)

        // Neu tham so la GraphQLStory va ham helper tra ve khac null => tra ve "GONE" de an story.
        // move-object + check-cast de verifier khong phan nan kieu cua p0.
        visibility.method.addInstructionsWithLabels(
            visibility[0],
            """
                instance-of v0, p0, $GRAPHQL_STORY_DESCRIPTOR
                if-eqz v0, :resume_normal
                move-object v0, p0
                check-cast v0, $GRAPHQL_STORY_DESCRIPTOR
                invoke-static {v0}, $helper
                move-result-object v0
                if-eqz v0, :resume_normal
                const-string v0, "GONE"
                return-object v0
                :resume_normal
                nop
            """,
        )
    }
}
