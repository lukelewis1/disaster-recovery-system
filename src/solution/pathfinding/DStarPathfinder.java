package solution.pathfinding;

import solution.Graph;
import solution.DStarLite;

import java.util.List;

/**
 * One-shot adapter exposing the D* Lite engine through the Pathfinder interface. Each call
 * builds a fresh target-rooted D* Lite (goal = destination) and extracts start→goal — a plain
 * search with no incremental benefit, so it's used only for outbound legs. Return legs reuse
 * PathFinder's persistent base-rooted D* Lite, where the incremental repair actually pays off.
 */
public class DStarPathfinder implements Pathfinder {

    @Override
    public String name() { return "DStar"; }

    @Override
    public List<String> findPath(Graph graph, String start, String goal) {
        if (start.equals(goal)) return List.of(start);
        DStarLite engine = new DStarLite(graph, goal);
        engine.initialize();
        return engine.getPath(start);
    }
}
