package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateTicketToolTest {

    @Test
    void toolMetadata() {
        CreateTicketTool tool = new CreateTicketTool();

        assertThat(tool.name()).isEqualTo("create_ticket");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.READ_ONLY);
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.outputSchema()).isNotNull();
    }

    @Test
    void inputSchemaHasRequiredFields() throws Exception {
        CreateTicketTool tool = new CreateTicketTool();
        ObjectMapper mapper = new ObjectMapper();
        String schema = mapper.writeValueAsString(tool.inputSchema());

        assertThat(schema).contains("projectId");
        assertThat(schema).contains("title");
    }
}
