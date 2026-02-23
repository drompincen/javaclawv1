package io.github.drompincen.javaclawv1.runtime.thing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ThingRepository;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ThingService {

    private final ThingRepository thingRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public ThingService(ThingRepository thingRepository, MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.thingRepository = thingRepository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    // ---- Core CRUD ----

    public ThingDocument save(ThingDocument thing) {
        if (thing.getId() == null) {
            thing.setId(UUID.randomUUID().toString());
        }
        if (thing.getCreateDate() == null) {
            thing.setCreateDate(Instant.now());
        }
        thing.setUpdateDate(Instant.now());
        return thingRepository.save(thing);
    }

    public Optional<ThingDocument> findById(String id) {
        return thingRepository.findById(id);
    }

    public Optional<ThingDocument> findById(String id, ThingCategory category) {
        return thingRepository.findByIdAndThingCategory(id, category);
    }

    public boolean existsById(String id) {
        return thingRepository.existsById(id);
    }

    public void deleteById(String id) {
        thingRepository.deleteById(id);
    }

    // ---- Category queries ----

    public List<ThingDocument> findByProject(String projectId) {
        return thingRepository.findByProjectId(projectId);
    }

    public List<ThingDocument> findByProjectAndCategory(String projectId, ThingCategory category) {
        return thingRepository.findByProjectIdAndThingCategory(projectId, category);
    }

    public List<ThingDocument> findByCategory(ThingCategory category) {
        return thingRepository.findByThingCategory(category);
    }

    public List<ThingDocument> findAll() {
        return thingRepository.findAll();
    }

    /** Fetches all things for a project, grouped by category. */
    public Map<ThingCategory, List<ThingDocument>> findByProjectGrouped(String projectId) {
        return thingRepository.findByProjectId(projectId).stream()
                .collect(Collectors.groupingBy(ThingDocument::getThingCategory));
    }

    // ---- Payload-based queries (MongoTemplate) ----

    /** Find things by category and a single payload field value. */
    public List<ThingDocument> findByPayloadField(ThingCategory category, String field, Object value) {
        Query query = new Query()
                .addCriteria(Criteria.where("thingCategory").is(category))
                .addCriteria(Criteria.where("payload." + field).is(value));
        return mongoTemplate.find(query, ThingDocument.class);
    }

    /** Find things by category and multiple payload field values. */
    public List<ThingDocument> findByPayloadFields(ThingCategory category, Map<String, Object> fieldValues) {
        Query query = new Query().addCriteria(Criteria.where("thingCategory").is(category));
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            query.addCriteria(Criteria.where("payload." + entry.getKey()).is(entry.getValue()));
        }
        return mongoTemplate.find(query, ThingDocument.class);
    }

    /** Find things by project, category, and a single payload field value. */
    public List<ThingDocument> findByProjectCategoryAndPayload(String projectId, ThingCategory category,
                                                                String field, Object value) {
        Query query = new Query()
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("thingCategory").is(category))
                .addCriteria(Criteria.where("payload." + field).is(value));
        return mongoTemplate.find(query, ThingDocument.class);
    }

    /** Find due reminders: triggered=false AND triggerAt < now. */
    public List<ThingDocument> findDueReminders(Instant before) {
        Query query = new Query()
                .addCriteria(Criteria.where("thingCategory").is(ThingCategory.REMINDER))
                .addCriteria(Criteria.where("payload.triggered").is(false))
                .addCriteria(Criteria.where("payload.triggerAt").lt(Date.from(before)));
        return mongoTemplate.find(query, ThingDocument.class);
    }

    /** Find a single thing by category and two payload fields (e.g., resourceId+ticketId). */
    public Optional<ThingDocument> findOneByPayloadFields(ThingCategory category, Map<String, Object> fieldValues) {
        List<ThingDocument> results = findByPayloadFields(category, fieldValues);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Find first thing by project, category, and case-insensitive payload.title match. */
    public Optional<ThingDocument> findByProjectCategoryAndTitleIgnoreCase(
            String projectId, ThingCategory category, String title) {
        Query query = new Query()
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("thingCategory").is(category))
                .addCriteria(Criteria.where("payload.title").regex(
                        "^" + Pattern.quote(title) + "$", "i"));
        ThingDocument result = mongoTemplate.findOne(query, ThingDocument.class);
        return Optional.ofNullable(result);
    }

    /** Find first thing by project, category, and case-insensitive match on an arbitrary payload field. */
    public Optional<ThingDocument> findByProjectCategoryAndPayloadFieldIgnoreCase(
            String projectId, ThingCategory category, String field, String value) {
        Query query = new Query()
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("thingCategory").is(category))
                .addCriteria(Criteria.where("payload." + field).regex(
                        "^" + Pattern.quote(value) + "$", "i"));
        ThingDocument result = mongoTemplate.findOne(query, ThingDocument.class);
        return Optional.ofNullable(result);
    }

    /** Find first thing by project, category, and case-insensitive payload.name match. */
    public Optional<ThingDocument> findByProjectCategoryAndNameIgnoreCase(
            String projectId, ThingCategory category, String name) {
        Query query = new Query()
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("thingCategory").is(category))
                .addCriteria(Criteria.where("payload.name").regex(
                        "^" + Pattern.quote(name) + "$", "i"));
        ThingDocument result = mongoTemplate.findOne(query, ThingDocument.class);
        return Optional.ofNullable(result);
    }

    /** Find things by project and category, sorted by a field (can be top-level or payload field). */
    public List<ThingDocument> findByProjectAndCategorySorted(
            String projectId, ThingCategory category, String sortField, boolean ascending) {
        Query query = new Query()
                .addCriteria(Criteria.where("projectId").is(projectId))
                .addCriteria(Criteria.where("thingCategory").is(category))
                .with(Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, sortField));
        return mongoTemplate.find(query, ThingDocument.class);
    }

    // ---- Convenience builders ----

    public ThingDocument createThing(String projectId, ThingCategory category, Map<String, Object> payload) {
        ThingDocument thing = new ThingDocument();
        thing.setProjectId(projectId);
        thing.setThingCategory(category);
        thing.setPayload(payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>());
        return save(thing);
    }

    /** Convert a typed POJO into a payload Map using Jackson. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toPayload(Object pojo) {
        return objectMapper.convertValue(pojo, Map.class);
    }

    /** Convert a payload Map back into a typed POJO using Jackson. */
    public <T> T fromPayload(ThingDocument thing, Class<T> type) {
        return objectMapper.convertValue(thing.getPayload(), type);
    }

    /** Update specific payload fields without replacing the entire payload. */
    public ThingDocument mergePayload(ThingDocument thing, Map<String, Object> updates) {
        Map<String, Object> payload = thing.getPayload();
        if (payload == null) {
            payload = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                payload.remove(entry.getKey());
            } else {
                payload.put(entry.getKey(), entry.getValue());
            }
        }
        thing.setPayload(payload);
        return save(thing);
    }
}
