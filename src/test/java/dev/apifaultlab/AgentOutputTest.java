package dev.apifaultlab;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentOutputTest {
    @Test void malformedJsonIsRejectedInsteadOfInventingAPlan() {
        var gateway = mock(AiGateway.class);
        when(gateway.chat(anyString(), anyString(), anyString())).thenReturn("this is not JSON");
        assertThatThrownBy(() -> new PlannerAgent(gateway).plan("request", List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
