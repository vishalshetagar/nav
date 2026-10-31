package com.example.nav.llm

import org.junit.Assert.*
import org.junit.Test

class RateLimitPolicyTest {
    @Test fun parsesGroqCompoundDurations() {
        assertEquals(179560L, RateLimitPolicy.durationMillis("2m59.56s"))
        assertEquals(7660L, RateLimitPolicy.durationMillis("7.66s"))
        assertEquals(50L, RateLimitPolicy.durationMillis("50ms"))
    }
    @Test fun respectsLongCooldownAndUsesSafeFallback() {
        assertEquals(120000L, RateLimitPolicy.retryMillis("120", "5s"))
        assertEquals(180000L, RateLimitPolicy.retryMillis("2", "3m"))
        assertEquals(30000L, RateLimitPolicy.retryMillis(null, "garbage"))
    }
}
