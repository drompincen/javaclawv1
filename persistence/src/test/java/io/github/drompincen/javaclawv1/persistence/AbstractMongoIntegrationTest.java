package io.github.drompincen.javaclawv1.persistence;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@DataMongoTest
@ActiveProfiles("test")
@ContextConfiguration(classes = TestMongoConfiguration.class)
public abstract class AbstractMongoIntegrationTest {

    @Autowired
    protected MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanDatabase() {
        for (String collectionName : mongoTemplate.getCollectionNames()) {
            mongoTemplate.dropCollection(collectionName);
        }
        ensureIndexes();
    }

    private void ensureIndexes() {
        // Recreate unique compound indexes that production creates via mongo-init.js
        mongoTemplate.indexOps("messages").ensureIndex(
                new CompoundIndexDefinition(
                        new Document("sessionId", 1).append("seq", 1))
                        .unique());
    }
}
