package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ThingRepository extends MongoRepository<ThingDocument, String> {

    List<ThingDocument> findByProjectId(String projectId);

    List<ThingDocument> findByProjectIdAndThingCategory(String projectId, ThingCategory thingCategory);

    List<ThingDocument> findByThingCategory(ThingCategory thingCategory);

    Optional<ThingDocument> findByIdAndThingCategory(String id, ThingCategory thingCategory);

    void deleteByProjectIdAndThingCategory(String projectId, ThingCategory thingCategory);
}
