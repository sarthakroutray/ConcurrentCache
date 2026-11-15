package com.concurrentcache.server.api;

import com.concurrentcache.server.sweeper.TtlSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP contract tests. Each test uses its own key names because the
 * application context (and therefore the cache) is shared inside this class.
 */
@SpringBootTest(properties = {
        "cache.implementation=global",
        "cache.capacity=100",
        "cache.default-ttl=60s",
        "cache.max-value-length=64",
        "cache.sweep-interval=1h"
})
@AutoConfigureMockMvc
class CacheApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TtlSweeper sweeper;

    @Test
    void putCreatesKeyWith201AndGetReadsItBack() throws Exception {
        mockMvc.perform(put("/keys/create-me").param("ttl", "60").content("first"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/keys/create-me"))
                .andExpect(status().isOk())
                .andExpect(content().string("first"));
    }

    @Test
    void putReplacesExistingKeyWith200() throws Exception {
        mockMvc.perform(put("/keys/replace-me").param("ttl", "60").content("first"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/keys/replace-me").param("ttl", "60").content("second"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/keys/replace-me")).andExpect(content().string("second"));
    }

    @Test
    void putWithoutTtlUsesTheConfiguredDefaultTtl() throws Exception {
        mockMvc.perform(put("/keys/default-ttl").content("value"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/keys/default-ttl")).andExpect(content().string("value"));
    }

    @Test
    void getUnknownKeyReturns404WithJsonError() throws Exception {
        mockMvc.perform(get("/keys/never-stored"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("no value stored for key: never-stored"));
    }

    @Test
    void getExpiredKeyReturns404() throws Exception {
        mockMvc.perform(put("/keys/expires-fast").param("ttl", "1").content("value"))
                .andExpect(status().isCreated());
        Thread.sleep(1_200);
        mockMvc.perform(get("/keys/expires-fast"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204AndThen404() throws Exception {
        mockMvc.perform(put("/keys/delete-me").param("ttl", "60").content("value"));
        mockMvc.perform(delete("/keys/delete-me")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/keys/delete-me")).andExpect(status().isNotFound());
    }

    @Test
    void incrementCreatesKeyAndIncrementsAtomically() throws Exception {
        mockMvc.perform(post("/keys/counter/increment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("counter"))
                .andExpect(jsonPath("$.value").value(1));
        mockMvc.perform(post("/keys/counter/increment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(2));
    }

    @Test
    void incrementOnNonNumericValueReturns409() throws Exception {
        mockMvc.perform(put("/keys/not-a-number").param("ttl", "60").content("abc"));
        mockMvc.perform(post("/keys/not-a-number/increment"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void blankKeyReturns400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/keys/{key}", " ")
                        .contentType(MediaType.TEXT_PLAIN)
                        .param("ttl", "60")
                        .content("value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("key must not be blank"));
    }

    @Test
    void missingKeySegmentReturns400() throws Exception {
        mockMvc.perform(put("/keys").content("value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("key must not be empty"));
    }

    @Test
    void nonPositiveTtlReturns400() throws Exception {
        mockMvc.perform(put("/keys/bad-ttl").param("ttl", "0").content("value"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/keys/bad-ttl").param("ttl", "-5").content("value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("ttl must be positive"));
    }

    @Test
    void nonNumericTtlReturns400() throws Exception {
        mockMvc.perform(put("/keys/bad-ttl-type").param("ttl", "abc").content("value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid value for parameter 'ttl'"));
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        mockMvc.perform(put("/keys/no-body"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("value must not be empty"));
    }

    @Test
    void oversizedValueReturns400() throws Exception {
        String tooLong = "x".repeat(65);
        mockMvc.perform(put("/keys/too-big").content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("value exceeds the maximum length of 64 characters"));
    }

    @Test
    void statsReportCountersAndSize() throws Exception {
        mockMvc.perform(put("/keys/stats-key").param("ttl", "60").content("value"));
        mockMvc.perform(get("/keys/stats-key"));
        mockMvc.perform(get("/keys/stats-missing"));
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isNumber())
                .andExpect(jsonPath("$.misses").isNumber())
                .andExpect(jsonPath("$.hitRatio").isNumber())
                .andExpect(jsonPath("$.size").isNumber());
    }

    @Test
    void healthProbesReportUp() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void ttlSweeperBeanIsRunning() {
        assertTrue(sweeper.isRunning());
    }

    private static MockHttpServletRequestBuilder put(String path) {
        return MockMvcRequestBuilders.put(path).contentType(MediaType.TEXT_PLAIN);
    }
}
