package sim;


import util.ConfigurationInfo;
import util.Pair;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

class Model {
    private static long TIME_FACTOR = 1000L;
    private int scenario = -1;
//    private int level = -1;
    private double roadDamage = -1;
    private double locationDamage;

    private String origin;
    private Long lifetime;
    double vehicleSpeed;
    private long rescueDuration;
    // Parameters
    private int numRescues;
    private long rescueTime;
    long startupPeriod;

    private RoadModel roadModel;
    Map<Integer, Vehicle> vehicles = new ConcurrentSkipListMap<>();
//    Map<Integer, VehiclePath> vehiclePaths = new ConcurrentSkipListMap<>();
    Map<String, Rescue> rescues = new ConcurrentSkipListMap<>();
    Map<Integer, Vehicle> pendingHalts = new ConcurrentSkipListMap<>();
    Map<String, String> destroyedLocations = new HashMap<>();

    Model(String cfgFile) {
        Properties cfg = ConfigurationInfo.loadConfig(cfgFile);

        scenario = Integer.parseInt(cfg.getProperty("MAP", "2"));
//        level = Integer.parseInt(cfg.getProperty("LEVEL", "0"));
        roadDamage = Double.parseDouble(cfg.getProperty("ROAD_DAMAGE", "0")) ;
        locationDamage = Double.parseDouble(cfg.getProperty("LOCATION_DAMAGE", "0")) ;
        long duration = Long.parseLong(cfg.getProperty("DURATION", "200000"));
        lifetime = TIME_FACTOR * duration;
        vehicleSpeed = Double.parseDouble(cfg.getProperty("VEHICLE_SPEED", "1.0"));
        rescueDuration = Long.parseLong(cfg.getProperty("RESCUE_DURATION", "1.0"));
        rescueDuration *= TIME_FACTOR;
        numRescues = Integer.parseInt(cfg.getProperty("NUM_RESCUES", "20"));
        startupPeriod = TIME_FACTOR*Long.parseLong(cfg.getProperty("STARTUP_PERIOD", "0"));
//        rescueTime = Long.parseLong(cfg.getProperty("RESCUE_TIME", "40000"));

        // Either this or deserialize!!!
//        originalGraph = loadGraph(ConfigurationInfo.mapFiles[scenario]);
//        roadModel = new RoadModel(originalGraph);

        System.out.print("LOADING MAP...");
        roadModel = RoadModel.deserialize("rm." + scenario + ".obj");
        System.out.println("DONE");
        origin = ConfigurationInfo.origins[scenario];

        for (int i = 0; i < ConfigurationInfo.NUMBER_OF_VEHICLES; i++) {
            vehicles.put(i, new Vehicle(i, origin));
        }
//        if (level > 2) {
//            try {
//                Pair<Graph, List<Edge>> p = corruptCopy(roadModel,damage);
//                corruptedGraph = p.first();
//                corruptedEdges = p.second();
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        }
    }

    public Long getLifetime() {
        return lifetime;
    }

    public String getOrigin() {
        return origin;
    }

    int getNumLostVehicles() {
        int counter = 0;
        for (Vehicle v: vehicles.values()) {
            if (!v.isAlive()) counter++;
        }
        return counter;
    }
    double getRoadWeight(String src, String dest) {
        RoadModel.RoadModelEdge rme = roadModel.roadsMap.get(new Pair(src, dest));
        if (rme == null) return -1;
        else return rme.getWeight();
    }

    double getTotalDistance(String dest) {
        return roadModel.getDistances().get(dest);
    }

    boolean roadExists(String src, String dest) {
        return roadModel.roadsMap.containsKey(new Pair(src, dest));
    }

    boolean isRoadOpen(String src, String dest)  {
        RoadModel.RoadModelEdge rme = roadModel.roadsMap.get(new Pair(src, dest));
        return (rme != null && rme.isOpen());
    }

    List<BuildingCollapse> getPreSimulationBuildingCollapses() {
        List<String> untouchables = roadModel.getUntouchableLocations();
        List<BuildingCollapse> ret = new ArrayList<>();
        Random r = new Random();
        for (String node: roadModel.getNodes()) {
            if (!untouchables.contains(node))
                if (r.nextDouble() < locationDamage) {
                    BuildingCollapse bc = new BuildingCollapse(node);
                    ret.add(bc);
                }
        }
        Collections.shuffle(ret);
        return ret;
    }

    List<RoadUpdate> getPreSimulationShuffledRoadsWithDamage() {
        List <RoadModel.RoadModelEdge> edges = roadModel.getRoadsList();
        List<String> untouchables = roadModel.getUntouchableLocations();
        List<RoadUpdate> ret = new ArrayList<>();
        Random r = new Random();
        for (RoadModel.RoadModelEdge edge: edges) {
            if (!untouchables.contains(edge.getSrc()) && !untouchables.contains(edge.getDest()))
                if (r.nextDouble() < roadDamage) {
                    edge.setOpen(false);
                }
            RoadUpdate ru = new RoadUpdate(edge);
            ret.add(ru);
        }
        return ret;
    }

    long getRoadUpdateInterval() {
        return lifetime /roadModel.getNumNodes();
    }

    long getLocationCollapseInterval() {
        return (long)(lifetime/ (locationDamage * roadModel.getNumNodes()));
    }


    List<RescueRequest> createRandomRescueRequests() {
        // choose regular times to update re road status
        List<RescueRequest> ret = new ArrayList<>();
        // choose random times for rescue requests, and corresponding building collapses

        long rescueInterval = lifetime / (numRescues +1);
        for (int i = 1; i <= numRescues; i++) {
            String target = roadModel.getRandomTarget();
            if (target.equals(origin)) continue;
            long startTick = randGaussian(i * rescueInterval, (long)Math.sqrt(rescueInterval));
            double distance = getTotalDistance(target);
            if (distance == Double.MAX_VALUE) continue;
            int numPeople = ConfigurationInfo.NUMBER_OF_VICTIMS_PER_RESCUE;

            long endTick = -1;
            if (rescueDuration != 0) {
                long time = (long) (distance / vehicleSpeed) + rescueDuration;
                endTick = startTick + time;
            }
            RescueRequest rr = new RescueRequest(target, startTick, endTick, numPeople);

            ret.add(rr);

//            model.rescues.put(target, rescue);
        }
        return ret;
    }

    private long randGaussian(long mean, long std) {
        return (long)(new Random().nextGaussian() * std + mean);
    }

    void addRescue(String location, int numPeople) {
        rescues.put(location, new Rescue(location, numPeople));
    }

    Vehicle getVehicle(int vehicleNo) {
        return vehicles.get(vehicleNo);
    }

}


class RoadModel implements Serializable {
    class RoadModelEdge implements  Serializable{
        private String src;
        private String dest;
        private double weight;
        private boolean open = true;

        public String getSrc() {
            return src;
        }

        public String getDest() {
            return dest;
        }

        public double getWeight() {
            return weight;
        }

        public boolean isOpen() {
            return open;
        }

        RoadModelEdge(String src, String dest, double weight, boolean open) {
            this.src = src;
            this.dest = dest;
            this.weight = weight;
            this.open = open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        String simEventMessage() {
            String s = "ROAD " + src + "-" + dest + ":" + (open ? "OPEN": "BLOCKED");
            return s;
        }
    }

    Map<Pair<String, String>, RoadModelEdge> roadsMap = new HashMap<>();
    List<RoadModelEdge> roadsList = new ArrayList<>();
    List<String> nodes = new ArrayList<>();
    private Map<String, Double> distances = new HashMap<>();
    Map<String, Integer> bfsDistances;
    List<String> untouchableLocations;

    public List<String> getUntouchableLocations() {
        return untouchableLocations;
    }

    public List<String> getNodes() {
        return nodes;
    }

    int getNumNodes() {
        return nodes.size();
    }

    public Map<String, Double> getDistances() {
        return distances;
    }

    void addEdge(String src, String dest, double weight, boolean open) {
        RoadModel.RoadModelEdge rme;
        rme = new RoadModel.RoadModelEdge(src, dest, weight, true);
        roadsMap.put(new Pair(src, dest), rme);
        roadsList.add(rme);
    }

    public double getWeight(String src, String dest) {
        RoadModelEdge rm = roadsMap.get(new Pair(src, dest));
        if (rm == null) return Double.MIN_VALUE;
        return rm.weight;
    }

    public RoadModel() {

    }

    List<RoadModelEdge> getRoadsList() {
        return roadsList;
    }

    String getRandomTarget() {
        Random r = new Random();
        return nodes.get(r.nextInt(nodes.size()));
    }

    static RoadModel deserialize(String filename) {
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(filename);
            ObjectInputStream objectInputStream
                    = new ObjectInputStream(fileInputStream);
            RoadModel rm = (RoadModel) objectInputStream.readObject();
            objectInputStream.close();
            return rm;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void serialize(RoadModel rm, String filename) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(filename);
            ObjectOutputStream objectOutputStream
                    = new ObjectOutputStream(fileOutputStream);
            objectOutputStream.writeObject(rm);
            objectOutputStream.flush();
            objectOutputStream.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}


class Vehicle {
    private int number;
    private int numPeople = 0;
    private String location;
    private boolean halted = true;
    private boolean alive = true;
    public void setHalted(boolean halted) {
        this.halted = halted;
    }

    Vehicle(int n, String origin) {
        number = n;
        location = origin;
    }

    int getNumber() {
        return number;
    }

    public String getLocation() {
        return location;
    }

    public boolean isHalted() {
        return halted;
    }

    public void halt() {
        halted = true;
    }

    public int getNumPeople() {
        return numPeople;
    }

    public void addPeople(int howmany) {
        numPeople += howmany;
    }

    public void unloadPeople() {
        numPeople = 0;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public void setNumPeople(int numPeople) {
        this.numPeople = numPeople;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}




class Rescue {
    private String location;
    private int numPeople;

    Rescue(String location, int numPeople) {
        this.location = location;
        this.numPeople = numPeople;
    }

    public int getNumPeople() {
        return numPeople;
    }

    public String getLocation() {
        return location;
    }

    void transferPeople(Vehicle v) {
        if (numPeople > 0) {
            v.addPeople(numPeople);
            numPeople = 0;
        }
    }
}