package io.github.nexalloy.morphe.youtube.misc.fix.padding

import io.github.nexalloy.RequireAppVersion
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterImmediately
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterWithin
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.StrictQuery
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.opcode

internal object PageBottomMarginFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->findFocus()Landroid/view/View;"),
        methodCall(
            smali = "Landroid/view/View;->onCheckIsTextEditor()Z",
            location = MatchAfterWithin(5)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "Landroid/graphics/Rect;",
            name = "bottom"
        )
    )
)

@RequireAppVersion(minVersion = "20.26.000")
@StrictQuery
internal object FeedListPaddingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setClipToPadding"
        ),
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setPadding",
            location = MatchAfterImmediately()
        )
    )
)


/**
 * Sets the bottom padding of the list of a browse page laid out behind the navigation bar,
 * to the navigation bar height plus the bottom window inset, once the window insets are known.
 */
@StrictQuery
internal object BrowseListBottomPaddingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "getPaddingRight"
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "Landroid/graphics/Rect;",
            name = "bottom",
            location = MatchAfterWithin(6)
        ),
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setPadding",
            location = MatchAfterWithin(3)
        )
    )
)

internal val browseListSetPaddingMethod = findMethodDirect {
    BrowseListBottomPaddingFingerprint.instructionMatches.last().instruction.methodRef!!
}


internal val pageBottomMarginConstructor = findMethodDirect {
    val match = PageBottomMarginFingerprint.match()
    match.method.instructions.drop(match.instructionMatches.last().index + 1).first {
        it.methodRef?.name == "<init>" &&
                it.methodRef?.paramTypes?.map { it.descriptor } == listOf("I", "I", "[B")
    }.methodRef!!
}

internal val pageKeyboardInsetsMethod = findMethodDirect {
    val match = PageBottomMarginFingerprint.match()
    val instructions = match.method.instructions
    val keyboardBranch = match.instructionMatches[1].index + 1

    fun lastInsetCall(from: Int, to: Int) = instructions.subList(from, to).lastOrNull {
        it.opcode in listOf(Opcode.INVOKE_STATIC.opCode, Opcode.INVOKE_STATIC_RANGE.opCode) &&
                it.methodRef?.paramTypes?.map { it.descriptor } == listOf("Landroid/view/View;")
    }

    // The helper sits just before the Rect.bottom fallback up to 21.45 and just after it from
    // 21.46, so fall back to the rest of the method when the original window comes up empty.
    val insetCall = lastInsetCall(keyboardBranch, match.instructionMatches.last().index)
        ?: lastInsetCall(keyboardBranch, instructions.size)

    requireNotNull(insetCall) { "No keyboard inset helper on the keyboard branch" }.methodRef!!
}
