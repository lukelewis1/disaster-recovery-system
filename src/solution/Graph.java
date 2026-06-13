package solution;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Graph {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> adjList = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> revIdx = new ConcurrentHashMap<>();

    public void addNode(String id) {
        adjList.putIfAbsent(id, new ConcurrentHashMap<>());
        revIdx.putIfAbsent(id, Collections.newSetFromMap(new ConcurrentHashMap<>()));
    }

    public void addEdge(String src, String dst, double weight) {
        addNode(src);
        addNode(dst);
        adjList.get(src).put(dst, weight);
        revIdx.get(dst).add(src);
    }

    public void removeEdge(String src, String dst) {
        ConcurrentHashMap<String, Double> neighbours = adjList.get(src);
        if (neighbours != null) {
            neighbours.remove(dst);
        }
        Set<String> incoming = revIdx.get(dst);
        if (incoming != null) {
            incoming.remove(src);
        }
    }

    public void removeNode(String id) {
        // Drop id as a destination: unlink it from every source that pointed at it.
        Set<String> sources = revIdx.remove(id);
        if (sources != null) {
            for (String src : sources) {
                ConcurrentHashMap<String, Double> neighbours = adjList.get(src);
                if (neighbours != null) {
                    neighbours.remove(id);
                }
            }
        }

        // Drop id as a source: remove it from the reverse index of each of its targets.
        ConcurrentHashMap<String, Double> destinations = adjList.remove(id);
        if (destinations != null) {
            for (String dst : destinations.keySet()) {
                Set<String> rev = revIdx.get(dst);
                if (rev != null) rev.remove(id);
            }
        }
    }

    public ConcurrentHashMap<String, Double> getNeighbours(String node) {
        return adjList.getOrDefault(node, new ConcurrentHashMap<>());
    }

    public boolean hasNode(String node) {
        return adjList.containsKey(node);
    }

    public Set<String> getNodes() {
        return adjList.keySet();
    }

    public Double getWeight(String src, String dst) {
        ConcurrentHashMap<String, Double> neighbours = adjList.get(src);
        if (neighbours == null) return null;
        return neighbours.get(dst);
    }

    // All nodes with an edge INTO `node`, mapped to edge weights — built from the
    // reverse index. Used by reverse Dijkstra and bidirectional Dijkstra's backward search.
    public Map<String, Double> getReverseNeighbours(String node) {
        Set<String> sources = revIdx.getOrDefault(node, Collections.emptySet());
        Map<String, Double> result = new HashMap<>();
        for (String src : sources) {
            Double w = getWeight(src, node);
            if (w != null) result.put(src, w);
        }
        return result;
    }

}
