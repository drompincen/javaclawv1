package io.github.drompincen.javaclawv1.protocol.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ofFactoryCreatesMessage() {
        WsMessage msg = WsMessage.of(WsMessageType.SUBSCRIBE_SESSION, "sess-1", new TextNode("payload"));

        assertThat(msg.type()).isEqualTo(WsMessageType.SUBSCRIBE_SESSION);
        assertThat(msg.sessionId()).isEqualTo("sess-1");
        assertThat(msg.payload().asText()).isEqualTo("payload");
        assertThat(msg.ts()).isNotNull();
    }

    @Test
    void errorFactoryCreatesErrorMessage() {
        WsMessage msg = WsMessage.error("sess-1", new TextNode("something went wrong"));

        assertThat(msg.type()).isEqualTo(WsMessageType.ERROR);
        assertThat(msg.sessionId()).isEqualTo("sess-1");
    }

    @Test
    void wsMessageTypesIncludeClientAndServerTypes() {
        assertThat(WsMessageType.valueOf("SUBSCRIBE_SESSION")).isNotNull();
        assertThat(WsMessageType.valueOf("UNSUBSCRIBE")).isNotNull();
        assertThat(WsMessageType.valueOf("EVENT")).isNotNull();
        assertThat(WsMessageType.valueOf("SUBSCRIBED")).isNotNull();
        assertThat(WsMessageType.valueOf("ERROR")).isNotNull();
    }
}
