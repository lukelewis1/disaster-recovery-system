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
        DESTROYED // permanently lost
    }

    public static class Vehicle {
        VehicleState state;
        String location;
        String target;      // rescue target this vehicle is heading to
        List<String> route; // current active waypoints

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
            v.state = VehicleState.HALTED;
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

    // VEHICLE|n|DESTROYED|LOCATION|x|PEOPLE|n
    public void onDestroyed(int vehicleNo) {
        Vehicle v = vehicles.get(vehicleNo);
        if (v != null) {
            v.state = VehicleState.DESTROYED;
            v.target = null;
            v.route.clear();
        }
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
