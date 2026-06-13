package solution.algorithms;

import sim.Message;
import solution.*;
import util.ConfigurationInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Responder using plain single-source Dijkstra. Precomputes forward and reverse Dijkstra from
 * base at startup. Unlike Hybrid/Bidir it is NOT split dispatch: it sends the full trip
 * base→target→base in one PATH (precomputed when valid, else a live reroute), and reroutes
 * both legs with standard Dijkstra. Keeps the rescue queue and unreachable-location filter,
 * but not the live reachability gate. Pathfinding runs on pathExecutor.
 */
public class Dijkstra extends DisasterResponder {

    private final ExecutorService pathExecutor = Executors.newFixedThreadPool(
        ConfigurationInfo.NUMBER_OF_VEHICLES
    );

    private Graph graph;
    private String base;
    private PathFinder pathFinder;
    private VehicleStateTracker vehicleTracker;
    private RescueQueue rescueQueue;
    private double vehicleSpeed;


    @Override
    protected void setup() {
        base = ConfigurationInfo.getOrigin(configFile);
        String mapFile = ConfigurationInfo.getMapFile(configFile);
        vehicleSpeed = Double.parseDouble(
            ConfigurationInfo.loadConfig(configFile).getProperty("VEHICLE_SPEED", "1.0")
        );

        try {
            graph = GraphBuilder.buildFromGraphML(mapFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load graph: " + e.getMessage(), e);
        }

        vehicleTracker = new VehicleStateTracker(base, ConfigurationInfo.NUMBER_OF_VEHICLES);
        rescueQueue = new RescueQueue(configFile);

        pathFinder = new PathFinder(graph, outMessageQueue, vehicleTracker);
        try {
            pathFinder.precompute(base);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Precomputation interrupted", e);
        }

        System.out.println("Precomputation complete. Base: " + base);
    }


    @Override
    protected void handle(Message m) {
        String[] parts = m.text.split("\\|");

        switch (parts[0]) {
            case "RESCUE"             -> handleRescue(parts);
            case "ROAD"               -> handleRoad(parts);
            case "LOCATION"           -> handleLocation(parts);
            case "WAYPOINT_INVALID"   -> handleWaypointInvalid(parts);
            case "VEHICLE"            -> handleVehicle(parts);
            case "PEOPLE_TRANSFERRED" -> handlePeopleTransferred(parts);
            case "PATH_INVALID"       -> {}
            default -> System.err.println("Unknown message: " + m.text);
        }
    }


    // Dispatch to an idle vehicle, else queue. Unreachable locations are discarded up front.
    private void handleRescue(String[] parts) {
        if (pathFinder == null) return;

        String location = parts[2];
        int numPeople = Integer.parseInt(parts[4]);

        if (pathFinder.isUnreachable(location)) {
            System.out.println("Discarding rescue at unreachable location: " + location);
            return;
        }

        double travelTime = pathFinder.getForwardDist(location) / vehicleSpeed;

        int vehicleNo = vehicleTracker.getIdleVehicle();
        if (vehicleNo == -1) {
            rescueQueue.enqueue(location, numPeople, travelTime);
            return;
        }

        dispatch(vehicleNo, location);
    }

    // Road BLOCKED: drop edge and halt vehicles routing through it. (CLEAR is ignored here.)
    private void handleRoad(String[] parts) {
        String src = parts[2];
        String dst = parts[4];
        String status = parts[6];

        if (!status.equals("BLOCKED")) return;

        pathFinder.removeEdge(src, dst);

        for (int vehicleNo : vehicleTracker.getVehiclesRoutingThrough(dst)) {
            sendHalt(vehicleNo);
        }
    }

    // Location collapse: remove node, cancel pending rescues there, halt vehicles through it.
    private void handleLocation(String[] parts) {
        String location = parts[1];

        pathFinder.removeNode(location);
        rescueQueue.removeLocation(location);

        for (int vehicleNo : vehicleTracker.getVehiclesRoutingThrough(location)) {
            sendHalt(vehicleNo);
        }
    }

    // A waypoint became invalid: drop the blocked edge and reroute via Dijkstra.
    private void handleWaypointInvalid(String[] parts) {
        int vehicleNo = Integer.parseInt(parts[2]);
        String haltedAt = parts[4];
        String blockedTo = parts[6];

        pathFinder.removeEdge(haltedAt, blockedTo);
        vehicleTracker.onHalted(vehicleNo, haltedAt);

        VehicleStateTracker.VehicleState state = vehicleTracker.getState(vehicleNo);
        String target = vehicleTracker.getTarget(vehicleNo);

        if (state == VehicleStateTracker.VehicleState.RETURNING) {
            pathExecutor.submit(() -> pathFinder.sendReroutePathDijkstra(vehicleNo, haltedAt, base, base));
        } else if (target != null) {
            pathExecutor.submit(() -> pathFinder.sendReroutePathDijkstra(vehicleNo, haltedAt, target, base));
        }
    }

    // Vehicle events: ARRIVED, HALTED, RETURNED, DESTROYED.
    private void handleVehicle(String[] parts) {
        int vehicleNo = Integer.parseInt(parts[1]);
        String event = parts[2];

        switch (event) {
            case "ARRIVED" -> vehicleTracker.onArrived(vehicleNo, parts[4]);
            case "HALTED" -> {
                String location = parts[4];
                vehicleTracker.onHalted(vehicleNo, location);

                // Reroute only for damage-driven halts (not at base).
                if (!location.equals(base)) {
                    VehicleStateTracker.VehicleState st = vehicleTracker.getState(vehicleNo);
                    String target = vehicleTracker.getTarget(vehicleNo);

                    if (st == VehicleStateTracker.VehicleState.RETURNING) {
                        pathExecutor.submit(() -> pathFinder.sendReroutePathDijkstra(vehicleNo, location, base, base));
                    } else if (target != null) {
                        pathExecutor.submit(() -> pathFinder.sendReroutePathDijkstra(vehicleNo, location, target, base));
                    }
                }
            }
            case "RETURNED" -> {
                vehicleTracker.onReturned(vehicleNo);
                RescueQueue.RescueMission next = rescueQueue.pollNext();
                if (next != null) dispatch(vehicleNo, next.location);
            }
            case "DESTROYED" -> {
                vehicleTracker.onDestroyed(vehicleNo);
                System.out.println("Vehicle " + vehicleNo + " destroyed");
            }
            default -> System.err.println("Unknown vehicle event: " + event);
        }
    }

    // People loaded: mark returning. Return leg was already part of the full dispatch path.
    private void handlePeopleTransferred(String[] parts) {
        int vehicleNo = Integer.parseInt(parts[4]);
        vehicleTracker.onPeopleTransferred(vehicleNo);
    }

    // Dispatch the FULL trip base->target->base: precomputed if valid, else live Dijkstra.
    private void dispatch(int vehicleNo, String target) {
        vehicleTracker.onDispatched(vehicleNo, target, List.of(base, target, base));

        List<String> precomputed = pathFinder.getPrecomputedPath(base, target);
        if (precomputed != null) {
            executor.submit(() -> pathFinder.sendPrecomputedPath(vehicleNo, precomputed));
        } else {
            pathExecutor.submit(() -> pathFinder.sendReroutePathDijkstra(vehicleNo, base, target, base));
        }
    }

    private void sendHalt(int vehicleNo) {
        try {
            outMessageQueue.put(new Message("HALT|VEHICLE|" + vehicleNo));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    @Override
    public void shutdown() {
        super.shutdown();
        pathExecutor.shutdown();
        try {
            if (!pathExecutor.awaitTermination(5, TimeUnit.SECONDS))
                pathExecutor.shutdownNow();
        } catch (InterruptedException e) {
            pathExecutor.shutdownNow();
        }
    }
}
