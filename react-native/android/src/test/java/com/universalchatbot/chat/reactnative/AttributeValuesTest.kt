package com.universalchatbot.chat.reactnative

import org.junit.Assert.assertEquals
import org.junit.Test

class AttributeValuesTest {
    @Test
    fun `whole numbers from JavaScript go on as whole numbers`() {
        assertEquals(
            mapOf("seats" to 3L, "negative" to -2L, "zero" to 0L, "price" to 9.99, "plan" to "pro", "trial" to true, "note" to null),
            AttributeValues.normalizeAll(mapOf("seats" to 3.0, "negative" to -2.0, "zero" to 0.0, "price" to 9.99, "plan" to "pro", "trial" to true, "note" to null)),
        )
    }

    @Test
    fun `nested maps and lists are converted too`() {
        assertEquals(
            mapOf("team" to mapOf("size" to 4L, "ratio" to 0.5), "ids" to listOf(1L, 2.5, listOf(3L))),
            AttributeValues.normalizeAll(mapOf("team" to hashMapOf("size" to 4.0, "ratio" to 0.5), "ids" to arrayListOf(1.0, 2.5, arrayListOf(3.0)))),
        )
    }

    @Test
    fun `numbers too large to be exact, and ones that are not numbers, stay as they came`() {
        assertEquals(1e20, AttributeValues.normalize(1e20))
        assertEquals(Double.POSITIVE_INFINITY, AttributeValues.normalize(Double.POSITIVE_INFINITY))
        assertEquals(Double.NaN, AttributeValues.normalize(Double.NaN))
    }
}
