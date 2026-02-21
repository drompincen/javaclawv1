package io.github.drompincen.javaclawv1.runtime.resource;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock
    private ThingService thingService;

    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        resourceService = new ResourceService(thingService);
    }

    @Test
    void getWorkloadsAggregatesCorrectly() {
        ThingDocument a1 = makeAssignment("r1", 50.0);
        ThingDocument a2 = makeAssignment("r1", 30.0);
        ThingDocument a3 = makeAssignment("r2", 100.0);

        when(thingService.findByCategory(ThingCategory.RESOURCE_ASSIGNMENT))
                .thenReturn(List.of(a1, a2, a3));

        Map<String, Double> workloads = resourceService.getWorkloads();

        assertThat(workloads).containsEntry("r1", 80.0);
        assertThat(workloads).containsEntry("r2", 100.0);
    }

    @Test
    void createReturnsThingDocument() {
        ThingDocument result = new ThingDocument();
        result.setId("new-id");
        result.setThingCategory(ThingCategory.RESOURCE);
        result.setPayload(Map.of("name", "Alice"));

        when(thingService.createThing(eq("proj1"), eq(ThingCategory.RESOURCE), any()))
                .thenReturn(result);

        ThingDocument saved = resourceService.create(Map.of("name", "Alice"), "proj1");

        assertThat(saved.getId()).isEqualTo("new-id");
    }

    @Test
    void assignToTicketCreatesAssignment() {
        ThingDocument result = new ThingDocument();
        result.setId("assign-id");
        result.setThingCategory(ThingCategory.RESOURCE_ASSIGNMENT);
        result.setPayload(Map.of("resourceId", "r1", "ticketId", "t1", "percentageAllocation", 50.0));

        when(thingService.createThing(eq(null), eq(ThingCategory.RESOURCE_ASSIGNMENT), any()))
                .thenReturn(result);

        ThingDocument assignment = resourceService.assignToTicket("r1", "t1", 50.0);

        assertThat(assignment.getId()).isEqualTo("assign-id");
        assertThat(assignment.getPayload().get("resourceId")).isEqualTo("r1");
        assertThat(assignment.getPayload().get("ticketId")).isEqualTo("t1");
    }

    private ThingDocument makeAssignment(String resourceId, double allocation) {
        ThingDocument doc = new ThingDocument();
        doc.setId("a-" + resourceId + "-" + allocation);
        doc.setThingCategory(ThingCategory.RESOURCE_ASSIGNMENT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resourceId);
        payload.put("percentageAllocation", allocation);
        doc.setPayload(payload);
        doc.setCreateDate(Instant.now());
        doc.setUpdateDate(Instant.now());
        return doc;
    }
}
