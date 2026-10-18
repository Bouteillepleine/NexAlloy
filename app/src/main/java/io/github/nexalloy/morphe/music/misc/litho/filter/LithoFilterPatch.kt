package io.github.nexalloy.morphe.music.misc.litho.filter

import io.github.nexalloy.morphe.music.misc.playservice.versionCheckPatch
import io.github.nexalloy.morphe.shared.misc.litho.context.conversionContextPatch
import io.github.nexalloy.morphe.shared.misc.litho.filter.sharedLithoFilterPatch

val LithoFilter = sharedLithoFilterPatch(
    hookNonNativeBuffer = { false },
    overrideUpbFeatureFlag = { false },
    useLegacyLithoFiltering = { false }
) {
    dependsOn(
        versionCheckPatch,
        conversionContextPatch,
    )
}
