package io.aerofleet.cloud.twin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TwinControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void testGetAllStates() throws Exception {
        mockMvc.perform(get("/api/v1/twin/state")).andExpect(status().isOk());
    }

    @Test
    void testPredict() throws Exception {
        mockMvc.perform(get("/api/v1/twin/predict/1").param("horizon", "10")).andExpect(status().isOk());
    }
}
