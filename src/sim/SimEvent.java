package sim;


import java.util.ArrayList;
import java.util.List;

class SimEvent {
    public static final SimEvent SHUTDOWN = new SimEvent("SHUTDOWN");

    protected String text;
    SimEvent(String text) {
        this.text = text;
    }

    public String toString() {
        return text;
    }
}

class RoadUpdate extends SimEvent {

    private String source;
    private String destination;
    private double weight;
    private boolean open;

    public RoadUpdate(RoadModel.RoadModelEdge r) {
        super("ROAD UPDATE");
        source = r.getSrc();
        destination = r.getDest();
        weight = r.getWeight();
        open = r.isOpen();
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isOpen() {
        return open;
    }
}

class InvalidPathRequest extends SimEvent {
    private int vehicleNo;
    private boolean moving;
    private boolean destroyed;
    private boolean nonexistent;
    private boolean notAtStart;

    public InvalidPathRequest(int vehicleNo) {
        super("Invalid Path Request");
        this.vehicleNo = vehicleNo;
    }

    public void setMoving(boolean moving) {
        this.moving = moving;
    }

    public void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
    }

    public void setNotAtStart(boolean notAtStart) {this.notAtStart = notAtStart;}

    public void setNonexistent(boolean nonexistent) {
        this.nonexistent = nonexistent;
    }

    public boolean isMoving() {
        return moving;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public boolean isNonexistent() {
        return nonexistent;
    }

    public boolean isNotAtStart() {
        return notAtStart;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }
}

class InvalidWaypoints extends SimEvent {
    private String source;
    private String destination;
    private boolean exists;
    private int vehicleNo;

    public InvalidWaypoints(int vehicleNo, String source, String destination, boolean exists) {
        super("Invalid Waypoints");
        this.vehicleNo = vehicleNo;
        this.source = source;
        this.destination = destination;
        this.exists = exists;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public boolean exists() {
        return exists;
    }
}

class RescueRequest extends SimEvent {

    private String target;
    private long startTick;
    private long endTick;
    private int numPeople;

    RescueRequest(String target, long startTick, long endTick, int numPeople) {
        super("RESCUE REQUEST");
        this.target = target;
        this.startTick = startTick;
        this.endTick = endTick;
        this.numPeople = numPeople;
    }

    long getStartTick() {
        return startTick;
    }
    long getEndTick() {
        return endTick;
    }

    public int getNumPeople() {
        return numPeople;
    }

    public String getTarget() {
        return target;
    }
}

class PeoplePickedUp extends SimEvent {
    private String location;
    private int numPeople;
    private int vehicleNo;

    public PeoplePickedUp(String location, int numPeople, int vehicleNo) {
        super("People Picked Up");
        this.location = location;
        this.numPeople = numPeople;
        this.vehicleNo = vehicleNo;
    }

    public String getLocation() {
        return location;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }

    public int getNumPeople() {
        return numPeople;
    }
}

class BuildingCollapse extends SimEvent {
    private String target;

    public BuildingCollapse(String target) {
        super("Building Collapse");
        this.target = target;
    }

    public String getTarget() {
        return target;
    }

}

class Shutdown extends SimEvent {

    public Shutdown() {
        super("SHUTDOWN");
    }
}

class VehicleDestroyed extends SimEvent {
    private int vehicleNo;
    private int numPeople;
    private String location;

    public VehicleDestroyed(int vehicleNo, int numPeople, String location) {
        super("Vehicle Destroyed");
        this.vehicleNo = vehicleNo;
        this.numPeople = numPeople;
        this.location = location;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }

    public int getNumPeople() {
        return numPeople;
    }

    public String getLocation() {
        return location;
    }
}

class VehicleHalted extends SimEvent {
    private int vehicleNo;
    private String location;

    public VehicleHalted(int vehicleNo, String location) {
        super("Vehcile Halted");
        this.vehicleNo = vehicleNo;
        this.location = location;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }

    public String getLocation() {
        return location;
    }
}

class VehicleArrived extends SimEvent {
    private int vehicleNo;
    private String location;

    public VehicleArrived(int vehicleNo, String location) {
        super("Vehicle Arrived");
        this.vehicleNo = vehicleNo;
        this.location = location;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }

    public String getLocation() {
        return location;
    }
}

class VehicleHaltRequest extends SimEvent {
    private int vehicleNo;

    public VehicleHaltRequest(int vehicleNo) {
        super("Vehicle Halt Request");
        this.vehicleNo = vehicleNo;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }
}

class VehicleReturned extends SimEvent {
    private int vehicleNo;
    private int numPeople;

    public VehicleReturned(int vehicleNo, int numPeople) {
        super("Vehicle Returned");
        this.vehicleNo = vehicleNo;
        this.numPeople = numPeople;
    }

    public int getNumPeople() {
        return numPeople;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }
}

class VehiclePath extends SimEvent {
    private List<String> waypoints;
    private int currentStepIdx = 0;
    private boolean completed = false;
    private int vehicleNo;
    public ArrayList<Long> ticks = new ArrayList<>();
    public ArrayList<Long> simulatorticks = new ArrayList<>();
    public ArrayList<Long> responderticks = new ArrayList<>();
    public long travelTime = 0;

    public VehiclePath(int vehicleNo, List<String> waypoints) {
        super("VEHICLE PATH");
        this.vehicleNo = vehicleNo;
        this.waypoints = waypoints;
    }

    void advance() {
        currentStepIdx++;
        completed = (currentStepIdx == waypoints.size() - 1);
    }


    String currentSource() {
        return waypoints.get(currentStepIdx);
    }

    String currentDestination() {
        return waypoints.get(currentStepIdx + 1);
    }

    public String toString(){
        String s = String.format("VEHICLE PATH - Vehicle %d; Next step is %s", vehicleNo, currentDestination());
        return s;
    }

    public boolean isCompleted() {
        return completed;
    }

    public int getVehicleNo() {
        return vehicleNo;
    }



    public VPDescription makeDescription() {
        VPDescription ret = new VPDescription();
        ret.realTime = (ticks.get(ticks.size()-1) - ticks.get(0));
        ret.travelTime = travelTime;
        ret.ticks = ticks;
        ret.simulatorticks = simulatorticks;
        ret.responderticks = responderticks;
        return ret;
    }


    class VPDescription {
        ArrayList<Long> ticks;
        ArrayList<Long> simulatorticks;
        ArrayList<Long> responderticks;
        long travelTime = 0;
        long realTime = 0;

        public long describe() {
            System.out.print("TICKS AT: ");
            for (Long t: ticks) {
                System.out.print(t +",");
            }
            System.out.println();
            System.out.print("SIMULATOR TICKS AT: ");
            for (Long t: simulatorticks) {
                System.out.print(t +",");
            }

            System.out.println("TICK ENGINE TOTAL TIME = " + realTime + " ticks.");
            System.out.println("SIM TOTAL TIME = " + (simulatorticks.get(simulatorticks.size()-1) - simulatorticks.get(0)) + " ticks.");
            System.out.print("TE-SIM GAPS: ");
            for (int i = 0; i < simulatorticks.size(); i++) {
                long gap = -1;
                if (i < ticks.size()) {
                    gap = simulatorticks.get(i) - ticks.get(i);
                }
                System.out.print(gap +",");
            }
            System.out.println();
            System.out.print("INTER-TE GAPS: ");
            for (int i = 1; i < ticks.size(); i++) {
                long gap = ticks.get(i) -  ticks.get(i-1);
                System.out.print(gap +",");
            }
            System.out.println();
            System.out.print("INTER-SIM GAPS: ");
            long gapsTot = 0;
            for (int i = 1; i < simulatorticks.size(); i++) {
                long gap = simulatorticks.get(i) -  simulatorticks.get(i-1);
                gapsTot += gap;
                System.out.print(gap +",");
            }
            System.out.println();
            long ret = (long)(gapsTot/simulatorticks.size());
            System.out.println("AVERAGE INTER SIM GAP: " +ret);
            System.out.println();
            return ret;
        }
    }
}

class InvalidWaypointException extends Exception {
    private String src;
    private String dst;
    private boolean exists;
    InvalidWaypointException(String msg) {
        super(msg);
    }
    InvalidWaypointException(String msg, String src, String dst, boolean exists) {
        this(msg);
        this.src = src;
        this.dst = dst;
        this.exists = exists;
    }

    public String getSrc() {
        return src;
    }

    public String getDst() {
        return dst;
    }

    public boolean exists() {
        return exists;
    }
}