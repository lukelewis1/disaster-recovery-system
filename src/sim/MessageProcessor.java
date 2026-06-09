package sim;

import util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class MessageProcessor {
    static Message vehicleDestroyed(int vehicleNo, String location, int people) {
        return new Message(String.format("VEHICLE|%d|DESTROYED|LOCATION|%s|PEOPLE|%d",
                vehicleNo, location, people));
    }

    static Message vehicleArrived(int vehicleNo, String location) {
        return new Message(String.format("VEHICLE|%d|ARRIVED|LOCATION|%s",
                vehicleNo, location));
    }

    static Message vehicleHalted(int vehicleNo, String location) {
        return new Message(String.format("VEHICLE|%d|HALTED|LOCATION|%s",
                vehicleNo, location));
    }

    static Message vehicleReturned(int vehicleNo, int numPeople) {
        return new Message(String.format("VEHICLE|%d|RETURNED|RESCUED|%d",
                vehicleNo, numPeople));
    }

    static Message invalidPathRequest(int vehicleNo, boolean moving, boolean destroyed,
                                      boolean notAtStart, boolean nonexistent) {
        return new Message(String.format("PATH_INVALID|VEHICLE|%d|%s",
                vehicleNo, nonexistent? "INVALID_NUMBER" : destroyed ? "DESTROYED" : moving ? "STILL_MOVING" :
                        notAtStart ? "INVALID_STARTING_POINT" : "UNKNOWN"));
    }

    static Message invalidWaypoint(int vehicleNo, String src, String dest, boolean exists) {
        return new Message(String.format("WAYPOINT_INVALID|VEHICLE|%d|FROM|%s|TO|%s|ROAD|%s",
                vehicleNo, src, dest, exists ? "BLOCKED" : "NON_EXISTENT"));
    }

    static Message locationCollapsed(String location) {
        return new Message(String.format("LOCATION|%s|COLLAPSED", location));
    }

    static Message peopleTransferred(String location, int vehicleNo, int numPeople) {
        return new Message(String.format("PEOPLE_TRANSFERRED|LOCATION|%s|VEHICLE|%d|PEOPLE|%d",
                location, vehicleNo, numPeople));
    }

    static Message roadStatus(String from, String to, boolean clear) {
        return new Message(String.format("ROAD|FROM|%s|TO|%s|STATUS|%s",
                from, to, (clear ? "CLEAR" : "BLOCKED")));
    }

    static Message rescueRequest(String location, int people) {
        return new Message(String.format("RESCUE|LOCATION|%s|PEOPLE|%d", location, people));
    }

    static Message errorMessage(String error) {
        return new Message(String.format("ERROR|%s", error));
    }

    static int parseVehicleHalt(String message, BlockingQueue outQueue) throws InterruptedException {
        int ret = -1;
        String[] bits = message.split("\\|");
        if (bits.length == 3) {
            if (bits[0].equals("HALT") && bits[1].equals("VEHICLE")) {
                try {
                    ret = Integer.parseInt(bits[2]);
                } catch (NumberFormatException nfe) {
                    String s = String.format("Error in input message [%s] - cannot extract vehicle number", message);
                    outQueue.put(errorMessage(s));
                }
            }
        }
        return ret;
    }

    static Pair<Integer, List<String>> parseVehiclePath(String message, BlockingQueue outQueue) throws InterruptedException {
        int vehicleNo = -1;
        List<String> wp = new ArrayList<>();
        String[] bits = message.split("\\|");
        if (bits.length == 5) {
            if (bits[0].equals("PATH") && bits[1].equals("VEHICLE")
                && bits[3].equals("WAYPOINTS")){
                try {
                    vehicleNo = Integer.parseInt(bits[2]);
                } catch (NumberFormatException nfe) {
                    String s = String.format("Error in input message [%s] - cannot extract vehicle number", message);
                    outQueue.put(errorMessage(s));
                }
                String[] points = bits[4].split(",");
                for (String point: points) {
                    long x = 0;
                    try {
                        x = Long.parseLong(point);
                        wp.add(point);
                    } catch (NumberFormatException nfe) {
                        String s = String.format("Error in input message [%s] - invalid waypoint number", message);
                        outQueue.put(errorMessage(s));
                    }
                }
            }
        }
        return new Pair(vehicleNo, wp);
    }
}
