package com.flagdash.sdk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FlagDashClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: FlagDashClient

    @Before fun setup() { server = MockWebServer(); server.start(); client = FlagDashClient("sk_test", server.url("/").toString(), region = "eu") }
    @After fun close() { client.close(); server.shutdown() }

    @Test fun readsTypedClientResources() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"flags":{"checkout":true}}"""))
        assertTrue(client.flagBoolean("checkout"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"checkout","value":true,"reason":"rule_match","variation_key":"on"}"""))
        assertEquals("rule_match", client.flagDetail("checkout", context = EvaluationContext(userId = "alice")).reason)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"theme","value":"violet"}"""))
        assertEquals(JsonPrimitive("violet"), client.config("theme"))
        server.takeRequest()
        assertEquals("alice", server.takeRequest().requestUrl!!.queryParameter("user_id"))
    }
}
