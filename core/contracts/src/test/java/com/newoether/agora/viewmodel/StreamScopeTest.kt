package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamScopeTest {
    @Test
    fun `cancelAll leaves another conversation scope untouched`() {
        val firstScope = StreamScope()
        val secondScope = StreamScope()
        var firstCancelled = 0
        var secondCancelled = 0
        firstScope.register(GenerationCancelHandle { firstCancelled++ })
        secondScope.register(GenerationCancelHandle { secondCancelled++ })

        firstScope.cancelAll()

        assertEquals(1, firstCancelled)
        assertEquals(0, secondCancelled)
        secondScope.cancelAll()
        assertEquals(1, secondCancelled)
    }

    @Test
    fun `unregistered handles are not cancelled`() {
        val scope = StreamScope()
        var cancelled = 0
        val handle = GenerationCancelHandle { cancelled++ }
        scope.register(handle)
        scope.unregister(handle)

        scope.cancelAll()

        assertEquals(0, cancelled)
    }

    @Test
    fun `one cancellation failure does not skip remaining handles or retain them`() {
        val scope = StreamScope()
        var attempts = 0
        scope.register(GenerationCancelHandle {
            attempts++
            throw IllegalStateException("transport close failed")
        })
        scope.register(GenerationCancelHandle { attempts++ })

        scope.cancelAll()
        assertEquals(2, attempts)
        scope.cancelAll()
        assertEquals(2, attempts)
    }

    @Test
    fun `duplicate registration cancels one handle once`() {
        val scope = StreamScope()
        var cancelled = 0
        val handle = GenerationCancelHandle { cancelled++ }
        scope.register(handle)
        scope.register(handle)

        scope.cancelAll()

        assertEquals(1, cancelled)
    }
}
