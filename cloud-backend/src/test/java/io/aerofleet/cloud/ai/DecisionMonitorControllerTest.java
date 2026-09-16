package io.aerofleet.cloud.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DecisionMonitorControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void testGetAllDecisions() throws Exception {
        mockMvc.perform(get("/api/ai/decisions")).andExpect(status().isOk());
    }

    @Test
    void testGetDroneDecisions() throws Exception {
        mockMvc.perform(get("/api/ai/decisions/1")).andExpect(status().isOk());
    }
}
