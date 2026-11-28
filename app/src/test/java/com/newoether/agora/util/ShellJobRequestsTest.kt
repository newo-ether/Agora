package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellJobRequestsTest {
    @Test
    fun startRetainsExactCommandDescriptionAndWorkingDirectory() {
        val command = "Write-Output \"中文\"\nGet-Date"
        val description = "Inspect \"output\"\nwithout losing text"
        val body = Json.parseToJsonElement(shellJobStartBody(command, 5_000, "F:\\work", description)).jsonObject
        assertEquals(command, body.getValue("command").jsonPrimitive.content)
        assertEquals(description, body.getValue("description").jsonPrimitive.content)
        assertEquals("F:\\work", body.getValue("workdir").jsonPrimitive.content)
        assertEquals(5_000, body.getValue("timeout_ms").jsonPrimitive.int)
    }

    @Test
    fun listEncodesNumericPageArguments() {
        val body = Json.parseToJsonElement(shellJobListBody(100, 25)).jsonObject
        assertEquals(100, body.getValue("offset").jsonPrimitive.int)
        assertEquals(25, body.getValue("limit").jsonPrimitive.int)
    }
}
