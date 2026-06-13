package solution.pathfinding;

import solution.Graph;

import java.util.*;

/**
 * Bidirectional Dijkstra — runs a forward search from start and a backward search from goal
 * at once, advancing whichever frontier has the cheaper top, then joins them at the best
 * meeting node (min fwdDist + bwdDist). Meeting in the middle settles roughly half the nodes
 * of a full Dijkstra on point-to-point queries, so it's the default search here.
 */
public class BidirectionalDijkstra implements Pathfinder {

    @Override
    public String name() { return "BidirectionalDijkstra"; }

    @Override
    public List<String> findPath(Graph graph, String start, String goal) {
        if (start.equals(goal)) return List.of(start);

        record Entry(double cost, String node) {}

        Map<String, Double> fwdDist = new HashMap<>();
        Map<String, String> fwdPrev = new HashMap<>();
        PriorityQueue<Entry> fwdPQ = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        Map<String, Double> bwdDist = new HashMap<>();
        Map<String, String> bwdPrev = new HashMap<>();
        PriorityQueue<Entry> bwdPQ = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        fwdDist.put(start, 0.0);
        fwdPQ.offer(new Entry(0.0, start));
        bwdDist.put(goal, 0.0);
        bwdPQ.offer(new Entry(0.0, goal));

        Set<String> fwdSettled = new HashSet<>();
        Set<String> bwdSettled = new HashSet<>();

        while (!fwdPQ.isEmpty() || !bwdPQ.isEmpty()) {
            double fwdTop = fwdPQ.isEmpty() ? Double.MAX_VALUE : fwdPQ.peek().cost();
            double bwdTop = bwdPQ.isEmpty() ? Double.MAX_VALUE : bwdPQ.peek().cost();

            if (fwdTop <= bwdTop) {
                Entry curr = fwdPQ.poll();
                if (fwdSettled.contains(curr.node())) continue;
                if (curr.cost() > fwdDist.getOrDefault(curr.node(), Double.MAX_VALUE)) continue;
                fwdSettled.add(curr.node());

                for (Map.Entry<String, Double> edge : graph.getNeighbours(curr.node()).entrySet()) {
                    String nb = edge.getKey();
                    double newCost = curr.cost() + edge.getValue();
                    if (newCost < fwdDist.getOrDefault(nb, Double.MAX_VALUE)) {
                        fwdDist.put(nb, newCost);
                        fwdPrev.put(nb, curr.node());
                        fwdPQ.offer(new Entry(newCost, nb));
                    }
                }
            } else {
                Entry curr = bwdPQ.poll();
                if (bwdSettled.contains(curr.node())) continue;
                if (curr.cost() > bwdDist.getOrDefault(curr.node(), Double.MAX_VALUE)) continue;
                bwdSettled.add(curr.node());

                for (Map.Entry<String, Double> edge : graph.getReverseNeighbours(curr.node()).entrySet()) {
                    String nb = edge.getKey();
                    double newCost = curr.cost() + edge.getValue();
                    if (newCost < bwdDist.getOrDefault(nb, Double.MAX_VALUE)) {
                        bwdDist.put(nb, newCost);
                        bwdPrev.put(nb, curr.node());
                        bwdPQ.offer(new Entry(newCost, nb));
                    }
                }
            }
        }

        // Find the minimum-cost meeting node reached by both frontiers.
        double bestCost = Double.MAX_VALUE;
        String meetingNode = null;
        for (String node : fwdDist.keySet()) {
            if (bwdDist.containsKey(node)) {
                double cost = fwdDist.get(node) + bwdDist.get(node);
                if (cost < bestCost) {
                    bestCost = cost;
                    meetingNode = node;
                }
            }
        }

        if (meetingNode == null) return null;

        // Forward half: start -> meetingNode
        List<String> fwdHalf = new ArrayList<>();
        String cur = meetingNode;
        while (cur != null && !cur.equals(start)) {
            fwdHalf.add(cur);
            cur = fwdPrev.get(cur);
        }
        if (cur == null) return null;
        fwdHalf.add(start);
        Collections.reverse(fwdHalf);

        // Backward half: meetingNode -> goal (bwdPrev already points toward goal)
        List<String> bwdHalf = new ArrayList<>();
        cur = meetingNode;
        while (cur != null && !cur.equals(goal)) {
            bwdHalf.add(cur);
            cur = bwdPrev.get(cur);
        }
        if (cur == null) return null;
        bwdHalf.add(goal);

        fwdHalf.addAll(bwdHalf.subList(1, bwdHalf.size()));
        return fwdHalf;
    }
}
