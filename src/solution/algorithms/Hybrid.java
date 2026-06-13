package solution.algorithms;

import sim.Message;
import solution.*;
import util.ConfigurationInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid responder — bidirectional Dijkstra outbound, incremental D* Lite return; the best
 * performer under heavy road damage. Uses split dispatch (outbound now, return computed fresh
 * on PEOPLE_TRANSFERRED) plus a robustness layer: a value-density rescue queue, a live
 * reachability gate that recycles vehicles with unreachable targets, duplicate-reroute guards,
 * and stuck-vehicle retry on ROAD|CLEAR. Pathfinding runs on pathExecutor.
 */
public class Hybrid extends DisasterResponder {

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

    // Road status. BLOCKED: drop edge, halt vehicles routing through it.
    // CLEAR: restore edge and retry stuck vehicles (a reopened road may unblock them).
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
            // Only act if we actually had this edge removed — at ROAD_DAMAGE=0 the sim emits
            // a CLEAR for every edge, most never removed.
            if (!pathFinder.restoreEdge(src, dst)) return;
            for (int vehicleNo : vehicleTracker.getStuckVehicles()) {
                String location = vehicleTracker.getLocation(vehicleNo);
                if (location == null) continue;
                String target = vehicleTracker.getTarget(vehicleNo);
                vehicleTracker.onHalted(vehicleNo, location); // STUCK -> HALTED
                if (target != null) {
                    pathExecutor.submit(() -> pathFinder.sendOutboundPath(vehicleNo, location, target));
                } else {
                    pathExecutor.submit(() -> pathFinder.sendReturnPath(vehicleNo, location, base));
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

    // A waypoint became invalid: drop the blocked edge and reroute from where the vehicle stopped.
    private void handleWaypointInvalid(String[] parts) {
        int vehicleNo = Integer.parseInt(parts[2]);
        String haltedAt = parts[4];  // last valid waypoint — vehicle stopped here
        String blockedTo = parts[6]; // blocked edge destination

        pathFinder.removeEdge(haltedAt, blockedTo);
        vehicleTracker.onHalted(vehicleNo, haltedAt);

        VehicleStateTracker.VehicleState state = vehicleTracker.getState(vehicleNo);
        String target = vehicleTracker.getTarget(vehicleNo);

        if (state == VehicleStateTracker.VehicleState.RETURNING) {
            pathExecutor.submit(() -> pathFinder.sendReturnPath(vehicleNo, haltedAt, base));
        } else if (target != null) {
            pathExecutor.submit(() -> pathFinder.sendOutboundPath(vehicleNo, haltedAt, target));
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
                // Read state BEFORE updating: if already HALTED, WAYPOINT_INVALID has already
                // queued a reroute — don't send a duplicate PATH.
                VehicleStateTracker.VehicleState prevSt = vehicleTracker.getState(vehicleNo);
                vehicleTracker.onHalted(vehicleNo, location);

                boolean returnAlreadySent = vehicleTracker.consumeReturnPathSent(vehicleNo);
                String target = vehicleTracker.getTarget(vehicleNo);
                boolean atTarget = target != null && location.equals(target);
                // Skip reroute when: at base, return already sent, a reroute is already pending
                // (prev HALTED/STUCK), or sitting on the target awaiting PEOPLE_TRANSFERRED.
                if (!location.equals(base)
                        && !returnAlreadySent
                        && !atTarget
                        && prevSt != VehicleStateTracker.VehicleState.HALTED
                        && prevSt != VehicleStateTracker.VehicleState.STUCK) {
                    VehicleStateTracker.VehicleState st = vehicleTracker.getState(vehicleNo);

                    if (st == VehicleStateTracker.VehicleState.RETURNING) {
                        pathExecutor.submit(() -> pathFinder.sendReturnPath(vehicleNo, location, base));
                    } else if (target != null) {
                        pathExecutor.submit(() -> pathFinder.sendOutboundPath(vehicleNo, location, target));
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

    // People loaded: mark returning and send a fresh return path (graph may have changed).
    private void handlePeopleTransferred(String[] parts) {
        String location = parts[2]; // PEOPLE_TRANSFERRED|LOCATION|x|VEHICLE|n|PEOPLE|n
        int vehicleNo = Integer.parseInt(parts[4]);
        vehicleTracker.onPeopleTransferred(vehicleNo);
        pathExecutor.submit(() -> pathFinder.sendReturnPath(vehicleNo, location, base));
    }

    // Dispatch OUTBOUND only; the return leg is sent on PEOPLE_TRANSFERRED.
    private void dispatch(int vehicleNo, String target) {
        // Mark in-motion synchronously so a concurrent rescue can't grab this same vehicle.
        vehicleTracker.onDispatched(vehicleNo, target, List.of(base, target));

        pathExecutor.submit(() -> {
            // Live reachability gate: unreachable-now == unreachable-forever, so recycle
            // rather than send the vehicle into a trap.
            if (!pathFinder.isReachableNow(target)) {
                System.out.println("Target " + target + " unreachable on live graph — recycling vehicle " + vehicleNo);
                recycleVehicle(vehicleNo);
                return;
            }

            List<String> outbound = pathFinder.getPrecomputedOutbound(base, target);
            if (outbound != null) {
                pathFinder.sendPrecomputedPath(vehicleNo, outbound);
            } else {
                pathFinder.sendOutboundPath(vehicleNo, base, target);
            }
        });
    }

    // Free a vehicle stuck with a dead target and immediately pull the next queued rescue.
    private void recycleVehicle(int vehicleNo) {
        vehicleTracker.onReturned(vehicleNo); // DISPATCHED -> IDLE, clears target
        RescueQueue.RescueMission next = rescueQueue.pollNext();
        if (next != null) dispatch(vehicleNo, next.location);
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
