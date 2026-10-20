package dev.apifaultlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(LabSettings.class)
public class ApiFaultLabApplication {
    public static void main(String[] args) { SpringApplication.run(ApiFaultLabApplication.class, args); }
    @Bean AiGateway aiGateway(LabSettings settings) { return new AiGateway(settings); }
    @Bean KnowledgeService knowledgeService(LabSettings settings, AiGateway gateway) { return new KnowledgeService(settings, gateway.embedding()); }
    @Bean PlannerAgent plannerAgent(AiGateway gateway) { return new PlannerAgent(gateway); }
    @Bean ReviewerAgent reviewerAgent(AiGateway gateway) { return new ReviewerAgent(gateway); }
    @Bean AiAssistanceService aiAssistanceService(AiGateway gateway) { return new AiAssistanceService(gateway); }
    @Bean ScenarioRunner scenarioRunner() { return new ScenarioRunner(); }
    @Bean ReadOnlyProbeService readOnlyProbeService(LabSettings settings, AiAssistanceService aiAssistance) {
        return new ReadOnlyProbeService(java.nio.file.Path.of(settings.runtimeDir()),
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build(),
                java.util.List.of("http://127.0.0.1:8080"), aiAssistance);
    }
    @Bean DrillOrchestrator drillOrchestrator(LabSettings settings, KnowledgeService knowledge, AiGateway gateway,
                                             PlannerAgent planner, ReviewerAgent reviewer, ScenarioRunner runner,
                                             AiAssistanceService aiAssistance) {
        return new DrillOrchestrator(settings, knowledge, gateway, planner, reviewer, runner, aiAssistance);
    }
}
