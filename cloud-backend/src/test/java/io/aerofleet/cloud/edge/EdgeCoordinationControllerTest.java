package io.aerofleet.cloud.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EdgeCoordinationControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void testGetAllTasks() throws Exception {
        mockMvc.perform(get("/api/edge/tasks")).andExpect(status().isOk());
    }
}
