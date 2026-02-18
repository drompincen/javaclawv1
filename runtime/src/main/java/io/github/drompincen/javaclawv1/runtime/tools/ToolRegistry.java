package io.github.drompincen.javaclawv1.runtime.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void loadTools() {
        ServiceLoader<Tool> loader = ServiceLoader.load(Tool.class);
        for (Tool tool : loader) {
            injectDependencies(tool);
            register(tool);
        }
        log.info("Loaded {} tools via SPI", tools.size());
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.debug("Registered tool: {}", tool.name());
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(t -> new ToolDescriptor(t.name(), t.description(),
                        t.inputSchema(), t.outputSchema(), t.riskProfiles()))
                .collect(Collectors.toList());
    }

    private void injectDependencies(Tool tool) {
        for (Method method : tool.getClass().getMethods()) {
            if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                try {
                    Object bean = applicationContext.getBean(paramType);
                    method.invoke(tool, bean);
                    log.debug("Injected {} into {}.{}", paramType.getSimpleName(),
                            tool.getClass().getSimpleName(), method.getName());
                } catch (NoSuchBeanDefinitionException e) {
                    log.trace("No bean of type {} for {}.{}", paramType.getSimpleName(),
                            tool.getClass().getSimpleName(), method.getName());
                } catch (Exception e) {
                    log.warn("Failed to inject {} into {}.{}: {}", paramType.getSimpleName(),
                            tool.getClass().getSimpleName(), method.getName(), e.getMessage());
                }
            }
        }
    }
}
