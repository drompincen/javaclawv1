package io.github.drompincen.javaclawv1.runtime.agent.llm;

public record AssertionResult(String name, boolean passed, String expected, String actual) {}
