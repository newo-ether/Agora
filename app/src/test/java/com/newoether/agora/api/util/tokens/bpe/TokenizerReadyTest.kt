package com.newoether.agora.api.util.tokens.bpe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TokenizerReadyTest {
    @Test fun bundledVocabularyPublishesReadinessForContextRepricing() = runTest {
        val ready = async { O200kBase.ready.first { it } }
        O200kBase.ensureLoaded(ApplicationProvider.getApplicationContext())
        assertTrue(ready.await())
        assertNotNull(O200kBase.loadedOrNull())
    }
}
