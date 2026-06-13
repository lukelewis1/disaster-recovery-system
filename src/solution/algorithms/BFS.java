package solution.algorithms;

import sim.Message;
import solution.*;
import util.ConfigurationInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Responder that uses BFS for all pathfinding — a baseline for the Dijkstra variants. No
 * precomputation: every dispatch and reroute runs a live fewest-hop BFS on the current graph,
 * cheap per search but blind to edge weights. Split dispatch (outbound now, return on
 * PEOPLE_TRANSFERRED). Keeps the shared robustness layer (rescue queue, duplicate-reroute
 * guards, stuck-vehicle retry) but not the reachability gate. Pathfinding runs on pathExecutor.
 */
public class BFS extends DisasterResponder {

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

        System.out.println("BFS ready. Base: " + base);
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


    // Dispatch to an idle vehicle, else queue.
    private void handleRescue(String[] parts) {
        String location = parts[2];
        int numPeople = Integer.parseInt(parts[4]);

        int vehicleNo = vehicleTracker.getIdleVehicle();
        if (vehicleNo == -1) {
            rescueQueue.enqueue(location, numPeople, 0);
            return;
        }

        dispatch(vehicleNo, location);
    }

    // Road status. BLOCKED: drop edge, halt affected vehicles.
    // CLEAR: restore edge and retry stuck vehicles via BFS.
    private void handleRoad(String[] parts) {
        String src = parts[2];
        String dst = parts[4];
        String status = parts[6];

        if (status.equals("BLOCKED")) {
            pathFinder.removeEdge(src, dst);
            for (int vehicleNo : vehicleTracker.getVehiclesRoutingThrough(dst)) {
                sendHalt(vehicleNo);
            }
        } else if (status.equals("CLEAR")) {
            if (!pathFinder.restoreEdge(src, dst)) return;
            for (int vehicleNo : vehicleTracker.getStuckVehicles()) {
                String location = vehicleTracker.getLocation(vehicleNo);
                if (location == null) continue;
                String target = vehicleTracker.getTarget(vehicleNo);
                vehicleTracker.onHalted(vehicleNo, location);
                if (target != null) {
                    pathExecutor.submit(() -> pathFinder.sendOutboundPathBFS(vehicleNo, location, target));
                } else {
                    pathExecutor.submit(() -> pathFinder.sendReturnPathBFS(vehicleNo, location, base));
                }
            }
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

    // A waypoint became invalid: drop the blocked edge and reroute via BFS.
    private void handleWaypointInvalid(String[] parts) {
        int vehicleNo = Integer.parseInt(parts[2]);
        String haltedAt = parts[4];
        String blockedTo = parts[6];

        pathFinder.removeEdge(haltedAt, blockedTo);
        vehicleTracker.onHalted(vehicleNo, haltedAt);

        VehicleStateTracker.VehicleState state = vehicleTracker.getState(vehicleNo);
        String target = vehicleTracker.getTarget(vehicleNo);

        if (state == VehicleStateTracker.VehicleState.RETURNING) {
            pathExecutor.submit(() -> pathFinder.sendReturnPathBFS(vehicleNo, haltedAt, base));
        } else if (target != null) {
            pathExecutor.submit(() -> pathFinder.sendOutboundPathBFS(vehicleNo, haltedAt, target));
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
                VehicleStateTracker.VehicleState prevSt = vehicleTracker.getState(vehicleNo);
                vehicleTracker.onHalted(vehicleNo, location);

                boolean returnAlreadySent = vehicleTracker.consumeReturnPathSent(vehicleNo);
                String target = vehicleTracker.getTarget(vehicleNo);
                boolean atTarget = target != null && location.equals(target);
                // Skip reroute when at base, return already sent, reroute pending, or at target.
                if (!location.equals(base)
                        && !returnAlreadySent
                        && !atTarget
                        && prevSt != VehicleStateTracker.VehicleState.HALTED
                        && prevSt != VehicleStateTracker.VehicleState.STUCK) {
                    VehicleStateTracker.VehicleState st = vehicleTracker.getState(vehicleNo);

                    if (st == VehicleStateTracker.VehicleState.RETURNING) {
                        pathExecutor.submit(() -> pathFinder.sendReturnPathBFS(vehicleNo, location, base));
                    } else if (target != null) {
                        pathExecutor.submit(() -> pathFinder.sendOutboundPathBFS(vehicleNo, location, target));
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

    // People loaded: mark returning and send a fresh BFS return path.
    private void handlePeopleTransferred(String[] parts) {
        String location = parts[2];
        int vehicleNo = Integer.parseInt(parts[4]);
        vehicleTracker.onPeopleTransferred(vehicleNo);
        pathExecutor.submit(() -> pathFinder.sendReturnPathBFS(vehicleNo, location, base));
    }

    // Dispatch OUTBOUND only via live BFS; the return leg is sent on PEOPLE_TRANSFERRED.
    private void dispatch(int vehicleNo, String target) {
        vehicleTracker.onDispatched(vehicleNo, target, List.of(base, target));
        pathExecutor.submit(() -> pathFinder.sendOutboundPathBFS(vehicleNo, base, target));
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
