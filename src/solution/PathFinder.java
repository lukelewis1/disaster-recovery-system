package solution;

import sim.Message;
import solution.pathfinding.Pathfinder;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PathFinder {

    private final Graph graph;
    private final BlockingQueue<Message> outMessageQueue;
    private final VehicleStateTracker tracker;
    private String baseNode;   // for the "route home" fallback in outbound senders

    // Pluggable one-shot search strategies — stateless, operate on the live graph.
    private final Pathfinder dijkstraAlgo = new solution.pathfinding.Dijkstra();
    private final Pathfinder bidirAlgo = new solution.pathfinding.BidirectionalDijkstra();
    private final Pathfinder bfsAlgo = new solution.pathfinding.Bfs();

    // Precomputed by precompute().
    private Map<String, Double> forwardDist;  // base -> node distances
    private Map<String, String> forwardPrev;  // base -> node predecessors (outbound)
    private Map<String, Double> reverseDist;  // node -> base distances
    private Map<String, String> reversePrev;  // node -> base predecessors (return)
    private Set<String> unreachable;          // nodes with no path from base
    private DStarLite dstar;                  // incremental shortest path to base

    // Weights of edges we removed, so ROAD|CLEAR can restore them.
    private final Map<String, Double> removedEdges = new ConcurrentHashMap<>();

    // Single daemon thread for D* Lite updates — keeps them off the event thread, serialized.
    private final ExecutorService dstarExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dstar-updater");
        t.setDaemon(true);
        return t;
    });

    public PathFinder(Graph graph, BlockingQueue<Message> outMessageQueue) {
        this(graph, outMessageQueue, null);
    }

    public PathFinder(Graph graph, BlockingQueue<Message> outMessageQueue, VehicleStateTracker tracker) {
        this.graph = graph;
        this.outMessageQueue = outMessageQueue;
        this.tracker = tracker;
    }

    // Run forward and reverse Dijkstra from base in parallel, then seed D* Lite. Call once at setup().
    public void precompute(String base) throws InterruptedException {
        this.baseNode = base;
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

        // Anything forward Dijkstra never reached is unreachable from base.
        unreachable = new HashSet<>();
        for (String node : graph.getNodes()) {
            if (!forwardDist.containsKey(node)) {
                unreachable.add(node);
            }
        }

        dstar = new DStarLite(graph, base);
        dstar.initialize();
    }


    // Single-source Dijkstra from src. reverse=false: src -> all (forward edges).
    // reverse=true: all -> src (reverse edges), giving return distances/predecessors.
    private void dijkstra(String src, boolean reverse,
                          Map<String, Double> dist,
                          Map<String, String> prev) {

        record Entry(double cost, String node) {}
        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        dist.put(src, 0.0);
        pq.offer(new Entry(0.0, src));

        while (!pq.isEmpty()) {
            Entry curr = pq.poll();

            if (curr.cost() > dist.getOrDefault(curr.node(), Double.MAX_VALUE)) continue; // stale

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


    // Walk predecessor links dst -> src and reverse into [src, ..., dst]. null if no path.
    private List<String> reconstructPath(Map<String, String> prev, String src, String dst) {
        List<String> path = new ArrayList<>();
        String current = dst;

        while (current != null && !current.equals(src)) {
            path.add(current);
            current = prev.get(current);
        }

        if (current == null) return null;

        path.add(src);
        Collections.reverse(path);
        return path;
    }


    // Remove a forward edge and patch D* Lite. Use instead of graph.removeEdge() directly.
    public void removeEdge(String src, String dst) {
        Double w = graph.getWeight(src, dst);
        if (w != null) removedEdges.put(src + "|" + dst, w); // remember weight for CLEAR
        graph.removeEdge(src, dst);
        if (dstar != null) dstarExecutor.submit(() -> dstar.edgeRemoved(src));
    }

    // Restore an edge we removed (ROAD|CLEAR). Returns false if we never removed it.
    public boolean restoreEdge(String src, String dst) {
        Double w = removedEdges.remove(src + "|" + dst);
        if (w == null) return false;
        graph.addEdge(src, dst, w);
        if (dstar != null) dstarExecutor.submit(() -> dstar.edgeRemoved(src)); // recomputes rhs[src]
        return true;
    }

    // Remove a node and patch D* Lite. Predecessors are captured before removal wipes them.
    public void removeNode(String node) {
        List<String> preds = new ArrayList<>(graph.getReverseNeighbours(node).keySet());
        graph.removeNode(node);
        if (dstar != null) {
            dstarExecutor.submit(() -> dstar.nodeRemoved(node, preds));
        }
    }

    // Dispatch base -> dst -> base from precomputed maps. Only valid on the unmodified graph.
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

        Collections.reverse(returning); // stored base->dst on reverse graph; flip to dst->base

        List<String> fullPath = new ArrayList<>(outbound);
        fullPath.addAll(returning.subList(1, returning.size())); // skip duplicate dst

        sendPath(vehicleNo, fullPath);
    }


    // True if every consecutive edge of path still exists — validates precomputed paths.
    public boolean isPathValid(List<String> path) {
        for (int i = 0; i < path.size() - 1; i++) {
            if (graph.getWeight(path.get(i), path.get(i + 1)) == null) return false;
        }
        return true;
    }

    // Precomputed full trip base->dst->base, or null if either leg is missing/stale.
    public List<String> getPrecomputedPath(String base, String dst) {
        List<String> outbound = reconstructPath(forwardPrev, base, dst);
        if (outbound == null) return null;
        List<String> returning = reconstructPath(reversePrev, base, dst);
        if (returning == null) return null;
        Collections.reverse(returning);
        if (!isPathValid(outbound) || !isPathValid(returning)) return null;
        List<String> full = new ArrayList<>(outbound);
        full.addAll(returning.subList(1, returning.size()));
        return full;
    }

    public void sendPrecomputedPath(int vehicleNo, List<String> path) {
        sendPath(vehicleNo, path);
    }

    // Precomputed OUTBOUND-only base->dst (split dispatch). Verifies a return path also exists.
    public List<String> getPrecomputedOutbound(String base, String dst) {
        List<String> outbound = reconstructPath(forwardPrev, base, dst);
        if (outbound == null || !isPathValid(outbound)) return null;
        if (reconstructPath(reversePrev, base, dst) == null) return null;
        return outbound;
    }

    // Outbound src->dst via bidirectional Dijkstra (Dijkstra fallback). Routes home if
    // dst unreachable; marks STUCK if home is unreachable too.
    public void sendOutboundPath(int vehicleNo, String src, String dst) {
        List<String> path = bidirectionalDijkstra(src, dst);
        if (path == null) path = dijkstraPath(src, dst);
        if (path == null) {
            List<String> home = bidirectionalDijkstra(src, getBase());
            if (home == null) home = dijkstraPath(src, getBase());
            if (home != null) {
                System.err.println("Vehicle " + vehicleNo + " — no path " + src + "→" + dst + "; routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, home);
            } else {
                System.err.println("Vehicle " + vehicleNo + " — no outbound path from " + src + " to " + dst);
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onRerouted(vehicleNo); // HALTED -> DISPATCHED
    }

    // Outbound src->dst via a fresh target-rooted D* Lite search. Same route-home fallback.
    public void sendOutboundPathDStar(int vehicleNo, String src, String dst) {
        List<String> path = dStarOutbound(src, dst);
        if (path == null) {
            List<String> home = dStarOutbound(src, getBase());
            if (home != null) {
                System.err.println("Vehicle " + vehicleNo + " — D*: no path " + src + "→" + dst + "; routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, home);
            } else {
                System.err.println("Vehicle " + vehicleNo + " — D*: no outbound path from " + src + " to " + dst);
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onRerouted(vehicleNo);
    }

    // Build a transient goal=dst D* Lite and extract src->dst on the current graph.
    private List<String> dStarOutbound(String src, String dst) {
        if (src.equals(dst)) return List.of(src);
        DStarLite d = new DStarLite(graph, dst);
        d.initialize();
        return d.getPath(src);
    }

    // Return src->base. Tries incremental D* Lite, then precomputed reverse, then live searches.
    public void sendReturnPath(int vehicleNo, String src, String base) {
        List<String> path = null;
        if (dstar != null) {
            path = dstar.getPath(src);
            if (path != null && !isPathValid(path)) path = null; // stale
        }
        if (path == null && reversePrev != null) {
            List<String> precomp = reconstructPath(reversePrev, base, src);
            if (precomp != null) {
                Collections.reverse(precomp);
                if (isPathValid(precomp)) path = precomp;
            }
        }
        if (path == null) path = bidirectionalDijkstra(src, base);
        if (path == null) path = dijkstraPath(src, base);
        if (path == null) {
            System.err.println("Vehicle " + vehicleNo + " — no return path from " + src + " to base");
            if (tracker != null) tracker.onStuck(vehicleNo);
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onReroutedToBase(vehicleNo); // keep RETURNING for later halts
    }

    // Return src->base via pure bidirectional Dijkstra (Dijkstra fallback), no D* Lite.
    public void sendReturnPathBidir(int vehicleNo, String src, String base) {
        List<String> path = bidirectionalDijkstra(src, base);
        if (path == null) path = dijkstraPath(src, base);
        if (path == null) {
            System.err.println("Vehicle " + vehicleNo + " — bidir: no return path from " + src + " to base");
            if (tracker != null) tracker.onStuck(vehicleNo);
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onReroutedToBase(vehicleNo);
    }

    // Return src->base via BFS.
    public void sendReturnPathBFS(int vehicleNo, String src, String base) {
        List<String> path = bfsPath(src, base);
        if (path == null) {
            System.err.println("Vehicle " + vehicleNo + " — BFS: no return path from " + src + " to base");
            if (tracker != null) tracker.onStuck(vehicleNo);
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onReroutedToBase(vehicleNo);
    }

    // Outbound src->dst via BFS, with the same route-home fallback as sendOutboundPath.
    public void sendOutboundPathBFS(int vehicleNo, String src, String dst) {
        List<String> path = bfsPath(src, dst);
        if (path == null) {
            List<String> home = bfsPath(src, getBase());
            if (home != null) {
                System.err.println("Vehicle " + vehicleNo + " — BFS: no path " + src + "→" + dst + "; routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, home);
            } else {
                System.err.println("Vehicle " + vehicleNo + " — BFS: no outbound path from " + src + " to " + dst);
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }
        sendPath(vehicleNo, path);
        if (tracker != null) tracker.onRerouted(vehicleNo);
    }

    // Full reroute: live bidirectional Dijkstra outbound + best-available return, computed
    // concurrently, then joined. Routes home / marks stuck if the outbound leg fails.
    public void sendReroutePath(int vehicleNo, String src, String dst, String base) {
        @SuppressWarnings("unchecked")
        List<String>[] results = new List[2];

        Thread t1 = new Thread(() -> {
            results[0] = bidirectionalDijkstra(src, dst);
            if (results[0] == null) {
                List<String> fallback = dijkstraPath(src, dst); // diagnose bidir miss
                if (fallback != null) {
                    System.err.println("WARN: bidir failed but Dijkstra found path " + src + "→" + dst + " (" + fallback.size() + " hops)");
                    results[0] = fallback;
                }
            }
        });
        // Return leg: D* Lite (already current with removals) -> precomputed reverse -> live bidir.
        Thread t2 = new Thread(() -> {
            if (dstar != null) {
                results[1] = dstar.getPath(dst);
                if (results[1] != null && !isPathValid(results[1])) results[1] = null;
            }
            if (results[1] == null && reversePrev != null) {
                List<String> precomputedReturn = reconstructPath(reversePrev, base, dst);
                if (precomputedReturn != null) {
                    Collections.reverse(precomputedReturn);
                    if (isPathValid(precomputedReturn)) {
                        results[1] = precomputedReturn;
                    }
                }
            }
            if (results[1] == null) {
                results[1] = bidirectionalDijkstra(dst, base);
            }
        });

        t1.start(); t2.start();
        try { t1.join(); t2.join(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return;
        }

        if (results[0] == null) {
            List<String> homeOnly = bidirectionalDijkstra(src, base);
            if (homeOnly == null) homeOnly = dijkstraPath(src, base);
            if (homeOnly != null) {
                System.err.println("No reroute path from " + src + " to " + dst + " — routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, homeOnly);
            } else {
                System.err.println("Vehicle " + vehicleNo + " stuck at " + src + " — no path to " + dst + " or base");
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }
        if (results[1] == null) { System.err.println("No return path from " + dst + " to " + base); return; }

        List<String> fullPath = new ArrayList<>(results[0]);
        fullPath.addAll(results[1].subList(1, results[1].size()));
        sendPath(vehicleNo, fullPath);
    }

    // Full reroute using plain single-source Dijkstra for both legs (concurrent).
    public void sendReroutePathDijkstra(int vehicleNo, String src, String dst, String base) {
        @SuppressWarnings("unchecked")
        List<String>[] results = new List[2];

        Thread t1 = new Thread(() -> results[0] = dijkstraPath(src, dst));
        Thread t2 = new Thread(() -> results[1] = dijkstraPath(dst, base));

        t1.start(); t2.start();
        try { t1.join(); t2.join(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return;
        }

        if (results[0] == null) {
            List<String> homeOnly = dijkstraPath(src, base);
            if (homeOnly != null) {
                System.err.println("No reroute path from " + src + " to " + dst + " — routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, homeOnly);
            } else {
                System.err.println("Vehicle " + vehicleNo + " stuck at " + src + " — no path to " + dst + " or base");
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }
        if (results[1] == null) { System.err.println("No return path from " + dst + " to " + base); return; }

        List<String> fullPath = new ArrayList<>(results[0]);
        fullPath.addAll(results[1].subList(1, results[1].size()));
        sendPath(vehicleNo, fullPath);
    }

    private List<String> dijkstraPath(String src, String dst) {
        return dijkstraAlgo.findPath(graph, src, dst);
    }

    private List<String> bidirectionalDijkstra(String src, String dst) {
        return bidirAlgo.findPath(graph, src, dst);
    }

    // Build the PATH message and enqueue it. Skips destroyed vehicles; records the route
    // so damage handlers know which vehicles to halt.
    private void sendPath(int vehicleNo, List<String> waypoints) {
        if (tracker != null) {
            VehicleStateTracker.VehicleState state = tracker.getState(vehicleNo);
            if (state == VehicleStateTracker.VehicleState.DESTROYED) return;
            tracker.updateRoute(vehicleNo, waypoints);
        }
        String waypointStr = String.join(",", waypoints);
        String msg = String.format("PATH|VEHICLE|%d|WAYPOINTS|%s", vehicleNo, waypointStr);
        try {
            outMessageQueue.put(new Message(msg));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // BFS src->dst, fewest hops, ignores weights. null if unreachable.
    public List<String> bfsPath(String src, String dst) {
        return bfsAlgo.findPath(graph, src, dst);
    }

    // Full reroute using BFS for both legs.
    public void sendReroutePathBFS(int vehicleNo, String src, String dst, String base) {
        List<String> outbound = bfsPath(src, dst);
        if (outbound == null) {
            List<String> homeOnly = bfsPath(src, base);
            if (homeOnly != null) {
                System.err.println("BFS: no path from " + src + " to " + dst + " — routing home");
                if (tracker != null) tracker.onReroutedToBase(vehicleNo);
                sendPath(vehicleNo, homeOnly);
            } else {
                System.err.println("Vehicle " + vehicleNo + " stuck at " + src + " — BFS: no path to " + dst + " or base");
                if (tracker != null) tracker.onStuck(vehicleNo);
            }
            return;
        }

        List<String> returning = bfsPath(dst, base);
        if (returning == null) {
            System.err.println("BFS: no return path from " + dst + " to " + base);
            return;
        }

        List<String> fullPath = new ArrayList<>(outbound);
        fullPath.addAll(returning.subList(1, returning.size()));
        sendPath(vehicleNo, fullPath);
    }

    // Reachability from the PRECOMPUTED (pre-damage) maps: also requires the node still
    // exists and can return to base. Cheap gate at receipt time.
    public boolean isUnreachable(String node) {
        if (!graph.hasNode(node)) return true;
        if (unreachable != null && unreachable.contains(node)) return true;
        if (reverseDist != null && !reverseDist.containsKey(node)) return true; // can't get home
        return false;
    }

    // Live two-way reachability on the CURRENT damaged graph: base->target AND target->base
    // must both have a path right now. Damage is permanent+monotonic, so a false result is
    // safe to drop forever. Runs two point-to-point searches — keep off the event thread.
    public boolean isReachableNow(String target) {
        String b = getBase();
        if (b == null) return true;            // not precomputed yet — don't block
        if (!graph.hasNode(target)) return false;
        if (target.equals(b)) return true;
        if (bidirectionalDijkstra(b, target) == null) return false;
        if (bidirectionalDijkstra(target, b) == null) return false;
        return true;
    }

    private String getBase() { return baseNode; }

    public double getForwardDist(String node) {
        return forwardDist.getOrDefault(node, Double.MAX_VALUE);
    }

    public double getReverseDist(String node) {
        return reverseDist.getOrDefault(node, Double.MAX_VALUE);
    }
}
