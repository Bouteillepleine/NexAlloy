package io.github.nexalloy.morphe.youtube.misc.litho.rendernext

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.morphe.youtube.insertLiteralOverride
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_29_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_47_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_43_or_greater
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val disableRenderNextPatch = patch(
    description = "Disables RenderNext, so the app is always rendered with Litho.",
) {

    dependsOn(VersionCheck)

    // Do not provide ElementsServices to the section list, required to convert Litho components.
    insertLiteralOverride(45661418L)

    // Do not present elements with RenderNext, even if the server enables it.
    // An element is RenderNext if the enablement check of its proto
    // or the RenderNext field of its config is true.
    RenderNextEnablementCheckFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))

    val configParentField = ::RenderNextConfigParentField.field
    val configField = ::RenderNextConfigField.field
    ::RenderNextConfigFieldReadFingerprint.dexMethodList.forEach {
        it.hookMethod {
            before {
                it.args[0].let { configParentField.get(it) }
                    ?.let { configField.set(it, false) }
            }
        }
    }

    if (is_20_29_or_greater) {
        // Do not convert Litho components to RenderNext.
        RenderNextTemplateCheckFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
    }

    if (is_21_43_or_greater) {
        ::RenderNextConfigFieldReadFingerprint.dexMethodList
            .filter { it.returnTypeName == "boolean" }
            .forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }

        val elementsServicesIndex = ::SectionContextConstructorFingerprint.constructor
            .parameterTypes.indexOfFirst { it.name == ELEMENTS_SERVICES }
        ::SectionContextConstructorFingerprint.hookMethod(object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[elementsServicesIndex] = null
            }
        })

        val configClass = configField.declaringClass
        configClass.declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == configClass }
            ?.let { defaultInstanceField ->
                defaultInstanceField.isAccessible = true
                defaultInstanceField.get(null)?.let { configField.set(it, false) }
            }
    }

    if (is_20_47_or_greater) {
        // TOD Safeguard: always present elements with Litho, even if they are RenderNext elements.
    }
}