package io.github.drompincen.javaclawv1.runtime.resource;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ResourceService {

    private final ThingService thingService;

    public ResourceService(ThingService thingService) {
        this.thingService = thingService;
    }

    public ThingDocument create(Map<String, Object> resourcePayload, String projectId) {
        return thingService.createThing(projectId, ThingCategory.RESOURCE, resourcePayload);
    }

    public List<ThingDocument> findAll() {
        return thingService.findByCategory(ThingCategory.RESOURCE);
    }

    public Optional<ThingDocument> findById(String resourceId) {
        return thingService.findById(resourceId, ThingCategory.RESOURCE);
    }

    public ThingDocument assignToTicket(String resourceId, String ticketId, double percentAllocation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resourceId);
        payload.put("ticketId", ticketId);
        payload.put("percentageAllocation", percentAllocation);
        return thingService.createThing(null, ThingCategory.RESOURCE_ASSIGNMENT, payload);
    }

    public Map<String, Double> getWorkloads() {
        return thingService.findByCategory(ThingCategory.RESOURCE_ASSIGNMENT).stream()
                .collect(Collectors.groupingBy(
                        a -> (String) a.getPayload().get("resourceId"),
                        Collectors.summingDouble(a -> {
                            Object alloc = a.getPayload().get("percentageAllocation");
                            return alloc != null ? ((Number) alloc).doubleValue() : 0;
                        })));
    }

    public List<ThingDocument> getAssignmentsForResource(String resourceId) {
        return thingService.findByPayloadField(ThingCategory.RESOURCE_ASSIGNMENT, "resourceId", resourceId);
    }
}
