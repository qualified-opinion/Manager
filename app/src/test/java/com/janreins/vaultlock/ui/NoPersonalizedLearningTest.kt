package com.janreins.vaultlock.ui

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.janreins.vaultlock.crypto.SessionManager
import com.janreins.vaultlock.ui.screens.AddEditEntryScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoPersonalizedLearningTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var store: ViewModelStore

    /** imeOptions each text field hands the keyboard, recorded outside the screen after its own interceptors. */
    private val startedImeOptions = mutableListOf<Int>()
    private val recorder = PlatformTextInputInterceptor { request, nextHandler ->
        startedImeOptions += EditorInfo().also { request.createInputConnection(it) }.imeOptions
        nextHandler.startInputMethod(request)
    }

    @Before
    fun setUp() {
        SessionManager.lock()
        val viewModel = VaultViewModel(ApplicationProvider.getApplicationContext<Application>())
        store = ViewModelStore().apply { put("vault", viewModel) }
        composeRule.setContent {
            InterceptPlatformTextInput(recorder) {
                AddEditEntryScreen(entryId = null, viewModel = viewModel, draftKey = "test", onNavigateBack = {})
            }
        }
    }

    @After
    fun tearDown() {
        store.clear()
        SessionManager.lock()
    }

    private fun imeOptionsWhenFocused(tag: String): Int {
        startedImeOptions.clear()
        composeRule.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        assertTrue("$tag did not start a keyboard session", startedImeOptions.isNotEmpty())
        return startedImeOptions.last()
    }

    private fun learnsTyping(imeOptions: Int) = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0

    @Test
    fun `username, url and notes ask the keyboard not to learn`() {
        for (tag in listOf("entry_input_username", "entry_input_url", "entry_input_notes")) {
            assertEquals(tag, false, learnsTyping(imeOptionsWhenFocused(tag)))
        }
    }

    @Test
    fun `title field is left as it was`() {
        assertEquals(true, learnsTyping(imeOptionsWhenFocused("entry_input_title")))
    }

    @Test
    fun `flag is added without dropping the field's own options`() {
        val options = withNoPersonalizedLearning(EditorInfo.IME_ACTION_DONE)
        assertEquals(EditorInfo.IME_ACTION_DONE, options and EditorInfo.IME_MASK_ACTION)
        assertEquals(false, learnsTyping(options))
    }
}
