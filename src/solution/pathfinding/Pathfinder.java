package solution.pathfinding;

import solution.Graph;

import java.util.List;

/**
 * Common interface for one-shot pathfinding strategies. Each implementation runs a single
 * stateless search on the current graph and returns a fresh path, so PathFinder can hold one
 * of each and swap algorithms just by swapping the implementation behind this interface.
 */
public interface Pathfinder {

    // Find a path start -> goal. Returns [start, ..., goal], or null if unreachable.
    List<String> findPath(Graph graph, String start, String goal);

    // Short identifier for logging/selection.
    String name();
}
