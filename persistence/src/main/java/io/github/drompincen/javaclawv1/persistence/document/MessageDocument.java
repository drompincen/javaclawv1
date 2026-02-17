package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "messages")
@CompoundIndex(name = "session_seq", def = "{'sessionId': 1, 'seq': 1}", unique = true)
public class MessageDocument {

    @Id
    private String messageId;
    private String sessionId;
    private long seq;
    private String role;
    private String content;
    private List<ContentPart> parts;
    private Instant timestamp;

    public MessageDocument() {}

    public static class ContentPart {
        private String type;      // "text" or "image"
        private String text;      // for type="text"
        private String mediaType; // for type="image", e.g. "image/png"
        private String data;      // for type="image", base64 encoded

        public ContentPart() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ContentPart> getParts() { return parts; }
    public void setParts(List<ContentPart> parts) { this.parts = parts; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
