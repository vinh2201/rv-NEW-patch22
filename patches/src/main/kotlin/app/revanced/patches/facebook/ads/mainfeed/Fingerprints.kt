package app.revanced.patches.facebook.ads.mainfeed

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val STORY_VISIBILITY_MARKER = "This should not be called for base class object"
internal const val GRAPHQL_STORY_DESCRIPTOR = "Lcom/facebook/graphql/model/GraphQLStory;"

// ---------------------------------------------------------------------------------------------
// Dung chung cho moi chien luoc
// ---------------------------------------------------------------------------------------------

/** Ham tra ve "visibility" cua story (nhan dien bang chuoi + 3 cap instance-of/if-nez). Co mat tren 490 va 521. */
internal val BytecodePatchContext.getStoryVisibilityMethodMatch by composingFirstMethod(STORY_VISIBILITY_MARKER) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Ljava/lang/String;")
    opcodes(
        Opcode.INSTANCE_OF,
        Opcode.IF_NEZ,
        Opcode.INSTANCE_OF,
        Opcode.IF_NEZ,
        Opcode.INSTANCE_OF,
        Opcode.IF_NEZ,
        Opcode.CONST,
    )
}

// ---------------------------------------------------------------------------------------------
// Chien luoc A (ban 490): lay 2 id tu ham mau cua GraphQLFBMultiAdsFeedUnit
// ---------------------------------------------------------------------------------------------

internal val BytecodePatchContext.baseModelMapperMethod by gettingFirstImmutableMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Lcom/facebook/graphql/modelutil/BaseModelWithTree;")
    parameterTypes("Ljava/lang/Class", "I", "I")
    opcodes(
        Opcode.SGET_OBJECT,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST_4,
        Opcode.IF_EQ,
    )
}

/** Tren ban 521 ham nay khong con -> truy cap se nem loi, patch se tu chuyen sang chien luoc B. */
internal val BytecodePatchContext.getSponsoredDataModelTemplateMethod by gettingFirstImmutableMethodDeclaratively {
    definingClass("Lcom/facebook/graphql/model/GraphQLFBMultiAdsFeedUnit;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("L")
    parameterTypes()
    opcodes(
        Opcode.CONST,
        Opcode.CONST,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.RETURN_OBJECT,
    )
}

// ---------------------------------------------------------------------------------------------
// Chien luoc B (ban 521+): lay ham truy cap "sponsored data" truc tiep tren GraphQLStory
// Ham X/2ge.A00(...)SponsoredImpression chua cung chuoi marker va co nhanh
//   check-cast GraphQLStory -> invoke-virtual GraphQLStory;->A2H()LX/3mx;
// -> khong phu thuoc ten da bi lam roi (A2H, X/3mx...), chi dua vao kieu goc cua Facebook.
// ---------------------------------------------------------------------------------------------

internal val BytecodePatchContext.sponsoredImpressionMethod by gettingFirstMethodDeclaratively(STORY_VISIBILITY_MARKER) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Lcom/facebook/graphql/model/SponsoredImpression;")
}
