package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "things")
@CompoundIndexes({
        @CompoundIndex(name = "project_category", def = "{'projectId': 1, 'thingCategory': 1}"),
        @CompoundIndex(name = "project_category_status", def = "{'projectId': 1, 'thingCategory': 1, 'payload.status': 1}")
})
public class ThingDocument {

    @Id
    private String id;
    private String projectId;
    private String projectName;
    private ThingCategory thingCategory;
    private Map<String, Object> payload;
    private Instant createDate;
    private Instant updateDate;

    public ThingDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public ThingCategory getThingCategory() { return thingCategory; }
    public void setThingCategory(ThingCategory thingCategory) { this.thingCategory = thingCategory; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getUpdateDate() { return updateDate; }
    public void setUpdateDate(Instant updateDate) { this.updateDate = updateDate; }

    /** Convenience: get a payload value with a default. */
    @SuppressWarnings("unchecked")
    public <T> T payloadGet(String key, T defaultValue) {
        if (payload == null) return defaultValue;
        Object val = payload.get(key);
        return val != null ? (T) val : defaultValue;
    }

    /** Convenience: get a payload string. */
    public String payloadString(String key) {
        if (payload == null) return null;
        Object val = payload.get(key);
        return val != null ? val.toString() : null;
    }
}
