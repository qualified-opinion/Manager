@file:OptIn(ExperimentalComposeUiApi::class)

package com.janreins.vaultlock.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Adds the flag that asks the keyboard not to learn (or sync) what is typed to an [EditorInfo.imeOptions] value. */
internal fun withNoPersonalizedLearning(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

private val noPersonalizedLearningInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    val modifiedRequest = object : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            request.createInputConnection(outAttributes).also {
                // The text field fills imeOptions first, so the flag is added on top of its own options.
                outAttributes.imeOptions = withNoPersonalizedLearning(outAttributes.imeOptions)
            }
    }
    nextHandler.startInputMethod(modifiedRequest)
}

/**
 * Text fields inside [content] ask the keyboard not to add what is typed to its personal dictionary,
 * so it can't suggest it in other apps. Suggestions and autocorrect still work while typing.
 */
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(noPersonalizedLearningInterceptor, content)
}
