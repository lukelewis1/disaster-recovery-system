package solution.pathfinding;

import solution.Graph;

import java.util.*;

/**
 * Standard single-source Dijkstra — shortest path by total edge weight. Expands nodes in
 * cheapest-cost order from start, relaxing neighbours and recording predecessors until goal
 * is settled (stale queue entries skipped via lazy deletion). Slower than the bidirectional
 * variant on point-to-point queries since it fans out over the whole reachable graph.
 */
public class Dijkstra implements Pathfinder {

    @Override
    public String name() { return "Dijkstra"; }

    @Override
    public List<String> findPath(Graph graph, String start, String goal) {
        if (start.equals(goal)) return List.of(start);

        record Entry(double cost, String node) {}
        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();

        dist.put(start, 0.0);
        pq.offer(new Entry(0.0, start));

        while (!pq.isEmpty()) {
            Entry curr = pq.poll();

            if (curr.node().equals(goal)) break;
            if (curr.cost() > dist.getOrDefault(curr.node(), Double.MAX_VALUE)) continue;

            for (Map.Entry<String, Double> edge : graph.getNeighbours(curr.node()).entrySet()) {
                String nb = edge.getKey();
                double newCost = curr.cost() + edge.getValue();
                if (newCost < dist.getOrDefault(nb, Double.MAX_VALUE)) {
                    dist.put(nb, newCost);
                    prev.put(nb, curr.node());
                    pq.offer(new Entry(newCost, nb));
                }
            }
        }

        return reconstruct(prev, start, goal);
    }

    private List<String> reconstruct(Map<String, String> prev, String start, String goal) {
        List<String> path = new ArrayList<>();
        String current = goal;
        while (current != null && !current.equals(start)) {
            path.add(current);
            current = prev.get(current);
        }
        if (current == null) return null;
        path.add(start);
        Collections.reverse(path);
        return path;
    }
}
