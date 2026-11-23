package com.project.devsphere.monitoring.application;

import com.project.devsphere.monitoring.api.dto.AffectedTaskDto;
import com.project.devsphere.monitoring.api.dto.ControlTowerGraphResponse;
import com.project.devsphere.monitoring.api.dto.CpmEdgeDto;
import com.project.devsphere.monitoring.api.dto.CpmNodeDto;
import com.project.devsphere.monitoring.api.dto.ImpactAnalysisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpmAnalyzerTest {

    private CpmAnalyzer analyzer;
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        analyzer = new CpmAnalyzer(null);
    }

    @Test
    @DisplayName("Empty task list produces empty CPM analysis with 0 makespan")
    void analyze_emptyTasks_returnsZeroMakespan() {
        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, List.of(), List.of());

        assertThat(result.projectMakespanMinutes()).isEqualTo(0L);
        assertThat(result.totalTasks()).isEqualTo(0);
        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
        assertThat(result.criticalPathItemIds()).isEmpty();
    }

    @Test
    @DisplayName("Single task has 0 slack and is on critical path")
    void analyze_singleTask_isCriticalWithZeroSlack() {
        UUID taskId = UUID.randomUUID();
        CpmAnalyzer.CpmTask task = new CpmAnalyzer.CpmTask(taskId, "T-1", "Setup DB", "TO_DO", false, 120);

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, List.of(task), List.of());

        assertThat(result.projectMakespanMinutes()).isEqualTo(120L);
        assertThat(result.criticalTasksCount()).isEqualTo(1);
        assertThat(result.criticalPathItemIds()).containsExactly(taskId);

        CpmNodeDto node = result.nodeMap().get(taskId);
        assertThat(node.earlyStartMinutes()).isEqualTo(0L);
        assertThat(node.earlyFinishMinutes()).isEqualTo(120L);
        assertThat(node.lateStartMinutes()).isEqualTo(0L);
        assertThat(node.lateFinishMinutes()).isEqualTo(120L);
        assertThat(node.totalSlackMinutes()).isEqualTo(0L);
        assertThat(node.freeSlackMinutes()).isEqualTo(0L);
        assertThat(node.critical()).isTrue();
    }

    @Test
    @DisplayName("Linear chain (A -> B -> C) calculates sequential early and late dates")
    void analyze_linearChain_correctEarlyAndLateDates() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 200),
                new CpmAnalyzer.CpmTask(idC, "T-C", "Task C", "TO_DO", false, 150)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idB, idC, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        assertThat(result.projectMakespanMinutes()).isEqualTo(450L); // 100 + 200 + 150
        assertThat(result.criticalPathItemIds()).containsExactly(idA, idB, idC);

        CpmNodeDto nodeA = result.nodeMap().get(idA);
        assertThat(nodeA.earlyStartMinutes()).isEqualTo(0L);
        assertThat(nodeA.earlyFinishMinutes()).isEqualTo(100L);
        assertThat(nodeA.totalSlackMinutes()).isEqualTo(0L);
        assertThat(nodeA.critical()).isTrue();

        CpmNodeDto nodeB = result.nodeMap().get(idB);
        assertThat(nodeB.earlyStartMinutes()).isEqualTo(100L);
        assertThat(nodeB.earlyFinishMinutes()).isEqualTo(300L);
        assertThat(nodeB.totalSlackMinutes()).isEqualTo(0L);
        assertThat(nodeB.critical()).isTrue();

        CpmNodeDto nodeC = result.nodeMap().get(idC);
        assertThat(nodeC.earlyStartMinutes()).isEqualTo(300L);
        assertThat(nodeC.earlyFinishMinutes()).isEqualTo(450L);
        assertThat(nodeC.totalSlackMinutes()).isEqualTo(0L);
        assertThat(nodeC.critical()).isTrue();
    }

    @Test
    @DisplayName("Precedence with lag minutes delays successor early start")
    void analyze_withLagMinutes_delaysSuccessor() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 50)
        );

        // 30-minute lag between A and B
        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 30)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        assertThat(result.projectMakespanMinutes()).isEqualTo(180L); // 100 + 30 lag + 50
        CpmNodeDto nodeB = result.nodeMap().get(idB);
        assertThat(nodeB.earlyStartMinutes()).isEqualTo(130L);
        assertThat(nodeB.earlyFinishMinutes()).isEqualTo(180L);
    }

    @Test
    @DisplayName("Diamond topology correctly identifies critical path and non-critical slack")
    void analyze_diamondDag_identifiesCriticalPathAndSlack() {
        // A (100) -> B (300) -> D (100) => 100 + 300 + 100 = 500 (Critical)
        // A (100) -> C (100) -> D (100) => 100 + 100 + 100 = 300 (Slack = 200 on C)
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();
        UUID idD = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 300),
                new CpmAnalyzer.CpmTask(idC, "T-C", "Task C", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idD, "T-D", "Task D", "TO_DO", false, 100)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idA, idC, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idB, idD, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idC, idD, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        assertThat(result.projectMakespanMinutes()).isEqualTo(500L);
        assertThat(result.criticalTasksCount()).isEqualTo(3); // A, B, D

        // Task C should have Total Slack = 200 and Free Slack = 200
        CpmNodeDto nodeC = result.nodeMap().get(idC);
        assertThat(nodeC.earlyStartMinutes()).isEqualTo(100L);
        assertThat(nodeC.earlyFinishMinutes()).isEqualTo(200L);
        assertThat(nodeC.lateStartMinutes()).isEqualTo(300L);
        assertThat(nodeC.lateFinishMinutes()).isEqualTo(400L);
        assertThat(nodeC.totalSlackMinutes()).isEqualTo(200L);
        assertThat(nodeC.freeSlackMinutes()).isEqualTo(200L);
        assertThat(nodeC.critical()).isFalse();

        // Task B is critical
        CpmNodeDto nodeB = result.nodeMap().get(idB);
        assertThat(nodeB.totalSlackMinutes()).isEqualTo(0L);
        assertThat(nodeB.critical()).isTrue();

        // ControlTowerGraphResponse mapping
        ControlTowerGraphResponse graph = result.toGraphResponse();
        assertThat(graph.projectMakespanMinutes()).isEqualTo(500L);
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.edges()).hasSize(4);
    }

    @Test
    @DisplayName("Cycle in dependencies throws MonitoringException with 422 DEPENDENCY_CYCLE_DETECTED")
    void analyze_cycleDetected_throws422() {
        // A -> B -> C -> A (Cycle)
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 50),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 50),
                new CpmAnalyzer.CpmTask(idC, "T-C", "Task C", "TO_DO", false, 50)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idB, idC, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idC, idA, "FINISH_TO_START", 0)
        );

        assertThatThrownBy(() -> analyzer.analyze(projectId, tasks, deps))
                .isInstanceOf(MonitoringException.class)
                .satisfies(ex -> {
                    MonitoringException me = (MonitoringException) ex;
                    assertThat(me.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(me.getCode()).isEqualTo("DEPENDENCY_CYCLE_DETECTED");
                    assertThat(me.getMessage()).contains("Dependency cycle detected");
                });
    }

    @Test
    @DisplayName("Impact analysis on critical task shifts project ETA by full delay")
    void calculateImpact_criticalTask_shiftsProjectEta() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idC, "T-C", "Task C", "TO_DO", false, 100)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idB, idC, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        // Delay task A by 60 minutes
        ImpactAnalysisResponse impact = result.calculateImpact(idA, 60);

        assertThat(impact.targetWorkItemId()).isEqualTo(idA);
        assertThat(impact.delayMinutes()).isEqualTo(60);
        assertThat(impact.targetTotalSlackMinutes()).isEqualTo(0L);
        assertThat(impact.projectEtaDelayMinutes()).isEqualTo(60L);
        assertThat(impact.shiftsProjectDeadline()).isTrue();

        // Downstream tasks B and C should be pushed by 60 minutes
        assertThat(impact.affectedTasks()).hasSize(2);

        AffectedTaskDto affB = impact.affectedTasks().stream()
                .filter(t -> t.workItemId().equals(idB)).findFirst().orElseThrow();
        assertThat(affB.originalEarlyStart()).isEqualTo(100L);
        assertThat(affB.delayedEarlyStart()).isEqualTo(160L);
        assertThat(affB.delayMinutes()).isEqualTo(60L);

        AffectedTaskDto affC = impact.affectedTasks().stream()
                .filter(t -> t.workItemId().equals(idC)).findFirst().orElseThrow();
        assertThat(affC.originalEarlyStart()).isEqualTo(200L);
        assertThat(affC.delayedEarlyStart()).isEqualTo(260L);
        assertThat(affC.delayMinutes()).isEqualTo(60L);
    }

    @Test
    @DisplayName("Impact analysis on task with slack absorbs delay when delay <= slack")
    void calculateImpact_taskWithSlack_delayLessThanSlack_noProjectShift() {
        // A (100) -> B (300) -> D (100) => Makespan = 500
        // A (100) -> C (100) -> D (100) => C has slack = 200
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();
        UUID idD = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idA, "T-A", "Task A", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idB, "T-B", "Task B", "TO_DO", false, 300),
                new CpmAnalyzer.CpmTask(idC, "T-C", "Task C", "TO_DO", false, 100),
                new CpmAnalyzer.CpmTask(idD, "T-D", "Task D", "TO_DO", false, 100)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idA, idB, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idA, idC, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idB, idD, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idC, idD, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        // Delay task C by 50 minutes (slack is 200)
        ImpactAnalysisResponse impact = result.calculateImpact(idC, 50);

        assertThat(impact.targetWorkItemId()).isEqualTo(idC);
        assertThat(impact.targetTotalSlackMinutes()).isEqualTo(200L);
        assertThat(impact.delayMinutes()).isEqualTo(50);
        assertThat(impact.projectEtaDelayMinutes()).isEqualTo(0L); // Absorbed!
        assertThat(impact.shiftsProjectDeadline()).isFalse();
        // Task D early start is not pushed because B finishes at 400, while C finishes at 250 <= 400
        assertThat(impact.affectedTasks()).isEmpty();

        // Now delay task C by 250 minutes (exceeds slack 200 by 50 mins)
        ImpactAnalysisResponse largeImpact = result.calculateImpact(idC, 250);
        assertThat(largeImpact.projectEtaDelayMinutes()).isEqualTo(50L);
        assertThat(largeImpact.shiftsProjectDeadline()).isTrue();
        assertThat(largeImpact.affectedTasks()).hasSize(1);
        AffectedTaskDto affD = largeImpact.affectedTasks().get(0);
        assertThat(affD.workItemId()).isEqualTo(idD);
        assertThat(affD.originalEarlyStart()).isEqualTo(400L);
        assertThat(affD.delayedEarlyStart()).isEqualTo(450L);
        assertThat(affD.delayMinutes()).isEqualTo(50L);
    }

    @Test
    @DisplayName("Intermediate node where Free Slack is strictly distinct from Total Slack (Free Slack = 0, Total Slack > 0)")
    void analyze_intermediateNode_freeSlackDistinctFromTotalSlack() {
        // S (10) -> X1 (10) -> X2 (10) -> J (10) => Path length 40
        // S (10) -> Y1 (20) -> Y2 (30) -> J (10) => Path length 70 (Critical)
        UUID idS = UUID.randomUUID();
        UUID idX1 = UUID.randomUUID();
        UUID idX2 = UUID.randomUUID();
        UUID idY1 = UUID.randomUUID();
        UUID idY2 = UUID.randomUUID();
        UUID idJ = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idS, "S", "Start", "TO_DO", false, 10),
                new CpmAnalyzer.CpmTask(idX1, "X1", "Task X1", "TO_DO", false, 10),
                new CpmAnalyzer.CpmTask(idX2, "X2", "Task X2", "TO_DO", false, 10),
                new CpmAnalyzer.CpmTask(idY1, "Y1", "Task Y1", "TO_DO", false, 20),
                new CpmAnalyzer.CpmTask(idY2, "Y2", "Task Y2", "TO_DO", false, 30),
                new CpmAnalyzer.CpmTask(idJ, "J", "Join", "TO_DO", false, 10)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idS, idX1, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idX1, idX2, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idX2, idJ, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idS, idY1, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idY1, idY2, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idY2, idJ, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        assertThat(result.projectMakespanMinutes()).isEqualTo(70L); // 10 + 20 + 30 + 10

        // For X1:
        // ES = 10, EF = 20.
        // ES(X2) = 20.
        // FreeSlack(X1) = ES(X2) - EF(X1) = 20 - 20 = 0.
        // LF(X1) = LS(X2) = 50 - 10 = 40. LS(X1) = 40.
        // TotalSlack(X1) = LS(X1) - ES(X1) = 40 - 10 = 30.
        CpmNodeDto nodeX1 = result.nodeMap().get(idX1);
        assertThat(nodeX1.earlyStartMinutes()).isEqualTo(10L);
        assertThat(nodeX1.earlyFinishMinutes()).isEqualTo(20L);
        assertThat(nodeX1.totalSlackMinutes()).isEqualTo(30L);
        assertThat(nodeX1.freeSlackMinutes()).isEqualTo(0L); // Free Slack == 0 while Total Slack == 30!
        assertThat(nodeX1.freeSlackMinutes()).isNotEqualTo(nodeX1.totalSlackMinutes());

        // For X2:
        // TotalSlack = 30, FreeSlack = ES(J) - EF(X2) = 60 - 30 = 30.
        CpmNodeDto nodeX2 = result.nodeMap().get(idX2);
        assertThat(nodeX2.totalSlackMinutes()).isEqualTo(30L);
        assertThat(nodeX2.freeSlackMinutes()).isEqualTo(30L);

        // Critical path nodes have 0 total slack
        assertThat(result.nodeMap().get(idY1).totalSlackMinutes()).isEqualTo(0L);
        assertThat(result.nodeMap().get(idY2).totalSlackMinutes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Disconnected DAG components calculate correct project makespan and component float")
    void analyze_disconnectedComponents_calculatesRelativeSlack() {
        // Component 1: C1_A (10) -> C1_B (20) => Length 30
        // Component 2: C2_X (50) -> C2_Y (50) => Length 100 (Critical)
        UUID idC1A = UUID.randomUUID();
        UUID idC1B = UUID.randomUUID();
        UUID idC2X = UUID.randomUUID();
        UUID idC2Y = UUID.randomUUID();

        List<CpmAnalyzer.CpmTask> tasks = List.of(
                new CpmAnalyzer.CpmTask(idC1A, "C1-A", "Comp 1 A", "TO_DO", false, 10),
                new CpmAnalyzer.CpmTask(idC1B, "C1-B", "Comp 1 B", "TO_DO", false, 20),
                new CpmAnalyzer.CpmTask(idC2X, "C2-X", "Comp 2 X", "TO_DO", false, 50),
                new CpmAnalyzer.CpmTask(idC2Y, "C2-Y", "Comp 2 Y", "TO_DO", false, 50)
        );

        List<CpmAnalyzer.CpmDependency> deps = List.of(
                new CpmAnalyzer.CpmDependency(idC1A, idC1B, "FINISH_TO_START", 0),
                new CpmAnalyzer.CpmDependency(idC2X, idC2Y, "FINISH_TO_START", 0)
        );

        CpmAnalyzer.CpmAnalysisResult result = analyzer.analyze(projectId, tasks, deps);

        assertThat(result.projectMakespanMinutes()).isEqualTo(100L); // Max across disconnected DAGs
        assertThat(result.criticalTasksCount()).isEqualTo(2); // C2_X, C2_Y
        assertThat(result.criticalPathItemIds()).containsExactly(idC2X, idC2Y);

        // Component 1 terminates at 30 min, so C1_B has 70 min float relative to project makespan 100
        CpmNodeDto nodeC1B = result.nodeMap().get(idC1B);
        assertThat(nodeC1B.earlyFinishMinutes()).isEqualTo(30L);
        assertThat(nodeC1B.totalSlackMinutes()).isEqualTo(70L);
        assertThat(nodeC1B.freeSlackMinutes()).isEqualTo(70L);
        assertThat(nodeC1B.critical()).isFalse();
    }
}
