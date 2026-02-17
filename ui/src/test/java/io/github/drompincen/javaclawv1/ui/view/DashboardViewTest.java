package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.repository.ScorecardRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.ScorecardDto;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardViewTest {

    @Mock
    private ScorecardRepository scorecardRepository;
    @Mock
    private TicketRepository ticketRepository;

    @Test
    void constructsWithoutErrors() {
        DashboardView view = new DashboardView(scorecardRepository, ticketRepository);
        assertThat(view).isNotNull();
    }

    @Test
    void setProjectIdTriggersRefresh() throws InvocationTargetException, InterruptedException {
        when(scorecardRepository.findByProjectId("p1")).thenReturn(Optional.empty());
        when(ticketRepository.findByProjectIdAndStatus(eq("p1"), any())).thenReturn(List.of());

        DashboardView view = new DashboardView(scorecardRepository, ticketRepository);
        view.setProjectId("p1");

        // Flush EDT queue so the invokeLater lambda executes
        SwingUtilities.invokeAndWait(() -> {});

        assertThat(view).isNotNull();
    }
}
