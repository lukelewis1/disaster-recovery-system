package solution;

import sim.Message;

import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * Encapsulates all pathfinding logic.
 * Runs exclusively on executor threads — never called directly from handle().
 */
public class PathFinder {

    private final Graph graph;
    private final BlockingQueue<Message> outMessageQueue;

    // Populated during precompute()
    private Map<String, Double> forwardDist;   // base -> node distances
    private Map<String, String> forwardPrev;   // predecessor map for outbound paths
    private Map<String, Double> reverseDist;   // node -> base distances (via reverse graph)
    private Map<String, String> reversePrev;   // predecessor map for return paths
    private Set<String> unreachable;           // nodes with no path from base

    public PathFinder(Graph graph, BlockingQueue<Message> outMessageQueue) {
        this.graph = graph;
        this.outMessageQueue = outMessageQueue;
    }

    // -------------------------------------------------------------------------
    // Precomputation — called once during setup() on two parallel threads
    // -------------------------------------------------------------------------

    /**
     * Runs forward and reverse Dijkstra from base in parallel.
     * Blocks until both complete. Safe to call from setup() only.
     */
    public void precompute(String base) throws InterruptedException {
        forwardDist = new HashMap<>();
        forwardPrev = new HashMap<>();
        reverseDist = new HashMap<>();
        reversePrev = new HashMap<>();

        Thread forwardThread = new Thread(() ->
                dijkstra(base, false, forwardDist, forwardPrev), "precompute-forward");

        Thread reverseThread = new Thread(() ->
                dijkstra(base, true, reverseDist, reversePrev), "precompute-reverse");

        forwardThread.start();
        reverseThread.start();
        forwardThread.join();
        reverseThread.join();

        // Build unreachable set — any node forwardDist never reached
        unreachable = new HashSet<>();
        for (String node : graph.getNodes()) {
            if (!forwardDist.containsKey(node)) {
                unreachable.add(node);
            }
        }
    }


    /**
     * Single-source Dijkstra from src.
     * reverse=false: uses forward edges (base -> all nodes).
     * reverse=true:  uses reverse edges (equivalent to running backwards from base,
     *                giving shortest path from every node back to base).
     */
    private void dijkstra(String src, boolean reverse,
                          Map<String, Double> dist,
                          Map<String, String> prev) {

        record Entry(double cost, String node) {}
        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        dist.put(src, 0.0);
        pq.offer(new Entry(0.0, src));

        while (!pq.isEmpty()) {
            Entry curr = pq.poll();

            // Lazy deletion — skip stale entries
            if (curr.cost() > dist.getOrDefault(curr.node(), Double.MAX_VALUE)) continue;

            Map<String, Double> neighbours = reverse
                    ? graph.getReverseNeighbours(curr.node())
                    : graph.getNeighbours(curr.node());

            for (Map.Entry<String, Double> edge : neighbours.entrySet()) {
                String dst = edge.getKey();
                double newCost = curr.cost() + edge.getValue();

                if (newCost < dist.getOrDefault(dst, Double.MAX_VALUE)) {
                    dist.put(dst, newCost);
                    prev.put(dst, curr.node());
                    pq.offer(new Entry(newCost, dst));
                }
            }
        }
    }


    /**
     * Reconstructs a path from a predecessor map by walking from dst back to src.
     * Returns the path as an ordered list [src, ..., dst], or null if unreachable.
     */
    private List<String> reconstructPath(Map<String, String> prev, String src, String dst) {
        List<String> path = new ArrayList<>();
        String current = dst;

        while (current != null && !current.equals(src)) {
            path.add(current);
            current = prev.get(current);
        }

        if (current == null) return null; // no path found

        path.add(src);
        Collections.reverse(path);
        return path;
    }


    /**
     * Sends a PATH message for a standard dispatch from base to target and back.
     * Uses precomputed forwardPrev and reversePrev — O(path length), no search.
     * Call this on the executor thread.
     */
    public void sendDispatchPath(int vehicleNo, String base, String dst) {
        List<String> outbound = reconstructPath(forwardPrev, base, dst);
        if (outbound == null) {
            System.err.println("No outbound path to " + dst + " — should have been caught by feasibility check");
            return;
        }

        List<String> returning = reconstructPath(reversePrev, base, dst);
        if (returning == null) {
            System.err.println("No return path from " + dst + " — cannot dispatch");
            return;
        }

        // Return path is stored as base->dst in reversePrev (reverse graph),
        // so reversing it gives dst->base
        Collections.reverse(returning);

        // Combine: outbound drops last node (dst) to avoid duplicate, then append return
        List<String> fullPath = new ArrayList<>(outbound);
        fullPath.addAll(returning.subList(1, returning.size())); // skip duplicate dst

        sendPath(vehicleNo, fullPath);
    }


    /**
     * Computes a new path from src to dst using bidirectional Dijkstra
     * on the current known graph, then sends the result followed by a return path.
     * Call this on the pathExecutor thread pool.
     */
    public void sendReroutePath(int vehicleNo, String src, String dst, String base) {
        List<String> outbound = bidirectionalDijkstra(src, dst);
        if (outbound == null) {
            System.err.println("No reroute path from " + src + " to " + dst);
            return;
        }

        // Return path: bidirectional Dijkstra from dst back to base
        List<String> returning = bidirectionalDijkstra(dst, base);
        if (returning == null) {
            System.err.println("No return path from " + dst + " to " + base);
            return;
        }

        List<String> fullPath = new ArrayList<>(outbound);
        fullPath.addAll(returning.subList(1, returning.size())); // skip duplicate dst

        sendPath(vehicleNo, fullPath);
    }

    /**
     * Bidirectional Dijkstra between src and dst on the current graph.
     * Explores roughly half the nodes of standard Dijkstra for point-to-point queries.
     * Returns the path as [src, ..., dst], or null if unreachable.
     */
    private List<String> bidirectionalDijkstra(String src, String dst) {
        if (src.equals(dst)) return List.of(src);

        record Entry(double cost, String node) {}

        // Forward search from src
        Map<String, Double> fwdDist = new HashMap<>();
        Map<String, String> fwdPrev = new HashMap<>();
        PriorityQueue<Entry> fwdPQ = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        // Backward search from dst (using reverse edges)
        Map<String, Double> bwdDist = new HashMap<>();
        Map<String, String> bwdPrev = new HashMap<>();
        PriorityQueue<Entry> bwdPQ = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        fwdDist.put(src, 0.0);
        fwdPQ.offer(new Entry(0.0, src));
        bwdDist.put(dst, 0.0);
        bwdPQ.offer(new Entry(0.0, dst));

        Set<String> fwdSettled = new HashSet<>();
        Set<String> bwdSettled = new HashSet<>();

        double bestCost = Double.MAX_VALUE;
        String meetingNode = null;

        while (!fwdPQ.isEmpty() || !bwdPQ.isEmpty()) {
            // Termination: both frontiers have exceeded the best known path
            double fwdTop = fwdPQ.isEmpty() ? Double.MAX_VALUE : fwdPQ.peek().cost();
            double bwdTop = bwdPQ.isEmpty() ? Double.MAX_VALUE : bwdPQ.peek().cost();
            if (fwdTop + bwdTop >= bestCost) break;

            // Expand the cheaper frontier
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
                    if (bwdSettled.contains(nb)) {
                        double candidate = newCost + bwdDist.getOrDefault(nb, Double.MAX_VALUE);
                        if (candidate < bestCost) {
                            bestCost = candidate;
                            meetingNode = nb;
                        }
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
                    if (fwdSettled.contains(nb)) {
                        double candidate = fwdDist.getOrDefault(nb, Double.MAX_VALUE) + newCost;
                        if (candidate < bestCost) {
                            bestCost = candidate;
                            meetingNode = nb;
                        }
                    }
                }
            }
        }

        if (meetingNode == null) return null;

        // Reconstruct forward half: src -> meetingNode
        List<String> fwdHalf = new ArrayList<>();
        String cur = meetingNode;
        while (cur != null && !cur.equals(src)) {
            fwdHalf.add(cur);
            cur = fwdPrev.get(cur);
        }
        if (cur == null) return null;
        fwdHalf.add(src);
        Collections.reverse(fwdHalf);

        // Reconstruct backward half: meetingNode -> dst
        // bwdPrev[node] = next hop toward dst, so walking it gives [meetingNode, ..., dst] directly
        List<String> bwdHalf = new ArrayList<>();
        cur = meetingNode;
        while (cur != null && !cur.equals(dst)) {
            bwdHalf.add(cur);
            cur = bwdPrev.get(cur);
        }
        if (cur == null) return null;
        bwdHalf.add(dst);
        // No reverse — bwdHalf is already in meetingNode -> dst order

        // Combine: fwdHalf = [src, ..., meetingNode], bwdHalf = [meetingNode, ..., dst]
        // skip duplicate meetingNode at start of bwdHalf
        fwdHalf.addAll(bwdHalf.subList(1, bwdHalf.size()));
        return fwdHalf;
    }

    // -------------------------------------------------------------------------
    // Message sending
    // -------------------------------------------------------------------------

    private void sendPath(int vehicleNo, List<String> waypoints) {
        String waypointStr = String.join(",", waypoints);
        String msg = String.format("PATH|VEHICLE|%d|WAYPOINTS|%s", vehicleNo, waypointStr);
        try {
            outMessageQueue.put(new Message(msg));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Feasibility checks — called from handle() on commsLoop thread
    // -------------------------------------------------------------------------

    public boolean isUnreachable(String node) {
        return unreachable != null && unreachable.contains(node);
    }

    public double getForwardDist(String node) {
        return forwardDist.getOrDefault(node, Double.MAX_VALUE);
    }

    public double getReverseDist(String node) {
        return reverseDist.getOrDefault(node, Double.MAX_VALUE);
    }
}
