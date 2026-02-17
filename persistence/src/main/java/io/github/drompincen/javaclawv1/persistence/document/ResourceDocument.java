package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ResourceDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "resources")
public class ResourceDocument {

    @Id
    private String resourceId;
    private String name;
    private String email;
    private ResourceDto.ResourceRole role;
    private List<String> skills;
    private double availability;

    public ResourceDocument() {}

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public ResourceDto.ResourceRole getRole() { return role; }
    public void setRole(ResourceDto.ResourceRole role) { this.role = role; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public double getAvailability() { return availability; }
    public void setAvailability(double availability) { this.availability = availability; }
}
