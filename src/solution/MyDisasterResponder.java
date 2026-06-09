package solution;

import sim.Message;
import util.ConfigurationInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MyDisasterResponder extends DisasterResponder {

    // Single-thread executor for D* Lite
    private final ExecutorService dStarExecutor = Executors.newSingleThreadExecutor();

    // Thread pool for bidirectional Dijkstra rerouting queries
    private final ExecutorService pathExecutor = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 2)
    );

    private Graph graph;
    private String base;
    private PathFinder pathFinder;
    private VehicleStateTracker vehicleTracker;
    private RescueQueue rescueQueue;
    private double vehicleSpeed;
    private volatile boolean graphModified = false; // true once any road/node is removed

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @Override
    protected void setup() {
        base = ConfigurationInfo.getOrigin(configFile);
        String mapFile = ConfigurationInfo.getMapFile(configFile);
        vehicleSpeed = Double.parseDouble(
            ConfigurationInfo.loadConfig(configFile).getProperty("VEHICLE_SPEED", "1.0")
        );

        // Build graph from GraphML
        try {
            graph = GraphBuilder.buildFromGraphML(mapFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load graph: " + e.getMessage(), e);
        }

        // Create PathFinder and run forward + reverse Dijkstra in parallel
        pathFinder = new PathFinder(graph, outMessageQueue);
        try {
            pathFinder.precompute(base);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Precomputation interrupted", e);
        }

        // Initialise vehicle tracker and rescue queue
        vehicleTracker = new VehicleStateTracker(base, ConfigurationInfo.NUMBER_OF_VEHICLES);
        rescueQueue = new RescueQueue(configFile);

        System.out.println("Precomputation complete. Base: " + base);
    }

    // -------------------------------------------------------------------------
    // Message handling — must return in microseconds, no pathfinding here
    // -------------------------------------------------------------------------

    @Override
    protected void handle(Message m) {
        String[] parts = m.text.split("\\|");

        switch (parts[0]) {
            case "RESCUE"           -> handleRescue(parts);
            case "ROAD"             -> handleRoad(parts);
            case "LOCATION"         -> handleLocation(parts);
            case "WAYPOINT_INVALID" -> handleWaypointInvalid(parts);
            case "VEHICLE"          -> handleVehicle(parts);
            case "PEOPLE_TRANSFERRED" -> {} // no action needed
            case "PATH_INVALID"       -> {} // handle later
            default                 -> System.err.println("Unknown message: " + m.text);
        }
    }

    // -------------------------------------------------------------------------
    // RESCUE|LOCATION|x|PEOPLE|n
    // -------------------------------------------------------------------------

    private void handleRescue(String[] parts) {
        if (pathFinder == null) return; // setup failed, nothing we can do

        // parts: [RESCUE, LOCATION, x, PEOPLE, n]
        String location = parts[2];
        int numPeople = Integer.parseInt(parts[4]);

        // Discard if unreachable from base
        if (pathFinder.isUnreachable(location)) {
            System.out.println("Discarding rescue at unreachable location: " + location);
            return;
        }

        double travelTime = pathFinder.getForwardDist(location) / vehicleSpeed;

        int vehicleNo = vehicleTracker.getIdleVehicle();
        if (vehicleNo == -1) {
            // No vehicle available — queue for later
            rescueQueue.enqueue(location, numPeople, travelTime);
            return;
        }

        dispatch(vehicleNo, location);
    }

    // -------------------------------------------------------------------------
    // ROAD|FROM|x|TO|y|STATUS|BLOCKED (or CLEAR — only BLOCKED matters)
    // -------------------------------------------------------------------------

    private void handleRoad(String[] parts) {
        // parts: [ROAD, FROM, x, TO, y, STATUS, BLOCKED/CLEAR]
        String src = parts[2];
        String dst = parts[4];
        String status = parts[6];

        if (!status.equals("BLOCKED")) return;

        graph.removeEdge(src, dst);
        graphModified = true;

        // Halt any vehicle whose active route uses this edge
        for (int vehicleNo : vehicleTracker.getVehiclesRoutingThrough(dst)) {
            sendHalt(vehicleNo);
        }
    }

    // -------------------------------------------------------------------------
    // LOCATION|x|COLLAPSED
    // -------------------------------------------------------------------------

    private void handleLocation(String[] parts) {
        // parts: [LOCATION, x, COLLAPSED]
        String location = parts[1];

        graph.removeNode(location);
        graphModified = true;

        // Remove any pending rescue at this location
        rescueQueue.removeLocation(location);

        // Halt any vehicle whose route passes through this node
        for (int vehicleNo : vehicleTracker.getVehiclesRoutingThrough(location)) {
            sendHalt(vehicleNo);
        }
    }

    // -------------------------------------------------------------------------
    // WAYPOINT_INVALID|VEHICLE|n|FROM|x|TO|y|ROAD|BLOCKED/NON_EXISTENT
    // -------------------------------------------------------------------------

    private void handleWaypointInvalid(String[] parts) {
        // parts: [WAYPOINT_INVALID, VEHICLE, n, FROM, x, TO, y, ROAD, status]
        int vehicleNo = Integer.parseInt(parts[2]);
        // Vehicle is now halted at its last valid waypoint — reroute from current location
        // onHalted will be sent by the simulator next; reroute submitted when HALTED arrives
        // (handled in handleVehicle -> HALTED branch)
    }

    // -------------------------------------------------------------------------
    // VEHICLE|n|ARRIVED/HALTED/RETURNED/DESTROYED|...
    // -------------------------------------------------------------------------

    private void handleVehicle(String[] parts) {
        // parts[0]=VEHICLE, parts[1]=vehicleNo, parts[2]=event type
        int vehicleNo = Integer.parseInt(parts[1]);
        String event = parts[2];

        switch (event) {
            case "ARRIVED" -> {
                // VEHICLE|n|ARRIVED|LOCATION|x
                String location = parts[4];
                vehicleTracker.onArrived(vehicleNo, location);
            }
            case "HALTED" -> {
                String location = parts[4];
                VehicleStateTracker.VehicleState stateBefore = vehicleTracker.getState(vehicleNo);
                String target = vehicleTracker.getTarget(vehicleNo);
                vehicleTracker.onHalted(vehicleNo, location);

                // Only reroute if halted mid-mission, not when stopping at base after returning
                if (target != null && stateBefore != VehicleStateTracker.VehicleState.IDLE) {
                    pathExecutor.submit(() ->
                            pathFinder.sendReroutePath(vehicleNo, location, target, base)
                    );
                }
            }
            case "RETURNED" -> {
                // VEHICLE|n|RETURNED|RESCUED|n
                vehicleTracker.onReturned(vehicleNo);

                // Try to dispatch the next queued rescue
                RescueQueue.RescueMission next = rescueQueue.pollNext();
                if (next != null) {
                    dispatch(vehicleNo, next.location);
                }
            }
            case "DESTROYED" -> {
                // VEHICLE|n|DESTROYED|LOCATION|x|PEOPLE|n
                vehicleTracker.onDestroyed(vehicleNo);
                System.out.println("Vehicle " + vehicleNo + " destroyed");
            }
            default -> System.err.println("Unknown vehicle event: " + event);
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch helpers
    // -------------------------------------------------------------------------

    // Dispatch — uses precomputed paths if graph is clean, bidirectional Dijkstra if modified
    private void dispatch(int vehicleNo, String target) {
        // Mark as dispatched immediately so no other rescue grabs this vehicle
        vehicleTracker.onDispatched(vehicleNo, target, List.of(base, target, base));

        if (graphModified) {
            // Graph has changed — precomputed paths may route through blocked roads
            pathExecutor.submit(() ->
                pathFinder.sendReroutePath(vehicleNo, base, target, base)
            );
        } else {
            // Graph unchanged — precomputed predecessor maps are valid, O(path length) lookup
            executor.submit(() ->
                pathFinder.sendDispatchPath(vehicleNo, base, target)
            );
        }
    }

    // Sends a HALT message for a vehicle directly on the commsLoop thread
    private void sendHalt(int vehicleNo) {
        try {
            outMessageQueue.put(new Message("HALT|VEHICLE|" + vehicleNo));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    @Override
    public void shutdown() {
        super.shutdown();
        dStarExecutor.shutdown();
        pathExecutor.shutdown();
        try {
            if (!dStarExecutor.awaitTermination(5, TimeUnit.SECONDS))
                dStarExecutor.shutdownNow();
            if (!pathExecutor.awaitTermination(5, TimeUnit.SECONDS))
                pathExecutor.shutdownNow();
        } catch (InterruptedException e) {
            dStarExecutor.shutdownNow();
            pathExecutor.shutdownNow();
        }
    }
}
