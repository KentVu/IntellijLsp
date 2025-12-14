package com.frenchef.intellijlsp.services

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T044: PortAllocator unit tests.
 *
 * Note: PortAllocator is a singleton object, so its internal state must be reset before each test.
 */
class PortAllocatorTest {

    @BeforeEach
    fun setUp() {
        // Clear allocatedPorts using reflection.
        resetAllocatedPorts()
    }

    @Test
    fun `allocatePort should return available port`() {
        val port = PortAllocator.allocatePort("TestProject")

        assertNotNull(port)
        assertTrue(port!! >= 2087) // Default starting port.
    }

    @Test
    fun `allocatePort should not return same port twice`() {
        val port1 = PortAllocator.allocatePort("Project1")
        val port2 = PortAllocator.allocatePort("Project2")

        assertNotNull(port1)
        assertNotNull(port2)
        assertNotEquals(port1, port2)
    }

    @Test
    fun `releasePort should free the port`() {
        val port = PortAllocator.allocatePort("TestProject")
        assertNotNull(port)
        assertTrue(PortAllocator.isAllocated(port!!))

        PortAllocator.releasePort(port)

        assertFalse(PortAllocator.isAllocated(port))
    }

    @Test
    fun `isAllocated should return correct status`() {
        val port = PortAllocator.allocatePort("TestProject")
        assertNotNull(port)

        assertTrue(PortAllocator.isAllocated(port!!))
        assertFalse(PortAllocator.isAllocated(99999)) // Unallocated port.
    }

    @Test
    fun `getProjectForPort should return project name`() {
        val projectName = "MyProject"
        val port = PortAllocator.allocatePort(projectName)
        assertNotNull(port)

        assertEquals(projectName, PortAllocator.getProjectForPort(port!!))
        assertNull(PortAllocator.getProjectForPort(99999))
    }

    @Test
    fun `getAllocatedPorts should return all allocations`() {
        PortAllocator.allocatePort("Project1")
        PortAllocator.allocatePort("Project2")

        val allocated = PortAllocator.getAllocatedPorts()

        assertEquals(2, allocated.size)
        assertTrue(allocated.values.contains("Project1"))
        assertTrue(allocated.values.contains("Project2"))
    }

    @Test
    fun `released port can be reallocated`() {
        val port1 = PortAllocator.allocatePort("Project1")
        assertNotNull(port1)

        PortAllocator.releasePort(port1!!)

        val port2 = PortAllocator.allocatePort("Project2")

        // A released port may be reallocated.
        assertNotNull(port2)
    }

    /** Resets PortAllocator's internal state using reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun resetAllocatedPorts() {
        try {
            val field: Field = PortAllocator::class.java.getDeclaredField("allocatedPorts")
            field.isAccessible = true
            val map = field.get(PortAllocator) as ConcurrentHashMap<Int, String>
            map.clear()
        } catch (e: Exception) {
            // Ignore reflection errors.
        }
    }
}
