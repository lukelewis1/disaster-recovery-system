package solution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VehicleStateTracker {
    private final ConcurrentHashMap<Integer, Vehicle> vehicles = new ConcurrentHashMap<>();


    public enum VehicleState {
        IDLE, // at base, available for dispatch
        DISPATCHED, // en route to rescue target
        RETURNING, // heading back to base after rescue
        HALTED, // stopped mid-route, awaiting reroute
        STUCK, // halted with no available path — waiting for road to clear
        DESTROYED // permanently lost
    }

    public static class Vehicle {
        VehicleState state;
        String location;
        String target;      // rescue target this vehicle is heading to
        List<String> route; // current active waypoints
        boolean returnPathSent; // true if return path was sent via PEOPLE_TRANSFERRED split dispatch

        Vehicle(String startLocation) {
            this.state = VehicleState.IDLE;
            this.location = startLocation;
            this.target = null;
            this.route = new ArrayList<>();
        }
    }

    public VehicleStateTracker(String base, int numVehicles) {
        for (int i = 0; i < numVehicles; i++) {
            vehicles.put(i, new Vehicle(base));
        }
    }


    // VEHICLE|n|ARRIVED|LOCATION|x — vehicle moved to next waypoint
    public void onArrived(int vehicleNo, String location) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) v.location = location;
    }

    // VEHICLE|n|HALTED|LOCATION|x — vehicle stopped, awaiting new PATH
    public void onHalted(int vehicleNo, String location) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.location = location;
            // Preserve RETURNING so successive halts on the return trip still reroute to base
            if (v.state != VehicleState.RETURNING) {
                v.state = VehicleState.HALTED;
            }
            v.route.clear();
            // target is kept — still needed to compute the reroute
        }
    }

    // VEHICLE|n|RETURNED|RESCUED|n — vehicle back at base
    public void onReturned(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.IDLE;
            v.target = null;
            v.route.clear();
        }
    }

    // PEOPLE_TRANSFERRED — vehicle picked up people, now returning to base
    public void onPeopleTransferred(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.RETURNING;
            v.target = null;
            v.returnPathSent = true; // next HALTED at this location should be skipped
        }
    }

    // Returns and clears the returnPathSent flag. True means the HALTED after PEOPLE_TRANSFERRED
    // should be ignored (return path was already submitted by handlePeopleTransferred).
    public boolean consumeReturnPathSent(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v == null) return false;
        boolean flag = v.returnPathSent;
        v.returnPathSent = false;
        return flag;
    }

    // VEHICLE|n|DESTROYED|LOCATION|x|PEOPLE|n
    public void onDestroyed(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.DESTROYED;
            v.target = null;
            v.route.clear();
        }
    }

    // Called after PATH is sent — stores actual waypoints so ROAD/LOCATION damage
    // can correctly identify which vehicles to halt.
    public void updateRoute(int vehicleNo, List<String> waypoints) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) v.route = new ArrayList<>(waypoints);
    }

    // Called when we send a PATH message — marks vehicle as in-motion
    public void onDispatched(int vehicleNo, String target, List<String> route) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.DISPATCHED;
            v.target = target;
            v.route = new ArrayList<>(route);
        }
    }


    // Called when target becomes unreachable mid-trip — vehicle re-routed directly to base.
    // Sets RETURNING so when it arrives we dispatch the next queued rescue.
    public void onReroutedToBase(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.RETURNING;
            v.target = null;
            v.route.clear();
        }
    }

    // Called after a rerouted outbound PATH is sent — transitions HALTED → DISPATCHED
    // so future VEHICLE|HALTED events (from new road blocks) will trigger another reroute.
    public void onRerouted(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) v.state = VehicleState.DISPATCHED;
    }

    // Called when no path exists to target OR base — vehicle is stuck waiting for road to clear.
    public void onStuck(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.STUCK;
            v.route.clear();
        }
    }

    // Returns all stuck vehicle numbers (no path available, waiting for road clear).
    public List<Integer> getStuckVehicles() {
        List<Integer> stuck = new ArrayList<>();
        for (Map.Entry<Integer, Vehicle> entry : vehicles.entrySet()) {
            if (entry.getValue().state == VehicleState.STUCK) {
                stuck.add(entry.getKey());
            }
        }
        return stuck;
    }

    // Find any idle vehicle — returns its number, or -1 if none available
    public int getIdleVehicle() {
        for (Map.Entry<Integer, Vehicle> entry : vehicles.entrySet()) {
            if (entry.getValue().state == VehicleState.IDLE) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public VehicleState getState(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        return v != null ? v.state : null;
    }

    public String getLocation(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        return v != null ? v.location : null;
    }

    public String getTarget(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        return v != null ? v.target : null;
    }

    // Returns all vehicle numbers whose active route contains a given node
    // Used when LOCATION_COLLAPSED arrives — halt any vehicle heading through it
    public List<Integer> getVehiclesRoutingThrough(String node) {
        List<Integer> affected = new ArrayList<>();
        for (Map.Entry<Integer, Vehicle> entry : vehicles.entrySet()) {
            if (entry.getValue().route.contains(node)) {
                affected.add(entry.getKey());
            }
        }
        return affected;
    }
}
