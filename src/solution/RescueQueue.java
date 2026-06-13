package solution;

import util.ConfigurationInfo;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Properties;

public class RescueQueue {
    public static class RescueMission {
        public final String location;
        public final int numPeople;
        public final double travelTime; //forwardDist / VEHICLE_SPEED - optimistic estimate
        public final long receiptTimeMs; // System.currentTimeMillis() when received

        RescueMission(String location, int numPeople, double travelTime) {
            this.location = location;
            this.numPeople = numPeople;
            this.travelTime = travelTime;
            this.receiptTimeMs = System.currentTimeMillis();
        }
    }

    private final long rescueDurationTicks;
    private final double vehicleSpeed;

    private final PriorityQueue<RescueMission> queue;

    public RescueQueue(String configFile) {
        Properties props = ConfigurationInfo.loadConfig(configFile);
        // Sim multiplies RESCUE_DURATION by TIME_FACTOR=1000 internally — match that here.
        this.rescueDurationTicks = Long.parseLong(props.getProperty("RESCUE_DURATION", "0")) * 1000L;
        this.vehicleSpeed = Double.parseDouble(props.getProperty("VEHICLE_SPEED", "1.0"));

        if (rescueDurationTicks > 0) {
            // EDF: all missions share the same duration, so deadline = receiptTimeMs + constant.
            // Sorting by receiptTimeMs ascending = earliest deadline first.
            this.queue = new PriorityQueue<>(Comparator.comparingLong(m -> m.receiptTimeMs));
        } else {
            // No deadline — dispatch nearest first. Every rescue carries the same number of
            // people (fixed sim constant), so shortest travel time maximises people delivered
            // per unit time under heavy transit attrition.
            this.queue = new PriorityQueue<>(Comparator.comparingDouble(m -> m.travelTime));
        }
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
