package solution.pathfinding;

import solution.Graph;

import java.util.*;

/**
 * Breadth-first search — fewest-hop path, ignoring edge weights. Explores level by level
 * from start, recording predecessors, then walks them back to rebuild the path. Cheap, but
 * can return a route longer in distance than a weighted search.
 */
public class Bfs implements Pathfinder {

    @Override
    public String name() { return "BFS"; }

    @Override
    public List<String> findPath(Graph graph, String start, String goal) {
        if (start.equals(goal)) return List.of(start);

        Map<String, String> prev = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();

        prev.put(start, null);
        queue.offer(start);

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr.equals(goal)) break;

            for (String nb : graph.getNeighbours(curr).keySet()) {
                if (!prev.containsKey(nb)) {
                    prev.put(nb, curr);
                    queue.offer(nb);
                }
            }
        }

        if (!prev.containsKey(goal)) return null;

        List<String> path = new ArrayList<>();
        String cur = goal;
        while (cur != null) {
            path.add(cur);
            cur = prev.get(cur);
        }
        Collections.reverse(path);
        return path;
    }
}
