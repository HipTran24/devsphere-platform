package com.project.devsphere.solver.application;

import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;

public interface SchedulingSolver {

    SolverResult solve(SolverInputSnapshot snapshot);
}
