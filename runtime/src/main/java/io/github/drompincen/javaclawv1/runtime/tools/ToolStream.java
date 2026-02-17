package io.github.drompincen.javaclawv1.runtime.tools;

public interface ToolStream {

    void stdoutDelta(String text);

    void stderrDelta(String text);

    void progress(int percent, String message);

    void artifactCreated(String type, String uriOrRef);
}
