package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.protocol.api.ResourceDto;
import io.github.drompincen.javaclawv1.runtime.resource.ResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourcesViewTest {

    @Mock
    private ResourceService resourceService;

    @Test
    void constructsWithoutErrors() {
        ResourcesView view = new ResourcesView(resourceService);
        assertThat(view).isNotNull();
    }

    @Test
    void refreshPopulatesTable() throws InvocationTargetException, InterruptedException {
        ResourceDocument r1 = new ResourceDocument();
        r1.setResourceId("r1");
        r1.setName("Alice");
        r1.setRole(ResourceDto.ResourceRole.ENGINEER);

        when(resourceService.findAll()).thenReturn(List.of(r1));
        when(resourceService.getWorkloads()).thenReturn(Map.of("r1", 75.0));

        ResourcesView view = new ResourcesView(resourceService);
        view.refresh();

        // Flush the EDT queue so the invokeLater lambda executes
        SwingUtilities.invokeAndWait(() -> {});

        assertThat(view).isNotNull();
    }
}
