package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.repository.IdeaRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.runtime.merge.MergeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IdeasViewTest {

    @Mock
    private IdeaRepository ideaRepository;
    @Mock
    private ChangeStreamService changeStreamService;
    @Mock
    private MergeService mergeService;

    @Test
    void constructsWithoutErrors() {
        IdeasView view = new IdeasView(ideaRepository, changeStreamService, mergeService);
        assertThat(view).isNotNull();
    }
}
