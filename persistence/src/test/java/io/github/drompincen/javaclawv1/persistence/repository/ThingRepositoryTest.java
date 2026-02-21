package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ThingRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private ThingRepository thingRepository;

    @Test
    void saveAndFindById() {
        ThingDocument thing = createThing("t1", "proj1", ThingCategory.TICKET, Map.of("title", "Fix bug"));
        thingRepository.save(thing);

        Optional<ThingDocument> found = thingRepository.findById("t1");
        assertThat(found).isPresent();
        assertThat(found.get().getThingCategory()).isEqualTo(ThingCategory.TICKET);
        assertThat(found.get().getPayload().get("title")).isEqualTo("Fix bug");
    }

    @Test
    void findByProjectId() {
        thingRepository.save(createThing("t1", "proj1", ThingCategory.TICKET, Map.of("title", "A")));
        thingRepository.save(createThing("t2", "proj1", ThingCategory.OBJECTIVE, Map.of("outcome", "B")));
        thingRepository.save(createThing("t3", "proj2", ThingCategory.TICKET, Map.of("title", "C")));

        List<ThingDocument> result = thingRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndThingCategory() {
        thingRepository.save(createThing("t1", "proj1", ThingCategory.TICKET, Map.of("title", "A")));
        thingRepository.save(createThing("t2", "proj1", ThingCategory.OBJECTIVE, Map.of("outcome", "B")));
        thingRepository.save(createThing("t3", "proj1", ThingCategory.TICKET, Map.of("title", "C")));

        List<ThingDocument> tickets = thingRepository.findByProjectIdAndThingCategory("proj1", ThingCategory.TICKET);
        assertThat(tickets).hasSize(2);

        List<ThingDocument> objectives = thingRepository.findByProjectIdAndThingCategory("proj1", ThingCategory.OBJECTIVE);
        assertThat(objectives).hasSize(1);
    }

    @Test
    void findByThingCategory() {
        thingRepository.save(createThing("t1", "proj1", ThingCategory.RESOURCE, Map.of("name", "Alice")));
        thingRepository.save(createThing("t2", "proj2", ThingCategory.RESOURCE, Map.of("name", "Bob")));
        thingRepository.save(createThing("t3", "proj1", ThingCategory.TICKET, Map.of("title", "X")));

        List<ThingDocument> resources = thingRepository.findByThingCategory(ThingCategory.RESOURCE);
        assertThat(resources).hasSize(2);
    }

    @Test
    void findByIdAndThingCategory() {
        thingRepository.save(createThing("t1", "proj1", ThingCategory.TICKET, Map.of("title", "A")));

        assertThat(thingRepository.findByIdAndThingCategory("t1", ThingCategory.TICKET)).isPresent();
        assertThat(thingRepository.findByIdAndThingCategory("t1", ThingCategory.OBJECTIVE)).isEmpty();
    }

    @Test
    void deleteByProjectIdAndThingCategory() {
        thingRepository.save(createThing("t1", "proj1", ThingCategory.TICKET, Map.of("title", "A")));
        thingRepository.save(createThing("t2", "proj1", ThingCategory.OBJECTIVE, Map.of("outcome", "B")));
        thingRepository.save(createThing("t3", "proj1", ThingCategory.TICKET, Map.of("title", "C")));

        thingRepository.deleteByProjectIdAndThingCategory("proj1", ThingCategory.TICKET);

        assertThat(thingRepository.findByProjectId("proj1")).hasSize(1);
        assertThat(thingRepository.findByProjectId("proj1").get(0).getThingCategory())
                .isEqualTo(ThingCategory.OBJECTIVE);
    }

    @Test
    void payloadNestedStructures() {
        Map<String, Object> payload = Map.of(
                "title", "Sprint 1",
                "risks", List.of("risk-a", "risk-b"),
                "coveragePercent", 85.5
        );
        thingRepository.save(createThing("o1", "proj1", ThingCategory.OBJECTIVE, payload));

        ThingDocument found = thingRepository.findById("o1").orElseThrow();
        assertThat(found.payloadString("title")).isEqualTo("Sprint 1");
        assertThat(found.payloadGet("coveragePercent", 0.0)).isEqualTo(85.5);
        assertThat(found.payloadGet("risks", List.of())).isEqualTo(List.of("risk-a", "risk-b"));
    }

    @Test
    void nullProjectIdForGlobalTemplates() {
        ThingDocument template = createThing("tpl1", null, ThingCategory.CHECKLIST_TEMPLATE,
                Map.of("name", "Code Review Checklist"));
        thingRepository.save(template);

        Optional<ThingDocument> found = thingRepository.findById("tpl1");
        assertThat(found).isPresent();
        assertThat(found.get().getProjectId()).isNull();
        assertThat(found.get().getThingCategory()).isEqualTo(ThingCategory.CHECKLIST_TEMPLATE);
    }

    private ThingDocument createThing(String id, String projectId, ThingCategory category, Map<String, Object> payload) {
        ThingDocument doc = new ThingDocument();
        doc.setId(id);
        doc.setProjectId(projectId);
        doc.setThingCategory(category);
        doc.setPayload(payload);
        doc.setCreateDate(Instant.now());
        doc.setUpdateDate(Instant.now());
        return doc;
    }
}
