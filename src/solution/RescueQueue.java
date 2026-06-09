package solution;

import util.ConfigurationInfo;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Properties;

public class RescueQueue {
    public static class RescueMission {
        final String location;
        final int numPeople;
        final double travelTime; //forwardDist / VEHICLE_SPEED - optimistic estimate
        final long receiptTimeMs; // System.currentTimeMillis() when received

        RescueMission(String location, int numPeople, double travelTime) {
            this.location = location;
            this.numPeople = numPeople;
            this.travelTime = travelTime;
            this.receiptTimeMs = System.currentTimeMillis();
        }
    }

    private final long rescueDurationTicks;
    private final double vehicleSpeed;

    // Ordered by estimated deadline — earliest receipt time first
    // Since rescueDurationTicks is constant, ordering by receiptTimeMs is equivalent
    private  final PriorityQueue<RescueMission> queue = new PriorityQueue<>(Comparator.comparingLong(m -> m.receiptTimeMs));

    public RescueQueue(String configFile) {
        Properties props = ConfigurationInfo.loadConfig(configFile);
        this.rescueDurationTicks = Long.parseLong(props.getProperty("RESCUE_DURATION", "0"));
        this.vehicleSpeed = Double.parseDouble(props.getProperty("VEHICLE_SPEED", "1.0"));
    }


    // Called from handle() when RESCUE arrives and no vehicle is idle
    public void enqueue(String location, int numPeople, double travelTime) {
        queue.offer(new RescueMission(location, numPeople, travelTime));
    }

    // Called when a vehicle returns — returns next feasible mission or null if none
    // Rechecks feasibility at dispatch time, not just at receipt
    public RescueMission pollNext() {
        while (!queue.isEmpty()) {
            RescueMission mission = queue.poll();

            // If RESCUE_DURATION is active, discard missions where travel time alone
            // exceeds the full rescue window — they were never feasible
            if (rescueDurationTicks > 0 && mission.travelTime > rescueDurationTicks) {
                System.out.println("Discarding infeasible rescue at " + mission.location
                        + " (travelTime=" + mission.travelTime
                        + " > rescueDuration=" + rescueDurationTicks + ")");
                continue;
            }

            return mission;
        }
        return null;
    }

    // Called immediately on LOCATION_COLLAPSED — removes any pending rescue for that node
    public void removeLocation(String location) {
        queue.removeIf(m -> m.location.equals(location));
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
