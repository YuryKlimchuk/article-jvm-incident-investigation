package com.hydroyura.article.jvmincidents;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JvmincidentsApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void incidentsListReturnsAllNineTypes() throws Exception {
		mockMvc.perform(get("/incidents"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(9)))
				.andExpect(jsonPath("$[*].type", containsInAnyOrder(
						"cpu", "allocation", "gc", "memory-leak", "native-memory",
						"deadlock", "threads", "blocking-io", "db-pool")));
	}

	@Test
	void swaggerApiDocsAvailable() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk());
	}

	@Test
	void incidentStatusUsesEnumPathVariable() throws Exception {
		mockMvc.perform(get("/incidents/cpu/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("cpu"))
				.andExpect(jsonPath("$.status").value("idle"));
	}

}
