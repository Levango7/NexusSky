package io.aerofleet.cloud.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EdgeCoordinationControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void testGetAllTasks() throws Exception {
        mockMvc.perform(get("/api/v1/edge/tasks")).andExpect(status().isOk());
    }

    @Test
    void submitResultWithValidBodyReturnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/edge/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sysid\":7,\"taskId\":\"task-1\",\"type\":\"detection\",\"confidence\":0.9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void submitResultWithoutSysidReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/edge/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"task-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitResultWithNonNumericSysidReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/edge/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sysid\":\"seven\"}"))
                .andExpect(status().isBadRequest());
    }
}
