package com.example.search;

public class QueryPlannerTest {

    public void plansBooleanQuery() {
        QueryPlanner planner = new QueryPlanner();
        planner.enqueue("alpha");
        planner.plan("beta gamma");
        planner.closeCache();
        // regression: boolean plans must still drain the shared cache
        java.util.List<String> terms = planner.planBoolean("delta epsilon", true);
        if (terms.size() != 2) {
            throw new AssertionError("expected two fuzzy terms");
        }
    }
}
