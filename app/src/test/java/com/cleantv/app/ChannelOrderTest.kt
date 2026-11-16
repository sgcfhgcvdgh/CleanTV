package com.cleantv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelOrderTest {
    private fun order(list: List<String>, saved: List<String>) = ChannelOrder.apply(list, saved) { it }

    @Test
    fun savedOrderComesFirst() {
        assertEquals(listOf("c", "a", "b"), order(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }

    @Test
    fun newChannelsGoToTheBottom() {
        assertEquals(listOf("b", "a", "new1", "new2"), order(listOf("new1", "a", "new2", "b"), listOf("b", "a")))
    }

    @Test
    fun removedChannelsDropOut() {
        assertEquals(listOf("b", "c"), order(listOf("b", "c"), listOf("gone", "b", "c")))
    }

    @Test
    fun noSavedOrderKeepsTheProviders() {
        assertEquals(listOf("a", "b"), order(listOf("a", "b"), emptyList()))
    }
}
