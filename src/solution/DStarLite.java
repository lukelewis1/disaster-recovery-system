package solution;

import java.util.*;

/**
 * Incremental D* Lite — shortest paths from any node to a single fixed goal. Maintains g (best
 * cost to goal) and rhs (one-step lookahead) per node; inconsistent nodes are reprocessed via
 * a lazy-deletion priority queue. When an edge or node is removed it repairs only the affected
 * region instead of recomputing, which suits continuously arriving road damage with a fixed
 * base. getPath() walks greedily to goal. All public methods are synchronized.
 */
public class DStarLite {

    private static final double INF = Double.MAX_VALUE / 2;

    private final Graph graph;
    private final String goal;

    private final Map<String, Double> g = new HashMap<>();
    private final Map<String, Double> rhs = new HashMap<>();

    // Lazy-deletion bookkeeping: a queue entry is live only if its ver matches version[node].
    private final Map<String, Long> version = new HashMap<>();
    private long globalVersion = 0;

    private record QEntry(double k1, double k2, String node, long ver)
        implements Comparable<QEntry> {
        public int compareTo(QEntry o) {
            int c = Double.compare(k1, o.k1);
            return c != 0 ? c : Double.compare(k2, o.k2);
        }
    }

    private final PriorityQueue<QEntry> pq = new PriorityQueue<>();

    // Call initialize() before getPath() or any update method.
    public DStarLite(Graph graph, String goal) {
        this.graph = graph;
        this.goal  = goal;
    }

    // Seed g/rhs for all nodes, then run the initial backward search from goal.
    public synchronized void initialize() {
        for (String node : graph.getNodes()) {
            g.put(node, INF);
            rhs.put(node, INF);
            version.put(node, 0L);
        }
        rhs.put(goal, 0.0);
        enqueue(goal);
        computeShortestPath();
    }

    // Forward edge (u -> v) was removed: u may have lost its best route, recompute and repair.
    public synchronized void edgeRemoved(String u) {
        updateVertex(u);
        computeShortestPath();
    }

    // Node removed: mark it unreachable and recompute every predecessor that routed through it.
    public synchronized void nodeRemoved(String node, Iterable<String> predecessors) {
        g.put(node, INF);
        rhs.put(node, INF);
        for (String pred : predecessors) {
            updateVertex(pred);
        }
        computeShortestPath();
    }

    // Greedy walk src -> goal following min-cost neighbours. null if unreachable.
    public synchronized List<String> getPath(String src) {
        if (g.getOrDefault(src, INF) >= INF) return null;

        List<String> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String curr = src;

        while (!curr.equals(goal)) {
            if (!visited.add(curr)) return null; // cycle guard
            path.add(curr);
            String next = null;
            double best = INF;
            for (Map.Entry<String, Double> e : graph.getNeighbours(curr).entrySet()) {
                double cost = e.getValue() + g.getOrDefault(e.getKey(), INF);
                if (cost < best) { best = cost; next = e.getKey(); }
            }
            if (next == null) return null;
            curr = next;
        }
        path.add(goal);
        return path;
    }


    private double g(String s)   { return g.getOrDefault(s, INF); }
    private double rhs(String s) { return rhs.getOrDefault(s, INF); }

    private void enqueue(String s) {
        long ver = ++globalVersion;
        version.put(s, ver);
        double m = Math.min(g(s), rhs(s));
        pq.offer(new QEntry(m, m, s, ver));
    }

    // Recompute rhs[u] from current graph + g values; requeue (with fresh version) if inconsistent.
    private void updateVertex(String u) {
        if (!u.equals(goal)) {
            double minRhs = INF;
            for (Map.Entry<String, Double> e : graph.getNeighbours(u).entrySet()) {
                double c = e.getValue() + g(e.getKey());
                if (c < minRhs) minRhs = c;
            }
            rhs.put(u, minRhs);
        }
        long ver = ++globalVersion;       // invalidates any older queue entry for u
        version.put(u, ver);
        if (g(u) != rhs(u)) {
            double m = Math.min(g(u), rhs(u));
            pq.offer(new QEntry(m, m, u, ver));
        }
        // If consistent: no new entry added and the old one is now stale — u leaves the queue.
    }

    // Process inconsistent nodes until every g equals its rhs.
    private void computeShortestPath() {
        while (!pq.isEmpty()) {
            QEntry top = pq.peek();

            if (top.ver() != version.getOrDefault(top.node(), 0L)) { // stale entry
                pq.poll();
                continue;
            }

            String u = pq.poll().node();
            double gu = g(u), rhu = rhs(u);

            if (gu > rhu) {
                // Overconsistent: g too high, lower it to rhs and push the gain to predecessors.
                g.put(u, rhu);
                for (String pred : graph.getReverseNeighbours(u).keySet()) {
                    updateVertex(pred);
                }
            } else {
                // Underconsistent: g too low, raise to INF then recompute u and its predecessors.
                g.put(u, INF);
                updateVertex(u);
                for (String pred : graph.getReverseNeighbours(u).keySet()) {
                    updateVertex(pred);
                }
            }
        }
    }
}
