package sim;

import util.ConfigurationInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class TickEngine implements Runnable {
    private Map<Long, List<SimEvent>> schedule; // incoming events from Simulation
//    private ConcurrentSkipListMap<Long, SimEventList> schedule; // incoming events from Simulation
    private BlockingQueue<SimEvent> outEventQueue;   //outgoing messages to Simulation
    private BlockingQueue<SimEvent> inEventQueue = new LinkedBlockingQueue<>();   //incoming messages from Simulation
    private Map<Integer, Integer> pendingHalts = new HashMap<>();

//    private ArrayList<VehiclePath.VPDescription> vpDescriptions = new ArrayList<>();
//    private ArrayList<Long> tickTimes = new ArrayList<>();
    private int state;
    volatile long current_tick = 0;
    private volatile boolean shutdown = false;
    private Model model;

    private int grandTotalPeople = 0;
    private int totalPeopleSaved = 0;

    public TickEngine(String cfgFile, long seed) {
        this.state = (int) seed;
        model = new Model(cfgFile);
        schedule = new HashMap<>();
        initSchedule();
    }

    public void run() {
        System.out.println("Starting Tick Engine on " + Thread.currentThread().getName());
        System.out.println("Tick Engine: STARTUP PERIOD STARTS NOW");
        dummyRun(model.startupPeriod);
        System.out.println("Tick Engine: STARTUP PERIOD OVER");

        while (!shutdown) {
            advance(1);
            try {
                checkSchedule();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shutdown = true;
                return;
            }
        }
//        combineVPDescriptions();
//        tellTickTimes();
        System.out.println("****************************************************");
        System.out.println(String.format("* GRAND TOTAL OF PEOPLE SAVED IS %d OUT OF %d. *", totalPeopleSaved, grandTotalPeople));
        System.out.println(String.format("* %d VEHICLES LOST OUT OF %d.                       *", model.getNumLostVehicles(), ConfigurationInfo.NUMBER_OF_VEHICLES));
        System.out.println("****************************************************");
        System.out.println("SIMULATOR EVENT THREAD HAS TERMINATED");
        System.out.println("TICK ENGINE HAS SHUT DOWN.");
    }

//    void combineVPDescriptions() {
//        ArrayList<Long> gapCollector = new ArrayList<>();
//        for (VehiclePath.VPDescription vpd: vpDescriptions) {
//            long i = vpd.describe();
//            gapCollector.add(i);
//        }
//        System.out.println();
//        System.out.println("MEAN INTER-SIM GAPS OVERALL");
//        for (long i: gapCollector) {
//            System.out.print(i + ",");
//        }
//        System.out.println();
//    }

    void dummyRun(long duration) {
        while (current_tick != duration) {
            advance(1);
        }
        current_tick = 0;
    }
    void advance(int steps) {
        int s = state;
        for (int i = 0; i < steps; i++) {
            s ^= (s << 13);
            s ^= (s >>> 17);
            s ^= (s << 5);
        }
        state = s;
        current_tick++;
    }

    void checkSchedule() throws InterruptedException {
        // check for pending paths
        SimEvent p = inEventQueue.poll();
        if (p != null) {
            if (p instanceof VehiclePath) {
                VehiclePath path = (VehiclePath) p;
                path.ticks.add(current_tick);
                int vehicleNo = path.getVehicleNo();
                if (vehicleNo < 0 || vehicleNo >= ConfigurationInfo.NUMBER_OF_VEHICLES) {
                    InvalidPathRequest ipr = new InvalidPathRequest(vehicleNo);
                    ipr.setNonexistent(true);
                    outEventQueue.put(ipr);
                }
                else {
                    Vehicle v = model.getVehicle(vehicleNo);
                    boolean moving, destroyed, invalidStart;
                    moving = !v.isHalted();
                    destroyed = !v.isAlive();
                    invalidStart = (!v.getLocation().equals(path.currentSource()));
                    if (moving || destroyed || invalidStart) { // && !model.pendingHalts.containsKey(vehicleNo)) {
                        InvalidPathRequest ipr = new InvalidPathRequest(vehicleNo);
                        ipr.setMoving(moving);
                        ipr.setDestroyed(destroyed);
                        ipr.setNotAtStart(invalidStart);
                        outEventQueue.put(ipr);
                    } else {
                        try {
                            addNextPathStep(path);
                        } catch (InvalidWaypointException vpe) {
                            v.halt();
                            outEventQueue.put(new InvalidWaypoints(vehicleNo, vpe.getSrc(), vpe.getDst(), vpe.exists()));
                        }
                    }
                }
            }

            else if (p instanceof VehicleHaltRequest) {
                VehicleHaltRequest vhr = (VehicleHaltRequest) p;
                int vehicleNo = vhr.getVehicleNo();
                pendingHalts.put(vehicleNo, vehicleNo);
            }
        }

        // now check schedule
        if (schedule.containsKey(current_tick)) {
            List<SimEvent> l = schedule.remove(current_tick);
            for (SimEvent o: l) {
//                System.out.println(current_tick + ": Scheduled Event: " + o);
                if (o==SimEvent.SHUTDOWN) {
                    outEventQueue.put(o);
                }
                if (o instanceof VehiclePath) {
                    VehiclePath path = (VehiclePath)o;
                    path.ticks.add(current_tick);
                    Vehicle v = model.getVehicle(path.getVehicleNo());

                    v.setLocation(path.currentDestination());
                    outEventQueue.put(new VehicleArrived(v.getNumber(), v.getLocation()));

                    if (model.destroyedLocations.containsKey(path.currentDestination())) {
                        // make vehicle explode!
                        v.halt();
                        v.setAlive(false);
                        v.setNumPeople(0);
                        outEventQueue.put(new VehicleDestroyed(v.getNumber(), v.getNumPeople(), path.currentDestination()));
                        continue;
                    }

                    if (model.getOrigin().equals(path.currentDestination())) { // returned home
                        int peopleSaved = v.getNumPeople();
                        totalPeopleSaved += peopleSaved;
                        v.setNumPeople(0);
                        outEventQueue.put(new VehicleReturned(v.getNumber(), peopleSaved));
                    }
                    if (model.rescues.containsKey(path.currentDestination())) { // a rescue occurs!
                        Rescue r = model.rescues.get(path.currentDestination());
                        r.transferPeople(v);
                        model.rescues.remove(path.currentDestination());
                        outEventQueue.put(new PeoplePickedUp(path.currentDestination(), v.getNumPeople(), v.getNumber()));
                    }
                    if (pendingHalts.containsKey(path.getVehicleNo())) {
                        v.halt();
                        outEventQueue.put(new VehicleHalted(v.getNumber(), v.getLocation()));
                        pendingHalts.remove(path.getVehicleNo());
                    }
                    else {
                        path.advance();
                        if (path.isCompleted()) {
                            v.halt();
                            outEventQueue.put(new VehicleHalted(v.getNumber(), v.getLocation()));
                        }
                        else {
                            // line up the next step
                            try {
                                addNextPathStep(path);
                            }
                            catch (InvalidWaypointException vpe) {
                                v.halt();
                                outEventQueue.put(new InvalidWaypoints(v.getNumber(), vpe.getSrc(), vpe.getDst(), vpe.exists()));
                            }
                        }
                    }
                }
                else if (o instanceof RoadUpdate) {
                    outEventQueue.put(o);
                }
                else if (o instanceof RescueRequest) {
                    RescueRequest rr = (RescueRequest) o;
                    model.addRescue(rr.getTarget(), rr.getNumPeople());
                    grandTotalPeople += rr.getNumPeople();
                    outEventQueue.put(o);
                }
                else if (o instanceof BuildingCollapse) {
                    BuildingCollapse bc = (BuildingCollapse)o;
                    outEventQueue.put(bc);
                    String location = bc.getTarget();
                    model.destroyedLocations.put(location, location);
                    for (Vehicle v: model.vehicles.values()){
                        if (v.getLocation().equals(location) && v.isAlive()) {
                            v.halt();
                            v.setAlive(false);
                            outEventQueue.put(new VehicleDestroyed(v.getNumber(), v.getNumPeople(), location));
                        }
                    }
                }
            }
        }
        if (current_tick % 1000000==0) {
            System.out.println("Current Step is " + current_tick);
        }
    }

    void addNextPathStep(VehiclePath path) throws InvalidWaypointException {
        String src = path.currentSource();
        String dst = path.currentDestination();

        if (!model.roadExists(src, dst)) {
            throw new InvalidWaypointException(String.format("Invalid path: No road from %s to %s", src, dst), src, dst, false);
        }
        else if (!model.isRoadOpen(src, dst)) {
            throw new InvalidWaypointException(String.format("Invalid path: Road from %s to %s is blocked", src, dst), src, dst, true);
        }

        double d = model.getRoadWeight(src, dst);
        long delay = (long)(d / model.vehicleSpeed);
        model.getVehicle(path.getVehicleNo()).setHalted(false);
//        path.travelTime += delay;
        addScheduledEventWithDelay(delay, path);
    }

    void shutdown() {
        System.out.println("Tick Engine shutdown called");
        shutdown = true;
    }

//    public void tellTickTimes() {
//        for(int i = 1; i < tickTimes.size(); i++) {
//            long interval = tickTimes.get(i) - tickTimes.get(i-1);
//            System.out.println("To tick " + (i*5000) + ": " + interval);
//        }
//    }
    BlockingQueue<SimEvent> getInEventQueue() {
        return inEventQueue;
    }

    void setOutEventQueue(BlockingQueue outEventQueue) {
        this.outEventQueue = outEventQueue;
    }

    void addScheduledEvent(Long tick, SimEvent s) {
        schedule.computeIfAbsent(tick,
                k -> new ArrayList<SimEvent>()).add(s);
    }

    void addScheduledEventWithDelay(Long delay, SimEvent s) {
        if (delay <= 10) {
            System.out.println("AARAGHH! SHORT DELAY of " + delay);
        }
        long when = current_tick + delay;
        schedule.computeIfAbsent(when,
                k -> new ArrayList<>()).add(s);
//        System.out.println("TICK ENGINE ADDED " + s + "FOR TICK " + when);
    }

    private void initSchedule() {
        // make random building collapses
        long collapseInterval = model.getLocationCollapseInterval();
        long y = collapseInterval;
        for (BuildingCollapse bc: model.getPreSimulationBuildingCollapses()) {
            addScheduledEvent(y, bc);
            y += collapseInterval;
        }

        // make random damaged road updates
        long roadInterval = model.getRoadUpdateInterval();
        long x = roadInterval;
        for (RoadUpdate ru: model.getPreSimulationShuffledRoadsWithDamage()) {
            addScheduledEvent(x, ru);
            x += roadInterval;
        }

        for (RescueRequest rescue: model.createRandomRescueRequests()) {
            addScheduledEvent(rescue.getStartTick(), rescue);
            if (rescue.getEndTick() != -1)
                addScheduledEvent(rescue.getEndTick(), new BuildingCollapse(rescue.getTarget()));
        }

        addScheduledEvent(model.getLifetime(), SimEvent.SHUTDOWN);
    }
}
